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

import java.security.MessageDigest
import kotlin.random.Random

data class TempParsedMessage(
    val fromNumber: String,
    val senderName: String,
    val body: String,
    val explicitCode: String? = null,
    val serviceName: String? = null,
    val receivedAtEpochMs: Long,
    val hasExplicitTimestamp: Boolean = true,
    val sourceTag: String,
    val messageKind: String = "sms",
    val audioUrl: String? = null,
    val overrideDedupHash: String? = null,
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

    data object SkippedInProgress : TempFetchResult() {
        override val attempts: List<SourceProbeAttempt> = emptyList()
    }
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

    // Exclude `title` so sender links like <a title="1415555123"> never spoof Unix timestamps
    private val attrTimestampRegex = Regex(
        """(?i)\b(?:data-created|data-time|data-timestamp|data-date|datetime)\s*=\s*(?:["']([^"']{4,48})["']|([^\s>"']{4,48}))"""
    )
    private val isoRegex = Regex(
        """(\d{4})[-/](\d{1,2})[-/](\d{1,2})[T ](\d{1,2}):(\d{2})(?::(\d{2}))?(?:\.(\d+))?(Z|[+-]\d{2}:?\d{2})?"""
    )
    private val dmyRegex = Regex(
        """(\d{1,2})[./-](\d{1,2})[./-](\d{4})[T ](\d{1,2}):(\d{2})(?::(\d{2}))?(?:\s*(AM|PM))?""",
        RegexOption.IGNORE_CASE,
    )
    private val dmyMonthRegex = Regex(
        """\b(\d{1,2})\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*[,\s]+(\d{4})(?:[,\s]+(\d{1,2}):(\d{2})(?::(\d{2}))?(?:\s*(AM|PM))?)?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val mdyMonthRegex = Regex(
        """\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+(\d{1,2})(?:st|nd|rd|th)?[,\s]+(\d{4})(?:[,\s]+(\d{1,2}):(\d{2})(?::(\d{2}))?(?:\s*(AM|PM))?)?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val relativeAgoRegex = Regex(
        """(?i)\b(\d+)\s*(sec(?:ond)?s?|s|min(?:ute)?s?|m|hour?s?|hr?s?|h|day?s?|d|week?s?|w)\s+ago\b"""
    )
    private val standaloneRelativeRegex = Regex(
        """(?i)^(\d+)\s*(sec(?:ond)?s?|s|min(?:ute)?s?|m|hour?s?|hr?s?|h|day?s?|d|week?s?|w)(?:\s*ago)?$"""
    )
    private val relativeSingularRegex = Regex(
        """(?i)\b(?:a|an|one)\s+(few\s+seconds?|sec(?:ond)?|min(?:ute)?|hour|hr|day|week)\s+ago\b"""
    )

    private const val MIN_VALID_EPOCH_MS = 1_577_836_800_000L // 2020-01-01T00:00:00Z

    fun extractFromHtmlOrText(rawHtml: String?, nowMs: Long = System.currentTimeMillis()): ParsedTimestamp {
        if (rawHtml.isNullOrBlank()) return ParsedTimestamp(nowMs, isExplicit = false)
        // Inspect raw HTML attributes like data-created="2026-11-24 05:12:00" BEFORE stripping tags
        for (match in attrTimestampRegex.findAll(rawHtml)) {
            val rawVal = match.groupValues[1].ifBlank { match.groupValues[2] }
            val candidate = parse(rawVal, nowMs)
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

        // Pure numeric Unix epoch seconds or milliseconds — validated against sane year range
        trimmed.toLongOrNull()?.let { numeric ->
            val maxValid = nowMs + 86_400_000L
            val candidateMs = when {
                numeric in MIN_VALID_EPOCH_MS..maxValid -> numeric
                numeric * 1_000L in MIN_VALID_EPOCH_MS..maxValid -> numeric * 1_000L
                else -> null
            }
            if (candidateMs != null) {
                return ParsedTimestamp(candidateMs.coerceAtMost(nowMs), isExplicit = true)
            }
            return ParsedTimestamp(nowMs, isExplicit = false)
        }

        // ISO-8601 / SQL date-time (supports - or / and 1-or-2 digit month/day/hour)
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

        // "24 Nov 2026 07:05" or "24 November 2026, 5:05 PM"
        dmyMonthRegex.find(trimmed)?.let { m ->
            val day = m.groupValues[1].toIntOrNull() ?: return ParsedTimestamp(nowMs, false)
            val month = monthNameToNumber(m.groupValues[2]) ?: return ParsedTimestamp(nowMs, false)
            val year = m.groupValues[3].toIntOrNull() ?: return ParsedTimestamp(nowMs, false)
            var hour = m.groupValues[4].toIntOrNull() ?: 0
            val minute = m.groupValues[5].toIntOrNull() ?: 0
            val second = m.groupValues[6].toIntOrNull() ?: 0
            val amPm = m.groupValues[7].uppercase()
            if (amPm == "PM" && hour < 12) hour += 12
            if (amPm == "AM" && hour == 12) hour = 0
            val parsed = buildEpochMs(year, month, day, hour, minute, second, TimeZone.getTimeZone("UTC"), nowMs)
            return ParsedTimestamp(parsed, isExplicit = true)
        }

        // "Nov 24, 2026 17:05" or "November 24th, 2026 5:05 PM"
        mdyMonthRegex.find(trimmed)?.let { m ->
            val month = monthNameToNumber(m.groupValues[1]) ?: return ParsedTimestamp(nowMs, false)
            val day = m.groupValues[2].toIntOrNull() ?: return ParsedTimestamp(nowMs, false)
            val year = m.groupValues[3].toIntOrNull() ?: return ParsedTimestamp(nowMs, false)
            var hour = m.groupValues[4].toIntOrNull() ?: 0
            val minute = m.groupValues[5].toIntOrNull() ?: 0
            val second = m.groupValues[6].toIntOrNull() ?: 0
            val amPm = m.groupValues[7].uppercase()
            if (amPm == "PM" && hour < 12) hour += 12
            if (amPm == "AM" && hour == 12) hour = 0
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

        // Relative English expressions: require explicit "ago" OR standalone age string ("5m", "12 mins")
        val relMatch = relativeAgoRegex.find(trimmed) ?: standaloneRelativeRegex.matchEntire(trimmed)
        relMatch?.let { match ->
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

    private fun monthNameToNumber(abbrev: String): Int? = when (abbrev.lowercase().take(3)) {
        "jan" -> 1
        "feb" -> 2
        "mar" -> 3
        "apr" -> 4
        "may" -> 5
        "jun" -> 6
        "jul" -> 7
        "aug" -> 8
        "sep" -> 9
        "oct" -> 10
        "nov" -> 11
        "dec" -> 12
        else -> null
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
 * Verified SMS24.me ingestion adapter (from production JS bundles `app-B4plS_UK.js` & `turnstile-Cr7KcrXV.js`):
 * - Transport: single-leg `in_page` (`TOKEN_TRANSPORT = "in_page"`), minting a fresh single-use
 *   Cloudflare Turnstile token (`action: "messages"`, sitekey from `meta[name="turnstile-site-key"]`)
 *   and immediately executing `POST https://sms24.me/api/messages/{digits}` with body
 *   `{"page": 1, "locale": "en"}` and header `X-Turnstile-Token` inside a persistent per-number
 *   Android Chromium WebView context.
 * - Never makes plain HTTP `GET /en/numbers/{digits}` HTML scrapes (the page HTML is lazy-loaded)
 *   and never sends `GET /api/messages/{digits}` (which returns Symfony 405).
 * - Dedupe: SHA-1(`from|created|kind|content`) since no `id` field exists.
 * - Call messages (`kind == "call"`): kept as audio URL metadata for download/playback, never printed as text.
 * - Failure matrix: 403/422 reloads once and retries within the same cycle; 429 backs off 120s; 5xx backs off 60s.
 * - Scheduling: jittered 90–180s cache window per number for automatic polls.
 */
class Sms24HtmlAdapter(
    @Suppress("UNUSED_PARAMETER") private val client: OkHttpClient,
    private val browserFetcher: BrowserDomFetcher? = null,
) {

    private data class JitterCacheEntry(
        val fetchedAtMs: Long,
        val nextAllowedAtMs: Long,
        val result: TempFetchResult.Success,
    )

    private data class BackoffState(
        val untilMs: Long,
        val httpCode: Int,
    )

    internal data class ParsedApiPage(
        val messages: List<TempParsedMessage>,
        val page: Int,
        val pages: Int,
        val validJson: Boolean,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cache = ConcurrentHashMap<String, JitterCacheEntry>()
    private val backoffUntil = ConcurrentHashMap<String, BackoffState>()

    suspend fun fetch(e164: String, force: Boolean = false): TempFetchResult {
        val digits = PhoneFormatter.digitsOnly(e164)
        val apiUrl = "https://sms24.me/api/messages/$digits"
        val now = System.currentTimeMillis()

        // Enforce failure-matrix backoff (429 -> 120s, 5xx -> 60s)
        val activeBackoff = backoffUntil[digits]
        if (activeBackoff != null && now < activeBackoff.untilMs) {
            if (activeBackoff.httpCode == 429 || !force) {
                val remainingMs = (activeBackoff.untilMs - now).coerceAtLeast(1_000L)
                val remainingSec = (remainingMs / 1_000L).coerceAtLeast(1L)
                val is429 = activeBackoff.httpCode == 429
                val attempt = SourceProbeAttempt(
                    sourceKey = TempNumberEntity.SOURCE_SMS24_ME,
                    displayName = "SMS24.me (POST API · $TOKEN_TRANSPORT)",
                    url = apiUrl,
                    httpCode = activeBackoff.httpCode,
                    outcome = if (is429) ProbeOutcome.RATE_LIMITED else ProbeOutcome.ERROR,
                    detailMessage = "Backoff active after HTTP ${activeBackoff.httpCode} (${remainingSec}s remaining)",
                )
                return TempFetchResult.Failure(
                    status = if (is429) TempNumberEntity.STATUS_RATE_LIMITED else TempNumberEntity.STATUS_ERROR,
                    errorMessage = if (is429) {
                        "SMS24.me throttled (HTTP 429). Backing off for ${remainingSec}s."
                    } else {
                        "SMS24.me upstream error (HTTP ${activeBackoff.httpCode}). Retrying in ${remainingSec}s."
                    },
                    retryAfterMs = remainingMs,
                    attempts = listOf(attempt),
                )
            }
        }

        // Jittered 90–180s per-number polling window for automatic background/foreground polls
        val cached = cache[digits]
        if (!force && cached != null && now < cached.nextAllowedAtMs) {
            return cached.result.copy(
                fromCache = true,
                attempts = cached.result.attempts.map { it.copy(fromCache = true) },
            )
        }

        if (browserFetcher == null) {
            val attempt = SourceProbeAttempt(
                sourceKey = TempNumberEntity.SOURCE_SMS24_ME,
                displayName = "SMS24.me (POST API · $TOKEN_TRANSPORT)",
                url = apiUrl,
                outcome = ProbeOutcome.ERROR,
                detailMessage = "Chromium WebView required to mint Turnstile token for POST /api/messages/$digits",
            )
            return TempFetchResult.Failure(
                status = TempNumberEntity.STATUS_ERROR,
                errorMessage = "SMS24.me requires Chromium WebView to mint a Turnstile token and call POST /api/messages/$digits.",
                attempts = listOf(attempt),
            )
        }

        val attempts = mutableListOf<SourceProbeAttempt>()
        // Up to 2 attempts within one cycle: initial in-page call + 1 reload-and-retry on 403/422 or transient WebView error
        for (attemptIdx in 0..1) {
            val forceReload = attemptIdx > 0
            val resp = browserFetcher.fetchSms24ApiInPage(
                digits = digits,
                page = 1,
                forceReload = forceReload,
                maxWaitMs = 45_000L,
            )

            if (resp == null) {
                val attempt = SourceProbeAttempt(
                    sourceKey = TempNumberEntity.SOURCE_SMS24_ME,
                    displayName = "SMS24.me (POST API · $TOKEN_TRANSPORT)",
                    url = apiUrl,
                    outcome = ProbeOutcome.ERROR,
                    detailMessage = "In-page API bridge unavailable",
                )
                attempts.add(attempt)
                return TempFetchResult.Failure(
                    status = TempNumberEntity.STATUS_ERROR,
                    errorMessage = "SMS24.me in-page WebView bridge returned no response.",
                    attempts = attempts.toList(),
                )
            }

            val snippet = resp.body.take(420).ifBlank { resp.error?.take(420) }
            val payloadBytes = resp.body.toByteArray().size
            val phaseLabel = if (forceReload) "reload-retry" else if (resp.pageReloaded) "fresh-load" else "warm-session"

            when {
                resp.status in 200..299 -> {
                    val parsedPage = parseApiJsonResponse(resp.body, System.currentTimeMillis())
                    if (!parsedPage.validJson) {
                        val botCheck = BotWallDetector.detect(resp.status, resp.body)
                        attempts.add(
                            SourceProbeAttempt(
                                sourceKey = TempNumberEntity.SOURCE_SMS24_ME,
                                displayName = "SMS24.me (POST API · $TOKEN_TRANSPORT)",
                                url = apiUrl,
                                httpCode = resp.status,
                                latencyMs = resp.latencyMs,
                                payloadBytes = payloadBytes,
                                botChallengeDetected = botCheck.isBlocked,
                                parserStrategy = "sms24-post-json-v1",
                                outcome = if (botCheck.isBlocked) ProbeOutcome.BOT_BLOCKED else ProbeOutcome.PARSE_MISMATCH,
                                detailMessage = "POST /api/messages/$digits ($phaseLabel) returned non-JSON payload${if (attemptIdx == 0) " — reloading once" else ""}",
                                responseSnippet = snippet,
                            )
                        )
                        if (attemptIdx == 0) continue
                        return TempFetchResult.Failure(
                            status = if (botCheck.isBlocked) TempNumberEntity.STATUS_BOT_BLOCKED else TempNumberEntity.STATUS_ERROR,
                            errorMessage = "SMS24.me POST /api/messages/$digits did not return valid JSON.",
                            attempts = attempts.toList(),
                        )
                    }

                    val outcome = if (parsedPage.messages.isNotEmpty()) {
                        ProbeOutcome.SUCCESS_WITH_MESSAGES
                    } else {
                        ProbeOutcome.EMPTY_INBOX
                    }
                    attempts.add(
                        SourceProbeAttempt(
                            sourceKey = TempNumberEntity.SOURCE_SMS24_ME,
                            displayName = "SMS24.me (POST API · $TOKEN_TRANSPORT)",
                            url = apiUrl,
                            httpCode = resp.status,
                            latencyMs = resp.latencyMs,
                            payloadBytes = payloadBytes,
                            parserStrategy = "sms24-post-json-v1",
                            parsedCount = parsedPage.messages.size,
                            outcome = outcome,
                            detailMessage = if (parsedPage.messages.isNotEmpty()) {
                                "POST /api/messages/$digits ($phaseLabel, Turnstile OK) returned ${parsedPage.messages.size} items (page ${parsedPage.page}/${parsedPage.pages})"
                            } else {
                                "POST /api/messages/$digits ($phaseLabel, Turnstile OK) confirmed empty inbox ({\"messages\":[]})"
                            },
                            responseSnippet = snippet,
                        )
                    )
                    val success = TempFetchResult.Success(
                        messages = parsedPage.messages,
                        resolvedSource = TempNumberEntity.SOURCE_SMS24_ME,
                        attempts = attempts.toList(),
                    )
                    val doneAt = System.currentTimeMillis()
                    val jitterMs = Random.nextLong(MIN_JITTER_POLL_MS, MAX_JITTER_POLL_MS + 1L)
                    backoffUntil.remove(digits)
                    cache[digits] = JitterCacheEntry(
                        fetchedAtMs = doneAt,
                        nextAllowedAtMs = doneAt + jitterMs,
                        result = success,
                    )
                    return success
                }

                resp.status == 403 || resp.status == 422 -> {
                    attempts.add(
                        SourceProbeAttempt(
                            sourceKey = TempNumberEntity.SOURCE_SMS24_ME,
                            displayName = "SMS24.me (POST API · $TOKEN_TRANSPORT)",
                            url = apiUrl,
                            httpCode = resp.status,
                            latencyMs = resp.latencyMs,
                            payloadBytes = payloadBytes,
                            botChallengeDetected = true,
                            parserStrategy = "sms24-post-json-v1",
                            outcome = ProbeOutcome.BOT_BLOCKED,
                            detailMessage = "HTTP ${resp.status} ($phaseLabel) — Turnstile token or challenge rejected${if (attemptIdx == 0) "; reloading page to mint fresh token" else ""}",
                            responseSnippet = snippet,
                        )
                    )
                    if (attemptIdx == 0) continue
                    return TempFetchResult.Failure(
                        status = TempNumberEntity.STATUS_BOT_BLOCKED,
                        errorMessage = "SMS24.me rejected Turnstile token after reload (HTTP ${resp.status}).",
                        attempts = attempts.toList(),
                    )
                }

                resp.status == 404 -> {
                    attempts.add(
                        SourceProbeAttempt(
                            sourceKey = TempNumberEntity.SOURCE_SMS24_ME,
                            displayName = "SMS24.me (POST API · $TOKEN_TRANSPORT)",
                            url = resp.finalUrl.ifBlank { apiUrl },
                            httpCode = 404,
                            latencyMs = resp.latencyMs,
                            payloadBytes = payloadBytes,
                            outcome = ProbeOutcome.NOT_FOUND,
                            detailMessage = resp.error ?: "Number +$digits not hosted on SMS24.me (HTTP 404)",
                            responseSnippet = snippet,
                        )
                    )
                    return TempFetchResult.Failure(
                        status = TempNumberEntity.STATUS_NOT_FOUND,
                        errorMessage = resp.error ?: "Number +$digits is not listed on SMS24.me (HTTP 404).",
                        attempts = attempts.toList(),
                    )
                }

                resp.status == 405 -> {
                    attempts.add(
                        SourceProbeAttempt(
                            sourceKey = TempNumberEntity.SOURCE_SMS24_ME,
                            displayName = "SMS24.me (POST API · $TOKEN_TRANSPORT)",
                            url = apiUrl,
                            httpCode = 405,
                            latencyMs = resp.latencyMs,
                            payloadBytes = payloadBytes,
                            outcome = ProbeOutcome.ERROR,
                            detailMessage = "HTTP 405 Method Not Allowed — endpoint requires POST /api/messages/$digits",
                            responseSnippet = snippet,
                        )
                    )
                    return TempFetchResult.Failure(
                        status = TempNumberEntity.STATUS_ERROR,
                        errorMessage = "SMS24.me returned HTTP 405 Method Not Allowed (POST required).",
                        attempts = attempts.toList(),
                    )
                }

                resp.status == 429 -> {
                    backoffUntil[digits] = BackoffState(System.currentTimeMillis() + BACKOFF_429_MS, 429)
                    attempts.add(
                        SourceProbeAttempt(
                            sourceKey = TempNumberEntity.SOURCE_SMS24_ME,
                            displayName = "SMS24.me (POST API · $TOKEN_TRANSPORT)",
                            url = apiUrl,
                            httpCode = 429,
                            latencyMs = resp.latencyMs,
                            payloadBytes = payloadBytes,
                            outcome = ProbeOutcome.RATE_LIMITED,
                            detailMessage = "SMS24.me throttled (HTTP 429) — backing off 120s",
                            responseSnippet = snippet,
                        )
                    )
                    return TempFetchResult.Failure(
                        status = TempNumberEntity.STATUS_RATE_LIMITED,
                        errorMessage = "SMS24.me rate limited (HTTP 429). Backing off for 120s.",
                        retryAfterMs = BACKOFF_429_MS,
                        attempts = attempts.toList(),
                    )
                }

                resp.status in 500..599 -> {
                    backoffUntil[digits] = BackoffState(System.currentTimeMillis() + BACKOFF_5XX_MS, resp.status)
                    attempts.add(
                        SourceProbeAttempt(
                            sourceKey = TempNumberEntity.SOURCE_SMS24_ME,
                            displayName = "SMS24.me (POST API · $TOKEN_TRANSPORT)",
                            url = apiUrl,
                            httpCode = resp.status,
                            latencyMs = resp.latencyMs,
                            payloadBytes = payloadBytes,
                            outcome = ProbeOutcome.ERROR,
                            detailMessage = "SMS24.me upstream fault (HTTP ${resp.status}) — backing off 60s",
                            responseSnippet = snippet,
                        )
                    )
                    return TempFetchResult.Failure(
                        status = TempNumberEntity.STATUS_ERROR,
                        errorMessage = "SMS24.me upstream error (HTTP ${resp.status}). Backing off for 60s.",
                        retryAfterMs = BACKOFF_5XX_MS,
                        attempts = attempts.toList(),
                    )
                }

                else -> {
                    val isTurnstileIssue = resp.error?.contains("turnstile", ignoreCase = true) == true
                    attempts.add(
                        SourceProbeAttempt(
                            sourceKey = TempNumberEntity.SOURCE_SMS24_ME,
                            displayName = "SMS24.me (POST API · $TOKEN_TRANSPORT)",
                            url = apiUrl,
                            httpCode = resp.status.takeIf { it > 0 },
                            latencyMs = resp.latencyMs,
                            payloadBytes = payloadBytes,
                            botChallengeDetected = isTurnstileIssue,
                            outcome = if (isTurnstileIssue) ProbeOutcome.BOT_BLOCKED else ProbeOutcome.ERROR,
                            detailMessage = (resp.error ?: "Unexpected status ${resp.status}") +
                                if (attemptIdx == 0) " ($phaseLabel) — reloading once" else " ($phaseLabel)",
                            responseSnippet = snippet,
                        )
                    )
                    if (attemptIdx == 0) continue
                    return TempFetchResult.Failure(
                        status = if (isTurnstileIssue) TempNumberEntity.STATUS_BOT_BLOCKED else TempNumberEntity.STATUS_ERROR,
                        errorMessage = resp.error ?: "SMS24.me API request failed (status ${resp.status}).",
                        attempts = attempts.toList(),
                    )
                }
            }
        }

        return TempFetchResult.Failure(
            status = TempNumberEntity.STATUS_ERROR,
            errorMessage = "SMS24.me POST /api/messages/$digits failed after reload.",
            attempts = attempts.toList(),
        )
    }

    /**
     * Parses the verified `POST /api/messages/{number}` JSON contract:
     * `{"messages": [{"from": "...", "senderUrl": null, "created": "...", "content": "...", "kind": "sms"|"call", ...}], "page": 1, "pages": 1}`
     *
     * Dedupe key: exact SHA-1 of `from|created|kind|content` (no `id` field exists).
     * When `kind == "call"`, `content` is an MP3 audio URL — never printed as SMS body text.
     */
    internal fun parseApiJsonResponse(
        rawJson: String,
        nowMs: Long = System.currentTimeMillis(),
    ): ParsedApiPage {
        val trimmed = rawJson.trim()
        if (!trimmed.startsWith("{")) {
            return ParsedApiPage(emptyList(), page = 1, pages = 1, validJson = false)
        }
        val root = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
            ?: return ParsedApiPage(emptyList(), page = 1, pages = 1, validJson = false)
        val messagesArray = root["messages"] as? JsonArray
            ?: return ParsedApiPage(emptyList(), page = 1, pages = 1, validJson = false)

        val page = (root["page"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 1
        val pages = (root["pages"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 1

        val out = ArrayList<TempParsedMessage>(messagesArray.size)
        val seenKeys = LinkedHashSet<String>()

        for (element in messagesArray) {
            val obj = element as? JsonObject ?: continue
            val rawFrom = obj.rawStringOrEmpty("from")
            val rawCreated = obj.rawStringOrEmpty("created")
            val rawKind = obj.rawStringOrEmpty("kind").ifBlank { "sms" }.lowercase()
            val rawContent = obj.rawStringOrEmpty("content")

            val dedupKey = computeDedupeKey(
                from = rawFrom,
                created = rawCreated,
                kind = rawKind,
                content = rawContent,
            )
            if (!seenKeys.add(dedupKey)) continue

            val sender = rawFrom.trim().ifEmpty { "Unknown sender" }.take(40)
            val ts = FlexibleTimestampParser.parse(rawCreated, nowMs)

            if (rawKind == "call") {
                val audioUrl = rawContent.trim().ifBlank { null }
                out.add(
                    TempParsedMessage(
                        fromNumber = sender.take(32),
                        senderName = sender,
                        body = "Voice call audio",
                        receivedAtEpochMs = ts.epochMs,
                        hasExplicitTimestamp = ts.isExplicit,
                        sourceTag = "sms24",
                        messageKind = "call",
                        audioUrl = audioUrl,
                        overrideDedupHash = dedupKey,
                    )
                )
            } else {
                val cleanBody = PhoneFormatter.sanitizeBody(rawContent, 640)
                if (cleanBody.isBlank() || Sms24NoiseFilter.isNonSmsPlaceholder(cleanBody)) continue
                out.add(
                    TempParsedMessage(
                        fromNumber = sender.take(32),
                        senderName = sender,
                        body = cleanBody,
                        receivedAtEpochMs = ts.epochMs,
                        hasExplicitTimestamp = ts.isExplicit,
                        sourceTag = "sms24",
                        messageKind = "sms",
                        audioUrl = null,
                        overrideDedupHash = dedupKey,
                    )
                )
            }
        }

        return ParsedApiPage(
            messages = out,
            page = page,
            pages = pages,
            validJson = true,
        )
    }

    private fun JsonObject.rawStringOrEmpty(key: String): String {
        val el = this[key] ?: return ""
        val prim = el as? JsonPrimitive ?: return ""
        return prim.contentOrNull ?: ""
    }

    internal fun isGenuinelyEmptySms24Page(html: String): Boolean {
        if (html.isBlank()) return false
        val visibleHtml = BotWallDetector.stripNonVisibleTags(html)
        val lower = visibleHtml.lowercase()
        // Never declare empty if message DOM markers exist in visible HTML
        val hasMessageMarkers = lower.contains("data-created=") ||
            lower.contains("/messages/") ||
            lower.contains("class=\"placeholder") ||
            lower.contains("class=placeholder") ||
            lower.contains("<dd")
        if (hasMessageMarkers) return false
        return lower.contains("has not received") ||
            lower.contains("no sms received") ||
            lower.contains("no messages received") ||
            lower.contains("no messages yet") ||
            lower.contains("inbox is empty") ||
            lower.contains("currently no messages") ||
            (visibleHtml.length < 2_000 && (lower.contains("no messages") || lower.contains("no sms")))
    }

    internal fun parseHtml(html: String, nowMs: Long = System.currentTimeMillis()): List<TempParsedMessage> {
        return parseHtmlWithStrategy(html, nowMs).first
    }

    internal fun parseHtmlWithStrategy(
        html: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<List<TempParsedMessage>, String?> {
        // Evaluate ALL strategies and pick the highest-scoring real SMS set so a single
        // non-SMS status/placeholder element in an earlier strategy never shadows real messages.
        var bestMsgs: List<TempParsedMessage> = emptyList()
        var bestStrategy: String? = null
        var bestScore = 0

        fun consider(raw: List<TempParsedMessage>, name: String) {
            val cleaned = dedupeParsed(raw)
            if (cleaned.isEmpty()) return
            val sc = Sms24NoiseFilter.score(cleaned)
            if (sc > bestScore) {
                bestScore = sc
                bestMsgs = cleaned
                bestStrategy = name
            }
        }

        // Strategy A1: Classic <dt ...>...</dt><dd ...>...</dd> (preserving <dt> tag attributes!)
        val dtDdOut = mutableListOf<TempParsedMessage>()
        for (match in dtDdRegex.findAll(html)) {
            val dtFullTag = match.groupValues[1]
            val ddBlock = match.groupValues[2]
            parseDdBlock(ddBlock = ddBlock, precedingContext = dtFullTag, nowMs = nowMs)?.let { dtDdOut.add(it) }
        }
        consider(dtDdOut, "dl-dt-dd-balanced")

        // Strategy A2: Relaxed balanced <dd> extraction (handles wrapper divs, comments, <hr>, or non-adjacent <dt>)
        val ddBlocks = extractBalancedElementsWithRanges(html, "dd", ddOpenRegex)
        if (ddBlocks.isNotEmpty()) {
            val relaxedOut = mutableListOf<TempParsedMessage>()
            for ((range, inner) in ddBlocks) {
                val lookbackStart = (range.first - 600).coerceAtLeast(0)
                val lookback = html.substring(lookbackStart, range.first)
                val lastDt = dtFullRegex.findAll(lookback).lastOrNull()?.value ?: lookback
                parseDdBlock(ddBlock = inner, precedingContext = lastDt, nowMs = nowMs)?.let { relaxedOut.add(it) }
            }
            consider(relaxedOut, "dd-relaxed-balanced")
        }

        // Strategy A3: HTML5 minified <dt>...<dd>... without optional </dt> / </dd> closing tags
        consider(parseUnclosedDtDd(html, nowMs), "dd-unclosed-html5")

        // Strategy B: Universal SMS24 `From:` / `/messages/` sender link anchor parser (layout & class agnostic!)
        consider(parseByFromMessageAnchors(html, nowMs), "from-messages-anchor")

        // Strategy C: `data-created` timestamp block segmentation (handles quoted & unquoted attributes)
        consider(parseByDataCreatedBlocks(html, nowMs), "data-created-blocks")

        // Strategy D: Balanced callout / card / shadow / message / list-group-item containers
        // Requires at least one real SMS signal (sender, explicit timestamp, From:, /messages/, or data-created)
        // so status banners like `<div class="callout">SMS inbox is ready</div>` can never match.
        val cardBlocks = BalancedHtmlExtractor.extractBalancedElements(
            html = html,
            tagName = "div",
            openTagRegex = calloutOpenRegex,
        ) + BalancedHtmlExtractor.extractBalancedElements(
            html = html,
            tagName = "li",
            openTagRegex = listGroupOpenRegex,
        ) + BalancedHtmlExtractor.extractBalancedElements(
            html = html,
            tagName = "article",
            openTagRegex = articleOpenRegex,
        )
        val cardOut = mutableListOf<TempParsedMessage>()
        for (block in cardBlocks) {
            if (block.length > 4_000) continue
            val ts = extractExplicitOrTimeTagTimestamp(block, nowMs)
            val labelBlock = fromLabelRegex.find(block)?.groupValues?.getOrNull(1).orEmpty()
            val senderRaw = fromLinkRegex.find(labelBlock)?.groupValues?.getOrNull(1)
                ?: messageHrefLinkRegex.find(block)?.groupValues?.getOrNull(1)
                ?: strongRegex.find(block)?.groupValues?.getOrNull(1)
                ?: labelBlock
            val sender = cleanSender(senderRaw)
            val hasSmsSignal = sender.isNotBlank() ||
                ts.isExplicit ||
                ts.epochMs != nowMs ||
                block.contains("data-created", ignoreCase = true) ||
                block.contains("/messages/", ignoreCase = true)
            if (!hasSmsSignal) continue
            val body = extractBestBodyFromContainer(block, sender) ?: continue
            val effectiveSender = sender.ifEmpty { PhoneFormatter.guessSender("", body).ifEmpty { "SMS24" } }
            cardOut.add(
                TempParsedMessage(
                    fromNumber = effectiveSender.take(32),
                    senderName = effectiveSender,
                    body = body,
                    receivedAtEpochMs = ts.epochMs,
                    hasExplicitTimestamp = ts.isExplicit,
                    sourceTag = "sms24",
                )
            )
        }
        consider(cardOut, "callout-cards-balanced")

        // Strategy E: Embedded JSON payloads inside <script> tags
        consider(parseEmbeddedJsonMessages(html, "sms24", "SMS24", nowMs), "embedded-json")

        // Strategy F: Generic HTML table rows
        consider(parseGenericHtmlTables(html, "sms24", "SMS24", nowMs), "html-table")

        return bestMsgs to bestStrategy
    }

    private fun parseUnclosedDtDd(html: String, nowMs: Long): List<TempParsedMessage> {
        val ddStarts = ddOpenRegex.findAll(html).toList()
        if (ddStarts.isEmpty()) return emptyList()
        val out = mutableListOf<TempParsedMessage>()
        for (i in ddStarts.indices) {
            val openMatch = ddStarts[i]
            val contentStart = openMatch.range.last + 1
            val nextDdStart = ddStarts.getOrNull(i + 1)?.range?.first ?: html.length
            val nextBoundary = listOfNotNull(
                dtOpenRegex.find(html, contentStart)?.range?.first,
                dlCloseRegex.find(html, contentStart)?.range?.first,
                ddCloseRegex.find(html, contentStart)?.range?.first,
                nextDdStart,
            ).minOrNull()?.coerceAtMost((contentStart + 2_500).coerceAtMost(html.length)) ?: nextDdStart
            if (nextBoundary <= contentStart) continue
            val ddInner = html.substring(contentStart, nextBoundary)
            val lookbackStart = (openMatch.range.first - 600).coerceAtLeast(0)
            val lookback = html.substring(lookbackStart, openMatch.range.first)
            val dtStartIdx = dtOpenRegex.findAll(lookback).lastOrNull()?.range?.first ?: 0
            val preceding = lookback.substring(dtStartIdx)
            parseDdBlock(ddBlock = ddInner, precedingContext = preceding, nowMs = nowMs)?.let { out.add(it) }
        }
        return out
    }

    private fun parseDdBlock(
        ddBlock: String,
        precedingContext: String,
        nowMs: Long,
    ): TempParsedMessage? {
        // Extract timestamp strictly from <dt>/preceding context or explicit time tags/attributes inside <dd>
        // (NEVER scan the SMS body text itself so phrases like "Valid for 7 days" cannot backdate the SMS!)
        val tsBefore = FlexibleTimestampParser.extractFromHtmlOrText(precedingContext, nowMs)
        val tsInside = extractExplicitOrTimeTagTimestamp(ddBlock, nowMs)
        val ts = when {
            tsBefore.isExplicit -> tsBefore
            tsInside.isExplicit -> tsInside
            tsBefore.epochMs != nowMs -> tsBefore
            else -> tsInside
        }

        val labelBlock = fromLabelRegex.find(ddBlock)?.groupValues?.getOrNull(1).orEmpty()
        val senderRaw = fromLinkRegex.find(labelBlock)?.groupValues?.getOrNull(1)
            ?: messageHrefLinkRegex.find(ddBlock)?.groupValues?.getOrNull(1)
            ?: labelBlock.ifBlank { fromLinkRegex.find(ddBlock)?.groupValues?.getOrNull(1).orEmpty() }
        val sender = cleanSender(senderRaw)

        val body = extractBestBodyFromContainer(ddBlock, sender) ?: return null
        val effectiveSender = sender.ifEmpty { PhoneFormatter.guessSender("", body).ifEmpty { "SMS24" } }
        return TempParsedMessage(
            fromNumber = effectiveSender.take(32),
            senderName = effectiveSender,
            body = body,
            receivedAtEpochMs = ts.epochMs,
            hasExplicitTimestamp = ts.isExplicit,
            sourceTag = "sms24",
        )
    }

    private fun extractExplicitOrTimeTagTimestamp(block: String, nowMs: Long): ParsedTimestamp {
        // Check explicit data-created / datetime attributes first
        val attrTs = FlexibleTimestampParser.extractFromHtmlOrText(
            dataCreatedAttrRegex.find(block)?.value ?: "",
            nowMs,
        )
        if (attrTs.isExplicit) return attrTs
        // Then check dedicated <small> / <time> / .text-muted tags (excluding the message body)
        val timeTag = timeRegex.find(block)?.value
        if (!timeTag.isNullOrBlank()) {
            return FlexibleTimestampParser.extractFromHtmlOrText(timeTag, nowMs)
        }
        return ParsedTimestamp(nowMs, isExplicit = false)
    }

    private fun cleanSender(raw: String): String {
        return HtmlEntityDecoder.decodeHtml(raw)
            .replace(Regex("""(?i)^from\s*:?\s*"""), "")
            .trim()
            .take(40)
    }

    /**
     * Strips <label>...</label>, sender /messages/ links, and time tags BEFORE scanning for the
     * SMS body so `<a class="placeholder ms-1">Sender</a>` or an empty badge `<span></span>`
     * can NEVER shadow the actual `<span class="placeholder text-break">` or plain `<span>` body.
     */
    private fun extractBestBodyFromContainer(containerHtml: String, sender: String): String? {
        val strippedMeta = containerHtml
            .replace(fullLabelBlockRegex, " ")
            .replace(messageHrefFullAnchorRegex, " ")
            .replace(timeRegex, " ")

        val candidates = mutableListOf<String>()
        candidates.addAll(
            BalancedHtmlExtractor.extractBalancedElements(
                html = strippedMeta,
                tagName = "span",
                openTagRegex = placeholderSpanOpenRegex,
            )
        )
        candidates.addAll(
            BalancedHtmlExtractor.extractBalancedElements(
                html = strippedMeta,
                tagName = "div",
                openTagRegex = textBreakDivOpenRegex,
            )
        )
        candidates.addAll(
            BalancedHtmlExtractor.extractBalancedElements(
                html = strippedMeta,
                tagName = "span",
                openTagRegex = anySpanOpenRegex,
            )
        )
        pTagRegex.findAll(strippedMeta).forEach { candidates.add(it.groupValues[1]) }
        candidates.add(strippedMeta)

        for (raw in candidates) {
            val cleaned = PhoneFormatter.sanitizeBody(HtmlEntityDecoder.decodeHtml(raw), 640)
            if (cleaned.length < 2) continue
            if (Sms24NoiseFilter.isNonSmsPlaceholder(cleaned)) continue
            if (sender.isNotBlank() && cleaned.equals(sender, ignoreCase = true)) continue
            if (cleaned.equals("From:", ignoreCase = true) || cleaned.equals("From", ignoreCase = true)) continue
            return cleaned
        }
        return null
    }

    private fun parseByFromMessageAnchors(html: String, nowMs: Long): List<TempParsedMessage> {
        val matches = fromAnchorRegex.findAll(html).toList()
        if (matches.isEmpty()) return emptyList()

        val out = mutableListOf<TempParsedMessage>()
        for (i in matches.indices) {
            val m = matches[i]
            val senderRaw = m.groupValues.drop(1).firstOrNull { it.isNotBlank() }.orEmpty()
            val sender = cleanSender(senderRaw)
            if (sender.isBlank()) continue

            val nextStart = if (i + 1 < matches.size) matches[i + 1].range.first else (m.range.last + 1_200).coerceAtMost(html.length)
            val afterSlice = html.substring((m.range.last + 1).coerceAtMost(html.length), nextStart.coerceAtMost(m.range.last + 1_200))
                .substringBefore("</dd>")
                .substringBefore("</li>")
                .substringBefore("</article>")
            val beforeStart = (m.range.first - 500).coerceAtLeast(if (i > 0) matches[i - 1].range.last + 1 else 0)
            val beforeSlice = html.substring(beforeStart, m.range.first)

            val body = extractBestBodyFromContainer(afterSlice, sender) ?: continue
            if (body.length < 3) continue

            val tsBefore = FlexibleTimestampParser.extractFromHtmlOrText(beforeSlice, nowMs)
            val tsAfter = extractExplicitOrTimeTagTimestamp(afterSlice, nowMs)
            val ts = when {
                tsBefore.isExplicit -> tsBefore
                tsAfter.isExplicit -> tsAfter
                tsBefore.epochMs != nowMs -> tsBefore
                else -> tsAfter
            }

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
        return out
    }

    private fun parseByDataCreatedBlocks(html: String, nowMs: Long): List<TempParsedMessage> {
        val matches = dataCreatedAttrRegex.findAll(html).toList()
        if (matches.isEmpty()) return emptyList()

        // Collapse nested duplicate data-created attributes inside the same <dt>/header wrapper
        // (e.g. <dt data-created="..."><div data-created="...">12 mins ago</div></dt>)
        val dedupedMatches = mutableListOf<MatchResult>()
        for (i in matches.indices) {
            val m = matches[i]
            val next = matches.getOrNull(i + 1)
            if (next != null && (next.range.first - m.range.first) < 180) {
                val between = html.substring(m.range.last + 1, next.range.first)
                if (!between.contains("</dd>", ignoreCase = true) && !between.contains("/messages/", ignoreCase = true)) {
                    continue
                }
            }
            dedupedMatches.add(m)
        }

        val out = mutableListOf<TempParsedMessage>()
        for (i in dedupedMatches.indices) {
            val m = dedupedMatches[i]
            val rawTime = m.groupValues[1].ifBlank { m.groupValues[2] }
            val ts = FlexibleTimestampParser.parse(rawTime, nowMs)
            // Always slice on tag boundaries ('<') so raw HTML attribute fragments never leak into decoded text
            val sliceStart = html.lastIndexOf('<', m.range.first).takeIf { it >= 0 } ?: m.range.first
            val nextMatch = dedupedMatches.getOrNull(i + 1)
            val nextSliceStart = if (nextMatch != null) {
                html.lastIndexOf('<', nextMatch.range.first).takeIf { it > sliceStart } ?: nextMatch.range.first
            } else {
                (sliceStart + 1_400).coerceAtMost(html.length)
            }
            val end = nextSliceStart.coerceAtMost((sliceStart + 1_400).coerceAtMost(html.length))
            if (end <= sliceStart) continue
            val slice = html.substring(sliceStart, end)

            val labelBlock = fromLabelRegex.find(slice)?.groupValues?.getOrNull(1).orEmpty()
            val senderRaw = fromLinkRegex.find(labelBlock)?.groupValues?.getOrNull(1)
                ?: messageHrefLinkRegex.find(slice)?.groupValues?.getOrNull(1)
                ?: strongRegex.find(slice)?.groupValues?.getOrNull(1)
                ?: labelBlock
            val sender = cleanSender(senderRaw)
            val body = extractBestBodyFromContainer(slice, sender) ?: continue
            if (body.length < 3) continue
            val effectiveSender = sender.ifEmpty { PhoneFormatter.guessSender("", body).ifEmpty { "SMS24" } }

            out.add(
                TempParsedMessage(
                    fromNumber = effectiveSender.take(32),
                    senderName = effectiveSender,
                    body = body,
                    receivedAtEpochMs = ts.epochMs,
                    hasExplicitTimestamp = ts.isExplicit,
                    sourceTag = "sms24",
                )
            )
        }
        return out
    }

    private fun extractBalancedElementsWithRanges(
        html: String,
        tagName: String,
        openTagRegex: Regex,
    ): List<Pair<IntRange, String>> {
        val openPattern = Regex("""<$tagName\b[^>]*>""", RegexOption.IGNORE_CASE)
        val closePattern = Regex("""</$tagName\s*>""", RegexOption.IGNORE_CASE)
        val results = mutableListOf<Pair<IntRange, String>>()
        var searchFrom = 0
        while (searchFrom < html.length) {
            val match = openTagRegex.find(html, searchFrom) ?: break
            val contentStart = match.range.last + 1
            var depth = 1
            var cursor = contentStart
            while (cursor < html.length && depth > 0) {
                val nextOpen = openPattern.find(html, cursor)
                val nextClose = closePattern.find(html, cursor)
                if (nextClose == null) {
                    depth = 0
                    cursor = html.length
                    break
                }
                if (nextOpen != null && nextOpen.range.first < nextClose.range.first) {
                    depth += 1
                    cursor = nextOpen.range.last + 1
                } else {
                    depth -= 1
                    if (depth == 0) {
                        results.add((match.range.first..nextClose.range.last) to html.substring(contentStart, nextClose.range.first))
                    }
                    cursor = nextClose.range.last + 1
                }
            }
            searchFrom = cursor.coerceAtLeast(match.range.last + 1)
        }
        return results
    }

    private fun dedupeParsed(items: List<TempParsedMessage>): List<TempParsedMessage> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<TempParsedMessage>(items.size)
        for (item in items) {
            if (Sms24NoiseFilter.isNonSmsPlaceholder(item.body)) continue
            val key = "${item.senderName.lowercase()}|${item.body.lowercase()}"
            if (seen.add(key)) out.add(item)
        }
        return out
    }

    companion object {
        const val TOKEN_TRANSPORT = "in_page"
        const val DEFAULT_TURNSTILE_SITEKEY = "0x4AAAAAAFQHYrfMKXcEi0gA"
        private const val MIN_JITTER_POLL_MS = 90_000L
        private const val MAX_JITTER_POLL_MS = 180_000L
        private const val BACKOFF_429_MS = 120_000L
        private const val BACKOFF_5XX_MS = 60_000L

        /**
         * Verified SMS24 dedupe key (no `id` field exists in the API response):
         * SHA-1 hex digest of `from|created|kind|content`.
         */
        fun computeDedupeKey(
            from: String,
            created: String,
            kind: String,
            content: String,
        ): String {
            val raw = "$from|$created|$kind|$content"
            val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray(StandardCharsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        // Capture full <dt ...>...</dt> so attributes like <dt data-created="..."> are preserved
        private val dtDdRegex = Regex("""(<dt\b[^>]*>[\s\S]*?</dt>)\s*<dd\b[^>]*>([\s\S]*?)</dd>""", RegexOption.IGNORE_CASE)
        private val dtFullRegex = Regex("""<dt\b[^>]*>[\s\S]*?</dt>""", RegexOption.IGNORE_CASE)
        private val dtOpenRegex = Regex("""<dt\b[^>]*>""", RegexOption.IGNORE_CASE)
        private val ddOpenRegex = Regex("""<dd\b[^>]*>""", RegexOption.IGNORE_CASE)
        private val ddCloseRegex = Regex("""</dd\s*>""", RegexOption.IGNORE_CASE)
        private val dlCloseRegex = Regex("""</dl\s*>""", RegexOption.IGNORE_CASE)
        private val anySpanOpenRegex = Regex("""<span\b[^>]*>""", RegexOption.IGNORE_CASE)
        private val fromLinkRegex = Regex("""<a[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        private val messageHrefLinkRegex = Regex(
            """<a[^>]*href\s*=\s*["']?[^"'\s>]*/messages?/[^"'\s>]+["']?[^>]*>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE,
        )
        private val messageHrefFullAnchorRegex = Regex(
            """<a[^>]*href\s*=\s*["']?[^"'\s>]*/messages?/[^"'\s>]+["']?[^>]*>[\s\S]*?</a>""",
            RegexOption.IGNORE_CASE,
        )
        private val fromAnchorRegex = Regex(
            """(?:<label[^>]*>\s*From\s*:?\s*(?:<a[^>]*>([\s\S]*?)</a>|([^<]{1,60}))\s*</label>|From\s*:\s*<a[^>]*>([\s\S]*?)</a>|<a[^>]*href\s*=\s*["']?(?:https?://[^"'\s>]+)?/(?:[a-z]{2}/)?messages?/[^"'\s>]+["']?[^>]*>([\s\S]*?)</a>)""",
            RegexOption.IGNORE_CASE,
        )
        private val dataCreatedAttrRegex = Regex(
            """data-created\s*=\s*(?:["']([^"']{4,48})["']|([^\s>"']{4,48}))""",
            RegexOption.IGNORE_CASE,
        )
        private val fromLabelRegex = Regex("""<label[^>]*>([\s\S]*?)</label>""", RegexOption.IGNORE_CASE)
        private val fullLabelBlockRegex = Regex("""<label[^>]*>[\s\S]*?</label>""", RegexOption.IGNORE_CASE)
        private val placeholderSpanOpenRegex = Regex(
            """<span[^>]*class\s*=\s*(?:["'][^"']*(?:placeholder|text-break|message)[^"']*["']|[^\s>]*?(?:placeholder|text-break|message)[^\s>]*)[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        private val textBreakDivOpenRegex = Regex(
            """<div[^>]*class\s*=\s*(?:["'][^"']*(?:placeholder|text-break|message-body|card-text)[^"']*["']|[^\s>]*?(?:placeholder|text-break|message-body|card-text)[^\s>]*)[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        private val calloutOpenRegex = Regex(
            """<div[^>]*class\s*=\s*(?:["'][^"']*(?:callout|sms-message|message-item|shadow-sm|sms-box|msg-box)[^"']*["']|[^\s>]*?(?:callout|sms-message|message-item|shadow-sm)[^\s>]*)[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        private val listGroupOpenRegex = Regex(
            """<li[^>]*class\s*=\s*(?:["'][^"']*list-group-item[^"']*["']|list-group-item)[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        private val articleOpenRegex = Regex("""<article\b[^>]*>""", RegexOption.IGNORE_CASE)
        private val strongRegex = Regex("""<(?:strong|b|h5|h6)[^>]*>([\s\S]*?)</(?:strong|b|h5|h6)>""", RegexOption.IGNORE_CASE)
        private val timeRegex = Regex("""<(?:small|time|div)[^>]*(?:data-created|datetime|text-muted|text-secondary)[^>]*>[\s\S]*?</(?:small|time|div)>|<(?:small|time)[^>]*>[\s\S]*?</(?:small|time)>""", RegexOption.IGNORE_CASE)
        private val pTagRegex = Regex("""<p[^>]*>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)
    }
}

internal object Sms24NoiseFilter {
    private val exactNoisePhrases = setOf(
        "sms inbox is ready",
        "inbox is ready",
        "your sms inbox is ready",
        "this sms inbox is ready",
        "waiting for sms",
        "waiting for messages",
        "waiting for new messages",
        "loading",
        "loading messages",
        "loading sms",
        "please wait",
        "refresh",
        "refresh messages",
        "refresh inbox",
        "update messages",
        "no messages",
        "no messages yet",
        "no sms",
        "no sms yet",
        "no sms received",
        "copy number",
        "copied",
        "from:",
        "from",
        "message",
        "messages",
    )

    private val pureTimestampRegex = Regex(
        """^(?:\d+\s*(?:sec(?:ond)?s?|min(?:ute)?s?|hour?s?|hr?s?|day?s?|wk?s?|week?s?)\s+ago|(?:a|an|one)\s+(?:few\s+seconds?|sec(?:ond)?|min(?:ute)?|hour|hr|day|week)\s+ago|just now|\d{4}[-/]\d{1,2}[-/]\d{1,2}[t ]\d{1,2}:\d{2}(?::\d{2})?)$"""
    )

    fun isNonSmsPlaceholder(body: String): Boolean {
        val norm = body.trim().lowercase().replace(Regex("""\s+"""), " ").trimEnd('.', '!', '…')
        if (norm.length < 2) return true
        if (norm in exactNoisePhrases) return true
        if (pureTimestampRegex.matches(norm)) return true
        if (norm.startsWith("data-created=") || norm.startsWith("class=\"") || norm.startsWith("href=\"")) return true
        if (norm.startsWith("sms inbox is ready")) return true
        if (norm.startsWith("privacy policy") || norm.startsWith("terms of service")) return true
        if (norm.startsWith("all messages are public") || norm.startsWith("receive sms online")) return true
        if (norm.startsWith("refresh this page")) return true
        return false
    }

    fun score(messages: List<TempParsedMessage>, fallbackSender: String = "SMS24"): Int {
        if (messages.isEmpty()) return 0
        var total = 0
        for (m in messages) {
            if (isNonSmsPlaceholder(m.body)) continue
            var pts = 10
            if (m.senderName.isNotBlank() && !m.senderName.equals(fallbackSender, ignoreCase = true)) {
                pts += 8
            }
            if (m.hasExplicitTimestamp) {
                pts += 6
            }
            if (m.body.length >= 12) {
                pts += 3
            }
            total += pts
        }
        return total
    }

    fun isLowConfidenceSingleFallback(messages: List<TempParsedMessage>, fallbackSender: String = "SMS24"): Boolean {
        if (messages.size != 1) return false
        val only = messages.first()
        return only.senderName.equals(fallbackSender, ignoreCase = true) && !only.hasExplicitTimestamp
    }
}

internal fun convertRenderedMessages(
    rendered: List<RenderedDomMessage>,
    sourceTag: String,
    fallbackSender: String,
    nowMs: Long,
): List<TempParsedMessage> {
    val out = ArrayList<TempParsedMessage>(rendered.size)
    val seen = LinkedHashSet<String>()
    for (msg in rendered) {
        val body = PhoneFormatter.sanitizeBody(msg.body, 640)
        if (body.length < 2 || Sms24NoiseFilter.isNonSmsPlaceholder(body)) continue
        val sender = msg.sender
            .replace(Regex("""(?i)^from\s*:?\s*"""), "")
            .trim()
            .take(40)
            .ifEmpty { PhoneFormatter.guessSender("", body).ifEmpty { fallbackSender } }
        val key = "${sender.lowercase()}|${body.lowercase()}"
        if (!seen.add(key)) continue
        val ts = FlexibleTimestampParser.parse(msg.timestampRaw, nowMs)
        out.add(
            TempParsedMessage(
                fromNumber = sender.take(32),
                senderName = sender,
                body = body,
                receivedAtEpochMs = ts.epochMs,
                hasExplicitTimestamp = ts.isExplicit,
                sourceTag = sourceTag,
            )
        )
    }
    return out
}

internal fun parseFlexibleJsonPayloads(
    payloads: List<String>,
    sourceTag: String,
    fallbackSender: String,
    nowMs: Long,
): List<TempParsedMessage> {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }
    val out = mutableListOf<TempParsedMessage>()
    val seen = LinkedHashSet<String>()

    fun walk(el: JsonElement) {
        if (out.size >= 80) return
        when (el) {
            is JsonArray -> el.forEach { walk(it) }
            is JsonObject -> {
                val bodyRaw = el.stringField("text", "body", "message", "content", "sms", "msg", "sms_text")
                if (!bodyRaw.isNullOrBlank()) {
                    val cleanBody = PhoneFormatter.sanitizeBody(bodyRaw, 640)
                    if (cleanBody.length >= 2 && !Sms24NoiseFilter.isNonSmsPlaceholder(cleanBody)) {
                        val senderRaw = el.stringField("from", "sender", "originator", "author", "phone", "service", "number_from")
                            .orEmpty()
                            .replace(Regex("""(?i)^from\s*:?\s*"""), "")
                            .trim()
                            .take(40)
                        val timeRaw = el.stringField("created_at", "received_at", "received", "created", "date", "time", "timestamp", "updated_at")
                            .orEmpty()
                        val effectiveSender = senderRaw.ifEmpty { PhoneFormatter.guessSender("", cleanBody).ifEmpty { fallbackSender } }
                        val key = "${effectiveSender.lowercase()}|${cleanBody.lowercase()}"
                        if (seen.add(key)) {
                            val ts = FlexibleTimestampParser.parse(timeRaw, nowMs)
                            out.add(
                                TempParsedMessage(
                                    fromNumber = effectiveSender.take(32),
                                    senderName = effectiveSender,
                                    body = cleanBody,
                                    receivedAtEpochMs = ts.epochMs,
                                    hasExplicitTimestamp = ts.isExplicit,
                                    sourceTag = sourceTag,
                                )
                            )
                        }
                        return
                    }
                }
                for (child in el.values) {
                    if (child is JsonArray || child is JsonObject) walk(child)
                }
            }
            else -> Unit
        }
    }

    for (raw in payloads) {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) continue
        val root = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() ?: continue
        walk(root)
    }
    return out
}

internal fun parseEmbeddedJsonMessages(
    html: String,
    sourceTag: String,
    fallbackSender: String,
    nowMs: Long,
): List<TempParsedMessage> {
    val text = html.replace("&amp;quot;", "\"").replace("&quot;", "\"")
    val regex = Regex(
        """\\?"(?:from|sender|originator)\\?"\s*:\s*\\?"((?:[^"\\]|\\.)*)\\?"\s*,\s*\\?"(?:text|message|body|content)\\?"\s*:\s*\\?"((?:[^"\\]|\\.)*)\\?"(?:\s*,\s*\\?"(?:received|received_at|created_at|date|time|timestamp)\\?"\s*:\s*\\?"([^"\\]*)\\?")?""",
        RegexOption.IGNORE_CASE,
    )
    val out = mutableListOf<TempParsedMessage>()
    for (m in regex.findAll(text)) {
        val senderRaw = HtmlEntityDecoder.decodeEscapedJsonFragment(m.groupValues[1]).take(40)
        val bodyRaw = PhoneFormatter.sanitizeBody(HtmlEntityDecoder.decodeEscapedJsonFragment(m.groupValues[2]), 640)
        val timeRaw = m.groupValues[3]
        if (bodyRaw.length < 3 || Sms24NoiseFilter.isNonSmsPlaceholder(bodyRaw)) continue
        val ts = FlexibleTimestampParser.parse(timeRaw, nowMs)
        val sender = senderRaw.ifEmpty { PhoneFormatter.guessSender("", bodyRaw).ifEmpty { fallbackSender } }
        out.add(
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
    return out
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
    private val browserFetcher: BrowserDomFetcher? = null,
) {
    val gvpnAdapter = GetVirtualPhoneApiAdapter(client)
    val sms24Adapter = Sms24HtmlAdapter(client, browserFetcher)
    val receiveSmssMirrorAdapter = ReceiveSmssMirrorAdapter(client)
    val quackrAdapter = QuackrAdapter(client, browserFetcher)
    val tempSmssAdapter = TempSmssAdapter(client, browserFetcher)
    val receiveSmsFreeAdapter = ReceiveSmsFreeAdapter(client, browserFetcher)
    val receiveSmsOnlineAdapter = ReceiveSmsOnlineAdapter(client, browserFetcher)
    val publicMirrorAdapter = PublicSmsMirrorAdapter(
        quackr = quackrAdapter,
        tempSmss = tempSmssAdapter,
        receiveSmsFree = receiveSmsFreeAdapter,
        receiveSmsOnline = receiveSmsOnlineAdapter,
    )
    val customAdapter = CustomSourceAdapter(client)

    private val perNumberLocks = ConcurrentHashMap<String, Mutex>()

    fun isSyncInProgress(numberId: String): Boolean = perNumberLocks[numberId]?.isLocked == true

    suspend fun fetchForTempNumber(
        temp: TempNumberEntity,
        customSource: CustomSourceEntity?,
        force: Boolean = false,
    ): TempFetchResult {
        val lock = perNumberLocks.getOrPut(temp.numberId) { Mutex() }
        if (force) {
            lock.lock()
        } else if (!lock.tryLock()) {
            // Never return a fake Success(emptyList()) on lock contention — that overwrote
            // DB status to STATUS_ONLINE (green) with 0 messages while a real fetch was running!
            return TempFetchResult.SkippedInProgress
        }
        return try {
            when (temp.sourceType) {
                TempNumberEntity.SOURCE_GET_VIRTUAL_PHONE_API -> gvpnAdapter.fetch(temp.e164, force)
                TempNumberEntity.SOURCE_ANOSIM -> anosimProvider.fetchForNumber(temp.e164, force)
                TempNumberEntity.SOURCE_SMS24_ME -> sms24Adapter.fetch(temp.e164, force)
                TempNumberEntity.SOURCE_RECEIVE_SMSS_MIRROR -> receiveSmssMirrorAdapter.fetch(temp.e164, force)
                TempNumberEntity.SOURCE_QUACKR -> quackrAdapter.fetch(temp.e164, force, allowBrowserFallback = true)
                TempNumberEntity.SOURCE_TEMPSMSS -> tempSmssAdapter.fetch(temp.e164, force, allowBrowserFallback = true)
                TempNumberEntity.SOURCE_RECEIVE_SMS_FREE -> receiveSmsFreeAdapter.fetch(temp.e164, force, allowBrowserFallback = true)
                TempNumberEntity.SOURCE_RECEIVE_SMS_ONLINE -> receiveSmsOnlineAdapter.fetch(temp.e164, force, allowBrowserFallback = true)
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
                TempNumberEntity.SOURCE_QUACKR -> quackrAdapter.fetch(temp.e164, force, allowBrowserFallback = false)
                TempNumberEntity.SOURCE_TEMPSMSS -> tempSmssAdapter.fetch(temp.e164, force, allowBrowserFallback = false)
                TempNumberEntity.SOURCE_RECEIVE_SMS_FREE -> receiveSmsFreeAdapter.fetch(temp.e164, force, allowBrowserFallback = false)
                TempNumberEntity.SOURCE_RECEIVE_SMS_ONLINE -> receiveSmsOnlineAdapter.fetch(temp.e164, force, allowBrowserFallback = false)
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
            // If a source genuinely confirmed an empty inbox (after Chromium DOM check when applicable),
            // do NOT let an unrelated mirror's HTTP 200 parse mismatch overwrite it.
            confirmedEmptySource != null -> TempFetchResult.Success(
                messages = emptyList(),
                resolvedSource = confirmedEmptySource,
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
