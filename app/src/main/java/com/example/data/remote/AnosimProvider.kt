package com.example.data.remote

import com.example.data.local.TempNumberEntity
import com.example.domain.PhoneFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

private data class AnosimCacheEntry(
    val fetchedAtMs: Long,
    val result: TempFetchResult.Success,
)

/**
 * Dual-endpoint Anosim.net provider supporting both the default +61485841378 pool line
 * and any user-added Anosim temporary number:
 * 1. Direct hosted country page (https://anosim.net/online-sms/{country-slug}/{digits})
 *    with realistic browser headers + session cookie capture.
 * 2. Cookie-authenticated AJAX feed (https://anosim.net/online-sms/messages?number=%2B{digits})
 *    with Referer and X-Requested-With headers.
 * 3. Balanced multi-pattern HTML parsing and auto-calibrated UTC/Sydney/Berlin timestamp resolution.
 */
class AnosimProvider(private val client: OkHttpClient) {

    private val mutex = Mutex()
    private val cache = ConcurrentHashMap<String, AnosimCacheEntry>()

    @Volatile
    private var lastFetchMs: Long = 0L

    suspend fun fetchMessages(force: Boolean = false): Result<List<ParsedRemoteSms>> {
        val now = System.currentTimeMillis()
        if (!force && (now - lastFetchMs) < MIN_GAP_MS) {
            return Result.success(emptyList())
        }
        if (!mutex.tryLock()) {
            return Result.success(emptyList())
        }
        return try {
            lastFetchMs = System.currentTimeMillis()
            when (val res = fetchForNumber(E164, force = force)) {
                is TempFetchResult.Success -> Result.success(
                    res.messages.map { m ->
                        ParsedRemoteSms(
                            numberId = NUMBER_ID,
                            fromNumber = m.fromNumber,
                            senderName = m.senderName,
                            body = m.body,
                            receivedAtEpochMs = m.receivedAtEpochMs,
                            source = "anosim",
                        )
                    }
                )
                is TempFetchResult.Failure -> Result.failure(IllegalStateException(res.errorMessage))
            }
        } finally {
            mutex.unlock()
        }
    }

    suspend fun fetchForNumber(e164: String, force: Boolean = false): TempFetchResult {
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
        val countrySlug = countryName.lowercase().replace(" ", "-")
        val directPageUrl = "https://anosim.net/online-sms/$countrySlug/$digits"
        val encodedE164 = URLEncoder.encode("+$digits", StandardCharsets.UTF_8.name())
        val ajaxPlusUrl = "https://anosim.net/online-sms/messages?number=$encodedE164"
        val ajaxDigitsUrl = "https://anosim.net/online-sms/messages?number=$digits"

        return withContext(Dispatchers.IO) {
            val attempts = mutableListOf<SourceProbeAttempt>()
            val mergedMessages = mutableListOf<TempParsedMessage>()
            var sessionCookieHeader: String? = null
            var directBlocked = false
            var directExplicitEmpty = false
            var directNotFound = false

            // Probe 1: Direct hosted number page (e.g. https://anosim.net/online-sms/australia/61485841378)
            val directStart = System.currentTimeMillis()
            try {
                val directReq = BrowserHttpHeaders.applyHtmlHeaders(
                    Request.Builder().url(directPageUrl).get(),
                    referer = "https://anosim.net/online-sms/$countrySlug",
                ).build()

                client.newCall(directReq).execute().use { response ->
                    val latency = (System.currentTimeMillis() - directStart).coerceAtLeast(1L)
                    val html = response.body?.string().orEmpty()
                    val snippet = BotWallDetector.buildSnippet(html)
                    val cookies = response.headers("Set-Cookie")
                        .mapNotNull { it.substringBefore(";").trim().ifBlank { null } }
                    if (cookies.isNotEmpty()) {
                        sessionCookieHeader = cookies.joinToString("; ")
                    }
                    val botCheck = BotWallDetector.detect(response.code, html)
                    val refNowMs = response.headers.getDate("Date")?.time ?: System.currentTimeMillis()

                    if (botCheck.isBlocked) {
                        directBlocked = true
                        attempts.add(
                            SourceProbeAttempt(
                                sourceKey = TempNumberEntity.SOURCE_ANOSIM,
                                displayName = "Anosim.net (Page)",
                                url = directPageUrl,
                                httpCode = response.code,
                                latencyMs = latency,
                                payloadBytes = html.toByteArray().size,
                                botChallengeDetected = true,
                                outcome = ProbeOutcome.BOT_BLOCKED,
                                detailMessage = botCheck.reason ?: "Anti-bot wall",
                                responseSnippet = snippet,
                            )
                        )
                    } else if (response.code in 200..299) {
                        val (parsed, strategy) = parseHtmlWithStrategy(html, refNowMs)
                        val lower = html.lowercase()
                        val explicitEmpty = lower.contains("no messages") ||
                            lower.contains("no sms") ||
                            lower.contains("waiting for sms")
                        if (explicitEmpty) directExplicitEmpty = true

                        val outcome = when {
                            parsed.isNotEmpty() -> ProbeOutcome.SUCCESS_WITH_MESSAGES
                            explicitEmpty || html.length < 1_500 -> ProbeOutcome.EMPTY_INBOX
                            else -> ProbeOutcome.PARSE_MISMATCH
                        }
                        mergedMessages.addAll(parsed)
                        attempts.add(
                            SourceProbeAttempt(
                                sourceKey = TempNumberEntity.SOURCE_ANOSIM,
                                displayName = "Anosim.net (Page)",
                                url = directPageUrl,
                                httpCode = response.code,
                                latencyMs = latency,
                                payloadBytes = html.toByteArray().size,
                                parserStrategy = strategy,
                                parsedCount = parsed.size,
                                outcome = outcome,
                                detailMessage = when (outcome) {
                                    ProbeOutcome.SUCCESS_WITH_MESSAGES -> "Parsed ${parsed.size} messages directly from hosted page ($strategy)"
                                    ProbeOutcome.EMPTY_INBOX -> "Hosted page reachable with 0 messages"
                                    else -> "Hosted page loaded (${html.length} chars) — checking AJAX message feed"
                                },
                                responseSnippet = snippet,
                            )
                        )
                    } else {
                        if (response.code == 404) directNotFound = true
                        attempts.add(
                            SourceProbeAttempt(
                                sourceKey = TempNumberEntity.SOURCE_ANOSIM,
                                displayName = "Anosim.net (Page)",
                                url = directPageUrl,
                                httpCode = response.code,
                                latencyMs = latency,
                                payloadBytes = html.toByteArray().size,
                                outcome = if (response.code == 404) ProbeOutcome.NOT_FOUND else ProbeOutcome.ERROR,
                                detailMessage = "Hosted page returned HTTP ${response.code}",
                                responseSnippet = snippet,
                            )
                        )
                    }
                }
            } catch (io: IOException) {
                attempts.add(
                    SourceProbeAttempt(
                        sourceKey = TempNumberEntity.SOURCE_ANOSIM,
                        displayName = "Anosim.net (Page)",
                        url = directPageUrl,
                        latencyMs = (System.currentTimeMillis() - directStart).coerceAtLeast(1L),
                        outcome = ProbeOutcome.OFFLINE,
                        detailMessage = "Network unreachable (${io.javaClass.simpleName})",
                    )
                )
            }

            // Probe 2: Cookie-authenticated AJAX feed with Referer (plus non-plus fallback if needed)
            val ajaxCandidates = listOf(
                "Anosim.net (AJAX)" to ajaxPlusUrl,
                "Anosim.net (AJAX Alt)" to ajaxDigitsUrl,
            )
            for ((label, ajaxUrl) in ajaxCandidates) {
                // Skip second AJAX variant if we already parsed messages
                if (label.contains("Alt") && mergedMessages.isNotEmpty()) break
                val ajaxStart = System.currentTimeMillis()
                try {
                    val reqBuilder = BrowserHttpHeaders.applyHtmlHeaders(
                        Request.Builder().url(ajaxUrl).get(),
                        referer = directPageUrl,
                    )
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Sec-Fetch-Dest", "empty")
                        .header("Sec-Fetch-Mode", "cors")
                        .header("Sec-Fetch-Site", "same-origin")
                    if (!sessionCookieHeader.isNullOrBlank()) {
                        reqBuilder.header("Cookie", sessionCookieHeader!!)
                    }

                    client.newCall(reqBuilder.build()).execute().use { response ->
                        val latency = (System.currentTimeMillis() - ajaxStart).coerceAtLeast(1L)
                        val body = response.body?.string().orEmpty()
                        val snippet = BotWallDetector.buildSnippet(body)
                        val botCheck = BotWallDetector.detect(response.code, body)
                        val refNowMs = response.headers.getDate("Date")?.time ?: System.currentTimeMillis()

                        if (botCheck.isBlocked) {
                            attempts.add(
                                SourceProbeAttempt(
                                    sourceKey = TempNumberEntity.SOURCE_ANOSIM,
                                    displayName = label,
                                    url = ajaxUrl,
                                    httpCode = response.code,
                                    latencyMs = latency,
                                    payloadBytes = body.toByteArray().size,
                                    botChallengeDetected = true,
                                    outcome = ProbeOutcome.BOT_BLOCKED,
                                    detailMessage = botCheck.reason ?: "Anti-bot wall",
                                    responseSnippet = snippet,
                                )
                            )
                        } else if (response.code in 200..299) {
                            val (parsed, strategy) = parseHtmlWithStrategy(body, refNowMs)
                            val outcome = when {
                                parsed.isNotEmpty() -> ProbeOutcome.SUCCESS_WITH_MESSAGES
                                body.isBlank() || body.length < 300 || body.lowercase().contains("no message") -> ProbeOutcome.EMPTY_INBOX
                                else -> ProbeOutcome.PARSE_MISMATCH
                            }
                            if (parsed.isNotEmpty()) {
                                mergeUniqueMessages(mergedMessages, parsed)
                            }
                            attempts.add(
                                SourceProbeAttempt(
                                    sourceKey = TempNumberEntity.SOURCE_ANOSIM,
                                    displayName = label,
                                    url = ajaxUrl,
                                    httpCode = response.code,
                                    latencyMs = latency,
                                    payloadBytes = body.toByteArray().size,
                                    parserStrategy = strategy,
                                    parsedCount = parsed.size,
                                    outcome = outcome,
                                    detailMessage = when (outcome) {
                                        ProbeOutcome.SUCCESS_WITH_MESSAGES -> "AJAX feed returned ${parsed.size} messages ($strategy)"
                                        ProbeOutcome.EMPTY_INBOX -> "AJAX feed reachable with 0 messages"
                                        else -> "AJAX feed returned ${body.length} chars with 0 matched items"
                                    },
                                    responseSnippet = snippet,
                                )
                            )
                        } else {
                            attempts.add(
                                SourceProbeAttempt(
                                    sourceKey = TempNumberEntity.SOURCE_ANOSIM,
                                    displayName = label,
                                    url = ajaxUrl,
                                    httpCode = response.code,
                                    latencyMs = latency,
                                    payloadBytes = body.toByteArray().size,
                                    outcome = when (response.code) {
                                        404 -> ProbeOutcome.NOT_FOUND
                                        429 -> ProbeOutcome.RATE_LIMITED
                                        else -> ProbeOutcome.ERROR
                                    },
                                    detailMessage = "AJAX returned HTTP ${response.code}",
                                    responseSnippet = snippet,
                                )
                            )
                        }
                    }
                } catch (io: IOException) {
                    attempts.add(
                        SourceProbeAttempt(
                            sourceKey = TempNumberEntity.SOURCE_ANOSIM,
                            displayName = label,
                            url = ajaxUrl,
                            latencyMs = (System.currentTimeMillis() - ajaxStart).coerceAtLeast(1L),
                            outcome = ProbeOutcome.OFFLINE,
                            detailMessage = "Network error (${io.javaClass.simpleName})",
                        )
                    )
                }
            }

            if (mergedMessages.isNotEmpty()) {
                val sorted = mergedMessages.sortedByDescending { it.receivedAtEpochMs }
                val success = TempFetchResult.Success(
                    messages = sorted,
                    resolvedSource = TempNumberEntity.SOURCE_ANOSIM,
                    attempts = attempts,
                )
                cache[digits] = AnosimCacheEntry(System.currentTimeMillis(), success)
                return@withContext success
            }

            // Classify failure cleanly when 0 messages were recovered
            val anyOfflineAll = attempts.isNotEmpty() && attempts.all { it.outcome == ProbeOutcome.OFFLINE }
            val anyBotBlocked = directBlocked || attempts.any { it.outcome == ProbeOutcome.BOT_BLOCKED }
            val anyRateLimited = attempts.any { it.outcome == ProbeOutcome.RATE_LIMITED }
            val anyParseMismatch = attempts.any { it.outcome == ProbeOutcome.PARSE_MISMATCH }

            when {
                anyOfflineAll -> TempFetchResult.Failure(
                    status = TempNumberEntity.STATUS_OFFLINE,
                    errorMessage = "Could not reach Anosim.net — check internet connection.",
                    attempts = attempts,
                )
                anyBotBlocked -> TempFetchResult.Failure(
                    status = TempNumberEntity.STATUS_BOT_BLOCKED,
                    errorMessage = "Anosim.net returned an anti-bot challenge. Tap Inspect for details.",
                    attempts = attempts,
                )
                anyRateLimited -> TempFetchResult.Failure(
                    status = TempNumberEntity.STATUS_RATE_LIMITED,
                    errorMessage = "Anosim.net rate limited this request (HTTP 429).",
                    retryAfterMs = 30_000L,
                    attempts = attempts,
                )
                directExplicitEmpty || attempts.any { it.outcome == ProbeOutcome.EMPTY_INBOX } -> TempFetchResult.Success(
                    messages = emptyList(),
                    resolvedSource = TempNumberEntity.SOURCE_ANOSIM,
                    attempts = attempts,
                )
                directNotFound && attempts.all { it.outcome == ProbeOutcome.NOT_FOUND || it.outcome == ProbeOutcome.ERROR } -> TempFetchResult.Failure(
                    status = TempNumberEntity.STATUS_NOT_FOUND,
                    errorMessage = "Number not listed on Anosim.net (HTTP 404).",
                    attempts = attempts,
                )
                anyParseMismatch -> TempFetchResult.Failure(
                    status = TempNumberEntity.STATUS_ERROR,
                    errorMessage = "Anosim.net loaded (HTTP 200) but matched 0 SMS blocks — tap Inspect to view snippet.",
                    attempts = attempts,
                )
                else -> TempFetchResult.Failure(
                    status = TempNumberEntity.STATUS_ERROR,
                    errorMessage = "Anosim.net sync failed across ${attempts.size} probes.",
                    attempts = attempts,
                )
            }
        }
    }

    private fun mergeUniqueMessages(
        target: MutableList<TempParsedMessage>,
        incoming: List<TempParsedMessage>,
    ) {
        val seen = target.map { "${it.senderName.lowercase()}|${it.body.lowercase()}" }.toMutableSet()
        for (msg in incoming) {
            val key = "${msg.senderName.lowercase()}|${msg.body.lowercase()}"
            if (seen.add(key)) {
                target.add(msg)
            }
        }
    }

    internal fun parseHtml(html: String, nowMs: Long = System.currentTimeMillis()): List<ParsedRemoteSms> {
        return parseHtmlWithStrategy(html, nowMs).first.map { m ->
            ParsedRemoteSms(
                numberId = NUMBER_ID,
                fromNumber = m.fromNumber,
                senderName = m.senderName,
                body = m.body,
                receivedAtEpochMs = m.receivedAtEpochMs,
                source = "anosim",
            )
        }
    }

    internal fun parseHtmlWithStrategy(
        html: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<List<TempParsedMessage>, String?> {
        val out = mutableListOf<TempParsedMessage>()

        // Strategy A: Flexible balanced extraction for list-group-item / message-item / sms-item divs or lis
        val itemBlocks = BalancedHtmlExtractor.extractBalancedElements(
            html = html,
            tagName = "div",
            openTagRegex = messageItemDivOpenRegex,
        ) + BalancedHtmlExtractor.extractBalancedElements(
            html = html,
            tagName = "li",
            openTagRegex = messageItemLiOpenRegex,
        )

        for (block in itemBlocks) {
            val senderRaw = headingRegex.find(block)?.groupValues?.getOrNull(1)
                ?: senderSpanRegex.find(block)?.groupValues?.getOrNull(1)
                ?: strongRegex.find(block)?.groupValues?.getOrNull(1)
                ?: ""
            val whenRawHtml = timeTagRegex.find(block)?.value ?: ""
            val bodyRaw = pBodyRegex.find(block)?.groupValues?.getOrNull(1)
                ?: pAnyRegex.find(block)?.groupValues?.getOrNull(1)
                ?: msgBodyDivRegex.find(block)?.groupValues?.getOrNull(1)
                ?: ""

            val body = PhoneFormatter.sanitizeBody(HtmlEntityDecoder.decodeHtml(bodyRaw), 640)
            if (body.isEmpty()) continue

            val sender = HtmlEntityDecoder.decodeHtml(senderRaw)
                .replace(Regex("""(?i)^from\s*:?\s*"""), "")
                .trim()
                .take(40)
                .ifEmpty { PhoneFormatter.guessSender("", body).ifEmpty { "Anosim" } }

            val ts = parseAnosimTimestamp(whenRawHtml.ifBlank { block }, nowMs)
            out.add(
                TempParsedMessage(
                    fromNumber = sender.take(32),
                    senderName = sender,
                    body = body,
                    receivedAtEpochMs = ts.epochMs,
                    hasExplicitTimestamp = ts.isExplicit,
                    sourceTag = "anosim",
                )
            )
        }
        if (out.isNotEmpty()) return out to "anosim-message-items"

        // Strategy B: Generic HTML table rows fallback
        val tableOut = parseGenericHtmlTables(html, "anosim", "Anosim", nowMs)
        if (tableOut.isNotEmpty()) return tableOut to "html-table"

        return emptyList<TempParsedMessage>() to null
    }

    internal fun parseAnosimTime(raw: String, nowMs: Long = System.currentTimeMillis()): Long {
        return parseAnosimTimestamp(raw, nowMs).epochMs
    }

    /**
     * Auto-calibrated Anosim timestamp resolver:
     * 1. Extracts explicit HTML attributes (data-created / datetime) or ISO-8601 / relative phrases first.
     * 2. For clock strings without explicit timezone (e.g. "11/23/2026 02:15 PM" or "23/11/2026 14:15"),
     *    evaluates UTC, Australia/Sydney (DST-aware), and Europe/Berlin (CET/CEST) and selects the most
     *    recent non-future candidate to prevent 1–11 hour backward timezone drift.
     */
    internal fun parseAnosimTimestamp(rawHtmlOrText: String, nowMs: Long = System.currentTimeMillis()): ParsedTimestamp {
        val rawDecoded = HtmlEntityDecoder.decodeHtml(rawHtmlOrText).trim()

        // 1. Check explicit HTML attributes (data-created / datetime) or ISO-8601 (YYYY-MM-DD) first
        val hasExplicitAttr = rawHtmlOrText.contains("data-created", ignoreCase = true) ||
            rawHtmlOrText.contains("datetime", ignoreCase = true) ||
            rawHtmlOrText.contains("data-time", ignoreCase = true)
        val hasIsoDate = Regex("""\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}""").containsMatchIn(rawDecoded)
        if (hasExplicitAttr || hasIsoDate) {
            val attrOrIso = FlexibleTimestampParser.extractFromHtmlOrText(rawHtmlOrText, nowMs)
            if (attrOrIso.isExplicit) return attrOrIso
        }

        // 2. Check AM/PM 12-hour format: MM/DD/YYYY hh:mm AM/PM (or DD/MM/YYYY if first > 12)
        date12hRegex.find(rawDecoded)?.let { m ->
            val p1 = m.groupValues[1].toIntOrNull()
            val p2 = m.groupValues[2].toIntOrNull()
            val year = m.groupValues[3].toIntOrNull()
            var hour = m.groupValues[4].toIntOrNull()
            val minute = m.groupValues[5].toIntOrNull()
            val second = m.groupValues[6].toIntOrNull() ?: 0
            val amPm = m.groupValues[7].uppercase()
            if (p1 != null && p2 != null && year != null && hour != null && minute != null) {
                if (amPm == "PM" && hour < 12) hour += 12
                if (amPm == "AM" && hour == 12) hour = 0
                val (month, day) = if (p1 > 12) p2 to p1 else p1 to p2
                val best = resolveBestTimezoneEpochMs(year, month, day, hour, minute, second, nowMs)
                return ParsedTimestamp(best, isExplicit = true)
            }
        }

        // 3. Check 24-hour slash/dot format: DD/MM/YYYY HH:mm or MM/DD/YYYY HH:mm
        date24hRegex.find(rawDecoded)?.let { m ->
            val p1 = m.groupValues[1].toIntOrNull()
            val p2 = m.groupValues[2].toIntOrNull()
            val year = m.groupValues[3].toIntOrNull()
            val hour = m.groupValues[4].toIntOrNull()
            val minute = m.groupValues[5].toIntOrNull()
            val second = m.groupValues[6].toIntOrNull() ?: 0
            if (p1 != null && p2 != null && year != null && hour != null && minute != null) {
                val candidates = mutableListOf<Long>()
                if (p1 in 1..12 && p2 in 1..31) {
                    candidates.add(resolveBestTimezoneEpochMs(year, p1, p2, hour, minute, second, nowMs))
                }
                if (p2 in 1..12 && p1 in 1..31 && p1 != p2) {
                    candidates.add(resolveBestTimezoneEpochMs(year, p2, p1, hour, minute, second, nowMs))
                }
                val nonFuture = candidates.filter { it <= nowMs + MAX_FUTURE_SKEW_MS }
                val best = nonFuture.maxOrNull() ?: candidates.minByOrNull { kotlin.math.abs(nowMs - it) } ?: nowMs
                return ParsedTimestamp(best.coerceAtMost(nowMs), isExplicit = true)
            }
        }

        // 4. Fallback to relative phrases ("3 minutes ago", "just now", etc.)
        return FlexibleTimestampParser.extractFromHtmlOrText(rawHtmlOrText, nowMs)
    }

    private fun resolveBestTimezoneEpochMs(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
        nowMs: Long,
    ): Long {
        val zoneIds = listOf("UTC", "Australia/Sydney", "Europe/Berlin")
        val candidates = zoneIds.map { zoneId ->
            Calendar.getInstance(TimeZone.getTimeZone(zoneId)).apply {
                clear()
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, (month - 1).coerceIn(0, 11))
                set(Calendar.DAY_OF_MONTH, day.coerceIn(1, 31))
                set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
                set(Calendar.MINUTE, minute.coerceIn(0, 59))
                set(Calendar.SECOND, second.coerceIn(0, 59))
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
        val valid = candidates.filter { it <= nowMs + MAX_FUTURE_SKEW_MS }
        return if (valid.isNotEmpty()) {
            // Most recent non-future match avoids shifting a live UTC message 11h backward
            valid.max().coerceAtMost(nowMs)
        } else {
            candidates.minByOrNull { kotlin.math.abs(nowMs - it) }?.coerceAtMost(nowMs) ?: nowMs
        }
    }

    companion object {
        const val NUMBER_ID = "61485841378"
        const val E164 = "+61485841378"
        private const val MIN_GAP_MS = 8_000L
        private const val CACHE_TTL_MS = 12_000L
        private const val MAX_FUTURE_SKEW_MS = 5L * 60L * 1000L

        private val messageItemDivOpenRegex = Regex(
            """<div[^>]*class=["'][^"']*(?:message-item|sms-item|list-group-item)[^"']*["'][^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        private val messageItemLiOpenRegex = Regex(
            """<li[^>]*class=["'][^"']*(?:message-item|sms-item|list-group-item)[^"']*["'][^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        private val headingRegex = Regex("""<h[456][^>]*>([\s\S]*?)</h[456]>""", RegexOption.IGNORE_CASE)
        private val senderSpanRegex = Regex("""<span[^>]*class=["'][^"']*(?:sender|from|fw-bold|font-weight-bold)[^"']*["'][^>]*>([\s\S]*?)</span>""", RegexOption.IGNORE_CASE)
        private val strongRegex = Regex("""<(?:strong|b)[^>]*>([\s\S]*?)</(?:strong|b)>""", RegexOption.IGNORE_CASE)
        private val timeTagRegex = Regex("""<(?:small|time)[^>]*>[\s\S]*?</(?:small|time)>""", RegexOption.IGNORE_CASE)
        private val pBodyRegex = Regex("""<p[^>]*class=["'][^"']*(?:mb-0|message|sms-text|text-break)[^"']*["'][^>]*>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)
        private val pAnyRegex = Regex("""<p[^>]*>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)
        private val msgBodyDivRegex = Regex("""<div[^>]*class=["'][^"']*(?:message-body|sms-body|text-break)[^"']*["'][^>]*>([\s\S]*?)</div>""", RegexOption.IGNORE_CASE)
        private val date12hRegex = Regex("""(\d{1,2})[./-](\d{1,2})[./-](\d{4})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?\s*(AM|PM)""", RegexOption.IGNORE_CASE)
        private val date24hRegex = Regex("""(\d{1,2})[./-](\d{1,2})[./-](\d{4})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?""")
    }
}
