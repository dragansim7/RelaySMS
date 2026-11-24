package com.example.data.remote

import com.example.data.local.TempNumberEntity
import com.example.domain.PhoneFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

private data class MirrorCacheEntry(
    val fetchedAtMs: Long,
    val result: TempFetchResult.Success,
)

/**
 * Fallback mirror for receive-smss.live (https://receive-smss.live/sms/{cc}/{digits}).
 * Supports both Next.js escaped JSON payloads and classic HTML message cards.
 */
class ReceiveSmssMirrorAdapter(private val client: OkHttpClient) {

    private val cache = ConcurrentHashMap<String, MirrorCacheEntry>()

    suspend fun fetch(e164: String, force: Boolean = false): TempFetchResult {
        val digits = PhoneFormatter.digitsOnly(e164)
        val (countryCode, _) = PhoneFormatter.inferCountryFromE164(e164)
        val cc = countryCode.lowercase()
        val url = "https://receive-smss.live/sms/$cc/$digits"
        val now = System.currentTimeMillis()
        val cached = cache[digits]
        if (!force && cached != null && (now - cached.fetchedAtMs) < CACHE_TTL_MS) {
            return cached.result.copy(
                fromCache = true,
                attempts = cached.result.attempts.map { it.copy(fromCache = true) },
            )
        }

        val started = System.currentTimeMillis()
        return withContext(Dispatchers.IO) {
            try {
                val request = BrowserHttpHeaders.applyHtmlHeaders(
                    Request.Builder().url(url).get(),
                    referer = "https://receive-smss.live/",
                ).build()

                client.newCall(request).execute().use { response ->
                    val finalUrl = response.request.url.toString()
                    val latency = (System.currentTimeMillis() - started).coerceAtLeast(1L)
                    val html = response.body?.string().orEmpty()
                    val snippet = BotWallDetector.buildSnippet(html)
                    val botCheck = BotWallDetector.detect(response.code, html)

                    if (botCheck.isBlocked) {
                        val attempt = SourceProbeAttempt(
                            sourceKey = TempNumberEntity.SOURCE_RECEIVE_SMSS_MIRROR,
                            displayName = "Receive-SMSS.live",
                            url = finalUrl,
                            httpCode = response.code,
                            latencyMs = latency,
                            payloadBytes = html.toByteArray().size,
                            botChallengeDetected = true,
                            outcome = ProbeOutcome.BOT_BLOCKED,
                            detailMessage = botCheck.reason ?: "Anti-bot challenge detected",
                            responseSnippet = snippet,
                        )
                        return@withContext TempFetchResult.Failure(
                            status = TempNumberEntity.STATUS_BOT_BLOCKED,
                            errorMessage = "Receive-SMSS.live blocked (${botCheck.reason ?: "HTTP ${response.code}"}).",
                            attempts = listOf(attempt),
                        )
                    }

                    when (response.code) {
                        in 200..299 -> {
                            val redirectedOffNumber = finalUrl != url &&
                                !finalUrl.contains(digits) &&
                                !finalUrl.contains(digits.takeLast(9))
                            if (redirectedOffNumber) {
                                val attempt = SourceProbeAttempt(
                                    sourceKey = TempNumberEntity.SOURCE_RECEIVE_SMSS_MIRROR,
                                    displayName = "Receive-SMSS.live",
                                    url = finalUrl,
                                    httpCode = response.code,
                                    latencyMs = latency,
                                    payloadBytes = html.toByteArray().size,
                                    outcome = ProbeOutcome.NOT_FOUND,
                                    detailMessage = "Redirected away from +$digits to $finalUrl",
                                    responseSnippet = snippet,
                                )
                                return@withContext TempFetchResult.Failure(
                                    status = TempNumberEntity.STATUS_NOT_FOUND,
                                    errorMessage = "Number not listed on Receive-SMSS.live (redirected).",
                                    attempts = listOf(attempt),
                                )
                            }

                            val (parsed, strategy) = parseHtmlWithStrategy(html)
                            val visibleLower = BotWallDetector.stripNonVisibleTags(html).lowercase()
                            val explicitEmpty = visibleLower.contains("has not received") ||
                                visibleLower.contains("no sms received") ||
                                visibleLower.contains("no messages received") ||
                                visibleLower.contains("no messages yet") ||
                                visibleLower.contains("inbox is empty")
                            val outcome = when {
                                parsed.isNotEmpty() -> ProbeOutcome.SUCCESS_WITH_MESSAGES
                                explicitEmpty -> ProbeOutcome.EMPTY_INBOX
                                else -> ProbeOutcome.PARSE_MISMATCH
                            }
                            val attempt = SourceProbeAttempt(
                                sourceKey = TempNumberEntity.SOURCE_RECEIVE_SMSS_MIRROR,
                                displayName = "Receive-SMSS.live",
                                url = finalUrl,
                                httpCode = response.code,
                                latencyMs = latency,
                                payloadBytes = html.toByteArray().size,
                                parserStrategy = strategy,
                                parsedCount = parsed.size,
                                outcome = outcome,
                                detailMessage = when (outcome) {
                                    ProbeOutcome.SUCCESS_WITH_MESSAGES -> "Parsed ${parsed.size} messages via $strategy"
                                    ProbeOutcome.EMPTY_INBOX -> "Number page reachable with no messages"
                                    else -> "Page returned ${html.length} chars without recognized message blocks"
                                },
                                responseSnippet = snippet,
                            )
                            if (outcome == ProbeOutcome.PARSE_MISMATCH) {
                                return@withContext TempFetchResult.Failure(
                                    status = TempNumberEntity.STATUS_ERROR,
                                    errorMessage = "Receive-SMSS.live loaded HTTP 200 but matched 0 SMS blocks.",
                                    attempts = listOf(attempt),
                                )
                            }
                            val result = TempFetchResult.Success(
                                messages = parsed,
                                resolvedSource = TempNumberEntity.SOURCE_RECEIVE_SMSS_MIRROR,
                                attempts = listOf(attempt),
                            )
                            if (parsed.isNotEmpty()) {
                                cache[digits] = MirrorCacheEntry(System.currentTimeMillis(), result)
                            }
                            result
                        }
                        404 -> {
                            val attempt = SourceProbeAttempt(
                                sourceKey = TempNumberEntity.SOURCE_RECEIVE_SMSS_MIRROR,
                                displayName = "Receive-SMSS.live",
                                url = url,
                                httpCode = 404,
                                latencyMs = latency,
                                payloadBytes = html.toByteArray().size,
                                outcome = ProbeOutcome.NOT_FOUND,
                                detailMessage = "Number not listed on Receive-SMSS.live (HTTP 404)",
                                responseSnippet = snippet,
                            )
                            TempFetchResult.Failure(
                                status = TempNumberEntity.STATUS_NOT_FOUND,
                                errorMessage = "Number not listed on Receive-SMSS.live (HTTP 404).",
                                attempts = listOf(attempt),
                            )
                        }
                        429 -> {
                            val attempt = SourceProbeAttempt(
                                sourceKey = TempNumberEntity.SOURCE_RECEIVE_SMSS_MIRROR,
                                displayName = "Receive-SMSS.live",
                                url = url,
                                httpCode = 429,
                                latencyMs = latency,
                                outcome = ProbeOutcome.RATE_LIMITED,
                                detailMessage = "Rate limited (HTTP 429)",
                            )
                            TempFetchResult.Failure(
                                status = TempNumberEntity.STATUS_RATE_LIMITED,
                                errorMessage = "Receive-SMSS.live rate limited this request (HTTP 429).",
                                retryAfterMs = 30_000L,
                                attempts = listOf(attempt),
                            )
                        }
                        else -> {
                            val attempt = SourceProbeAttempt(
                                sourceKey = TempNumberEntity.SOURCE_RECEIVE_SMSS_MIRROR,
                                displayName = "Receive-SMSS.live",
                                url = url,
                                httpCode = response.code,
                                latencyMs = latency,
                                payloadBytes = html.toByteArray().size,
                                outcome = ProbeOutcome.ERROR,
                                detailMessage = "Unexpected HTTP ${response.code}",
                                responseSnippet = snippet,
                            )
                            TempFetchResult.Failure(
                                status = TempNumberEntity.STATUS_ERROR,
                                errorMessage = "Receive-SMSS.live returned HTTP ${response.code}.",
                                attempts = listOf(attempt),
                            )
                        }
                    }
                }
            } catch (io: IOException) {
                val latency = (System.currentTimeMillis() - started).coerceAtLeast(1L)
                val attempt = SourceProbeAttempt(
                    sourceKey = TempNumberEntity.SOURCE_RECEIVE_SMSS_MIRROR,
                    displayName = "Receive-SMSS.live",
                    url = url,
                    latencyMs = latency,
                    outcome = ProbeOutcome.OFFLINE,
                    detailMessage = "Network unreachable (${io.javaClass.simpleName})",
                )
                TempFetchResult.Failure(
                    status = TempNumberEntity.STATUS_OFFLINE,
                    errorMessage = "Could not reach Receive-SMSS.live (${io.javaClass.simpleName}).",
                    attempts = listOf(attempt),
                )
            } catch (t: Throwable) {
                val latency = (System.currentTimeMillis() - started).coerceAtLeast(1L)
                val attempt = SourceProbeAttempt(
                    sourceKey = TempNumberEntity.SOURCE_RECEIVE_SMSS_MIRROR,
                    displayName = "Receive-SMSS.live",
                    url = url,
                    latencyMs = latency,
                    outcome = ProbeOutcome.ERROR,
                    detailMessage = t.message ?: "Parser exception",
                )
                TempFetchResult.Failure(
                    status = TempNumberEntity.STATUS_ERROR,
                    errorMessage = t.message ?: "Failed to parse Receive-SMSS.live.",
                    attempts = listOf(attempt),
                )
            }
        }
    }

    internal fun parseHtmlWithStrategy(
        html: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<List<TempParsedMessage>, String?> {
        val text = html.replace("&amp;quot;", "\"").replace("&quot;", "\"")
        val jsonOut = mutableListOf<TempParsedMessage>()

        for (match in embeddedJsonRegex.findAll(text)) {
            val senderRaw = HtmlEntityDecoder.decodeEscapedJsonFragment(match.groupValues[1]).take(40)
            val bodyRaw = HtmlEntityDecoder.decodeEscapedJsonFragment(match.groupValues[2]).take(640)
            val timeRaw = match.groupValues[3]
            if (bodyRaw.isEmpty()) continue
            val ts = FlexibleTimestampParser.parse(timeRaw, nowMs)
            val sender = senderRaw.ifEmpty { PhoneFormatter.guessSender("", bodyRaw).ifEmpty { "Receive-SMSS" } }
            jsonOut.add(
                TempParsedMessage(
                    fromNumber = sender.take(32),
                    senderName = sender,
                    body = bodyRaw,
                    receivedAtEpochMs = ts.epochMs,
                    hasExplicitTimestamp = ts.isExplicit,
                    sourceTag = "receive_smss_mirror",
                )
            )
        }
        if (jsonOut.isNotEmpty()) return jsonOut to "embedded-json"

        val tableOut = parseGenericHtmlTables(html, "receive_smss_mirror", "Receive-SMSS", nowMs)
        if (tableOut.isNotEmpty()) return tableOut to "html-table"

        return emptyList<TempParsedMessage>() to null
    }

    companion object {
        private const val CACHE_TTL_MS = 15_000L
        private val embeddedJsonRegex = Regex(
            """\\?"sender\\?"\s*:\s*\\?"((?:[^"\\]|\\.)*)\\?"\s*,\s*\\?"content\\?"\s*:\s*\\?"((?:[^"\\]|\\.)*)\\?"\s*,\s*\\?"received_at\\?"\s*:\s*\\?"([^"\\]+)\\?""""
        )
    }
}

/**
 * Shared base engine for Australian (+61) & international public SMS web portals
 * (Quackr.io, TempSMSS.com, Receive-SMS-Free.cc, Receive-SMS-Online.info).
 */
open class GenericHtmlSmsHostAdapter(
    private val client: OkHttpClient,
    protected val sourceKey: String,
    protected val displayName: String,
    protected val sourceTag: String,
    private val refererRoot: String,
    private val browserFetcher: BrowserDomFetcher? = null,
    private val urlsForNumber: (digits: String, countryTitleSlug: String, countryLowerSlug: String) -> List<String>,
) {
    private val cache = ConcurrentHashMap<String, MirrorCacheEntry>()

    suspend fun fetch(
        e164: String,
        force: Boolean = false,
        allowBrowserFallback: Boolean = false,
    ): TempFetchResult {
        val digits = PhoneFormatter.digitsOnly(e164)
        val now = System.currentTimeMillis()
        val cached = cache[digits]
        if (!force && cached != null && (now - cached.fetchedAtMs) < CACHE_TTL_MS) {
            return cached.result.copy(
                fromCache = true,
                attempts = cached.result.attempts.map { it.copy(fromCache = true) },
            )
        }

        val (_, countryName) = PhoneFormatter.inferCountryFromE164(e164)
        val titleSlug = countryName.replace(" ", "-")
        val lowerSlug = titleSlug.lowercase()
        val candidateUrls = urlsForNumber(digits, titleSlug, lowerSlug)

        val attempts = mutableListOf<SourceProbeAttempt>()
        var lastFailure: TempFetchResult.Failure? = null

        val httpResult = withContext(Dispatchers.IO) {
            for (url in candidateUrls) {
                val started = System.currentTimeMillis()
                try {
                    val request = BrowserHttpHeaders.applyHtmlHeaders(
                        Request.Builder().url(url).get(),
                        referer = refererRoot,
                    ).build()

                    client.newCall(request).execute().use { response ->
                        val finalUrl = response.request.url.toString()
                        val latency = (System.currentTimeMillis() - started).coerceAtLeast(1L)
                        val html = response.body?.string().orEmpty()
                        val snippet = BotWallDetector.buildSnippet(html)
                        val botCheck = BotWallDetector.detect(response.code, html)

                        if (botCheck.isBlocked) {
                            attempts.add(
                                SourceProbeAttempt(
                                    sourceKey = sourceKey,
                                    displayName = displayName,
                                    url = finalUrl,
                                    httpCode = response.code,
                                    latencyMs = latency,
                                    payloadBytes = html.toByteArray().size,
                                    botChallengeDetected = true,
                                    outcome = ProbeOutcome.BOT_BLOCKED,
                                    detailMessage = botCheck.reason ?: "Anti-bot wall",
                                    responseSnippet = snippet,
                                )
                            )
                            lastFailure = TempFetchResult.Failure(
                                status = TempNumberEntity.STATUS_BOT_BLOCKED,
                                errorMessage = "$displayName blocked (${botCheck.reason ?: "HTTP ${response.code}"}).",
                                attempts = attempts.toList(),
                            )
                        } else if (response.code in 200..299) {
                            val redirectedOffNumber = finalUrl != url &&
                                !finalUrl.contains(digits) &&
                                !finalUrl.contains(digits.takeLast(9))
                            if (redirectedOffNumber) {
                                attempts.add(
                                    SourceProbeAttempt(
                                        sourceKey = sourceKey,
                                        displayName = displayName,
                                        url = finalUrl,
                                        httpCode = response.code,
                                        latencyMs = latency,
                                        payloadBytes = html.toByteArray().size,
                                        outcome = ProbeOutcome.NOT_FOUND,
                                        detailMessage = "Redirected away from +$digits to $finalUrl",
                                        responseSnippet = snippet,
                                    )
                                )
                                lastFailure = TempFetchResult.Failure(
                                    status = TempNumberEntity.STATUS_NOT_FOUND,
                                    errorMessage = "Number not listed on $displayName (redirected).",
                                    attempts = attempts.toList(),
                                )
                            } else {
                                val (parsed, strategy) = parseHostHtml(html)
                                val visibleLower = BotWallDetector.stripNonVisibleTags(html).lowercase()
                                val explicitEmpty = visibleLower.contains("has not received") ||
                                    visibleLower.contains("no sms received") ||
                                    visibleLower.contains("no messages received") ||
                                    visibleLower.contains("inbox is empty")
                                val outcome = when {
                                    parsed.isNotEmpty() -> ProbeOutcome.SUCCESS_WITH_MESSAGES
                                    explicitEmpty -> ProbeOutcome.EMPTY_INBOX
                                    else -> ProbeOutcome.PARSE_MISMATCH
                                }
                                attempts.add(
                                    SourceProbeAttempt(
                                        sourceKey = sourceKey,
                                        displayName = displayName,
                                        url = finalUrl,
                                        httpCode = response.code,
                                        latencyMs = latency,
                                        payloadBytes = html.toByteArray().size,
                                        parserStrategy = strategy,
                                        parsedCount = parsed.size,
                                        outcome = outcome,
                                        detailMessage = when (outcome) {
                                            ProbeOutcome.SUCCESS_WITH_MESSAGES -> "Parsed ${parsed.size} messages via $strategy"
                                            ProbeOutcome.EMPTY_INBOX -> "$displayName page loaded with 0 messages"
                                            else -> "Page returned ${html.length} chars with 0 matched SMS entries"
                                        },
                                        responseSnippet = snippet,
                                    )
                                )
                                if (parsed.isNotEmpty()) {
                                    val success = TempFetchResult.Success(
                                        messages = parsed,
                                        resolvedSource = sourceKey,
                                        attempts = attempts.toList(),
                                    )
                                    cache[digits] = MirrorCacheEntry(System.currentTimeMillis(), success)
                                    return@withContext success
                                }
                                if (outcome == ProbeOutcome.PARSE_MISMATCH) {
                                    lastFailure = TempFetchResult.Failure(
                                        status = TempNumberEntity.STATUS_ERROR,
                                        errorMessage = "$displayName loaded (HTTP 200) but matched 0 SMS blocks.",
                                        attempts = attempts.toList(),
                                    )
                                }
                            }
                        } else {
                            val outcome = when (response.code) {
                                404 -> ProbeOutcome.NOT_FOUND
                                429 -> ProbeOutcome.RATE_LIMITED
                                else -> ProbeOutcome.ERROR
                            }
                            val status = when (response.code) {
                                404 -> TempNumberEntity.STATUS_NOT_FOUND
                                429 -> TempNumberEntity.STATUS_RATE_LIMITED
                                else -> TempNumberEntity.STATUS_ERROR
                            }
                            attempts.add(
                                SourceProbeAttempt(
                                    sourceKey = sourceKey,
                                    displayName = displayName,
                                    url = url,
                                    httpCode = response.code,
                                    latencyMs = latency,
                                    payloadBytes = html.toByteArray().size,
                                    outcome = outcome,
                                    detailMessage = "HTTP ${response.code} from $displayName",
                                    responseSnippet = snippet,
                                )
                            )
                            lastFailure = TempFetchResult.Failure(
                                status = status,
                                errorMessage = "$displayName returned HTTP ${response.code}.",
                                retryAfterMs = if (response.code == 429) 30_000L else null,
                                attempts = attempts.toList(),
                            )
                        }
                    }
                } catch (io: IOException) {
                    val latency = (System.currentTimeMillis() - started).coerceAtLeast(1L)
                    attempts.add(
                        SourceProbeAttempt(
                            sourceKey = sourceKey,
                            displayName = displayName,
                            url = url,
                            latencyMs = latency,
                            outcome = ProbeOutcome.OFFLINE,
                            detailMessage = "Network unreachable (${io.javaClass.simpleName})",
                        )
                    )
                    lastFailure = TempFetchResult.Failure(
                        status = TempNumberEntity.STATUS_OFFLINE,
                        errorMessage = "Could not reach $displayName (${io.javaClass.simpleName}).",
                        attempts = attempts.toList(),
                    )
                }
            }
            null
        }

        if (httpResult != null) return httpResult

        // Optional Stage 2: Live Chromium DOM extraction when explicitly selected and static HTML matched 0 blocks or hit JS bot wall
        if (allowBrowserFallback && browserFetcher != null && attempts.any { it.outcome == ProbeOutcome.PARSE_MISMATCH || it.outcome == ProbeOutcome.BOT_BLOCKED }) {
            val targetUrl = candidateUrls.firstOrNull()
            if (targetUrl != null) {
                val rendered = browserFetcher.renderAndExtract(targetUrl, referer = refererRoot, maxWaitMs = 6_000L)
                if (rendered != null) {
                    val nowRender = System.currentTimeMillis()
                    val jsMsgs = convertRenderedMessages(rendered.messages, sourceTag, displayName, nowRender)
                    val (finalMsgs, strategy) = if (jsMsgs.isNotEmpty()) {
                        jsMsgs to (rendered.messages.firstOrNull()?.strategy ?: "chromium-dom")
                    } else if (rendered.outerHtml.isNotBlank()) {
                        val (hMsgs, hStrat) = parseHostHtml(rendered.outerHtml, nowRender)
                        hMsgs to hStrat?.let { "chromium+$it" }
                    } else {
                        emptyList<TempParsedMessage>() to null
                    }
                    val outcome = if (finalMsgs.isNotEmpty()) ProbeOutcome.SUCCESS_WITH_MESSAGES else ProbeOutcome.PARSE_MISMATCH
                    attempts.add(
                        SourceProbeAttempt(
                            sourceKey = sourceKey,
                            displayName = "$displayName (Live Chromium DOM)",
                            url = rendered.finalUrl,
                            httpCode = if (rendered.error == null) 200 else null,
                            latencyMs = rendered.latencyMs,
                            payloadBytes = rendered.outerHtml.toByteArray().size,
                            parserStrategy = strategy,
                            parsedCount = finalMsgs.size,
                            outcome = outcome,
                            detailMessage = if (finalMsgs.isNotEmpty()) {
                                "Extracted ${finalMsgs.size} messages via $strategy (${rendered.domSummary})"
                            } else {
                                "Live Chromium DOM matched 0 messages (${rendered.domSummary})"
                            },
                            responseSnippet = "[${rendered.domSummary}] ${rendered.visibleTextSnippet}".take(420),
                        )
                    )
                    if (finalMsgs.isNotEmpty()) {
                        val success = TempFetchResult.Success(
                            messages = finalMsgs,
                            resolvedSource = sourceKey,
                            attempts = attempts.toList(),
                        )
                        cache[digits] = MirrorCacheEntry(System.currentTimeMillis(), success)
                        return success
                    }
                }
            }
        }

        return if (attempts.any { it.outcome == ProbeOutcome.EMPTY_INBOX }) {
            TempFetchResult.Success(
                messages = emptyList(),
                resolvedSource = sourceKey,
                attempts = attempts.toList(),
            )
        } else {
            lastFailure?.copy(attempts = attempts.toList()) ?: TempFetchResult.Failure(
                status = TempNumberEntity.STATUS_NOT_FOUND,
                errorMessage = "Number not found on $displayName.",
                attempts = attempts.toList(),
            )
        }
    }

    internal fun parseHostHtml(
        html: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<List<TempParsedMessage>, String?> {
        // Strategy 1: HTML tables (Quackr, TempSMSS, Receive-SMS-Online, etc.)
        val tableMsgs = parseGenericHtmlTables(html, sourceTag, displayName, nowMs)
        if (tableMsgs.isNotEmpty()) return tableMsgs to "html-table"

        // Strategy 2: Bootstrap row / card SMS blocks (Receive-SMS-Free, TempSMSS, Quackr cards)
        val rowBlocks = BalancedHtmlExtractor.extractBalancedElements(
            html = html,
            tagName = "div",
            openTagRegex = cardBlockOpenRegex,
        )
        val cardMsgs = mutableListOf<TempParsedMessage>()
        for (block in rowBlocks) {
            val cols = BalancedHtmlExtractor.extractBalancedElements(
                html = block,
                tagName = "div",
                openTagRegex = colDivOpenRegex,
            )
            if (cols.size >= 3) {
                val c0 = HtmlEntityDecoder.decodeHtml(cols[0]).trim()
                val c1 = HtmlEntityDecoder.decodeHtml(cols[1]).trim()
                val c2 = HtmlEntityDecoder.decodeHtml(cols[2]).trim()
                val ts0 = FlexibleTimestampParser.extractFromHtmlOrText(cols[0], nowMs)
                val ts1 = FlexibleTimestampParser.extractFromHtmlOrText(cols[1], nowMs)
                val ts2 = FlexibleTimestampParser.extractFromHtmlOrText(cols[2], nowMs)
                val (sender, body, ts) = when {
                    ts0.isExplicit -> Triple(c1, c2, ts0)
                    ts1.isExplicit && c2.length >= c1.length -> Triple(c0, c2, ts1)
                    ts2.isExplicit -> Triple(c0, c1, ts2)
                    else -> Triple(c0, if (c2.length > c1.length) c2 else c1, ts2)
                }
                val cleanBody = PhoneFormatter.sanitizeBody(body, 640)
                if (cleanBody.length < 3) continue
                val cleanSender = sender.replace(Regex("""(?i)^from\s*:?\s*"""), "").trim().take(40)
                    .ifEmpty { PhoneFormatter.guessSender("", cleanBody).ifEmpty { displayName } }
                cardMsgs.add(
                    TempParsedMessage(
                        fromNumber = cleanSender.take(32),
                        senderName = cleanSender,
                        body = cleanBody,
                        receivedAtEpochMs = ts.epochMs,
                        hasExplicitTimestamp = ts.isExplicit,
                        sourceTag = sourceTag,
                    )
                )
            } else {
                // Single-column card with heading/sender + paragraph/body + small/time
                val senderRaw = cardSenderRegex.find(block)?.groupValues?.getOrNull(1).orEmpty()
                val bodyRaw = cardBodyRegex.find(block)?.groupValues?.getOrNull(1).orEmpty()
                val timeHtml = cardTimeRegex.find(block)?.value ?: block
                val cleanBody = PhoneFormatter.sanitizeBody(HtmlEntityDecoder.decodeHtml(bodyRaw), 640)
                if (cleanBody.length < 3) continue
                val cleanSender = HtmlEntityDecoder.decodeHtml(senderRaw)
                    .replace(Regex("""(?i)^from\s*:?\s*"""), "")
                    .trim()
                    .take(40)
                    .ifEmpty { PhoneFormatter.guessSender("", cleanBody).ifEmpty { displayName } }
                val ts = FlexibleTimestampParser.extractFromHtmlOrText(timeHtml, nowMs)
                cardMsgs.add(
                    TempParsedMessage(
                        fromNumber = cleanSender.take(32),
                        senderName = cleanSender,
                        body = cleanBody,
                        receivedAtEpochMs = ts.epochMs,
                        hasExplicitTimestamp = ts.isExplicit,
                        sourceTag = sourceTag,
                    )
                )
            }
        }
        if (cardMsgs.isNotEmpty()) return cardMsgs to "html-cards-balanced"

        // Strategy 3: Embedded JSON message objects (common in SPA/Next/Nuxt SMS sites)
        val text = html.replace("&amp;quot;", "\"").replace("&quot;", "\"")
        val jsonOut = mutableListOf<TempParsedMessage>()
        for (m in genericJsonMsgRegex.findAll(text)) {
            val senderRaw = HtmlEntityDecoder.decodeEscapedJsonFragment(m.groupValues[1]).take(40)
            val bodyRaw = PhoneFormatter.sanitizeBody(HtmlEntityDecoder.decodeEscapedJsonFragment(m.groupValues[2]), 640)
            val timeRaw = m.groupValues[3]
            if (bodyRaw.length < 3) continue
            val ts = FlexibleTimestampParser.parse(timeRaw, nowMs)
            val sender = senderRaw.ifEmpty { PhoneFormatter.guessSender("", bodyRaw).ifEmpty { displayName } }
            jsonOut.add(
                TempParsedMessage(
                    fromNumber = sender.take(32),
                    senderName = sender,
                    body = bodyRaw,
                    receivedAtEpochMs = ts.epochMs,
                    hasExplicitTimestamp = ts.isExplicit,
                    sourceTag = sourceTag,
                )
            )
        }
        if (jsonOut.isNotEmpty()) return jsonOut to "embedded-json"

        return emptyList<TempParsedMessage>() to null
    }

    companion object {
        private const val CACHE_TTL_MS = 15_000L
        private val cardBlockOpenRegex = Regex(
            """<div[^>]*class=["'][^"']*(?:casetext|row\s+border|sms-item|message-card|msg-row|sms-box|message_details|msgg)[^"']*["'][^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        private val colDivOpenRegex = Regex("""<div[^>]*class=["'][^"']*col-[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE)
        private val cardSenderRegex = Regex("""<(?:h[456]|strong|b|span[^>]*class=["'][^"']*(?:sender|from)[^"']*["'])[^>]*>([\s\S]*?)</(?:h[456]|strong|b|span)>""", RegexOption.IGNORE_CASE)
        private val cardBodyRegex = Regex("""<(?:p|div[^>]*class=["'][^"']*(?:msg|message|text-break|body)[^"']*["'])[^>]*>([\s\S]*?)</(?:p|div)>""", RegexOption.IGNORE_CASE)
        private val cardTimeRegex = Regex("""<(?:small|time|span[^>]*class=["'][^"']*(?:time|date|ago)[^"']*["'])[^>]*>[\s\S]*?</(?:small|time|span)>""", RegexOption.IGNORE_CASE)
        private val genericJsonMsgRegex = Regex(
            """\\?"(?:from|sender|originator)\\?"\s*:\s*\\?"((?:[^"\\]|\\.)*)\\?"\s*,\s*\\?"(?:text|message|body|content)\\?"\s*:\s*\\?"((?:[^"\\]|\\.)*)\\?"(?:\s*,\s*\\?"(?:received|received_at|created_at|date|time|timestamp)\\?"\s*:\s*\\?"([^"\\]*)\\?")?""",
            RegexOption.IGNORE_CASE,
        )
    }
}

class QuackrAdapter(
    client: OkHttpClient,
    browserFetcher: BrowserDomFetcher? = null,
) : GenericHtmlSmsHostAdapter(
    client = client,
    sourceKey = TempNumberEntity.SOURCE_QUACKR,
    displayName = "Quackr.io",
    sourceTag = "quackr",
    refererRoot = "https://quackr.io/temporary-numbers/australia",
    browserFetcher = browserFetcher,
    urlsForNumber = { digits, _, lowerSlug ->
        listOf("https://quackr.io/temporary-numbers/$lowerSlug/$digits")
    },
)

class TempSmssAdapter(
    client: OkHttpClient,
    browserFetcher: BrowserDomFetcher? = null,
) : GenericHtmlSmsHostAdapter(
    client = client,
    sourceKey = TempNumberEntity.SOURCE_TEMPSMSS,
    displayName = "TempSMSS.com",
    sourceTag = "tempsmss",
    refererRoot = "https://tempsmss.com/",
    browserFetcher = browserFetcher,
    urlsForNumber = { digits, _, lowerSlug ->
        listOf(
            "https://tempsmss.com/receive-sms-online/$lowerSlug-phone-number-$digits/",
            "https://tempsmss.com/number/$digits",
        )
    },
)

class ReceiveSmsFreeAdapter(
    client: OkHttpClient,
    browserFetcher: BrowserDomFetcher? = null,
) : GenericHtmlSmsHostAdapter(
    client = client,
    sourceKey = TempNumberEntity.SOURCE_RECEIVE_SMS_FREE,
    displayName = "Receive-SMS-Free.cc",
    sourceTag = "receive_sms_free",
    refererRoot = "https://receive-sms-free.cc/",
    browserFetcher = browserFetcher,
    urlsForNumber = { digits, titleSlug, _ ->
        listOf("https://receive-sms-free.cc/Free-$titleSlug-Phone-Number/$digits/")
    },
)

class ReceiveSmsOnlineAdapter(
    client: OkHttpClient,
    browserFetcher: BrowserDomFetcher? = null,
) : GenericHtmlSmsHostAdapter(
    client = client,
    sourceKey = TempNumberEntity.SOURCE_RECEIVE_SMS_ONLINE,
    displayName = "Receive-SMS-Online.info",
    sourceTag = "receive_sms_online",
    refererRoot = "https://receive-sms-online.info/",
    browserFetcher = browserFetcher,
    urlsForNumber = { digits, titleSlug, _ ->
        listOf("https://receive-sms-online.info/$digits-$titleSlug")
    },
)

/**
 * Legacy multi-host mirror adapter retained for backwards compatibility with any saved
 * SOURCE_PUBLIC_MIRROR entries. Cascades through Quackr → TempSMSS → Receive-SMS-Free → Receive-SMS-Online.
 */
class PublicSmsMirrorAdapter(
    private val quackr: QuackrAdapter,
    private val tempSmss: TempSmssAdapter,
    private val receiveSmsFree: ReceiveSmsFreeAdapter,
    private val receiveSmsOnline: ReceiveSmsOnlineAdapter,
) {
    constructor(client: OkHttpClient) : this(
        quackr = QuackrAdapter(client),
        tempSmss = TempSmssAdapter(client),
        receiveSmsFree = ReceiveSmsFreeAdapter(client),
        receiveSmsOnline = ReceiveSmsOnlineAdapter(client),
    )

    suspend fun fetch(e164: String, force: Boolean = false): TempFetchResult {
        val allAttempts = mutableListOf<SourceProbeAttempt>()
        val adapters = listOf(quackr, tempSmss, receiveSmsFree, receiveSmsOnline)
        var lastFailure: TempFetchResult.Failure? = null

        for (adapter in adapters) {
            when (val res = adapter.fetch(e164, force)) {
                is TempFetchResult.Success -> {
                    allAttempts.addAll(res.attempts)
                    if (res.messages.isNotEmpty()) {
                        return res.copy(attempts = allAttempts.toList())
                    }
                }
                is TempFetchResult.Failure -> {
                    allAttempts.addAll(res.attempts)
                    lastFailure = res
                }
                TempFetchResult.SkippedInProgress -> Unit
            }
        }
        if (allAttempts.any { it.outcome == ProbeOutcome.EMPTY_INBOX }) {
            return TempFetchResult.Success(
                messages = emptyList(),
                resolvedSource = TempNumberEntity.SOURCE_PUBLIC_MIRROR,
                attempts = allAttempts.toList(),
            )
        }
        return lastFailure?.copy(attempts = allAttempts.toList()) ?: TempFetchResult.Failure(
            status = TempNumberEntity.STATUS_NOT_FOUND,
            errorMessage = "Number not found on public SMS mirrors.",
            attempts = allAttempts.toList(),
        )
    }

    internal fun parseMirrorHtml(
        html: String,
        hostLabel: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<List<TempParsedMessage>, String?> {
        return quackr.parseHostHtml(html, nowMs)
    }
}

internal fun parseGenericHtmlTables(
    html: String,
    sourceTag: String,
    fallbackSender: String,
    nowMs: Long,
): List<TempParsedMessage> {
    val trRegex = Regex("""<tr[^>]*>([\s\S]*?)</tr>""", RegexOption.IGNORE_CASE)
    val tdRegex = Regex("""<td[^>]*>([\s\S]*?)</td>""", RegexOption.IGNORE_CASE)
    val out = mutableListOf<TempParsedMessage>()

    for (rowMatch in trRegex.findAll(html)) {
        val cells = tdRegex.findAll(rowMatch.groupValues[1]).map { it.groupValues[1] }.toList()
        if (cells.size < 2) continue
        val decoded = cells.map { HtmlEntityDecoder.decodeHtml(it).trim() }
        val firstLower = decoded[0].lowercase()
        if (firstLower == "from" || firstLower == "sender" || firstLower == "time" || firstLower == "date") continue

        val (senderRaw, bodyRaw, ts) = if (cells.size >= 3) {
            val ts0 = FlexibleTimestampParser.extractFromHtmlOrText(cells[0], nowMs)
            val ts1 = FlexibleTimestampParser.extractFromHtmlOrText(cells[1], nowMs)
            val ts2 = FlexibleTimestampParser.extractFromHtmlOrText(cells[2], nowMs)
            when {
                ts0.isExplicit -> Triple(decoded[1], decoded[2], ts0)
                ts1.isExplicit && decoded[2].length >= decoded[1].length -> Triple(decoded[0], decoded[2], ts1)
                ts2.isExplicit -> Triple(decoded[0], decoded[1], ts2)
                decoded[2].length > decoded[1].length -> Triple(decoded[0], decoded[2], ts1)
                else -> Triple(decoded[0], decoded[1], ts2)
            }
        } else {
            val ts1 = FlexibleTimestampParser.extractFromHtmlOrText(cells[1], nowMs)
            Triple(decoded[0], decoded[1], ts1)
        }

        val cleanBody = PhoneFormatter.sanitizeBody(bodyRaw, 640)
        if (cleanBody.length < 3) continue
        val cleanSender = senderRaw.replace(Regex("""(?i)^from\s*:?\s*"""), "").trim().take(40)
            .ifEmpty { PhoneFormatter.guessSender("", cleanBody).ifEmpty { fallbackSender } }

        out.add(
            TempParsedMessage(
                fromNumber = cleanSender.take(32),
                senderName = cleanSender,
                body = cleanBody,
                receivedAtEpochMs = ts.epochMs,
                hasExplicitTimestamp = ts.isExplicit,
                sourceTag = sourceTag,
            )
        )
    }
    return out
}
