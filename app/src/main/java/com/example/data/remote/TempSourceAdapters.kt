package com.example.data.remote

import com.example.data.local.CustomSourceEntity
import com.example.data.local.TempNumberEntity
import com.example.domain.PhoneFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

data class TempParsedMessage(
    val fromNumber: String,
    val senderName: String,
    val body: String,
    val explicitCode: String? = null,
    val serviceName: String? = null,
    val receivedAtEpochMs: Long,
    val hasExplicitTimestamp: Boolean = true,
    val sourceTag: String,
)

sealed class TempFetchResult {
    abstract val attempts: List<SourceProbeAttempt>

    data class Success(
        val messages: List<TempParsedMessage>,
        val resolvedSource: String,
        val fromCache: Boolean = false,
        override val attempts: List<SourceProbeAttempt> = emptyList(),
    ) : TempFetchResult()

    data class Failure(
        val status: String,
        val errorMessage: String,
        val retryAfterMs: Long? = null,
        override val attempts: List<SourceProbeAttempt> = emptyList(),
    ) : TempFetchResult()
}

private data class CacheEntry(
    val fetchedAtMs: Long,
    val result: TempFetchResult.Success,
)

data class ParsedTimestamp(
    val epochMs: Long,
    val isExplicit: Boolean,
)

object FlexibleTimestampParser {

    private val attrTimestampRegex = Regex(
        """(?i)\b(?:data-created|data-time|data-timestamp|data-date|datetime|title)\s*=\s*["']([^"']{4,48})["']"""
    )
    private val isoRegex = Regex(
        """(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?(?:\.(\d+))?(Z|[+-]\d{2}:?\d{2})?"""
    )
    private val dmyRegex = Regex(
        """(\d{1,2})[./-](\d{1,2})[./-](\d{4})[T ](\d{1,2}):(\d{2})(?::(\d{2}))?(?:\s*(AM|PM))?""",
        RegexOption.IGNORE_CASE,
    )
    private val relativeNumericRegex = Regex(
        """(?i)(\d+)\s*(sec(?:ond)?s?|s|min(?:ute)?s?|m|hour?s?|hr?s?|h|day?s?|d|week?s?|w)(?:\s*ago)?\b"""
    )
    private val relativeSingularRegex = Regex(
        """(?i)\b(?:a|an|one)\s+(few\s+seconds?|sec(?:ond)?|min(?:ute)?|hour|hr|day|week)\s*ago\b"""
    )

    fun extractFromHtmlOrText(rawHtml: String?, nowMs: Long = System.currentTimeMillis()): ParsedTimestamp {
        if (rawHtml.isNullOrBlank()) return ParsedTimestamp(nowMs, isExplicit = false)
        // Inspect raw HTML attributes like data-created="2026-11-24 05:12:00" BEFORE stripping tags
        for (match in attrTimestampRegex.findAll(rawHtml)) {
            val candidate = parse(match.groupValues[1], nowMs)
            if (candidate.isExplicit) return candidate
        }
        val decoded = HtmlEntityDecoder.decodeHtml(rawHtml)
        return parse(decoded, nowMs)
    }

    fun parseToEpochMs(raw: String?, nowMs: Long = System.currentTimeMillis()): Long {
        return parse(raw, nowMs).epochMs
    }

    fun parse(raw: String?, nowMs: Long = System.currentTimeMillis()): ParsedTimestamp {
        if (raw.isNullOrBlank()) return ParsedTimestamp(nowMs, isExplicit = false)
        val trimmed = raw.trim()
        val lower = trimmed.lowercase()

        if (lower == "just now" || lower == "now" || lower.contains("moments ago") || lower.contains("a few seconds")) {
            return ParsedTimestamp(nowMs, isExplicit = false)
        }
        if (lower == "yesterday" || lower.startsWith("yesterday")) {
            return ParsedTimestamp((nowMs - 86_400_000L).coerceAtLeast(0L), isExplicit = false)
        }

        // Pure numeric epoch seconds or milliseconds
        trimmed.toLongOrNull()?.let { numeric ->
            return when {
                numeric > 1_000_000_000_000L -> ParsedTimestamp(numeric, isExplicit = true)
                numeric > 1_000_000_000L -> ParsedTimestamp(numeric * 1_000L, isExplicit = true)
                else -> ParsedTimestamp(nowMs, isExplicit = false)
            }
        }

        // ISO-8601 / SQL date-time (highest fidelity — explicit)
        isoRegex.find(trimmed)?.let { m ->
            val year = m.groupValues[1].toIntOrNull() ?: return ParsedTimestamp(nowMs, false)
            val month = m.groupValues[2].toIntOrNull() ?: return ParsedTimestamp(nowMs, false)
            val day = m.groupValues[3].toIntOrNull() ?: return ParsedTimestamp(nowMs, false)
            val hour = m.groupValues[4].toIntOrNull() ?: return ParsedTimestamp(nowMs, false)
            val minute = m.groupValues[5].toIntOrNull() ?: return ParsedTimestamp(nowMs, false)
            val second = m.groupValues[6].toIntOrNull() ?: 0
            val tzRaw = m.groupValues[8]
            val tz = when {
                tzRaw.isEmpty() || tzRaw.equals("Z", ignoreCase = true) -> TimeZone.getTimeZone("UTC")
                else -> TimeZone.getTimeZone("GMT$tzRaw")
            }
            val parsed = buildEpochMs(year, month, day, hour, minute, second, tz, nowMs)
            return ParsedTimestamp(parsed, isExplicit = true)
        }

        // DD/MM/YYYY HH:mm or MM/DD/YYYY hh:mm AM/PM
        dmyRegex.find(trimmed)?.let { m ->
            val p1 = m.groupValues[1].toIntOrNull() ?: return ParsedTimestamp(nowMs, false)
            val p2 = m.groupValues[2].toIntOrNull() ?: return ParsedTimestamp(nowMs, false)
            val year = m.groupValues[3].toIntOrNull() ?: return ParsedTimestamp(nowMs, false)
            var hour = m.groupValues[4].toIntOrNull() ?: return ParsedTimestamp(nowMs, false)
            val minute = m.groupValues[5].toIntOrNull() ?: return ParsedTimestamp(nowMs, false)
            val second = m.groupValues[6].toIntOrNull() ?: 0
            val amPm = m.groupValues[7].uppercase()
            if (amPm == "PM" && hour < 12) hour += 12
            if (amPm == "AM" && hour == 12) hour = 0
            val (day, month) = if (p2 > 12) p2 to p1 else p1 to p2
            val parsed = buildEpochMs(year, month, day, hour, minute, second, TimeZone.getTimeZone("UTC"), nowMs)
            return ParsedTimestamp(parsed, isExplicit = true)
        }

        // Singular natural language: "a minute ago", "an hour ago", "a day ago"
        relativeSingularRegex.find(trimmed)?.let { m ->
            val unit = m.groupValues[1].lowercase()
            val deltaMs = when {
                unit.contains("sec") -> 10_000L
                unit.startsWith("min") -> 60_000L
                unit.startsWith("h") -> 3_600_000L
                unit.startsWith("d") -> 86_400_000L
                unit.startsWith("w") -> 7 * 86_400_000L
                else -> 0L
            }
            return ParsedTimestamp((nowMs - deltaMs).coerceAtLeast(0L), isExplicit = false)
        }

        // Relative English expressions: "5 minutes ago", "2h ago", "12 min"
        relativeNumericRegex.find(trimmed)?.let { match ->
            val amount = match.groupValues[1].toLongOrNull() ?: return ParsedTimestamp(nowMs, false)
            val unit = match.groupValues[2].lowercase()
            val deltaMs = when {
                unit.startsWith("s") -> amount * 1_000L
                unit.startsWith("m") -> amount * 60_000L
                unit.startsWith("h") -> amount * 3_600_000L
                unit.startsWith("d") -> amount * 86_400_000L
                unit.startsWith("w") -> amount * 7 * 86_400_000L
                else -> 0L
            }
            return ParsedTimestamp((nowMs - deltaMs).coerceAtLeast(0L), isExplicit = false)
        }

        return ParsedTimestamp(nowMs, isExplicit = false)
    }

    private fun buildEpochMs(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
        tz: TimeZone,
        nowMs: Long,
    ): Long {
        val cal = Calendar.getInstance(tz).apply {
            clear()
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, (month - 1).coerceIn(0, 11))
            set(Calendar.DAY_OF_MONTH, day.coerceIn(1, 31))
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, second.coerceIn(0, 59))
            set(Calendar.MILLISECOND, 0)
        }
        val parsed = cal.timeInMillis
        return if (parsed > nowMs + 5 * 60_000L) nowMs else parsed
    }
}

/**
 * Primary clean JSON API adapter:
 * GET https://getvirtualphonenumber.com/api/v1/sms?number={E.164}
 * Returns array of objects with from, text, code, received, service.
 * Enforces a 15-second per-number cache and respects HTTP 429 Retry-After.
 */
class GetVirtualPhoneApiAdapter(private val client: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val rateLimitUntil = ConcurrentHashMap<String, Long>()

    suspend fun fetch(e164: String, force: Boolean = false): TempFetchResult {
        val encoded = URLEncoder.encode(e164, StandardCharsets.UTF_8.name())
        val url = "$BASE_URL?number=$encoded"
        val now = System.currentTimeMillis()
        val blockedUntil = rateLimitUntil[e164] ?: 0L
        if (now < blockedUntil) {
            val remainingSec = ((blockedUntil - now) / 1000L).coerceAtLeast(1L)
            val attempt = SourceProbeAttempt(
                sourceKey = TempNumberEntity.SOURCE_GET_VIRTUAL_PHONE_API,
                displayName = "GetVirtualPhoneNumber API",
                url = url,
                httpCode = 429,
                outcome = ProbeOutcome.RATE_LIMITED,
                detailMessage = "Local 429 cooldown active (${remainingSec}s left)",
            )
            return TempFetchResult.Failure(
                status = TempNumberEntity.STATUS_RATE_LIMITED,
                errorMessage = "Rate limited by API. Retrying in ${remainingSec}s.",
                retryAfterMs = blockedUntil - now,
                attempts = listOf(attempt),
            )
        }

        val cached = cache[e164]
        if (!force && cached != null && (now - cached.fetchedAtMs) < CACHE_TTL_MS) {
            return cached.result.copy(
                fromCache = true,
                attempts = cached.result.attempts.map { it.copy(fromCache = true) },
            )
        }

        val started = System.currentTimeMillis()
        return withContext(Dispatchers.IO) {
            try {
                val request = BrowserHttpHeaders.applyJsonHeaders(
                    Request.Builder().url(url).get()
                ).build()

                client.newCall(request).execute().use { response ->
                    val latency = (System.currentTimeMillis() - started).coerceAtLeast(1L)
                    val raw = response.body?.string().orEmpty()
                    val snippet = BotWallDetector.buildSnippet(raw)
                    val botCheck = BotWallDetector.detect(response.code, raw)

                    if (botCheck.isBlocked) {
                        val attempt = SourceProbeAttempt(
                            sourceKey = TempNumberEntity.SOURCE_GET_VIRTUAL_PHONE_API,
                            displayName = "GetVirtualPhoneNumber API",
                            url = url,
                            httpCode = response.code,
                            latencyMs = latency,
                            payloadBytes = raw.toByteArray().size,
                            botChallengeDetected = true,
                            outcome = ProbeOutcome.BOT_BLOCKED,
                            detailMessage = botCheck.reason ?: "Anti-bot block",
                            responseSnippet = snippet,
                        )
                        return@withContext TempFetchResult.Failure(
                            status = TempNumberEntity.STATUS_BOT_BLOCKED,
                            errorMessage = "GetVirtualPhoneNumber API blocked (${botCheck.reason ?: "HTTP ${response.code}"}).",
                            attempts = listOf(attempt),
                        )
                    }

                    when (response.code) {
                        in 200..299 -> {
                            val parsed = parseJson(raw)
                            val outcome = if (parsed.isNotEmpty()) ProbeOutcome.SUCCESS_WITH_MESSAGES else ProbeOutcome.EMPTY_INBOX
                            val attempt = SourceProbeAttempt(
                                sourceKey = TempNumberEntity.SOURCE_GET_VIRTUAL_PHONE_API,
                                displayName = "GetVirtualPhoneNumber API",
                                url = url,
                                httpCode = response.code,
                                latencyMs = latency,
                                payloadBytes = raw.toByteArray().size,
                                parserStrategy = "json-v1",
                                parsedCount = parsed.size,
                                outcome = outcome,
                                detailMessage = if (parsed.isNotEmpty()) {
                                    "Received ${parsed.size} JSON SMS records"
                                } else {
                                    "JSON API returned empty array for this number"
                                },
                                responseSnippet = snippet,
                            )
                            val result = TempFetchResult.Success(
                                messages = parsed,
                                resolvedSource = TempNumberEntity.SOURCE_GET_VIRTUAL_PHONE_API,
                                attempts = listOf(attempt),
                            )
                            if (parsed.isNotEmpty()) {
                                cache[e164] = CacheEntry(System.currentTimeMillis(), result)
                            }
                            result
                        }
                        404 -> {
                            val attempt = SourceProbeAttempt(
                                sourceKey = TempNumberEntity.SOURCE_GET_VIRTUAL_PHONE_API,
                                displayName = "GetVirtualPhoneNumber API",
                                url = url,
                                httpCode = 404,
                                latencyMs = latency,
                                payloadBytes = raw.toByteArray().size,
                                outcome = ProbeOutcome.NOT_FOUND,
                                detailMessage = "Number not hosted on GetVirtualPhoneNumber API (HTTP 404)",
                                responseSnippet = snippet,
                            )
                            TempFetchResult.Failure(
                                status = TempNumberEntity.STATUS_NOT_FOUND,
                                errorMessage = "Number not hosted on GetVirtualPhoneNumber API (HTTP 404).",
                                attempts = listOf(attempt),
                            )
                        }
                        429 -> {
                            val retrySec = response.header("Retry-After")?.toLongOrNull()?.coerceIn(10L, 300L) ?: 30L
                            rateLimitUntil[e164] = System.currentTimeMillis() + retrySec * 1_000L
                            val attempt = SourceProbeAttempt(
                                sourceKey = TempNumberEntity.SOURCE_GET_VIRTUAL_PHONE_API,
                                displayName = "GetVirtualPhoneNumber API",
                                url = url,
                                httpCode = 429,
                                latencyMs = latency,
                                outcome = ProbeOutcome.RATE_LIMITED,
                                detailMessage = "Rate limited (HTTP 429 · retry in ${retrySec}s)",
                                responseSnippet = snippet,
                            )
                            TempFetchResult.Failure(
                                status = TempNumberEntity.STATUS_RATE_LIMITED,
                                errorMessage = "API rate limit reached (HTTP 429). Cooling down for ${retrySec}s.",
                                retryAfterMs = retrySec * 1_000L,
                                attempts = listOf(attempt),
                            )
                        }
                        else -> {
                            val attempt = SourceProbeAttempt(
                                sourceKey = TempNumberEntity.SOURCE_GET_VIRTUAL_PHONE_API,
                                displayName = "GetVirtualPhoneNumber API",
                                url = url,
                                httpCode = response.code,
                                latencyMs = latency,
                                payloadBytes = raw.toByteArray().size,
                                outcome = ProbeOutcome.ERROR,
                                detailMessage = "API returned HTTP ${response.code}",
                                responseSnippet = snippet,
                            )
                            TempFetchResult.Failure(
                                status = TempNumberEntity.STATUS_ERROR,
                                errorMessage = "API returned HTTP ${response.code}. Keeping cached messages.",
                                attempts = listOf(attempt),
                            )
                        }
                    }
                }
            } catch (io: IOException) {
                val latency = (System.currentTimeMillis() - started).coerceAtLeast(1L)
                val attempt = SourceProbeAttempt(
                    sourceKey = TempNumberEntity.SOURCE_GET_VIRTUAL_PHONE_API,
                    displayName = "GetVirtualPhoneNumber API",
                    url = url,
                    latencyMs = latency,
                    outcome = ProbeOutcome.OFFLINE,
                    detailMessage = "Network unreachable (${io.javaClass.simpleName})",
                )
                TempFetchResult.Failure(
                    status = TempNumberEntity.STATUS_OFFLINE,
                    errorMessage = "Network unreachable (${io.javaClass.simpleName}). Keeping last known messages.",
                    attempts = listOf(attempt),
                )
            } catch (t: Throwable) {
                val latency = (System.currentTimeMillis() - started).coerceAtLeast(1L)
                val attempt = SourceProbeAttempt(
                    sourceKey = TempNumberEntity.SOURCE_GET_VIRTUAL_PHONE_API,
                    displayName = "GetVirtualPhoneNumber API",
                    url = url,
                    latencyMs = latency,
                    outcome = ProbeOutcome.ERROR,
                    detailMessage = t.message ?: "Failed to parse JSON API response",
                )
                TempFetchResult.Failure(
                    status = TempNumberEntity.STATUS_ERROR,
                    errorMessage = t.message ?: "Failed to parse JSON API response.",
                    attempts = listOf(attempt),
                )
            }
        }
    }

    internal fun parseJson(raw: String, nowMs: Long = System.currentTimeMillis()): List<TempParsedMessage> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        val element = json.parseToJsonElement(trimmed)
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> {
                (element["messages"] as? JsonArray)
                    ?: (element["data"] as? JsonArray)
                    ?: (element["sms"] as? JsonArray)
                    ?: emptyList()
            }
            else -> emptyList()
        }

        val out = ArrayList<TempParsedMessage>(array.size)
        for (item in array) {
            val obj = item as? JsonObject ?: continue
            val text = obj.stringField("text", "body", "message", "content")
                ?.let { PhoneFormatter.sanitizeBody(it, 640) }
                .orEmpty()
            if (text.isEmpty()) continue

            val from = obj.stringField("from", "sender", "number").orEmpty().take(32)
            val service = obj.stringField("service", "app", "provider")?.take(40)
            val code = obj.stringField("code", "otp", "pin")?.take(24)?.ifBlank { null }
            val receivedRaw = obj.stringField("received", "receivedAt", "timestamp", "date", "time")
            val ts = FlexibleTimestampParser.parse(receivedRaw, nowMs)

            val displaySender = when {
                !service.isNullOrBlank() -> service
                from.isNotBlank() -> from
                else -> PhoneFormatter.guessSender(from, text).ifEmpty { "Public SMS" }
            }

            out.add(
                TempParsedMessage(
                    fromNumber = from.ifBlank { displaySender.take(32) },
                    senderName = displaySender.take(40),
                    body = text,
                    explicitCode = code,
                    serviceName = service,
                    receivedAtEpochMs = ts.epochMs,
                    hasExplicitTimestamp = ts.isExplicit,
                    sourceTag = "gvpn_api",
                )
            )
        }
        return out
    }

    companion object {
        private const val BASE_URL = "https://getvirtualphonenumber.com/api/v1/sms"
        private const val CACHE_TTL_MS = 15_000L
    }
}

/**
 * Hardened HTML scraper for SMS24.me (https://sms24.me/en/numbers/{digits}).
 * Handles realistic mobile-browser headers, Cloudflare/anti-bot detection,
 * raw `data-created` timestamp extraction, balanced nested-tag body extraction,
 * and multiple fallback DOM layouts (`dl/dt/dd`, `callout`, `list-group-item`, `table`).
 */
class Sms24HtmlAdapter(private val client: OkHttpClient) {

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    suspend fun fetch(e164: String, force: Boolean = false): TempFetchResult {
        val digits = PhoneFormatter.digitsOnly(e164)
        val url = "https://sms24.me/en/numbers/$digits"
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
                    referer = "https://sms24.me/en/numbers",
                ).build()

                client.newCall(request).execute().use { response ->
                    val latency = (System.currentTimeMillis() - started).coerceAtLeast(1L)
                    val html = response.body?.string().orEmpty()
                    val snippet = BotWallDetector.buildSnippet(html)
                    val botCheck = BotWallDetector.detect(response.code, html)

                    if (botCheck.isBlocked) {
                        val attempt = SourceProbeAttempt(
                            sourceKey = TempNumberEntity.SOURCE_SMS24_ME,
                            displayName = "SMS24.me",
                            url = url,
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
                            errorMessage = "SMS24.me blocked by ${botCheck.reason ?: "anti-bot challenge"} (HTTP ${response.code}).",
                            attempts = listOf(attempt),
                        )
                    }

                    when (response.code) {
                        in 200..299 -> {
                            val (parsed, strategy) = parseHtmlWithStrategy(html)
                            val lower = html.lowercase()
                            val explicitEmpty = lower.contains("no messages") ||
                                lower.contains("no sms") ||
                                lower.contains("has not received") ||
                                lower.contains("inbox is empty")
                            val outcome = when {
                                parsed.isNotEmpty() -> ProbeOutcome.SUCCESS_WITH_MESSAGES
                                explicitEmpty || html.length < 1_500 -> ProbeOutcome.EMPTY_INBOX
                                else -> ProbeOutcome.PARSE_MISMATCH
                            }
                            val attempt = SourceProbeAttempt(
                                sourceKey = TempNumberEntity.SOURCE_SMS24_ME,
                                displayName = "SMS24.me",
                                url = url,
                                httpCode = response.code,
                                latencyMs = latency,
                                payloadBytes = html.toByteArray().size,
                                parserStrategy = strategy,
                                parsedCount = parsed.size,
                                outcome = outcome,
                                detailMessage = when (outcome) {
                                    ProbeOutcome.SUCCESS_WITH_MESSAGES -> "Parsed ${parsed.size} messages via $strategy"
                                    ProbeOutcome.EMPTY_INBOX -> "SMS24.me page loaded cleanly with an empty inbox"
                                    else -> "SMS24.me returned ${html.length} chars of HTML but 0 SMS blocks matched"
                                },
                                responseSnippet = snippet,
                            )
                            if (outcome == ProbeOutcome.PARSE_MISMATCH) {
                                TempFetchResult.Failure(
                                    status = TempNumberEntity.STATUS_ERROR,
                                    errorMessage = "SMS24.me page loaded (HTTP 200) but matched 0 SMS blocks — layout may have changed or requires JS.",
                                    attempts = listOf(attempt),
                                )
                            } else {
                                val result = TempFetchResult.Success(
                                    messages = parsed,
                                    resolvedSource = TempNumberEntity.SOURCE_SMS24_ME,
                                    attempts = listOf(attempt),
                                )
                                if (parsed.isNotEmpty()) {
                                    cache[digits] = CacheEntry(System.currentTimeMillis(), result)
                                }
                                result
                            }
                        }
                        404 -> {
                            val attempt = SourceProbeAttempt(
                                sourceKey = TempNumberEntity.SOURCE_SMS24_ME,
                                displayName = "SMS24.me",
                                url = url,
                                httpCode = 404,
                                latencyMs = latency,
                                payloadBytes = html.toByteArray().size,
                                outcome = ProbeOutcome.NOT_FOUND,
                                detailMessage = "Number not listed on SMS24.me (HTTP 404)",
                                responseSnippet = snippet,
                            )
                            TempFetchResult.Failure(
                                status = TempNumberEntity.STATUS_NOT_FOUND,
                                errorMessage = "Number not listed on SMS24.me (HTTP 404).",
                                attempts = listOf(attempt),
                            )
                        }
                        429 -> {
                            val attempt = SourceProbeAttempt(
                                sourceKey = TempNumberEntity.SOURCE_SMS24_ME,
                                displayName = "SMS24.me",
                                url = url,
                                httpCode = 429,
                                latencyMs = latency,
                                outcome = ProbeOutcome.RATE_LIMITED,
                                detailMessage = "SMS24.me rate limited (HTTP 429)",
                                responseSnippet = snippet,
                            )
                            TempFetchResult.Failure(
                                status = TempNumberEntity.STATUS_RATE_LIMITED,
                                errorMessage = "SMS24.me rate limited this request (HTTP 429).",
                                retryAfterMs = 30_000L,
                                attempts = listOf(attempt),
                            )
                        }
                        else -> {
                            val attempt = SourceProbeAttempt(
                                sourceKey = TempNumberEntity.SOURCE_SMS24_ME,
                                displayName = "SMS24.me",
                                url = url,
                                httpCode = response.code,
                                latencyMs = latency,
                                payloadBytes = html.toByteArray().size,
                                outcome = ProbeOutcome.ERROR,
                                detailMessage = "SMS24.me returned HTTP ${response.code}",
                                responseSnippet = snippet,
                            )
                            TempFetchResult.Failure(
                                status = TempNumberEntity.STATUS_ERROR,
                                errorMessage = "SMS24.me returned HTTP ${response.code}. Keeping cached messages.",
                                attempts = listOf(attempt),
                            )
                        }
                    }
                }
            } catch (io: IOException) {
                val latency = (System.currentTimeMillis() - started).coerceAtLeast(1L)
                val attempt = SourceProbeAttempt(
                    sourceKey = TempNumberEntity.SOURCE_SMS24_ME,
                    displayName = "SMS24.me",
                    url = url,
                    latencyMs = latency,
                    outcome = ProbeOutcome.OFFLINE,
                    detailMessage = "Network unreachable (${io.javaClass.simpleName})",
                )
                TempFetchResult.Failure(
                    status = TempNumberEntity.STATUS_OFFLINE,
                    errorMessage = "Could not reach SMS24.me (${io.javaClass.simpleName}).",
                    attempts = listOf(attempt),
                )
            } catch (t: Throwable) {
                val latency = (System.currentTimeMillis() - started).coerceAtLeast(1L)
                val attempt = SourceProbeAttempt(
                    sourceKey = TempNumberEntity.SOURCE_SMS24_ME,
                    displayName = "SMS24.me",
                    url = url,
                    latencyMs = latency,
                    outcome = ProbeOutcome.ERROR,
                    detailMessage = t.message ?: "Failed to parse SMS24.me page",
                )
                TempFetchResult.Failure(
                    status = TempNumberEntity.STATUS_ERROR,
                    errorMessage = t.message ?: "Failed to parse SMS24.me page.",
                    attempts = listOf(attempt),
                )
            }
        }
    }

    internal fun parseHtml(html: String, nowMs: Long = System.currentTimeMillis()): List<TempParsedMessage> {
        return parseHtmlWithStrategy(html, nowMs).first
    }

    internal fun parseHtmlWithStrategy(
        html: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<List<TempParsedMessage>, String?> {
        val out = mutableListOf<TempParsedMessage>()

        // Strategy A: <dt>raw timestamp / data-created</dt><dd><label>From: <a>Sender</a></label><span class="placeholder text-break">Body (possibly with nested tags)</span></dd>
        for (match in dtDdRegex.findAll(html)) {
            val dtRawHtml = match.groupValues[1]
            val ddBlock = match.groupValues[2]

            val ts = FlexibleTimestampParser.extractFromHtmlOrText(dtRawHtml, nowMs)
            val labelBlock = fromLabelRegex.find(ddBlock)?.groupValues?.getOrNull(1).orEmpty()
            val senderRaw = fromLinkRegex.find(labelBlock)?.groupValues?.getOrNull(1)
                ?: labelBlock.ifBlank { fromLinkRegex.find(ddBlock)?.groupValues?.getOrNull(1).orEmpty() }

            // Balanced span extraction prevents premature truncation on nested <span>/<a>/<b> inside SMS text
            val bodyRaw = BalancedHtmlExtractor.extractBalancedElements(
                html = ddBlock,
                tagName = "span",
                openTagRegex = placeholderSpanOpenRegex,
            ).firstOrNull()
                ?: BalancedHtmlExtractor.extractBalancedElements(
                    html = ddBlock,
                    tagName = "div",
                    openTagRegex = textBreakDivOpenRegex,
                ).firstOrNull()
                ?: pTagRegex.find(ddBlock)?.groupValues?.getOrNull(1)
                ?: ddBlock.replace(fullLabelBlockRegex, " ")

            val body = PhoneFormatter.sanitizeBody(HtmlEntityDecoder.decodeHtml(bodyRaw), 640)
            if (body.isEmpty()) continue
            val sender = HtmlEntityDecoder.decodeHtml(senderRaw)
                .replace(Regex("""(?i)^from\s*:?\s*"""), "")
                .trim()
                .take(40)
                .ifEmpty { PhoneFormatter.guessSender("", body).ifEmpty { "SMS24" } }
            out.add(
                TempParsedMessage(
                    fromNumber = sender.take(32),
                    senderName = sender,
                    body = body,
                    receivedAtEpochMs = ts.epochMs,
                    hasExplicitTimestamp = ts.isExplicit,
                    sourceTag = "sms24",
                )
            )
        }
        if (out.isNotEmpty()) return out to "dl-dt-dd-balanced"

        // Strategy B: Balanced callout / message card / list-group-item blocks
        val cardBlocks = BalancedHtmlExtractor.extractBalancedElements(
            html = html,
            tagName = "div",
            openTagRegex = calloutOpenRegex,
        ) + BalancedHtmlExtractor.extractBalancedElements(
            html = html,
            tagName = "li",
            openTagRegex = listGroupOpenRegex,
        )
        for (block in cardBlocks) {
            val ts = FlexibleTimestampParser.extractFromHtmlOrText(
                timeRegex.find(block)?.value ?: block,
                nowMs,
            )
            val senderRaw = fromLinkRegex.find(fromLabelRegex.find(block)?.value.orEmpty())?.groupValues?.getOrNull(1)
                ?: strongRegex.find(block)?.groupValues?.getOrNull(1)
                ?: fromLabelRegex.find(block)?.groupValues?.getOrNull(1)
                ?: ""
            val bodyRaw = BalancedHtmlExtractor.extractBalancedElements(
                html = block,
                tagName = "span",
                openTagRegex = placeholderSpanOpenRegex,
            ).firstOrNull()
                ?: pTagRegex.find(block)?.groupValues?.getOrNull(1)
                ?: ""
            val body = PhoneFormatter.sanitizeBody(HtmlEntityDecoder.decodeHtml(bodyRaw), 640)
            if (body.isEmpty()) continue
            val sender = HtmlEntityDecoder.decodeHtml(senderRaw)
                .replace(Regex("""(?i)^from\s*:?\s*"""), "")
                .trim()
                .take(40)
                .ifEmpty { PhoneFormatter.guessSender("", body).ifEmpty { "SMS24" } }
            out.add(
                TempParsedMessage(
                    fromNumber = sender.take(32),
                    senderName = sender,
                    body = body,
                    receivedAtEpochMs = ts.epochMs,
                    hasExplicitTimestamp = ts.isExplicit,
                    sourceTag = "sms24",
                )
            )
        }
        if (out.isNotEmpty()) return out to "callout-cards-balanced"

        // Strategy C: Generic HTML table rows
        val tableOut = parseGenericHtmlTables(html, "sms24", "SMS24", nowMs)
        if (tableOut.isNotEmpty()) return tableOut to "html-table"

        return emptyList<TempParsedMessage>() to null
    }

    companion object {
        private const val CACHE_TTL_MS = 15_000L
        private val dtDdRegex = Regex("""<dt[^>]*>([\s\S]*?)</dt>\s*<dd[^>]*>([\s\S]*?)</dd>""", RegexOption.IGNORE_CASE)
        private val fromLinkRegex = Regex("""<a[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        private val fromLabelRegex = Regex("""<label[^>]*>([\s\S]*?)</label>""", RegexOption.IGNORE_CASE)
        private val fullLabelBlockRegex = Regex("""<label[^>]*>[\s\S]*?</label>""", RegexOption.IGNORE_CASE)
        private val placeholderSpanOpenRegex = Regex("""<span[^>]*class=["'][^"']*(?:placeholder|text-break|message)[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE)
        private val textBreakDivOpenRegex = Regex("""<div[^>]*class=["'][^"']*(?:placeholder|text-break|message-body)[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE)
        private val calloutOpenRegex = Regex("""<div[^>]*class=["'][^"']*(?:callout|sms-message|message-item)[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE)
        private val listGroupOpenRegex = Regex("""<li[^>]*class=["'][^"']*list-group-item[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE)
        private val strongRegex = Regex("""<(?:strong|b|h5|h6)[^>]*>([\s\S]*?)</(?:strong|b|h5|h6)>""", RegexOption.IGNORE_CASE)
        private val timeRegex = Regex("""<(?:small|time|div)[^>]*(?:data-created|datetime|text-muted)[^>]*>[\s\S]*?</(?:small|time|div)>|<(?:small|time)[^>]*>[\s\S]*?</(?:small|time)>""", RegexOption.IGNORE_CASE)
        private val pTagRegex = Regex("""<p[^>]*>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)
    }
}

/**
 * User-configurable custom source adapter supporting:
 * - URL placeholders: {E164}, {E164_ENCODED}, {E164_RAW}, {NUMBER}
 * - JSON field mapping (with optional dotted jsonArrayPath)
 * - HTML Regex extraction (positional capture groups 1=sender, 2=body, 3=time, 4=code)
 */
class CustomSourceAdapter(private val client: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    suspend fun fetch(
        e164: String,
        source: CustomSourceEntity,
        force: Boolean = false,
    ): TempFetchResult {
        val resolvedUrl = resolveUrl(source.urlTemplate, e164)
        val cacheKey = "${source.id}:$e164"
        val cacheTtlMs = source.cacheSeconds.coerceIn(5, 600) * 1_000L
        val now = System.currentTimeMillis()
        val cached = cache[cacheKey]
        if (!force && cached != null && (now - cached.fetchedAtMs) < cacheTtlMs) {
            return cached.result.copy(
                fromCache = true,
                attempts = cached.result.attempts.map { it.copy(fromCache = true) },
            )
        }

        val started = System.currentTimeMillis()
        return withContext(Dispatchers.IO) {
            try {
                val requestBuilder = Request.Builder().url(resolvedUrl).get()
                val request = if (source.parserType == CustomSourceEntity.PARSER_JSON) {
                    BrowserHttpHeaders.applyJsonHeaders(requestBuilder).build()
                } else {
                    BrowserHttpHeaders.applyHtmlHeaders(requestBuilder).build()
                }

                client.newCall(request).execute().use { response ->
                    val latency = (System.currentTimeMillis() - started).coerceAtLeast(1L)
                    val payload = response.body?.string().orEmpty()
                    val snippet = BotWallDetector.buildSnippet(payload)
                    val botCheck = BotWallDetector.detect(response.code, payload)

                    if (botCheck.isBlocked) {
                        val attempt = SourceProbeAttempt(
                            sourceKey = "CUSTOM:${source.name}",
                            displayName = source.name,
                            url = resolvedUrl,
                            httpCode = response.code,
                            latencyMs = latency,
                            payloadBytes = payload.toByteArray().size,
                            botChallengeDetected = true,
                            outcome = ProbeOutcome.BOT_BLOCKED,
                            detailMessage = botCheck.reason ?: "Anti-bot wall",
                            responseSnippet = snippet,
                        )
                        return@withContext TempFetchResult.Failure(
                            status = TempNumberEntity.STATUS_BOT_BLOCKED,
                            errorMessage = "${source.name}: Blocked by ${botCheck.reason ?: "HTTP ${response.code}"}.",
                            attempts = listOf(attempt),
                        )
                    }

                    when (response.code) {
                        in 200..299 -> {
                            val messages = if (source.parserType == CustomSourceEntity.PARSER_JSON) {
                                parseCustomJson(payload, source)
                            } else {
                                parseCustomHtmlRegex(payload, source)
                            }
                            val outcome = if (messages.isNotEmpty()) ProbeOutcome.SUCCESS_WITH_MESSAGES else ProbeOutcome.EMPTY_INBOX
                            val attempt = SourceProbeAttempt(
                                sourceKey = "CUSTOM:${source.name}",
                                displayName = source.name,
                                url = resolvedUrl,
                                httpCode = response.code,
                                latencyMs = latency,
                                payloadBytes = payload.toByteArray().size,
                                parserStrategy = source.parserType.lowercase(),
                                parsedCount = messages.size,
                                outcome = outcome,
                                detailMessage = if (messages.isNotEmpty()) {
                                    "Extracted ${messages.size} messages (${source.parserType})"
                                } else {
                                    "Endpoint returned 0 matching records (${source.parserType})"
                                },
                                responseSnippet = snippet,
                            )
                            val result = TempFetchResult.Success(
                                messages = messages,
                                resolvedSource = "CUSTOM:${source.name}",
                                attempts = listOf(attempt),
                            )
                            if (messages.isNotEmpty()) {
                                cache[cacheKey] = CacheEntry(System.currentTimeMillis(), result)
                            }
                            result
                        }
                        404 -> {
                            val attempt = SourceProbeAttempt(
                                sourceKey = "CUSTOM:${source.name}",
                                displayName = source.name,
                                url = resolvedUrl,
                                httpCode = 404,
                                latencyMs = latency,
                                payloadBytes = payload.toByteArray().size,
                                outcome = ProbeOutcome.NOT_FOUND,
                                detailMessage = "Number not found (HTTP 404)",
                                responseSnippet = snippet,
                            )
                            TempFetchResult.Failure(
                                status = TempNumberEntity.STATUS_NOT_FOUND,
                                errorMessage = "${source.name}: Number not found (HTTP 404).",
                                attempts = listOf(attempt),
                            )
                        }
                        429 -> {
                            val attempt = SourceProbeAttempt(
                                sourceKey = "CUSTOM:${source.name}",
                                displayName = source.name,
                                url = resolvedUrl,
                                httpCode = 429,
                                latencyMs = latency,
                                outcome = ProbeOutcome.RATE_LIMITED,
                                detailMessage = "Rate limited (HTTP 429)",
                                responseSnippet = snippet,
                            )
                            TempFetchResult.Failure(
                                status = TempNumberEntity.STATUS_RATE_LIMITED,
                                errorMessage = "${source.name}: Rate limited (HTTP 429).",
                                retryAfterMs = 30_000L,
                                attempts = listOf(attempt),
                            )
                        }
                        else -> {
                            val attempt = SourceProbeAttempt(
                                sourceKey = "CUSTOM:${source.name}",
                                displayName = source.name,
                                url = resolvedUrl,
                                httpCode = response.code,
                                latencyMs = latency,
                                payloadBytes = payload.toByteArray().size,
                                outcome = ProbeOutcome.ERROR,
                                detailMessage = "HTTP ${response.code}",
                                responseSnippet = snippet,
                            )
                            TempFetchResult.Failure(
                                status = TempNumberEntity.STATUS_ERROR,
                                errorMessage = "${source.name}: HTTP ${response.code}. Keeping cached messages.",
                                attempts = listOf(attempt),
                            )
                        }
                    }
                }
            } catch (io: IOException) {
                val latency = (System.currentTimeMillis() - started).coerceAtLeast(1L)
                val attempt = SourceProbeAttempt(
                    sourceKey = "CUSTOM:${source.name}",
                    displayName = source.name,
                    url = resolvedUrl,
                    latencyMs = latency,
                    outcome = ProbeOutcome.OFFLINE,
                    detailMessage = "Network unreachable (${io.javaClass.simpleName})",
                )
                TempFetchResult.Failure(
                    status = TempNumberEntity.STATUS_OFFLINE,
                    errorMessage = "${source.name}: Network unreachable (${io.javaClass.simpleName}).",
                    attempts = listOf(attempt),
                )
            } catch (t: Throwable) {
                val latency = (System.currentTimeMillis() - started).coerceAtLeast(1L)
                val attempt = SourceProbeAttempt(
                    sourceKey = "CUSTOM:${source.name}",
                    displayName = source.name,
                    url = resolvedUrl,
                    latencyMs = latency,
                    outcome = ProbeOutcome.ERROR,
                    detailMessage = t.message ?: "Parse error",
                )
                TempFetchResult.Failure(
                    status = TempNumberEntity.STATUS_ERROR,
                    errorMessage = "${source.name}: ${t.message ?: "Parse error"}",
                    attempts = listOf(attempt),
                )
            }
        }
    }

    internal fun resolveUrl(template: String, e164: String): String {
        val digits = PhoneFormatter.digitsOnly(e164)
        val encodedE164 = URLEncoder.encode(e164, StandardCharsets.UTF_8.name())
        return template
            .replace("{E164_ENCODED}", encodedE164)
            .replace("{E164}", encodedE164)
            .replace("{E164_RAW}", e164)
            .replace("{NUMBER}", digits)
            .replace("{number}", digits)
    }

    internal fun parseCustomJson(
        raw: String,
        source: CustomSourceEntity,
        nowMs: Long = System.currentTimeMillis(),
    ): List<TempParsedMessage> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        var current: JsonElement = json.parseToJsonElement(trimmed)

        if (source.jsonArrayPath.isNotBlank()) {
            for (segment in source.jsonArrayPath.split(".")) {
                val key = segment.trim()
                if (key.isEmpty()) continue
                current = (current as? JsonObject)?.get(key) ?: return emptyList()
            }
        }

        val array = when (current) {
            is JsonArray -> current
            is JsonObject -> {
                (current["messages"] as? JsonArray)
                    ?: (current["data"] as? JsonArray)
                    ?: (current["sms"] as? JsonArray)
                    ?: emptyList()
            }
            else -> emptyList()
        }

        val out = ArrayList<TempParsedMessage>(array.size)
        for (item in array) {
            val obj = item as? JsonObject ?: continue
            val text = obj.stringField(source.jsonTextKey, "text", "body", "message")
                ?.let { PhoneFormatter.sanitizeBody(it, 640) }
                .orEmpty()
            if (text.isEmpty()) continue

            val from = obj.stringField(source.jsonFromKey, "from", "sender").orEmpty().take(32)
            val service = if (source.jsonServiceKey.isNotBlank()) obj.stringField(source.jsonServiceKey)?.take(40) else null
            val code = if (source.jsonCodeKey.isNotBlank()) obj.stringField(source.jsonCodeKey)?.take(24)?.ifBlank { null } else null
            val timeRaw = if (source.jsonTimeKey.isNotBlank()) obj.stringField(source.jsonTimeKey) else null
            val ts = FlexibleTimestampParser.parse(timeRaw, nowMs)

            val sender = service?.ifBlank { null }
                ?: from.ifBlank { null }
                ?: PhoneFormatter.guessSender(from, text).ifEmpty { source.name.take(40) }

            out.add(
                TempParsedMessage(
                    fromNumber = from.ifBlank { sender.take(32) },
                    senderName = sender.take(40),
                    body = text,
                    explicitCode = code,
                    serviceName = service,
                    receivedAtEpochMs = ts.epochMs,
                    hasExplicitTimestamp = ts.isExplicit,
                    sourceTag = "custom:${source.id}",
                )
            )
        }
        return out
    }

    internal fun parseCustomHtmlRegex(
        raw: String,
        source: CustomSourceEntity,
        nowMs: Long = System.currentTimeMillis(),
    ): List<TempParsedMessage> {
        if (source.htmlRegexPattern.isBlank()) return emptyList()
        val regex = Regex(source.htmlRegexPattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val out = mutableListOf<TempParsedMessage>()
        for (match in regex.findAll(raw)) {
            val groups = match.groupValues
            val senderRaw = groups.getOrNull(1).orEmpty()
            val bodyRaw = groups.getOrNull(2).orEmpty()
            val timeRaw = groups.getOrNull(3)
            val codeRaw = groups.getOrNull(4)?.trim()?.ifBlank { null }

            val body = HtmlEntityDecoder.decodeHtml(bodyRaw).take(640)
            if (body.isEmpty()) continue
            val sender = HtmlEntityDecoder.decodeHtml(senderRaw)
                .take(40)
                .ifEmpty { source.name.take(40) }
            val ts = FlexibleTimestampParser.extractFromHtmlOrText(timeRaw, nowMs)

            out.add(
                TempParsedMessage(
                    fromNumber = sender.take(32),
                    senderName = sender,
                    body = body,
                    explicitCode = codeRaw?.take(24),
                    receivedAtEpochMs = ts.epochMs,
                    hasExplicitTimestamp = ts.isExplicit,
                    sourceTag = "custom:${source.id}",
                )
            )
        }
        return out
    }
}

class TempSourceEngine(
    client: OkHttpClient,
    val anosimProvider: AnosimProvider = AnosimProvider(client),
) {
    val gvpnAdapter = GetVirtualPhoneApiAdapter(client)
    val sms24Adapter = Sms24HtmlAdapter(client)
    val receiveSmssMirrorAdapter = ReceiveSmssMirrorAdapter(client)
    val quackrAdapter = QuackrAdapter(client)
    val tempSmssAdapter = TempSmssAdapter(client)
    val receiveSmsFreeAdapter = ReceiveSmsFreeAdapter(client)
    val receiveSmsOnlineAdapter = ReceiveSmsOnlineAdapter(client)
    val publicMirrorAdapter = PublicSmsMirrorAdapter(
        quackr = quackrAdapter,
        tempSmss = tempSmssAdapter,
        receiveSmsFree = receiveSmsFreeAdapter,
        receiveSmsOnline = receiveSmsOnlineAdapter,
    )
    val customAdapter = CustomSourceAdapter(client)

    private val perNumberLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun fetchForTempNumber(
        temp: TempNumberEntity,
        customSource: CustomSourceEntity?,
        force: Boolean = false,
    ): TempFetchResult {
        val lock = perNumberLocks.getOrPut(temp.numberId) { Mutex() }
        if (!lock.tryLock()) {
            return TempFetchResult.Success(
                messages = emptyList(),
                resolvedSource = temp.resolvedSource ?: temp.sourceType,
                fromCache = true,
            )
        }
        return try {
            when (temp.sourceType) {
                TempNumberEntity.SOURCE_GET_VIRTUAL_PHONE_API -> gvpnAdapter.fetch(temp.e164, force)
                TempNumberEntity.SOURCE_ANOSIM -> anosimProvider.fetchForNumber(temp.e164, force)
                TempNumberEntity.SOURCE_SMS24_ME -> sms24Adapter.fetch(temp.e164, force)
                TempNumberEntity.SOURCE_RECEIVE_SMSS_MIRROR -> receiveSmssMirrorAdapter.fetch(temp.e164, force)
                TempNumberEntity.SOURCE_QUACKR -> quackrAdapter.fetch(temp.e164, force)
                TempNumberEntity.SOURCE_TEMPSMSS -> tempSmssAdapter.fetch(temp.e164, force)
                TempNumberEntity.SOURCE_RECEIVE_SMS_FREE -> receiveSmsFreeAdapter.fetch(temp.e164, force)
                TempNumberEntity.SOURCE_RECEIVE_SMS_ONLINE -> receiveSmsOnlineAdapter.fetch(temp.e164, force)
                TempNumberEntity.SOURCE_PUBLIC_MIRROR -> publicMirrorAdapter.fetch(temp.e164, force)
                TempNumberEntity.SOURCE_CUSTOM -> {
                    if (customSource == null) {
                        TempFetchResult.Failure(
                            status = TempNumberEntity.STATUS_ERROR,
                            errorMessage = "Custom source was removed. Edit this number to pick another source.",
                        )
                    } else {
                        customAdapter.fetch(temp.e164, customSource, force)
                    }
                }
                else -> fetchAutoDetectChain(temp, force)
            }
        } finally {
            lock.unlock()
        }
    }

    private suspend fun fetchAutoDetectChain(
        temp: TempNumberEntity,
        force: Boolean,
    ): TempFetchResult {
        val defaultChain = listOf(
            TempNumberEntity.SOURCE_GET_VIRTUAL_PHONE_API,
            TempNumberEntity.SOURCE_ANOSIM,
            TempNumberEntity.SOURCE_SMS24_ME,
            TempNumberEntity.SOURCE_RECEIVE_SMSS_MIRROR,
            TempNumberEntity.SOURCE_QUACKR,
            TempNumberEntity.SOURCE_TEMPSMSS,
            TempNumberEntity.SOURCE_RECEIVE_SMS_FREE,
            TempNumberEntity.SOURCE_RECEIVE_SMS_ONLINE,
        )
        // Prioritize last working resolvedSource first while keeping complete AU/global fallback chain
        val orderedSources = buildList {
            val affinity = temp.resolvedSource
            if (affinity != null && affinity in defaultChain) {
                add(affinity)
            }
            for (src in defaultChain) {
                if (src !in this) add(src)
            }
        }

        val allAttempts = mutableListOf<SourceProbeAttempt>()
        var confirmedEmptySource: String? = null

        for (sourceKey in orderedSources) {
            val result = when (sourceKey) {
                TempNumberEntity.SOURCE_GET_VIRTUAL_PHONE_API -> gvpnAdapter.fetch(temp.e164, force)
                TempNumberEntity.SOURCE_ANOSIM -> anosimProvider.fetchForNumber(temp.e164, force)
                TempNumberEntity.SOURCE_SMS24_ME -> sms24Adapter.fetch(temp.e164, force)
                TempNumberEntity.SOURCE_RECEIVE_SMSS_MIRROR -> receiveSmssMirrorAdapter.fetch(temp.e164, force)
                TempNumberEntity.SOURCE_QUACKR -> quackrAdapter.fetch(temp.e164, force)
                TempNumberEntity.SOURCE_TEMPSMSS -> tempSmssAdapter.fetch(temp.e164, force)
                TempNumberEntity.SOURCE_RECEIVE_SMS_FREE -> receiveSmsFreeAdapter.fetch(temp.e164, force)
                TempNumberEntity.SOURCE_RECEIVE_SMS_ONLINE -> receiveSmsOnlineAdapter.fetch(temp.e164, force)
                else -> continue
            }
            allAttempts.addAll(result.attempts)

            if (result is TempFetchResult.Success && result.messages.isNotEmpty()) {
                return result.copy(attempts = allAttempts.toList())
            }
            if (result is TempFetchResult.Success && result.attempts.any { it.outcome == ProbeOutcome.EMPTY_INBOX }) {
                if (confirmedEmptySource == null) confirmedEmptySource = result.resolvedSource
            }
        }

        val botBlocked = allAttempts.filter { it.outcome == ProbeOutcome.BOT_BLOCKED }
        val parseMismatches = allAttempts.filter { it.outcome == ProbeOutcome.PARSE_MISMATCH }
        val rateLimited = allAttempts.filter { it.outcome == ProbeOutcome.RATE_LIMITED }
        val offlineAll = allAttempts.isNotEmpty() && allAttempts.all { it.outcome == ProbeOutcome.OFFLINE }

        return when {
            offlineAll -> TempFetchResult.Failure(
                status = TempNumberEntity.STATUS_OFFLINE,
                errorMessage = "Offline — could not reach any of the ${allAttempts.size} checked SMS sources.",
                attempts = allAttempts,
            )
            botBlocked.isNotEmpty() -> {
                val hosts = botBlocked.map { it.displayName }.distinct().take(3).joinToString(", ")
                TempFetchResult.Failure(
                    status = TempNumberEntity.STATUS_BOT_BLOCKED,
                    errorMessage = "Anti-bot challenge on $hosts (${allAttempts.size} probes, 0 messages). Tap Inspect for details.",
                    attempts = allAttempts,
                )
            }
            parseMismatches.isNotEmpty() -> {
                val hosts = parseMismatches.map { it.displayName }.distinct().take(3).joinToString(", ")
                TempFetchResult.Failure(
                    status = TempNumberEntity.STATUS_ERROR,
                    errorMessage = "$hosts loaded HTTP 200 but matched 0 SMS blocks (${allAttempts.size} probes). Tap Inspect.",
                    attempts = allAttempts,
                )
            }
            confirmedEmptySource != null -> TempFetchResult.Success(
                messages = emptyList(),
                resolvedSource = confirmedEmptySource,
                attempts = allAttempts,
            )
            rateLimited.isNotEmpty() -> TempFetchResult.Failure(
                status = TempNumberEntity.STATUS_RATE_LIMITED,
                errorMessage = "Sources are temporarily rate-limited (${allAttempts.size} probes).",
                retryAfterMs = 30_000L,
                attempts = allAttempts,
            )
            else -> TempFetchResult.Failure(
                status = TempNumberEntity.STATUS_NOT_FOUND,
                errorMessage = "Number not found across ${allAttempts.size} public SMS sources. Tap Inspect or pick a specific platform.",
                attempts = allAttempts,
            )
        }
    }
}

private fun JsonObject.stringField(vararg keys: String): String? {
    for (key in keys) {
        if (key.isBlank()) continue
        val el = this[key] ?: continue
        val prim = el as? JsonPrimitive ?: continue
        val content = prim.contentOrNull?.trim()
        if (!content.isNullOrEmpty()) return content
    }
    return null
}
