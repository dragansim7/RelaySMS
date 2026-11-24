package com.example.data.remote

import okhttp3.Request

enum class ProbeOutcome(val label: String) {
    SUCCESS_WITH_MESSAGES("LIVE"),
    EMPTY_INBOX("EMPTY INBOX"),
    BOT_BLOCKED("BOT BLOCKED"),
    PARSE_MISMATCH("PARSE MISMATCH"),
    NOT_FOUND("NOT FOUND"),
    RATE_LIMITED("RATE LIMITED"),
    OFFLINE("OFFLINE"),
    ERROR("ERROR"),
}

data class SourceProbeAttempt(
    val sourceKey: String,
    val displayName: String,
    val url: String,
    val httpCode: Int? = null,
    val latencyMs: Long = 0L,
    val payloadBytes: Int = 0,
    val botChallengeDetected: Boolean = false,
    val parserStrategy: String? = null,
    val parsedCount: Int = 0,
    val insertedCount: Int = 0,
    val dedupSkippedCount: Int = 0,
    val outcome: ProbeOutcome = ProbeOutcome.ERROR,
    val detailMessage: String = "",
    val responseSnippet: String? = null,
    val fromCache: Boolean = false,
)

data class TempSyncDiagnosticReport(
    val numberId: String,
    val e164: String,
    val configuredSource: String,
    val resolvedSource: String?,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long,
    val overallVerdict: ProbeOutcome,
    val summary: String,
    val attempts: List<SourceProbeAttempt>,
) {
    val totalLatencyMs: Long
        get() = (completedAtEpochMs - startedAtEpochMs).coerceAtLeast(1L)

    fun formatShareableText(): String {
        val sb = StringBuilder()
        sb.appendLine("Relay Live Sync Diagnostic Report")
        sb.appendLine("Number: $e164")
        sb.appendLine("Configured Source: $configuredSource")
        sb.appendLine("Resolved Source: ${resolvedSource ?: "None"}")
        sb.appendLine("Verdict: ${overallVerdict.label} (${totalLatencyMs}ms)")
        sb.appendLine("Summary: $summary")
        sb.appendLine("---")
        attempts.forEachIndexed { idx, a ->
            sb.appendLine("${idx + 1}. ${a.displayName} [${a.outcome.label}]")
            sb.appendLine("   URL: ${a.url}")
            sb.appendLine("   HTTP: ${a.httpCode ?: "N/A"} | Latency: ${a.latencyMs}ms | Size: ${a.payloadBytes}B")
            if (a.parserStrategy != null) sb.appendLine("   Parser: ${a.parserStrategy}")
            if (a.botChallengeDetected) sb.appendLine("   Anti-Bot: Challenge page detected")
            sb.appendLine("   Messages: parsed=${a.parsedCount}, inserted=${a.insertedCount}, dupSkipped=${a.dedupSkippedCount}")
            if (a.detailMessage.isNotBlank()) sb.appendLine("   Detail: ${a.detailMessage}")
            if (!a.responseSnippet.isNullOrBlank()) {
                sb.appendLine("   Snippet: ${a.responseSnippet.take(180)}")
            }
        }
        return sb.toString().trim()
    }
}

object BrowserHttpHeaders {
    private const val CHROME_MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    fun applyHtmlHeaders(builder: Request.Builder, referer: String? = null): Request.Builder {
        builder
            .header("User-Agent", CHROME_MOBILE_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .header("Upgrade-Insecure-Requests", "1")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", if (referer != null) "same-origin" else "none")
            .header("Sec-Fetch-User", "?1")
        if (!referer.isNullOrBlank()) {
            builder.header("Referer", referer)
        }
        return builder
    }

    fun applyJsonHeaders(builder: Request.Builder): Request.Builder {
        return builder
            .header("User-Agent", CHROME_MOBILE_UA)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cache-Control", "no-cache")
    }
}

data class BotDetectionResult(
    val isBlocked: Boolean,
    val reason: String? = null,
)

object BotWallDetector {
    private val titleRegex = Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)
    private val scriptStyleRegex = Regex("""<(?:script|style|noscript)[^>]*>[\s\S]*?</(?:script|style|noscript)>""", RegexOption.IGNORE_CASE)
    private val tagRegex = Regex("""<[^>]+>""")
    private val whitespaceRegex = Regex("""\s+""")

    private val challengeMarkers = listOf(
        "just a moment..." to "Cloudflare browser verification",
        "attention required! | cloudflare" to "Cloudflare access block",
        "cf-browser-verification" to "Cloudflare browser check",
        "cf_chl_opt" to "Cloudflare JS challenge",
        "challenges.cloudflare.com" to "Cloudflare Turnstile challenge",
        "checking your browser before accessing" to "DDoS/browser challenge",
        "enable javascript and cookies to continue" to "JavaScript & cookie challenge",
        "ddos-guard" to "DDoS-Guard interstitial",
        "please verify you are a human" to "Human verification captcha",
        "g-recaptcha" to "reCAPTCHA verification wall",
        "h-captcha" to "hCaptcha verification wall",
        "access denied | " to "WAF access denied page",
    )

    fun detect(httpCode: Int, body: String): BotDetectionResult {
        val lower = body.lowercase()
        for ((needle, reason) in challengeMarkers) {
            if (lower.contains(needle)) {
                return BotDetectionResult(isBlocked = true, reason = reason)
            }
        }
        if (httpCode == 403 || httpCode == 503) {
            val title = extractTitle(body)
            val suffix = if (!title.isNullOrBlank()) " ($title)" else ""
            return BotDetectionResult(
                isBlocked = true,
                reason = "HTTP $httpCode anti-bot or WAF block$suffix",
            )
        }
        return BotDetectionResult(isBlocked = false)
    }

    fun extractTitle(html: String): String? {
        val match = titleRegex.find(html) ?: return null
        return HtmlEntityDecoder.decodeHtml(match.groupValues[1]).take(120).ifBlank { null }
    }

    fun buildSnippet(raw: String, maxLen: Int = 360): String {
        val title = extractTitle(raw)
        val trimmed = raw.trim()
        val bodyText = if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            whitespaceRegex.replace(trimmed, " ")
        } else {
            val noScripts = scriptStyleRegex.replace(trimmed, " ")
            val stripped = tagRegex.replace(noScripts, " ")
            whitespaceRegex.replace(HtmlEntityDecoder.decodeHtml(stripped), " ").trim()
        }
        val combined = if (!title.isNullOrBlank() && !bodyText.startsWith(title)) {
            "[$title] $bodyText"
        } else {
            bodyText
        }
        return combined.take(maxLen).ifBlank { "(empty response body)" }
    }
}

/**
 * Extracts balanced inner HTML for tags like <span class="...placeholder...">...</span>
 * even when SMS messages contain nested <span>, <a>, or <b> tags.
 */
object BalancedHtmlExtractor {

    fun extractBalancedElements(
        html: String,
        tagName: String,
        openTagRegex: Regex,
    ): List<String> {
        val openPattern = Regex("""<$tagName\b[^>]*>""", RegexOption.IGNORE_CASE)
        val closePattern = Regex("""</$tagName\s*>""", RegexOption.IGNORE_CASE)
        val results = mutableListOf<String>()
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
                        results.add(html.substring(contentStart, nextClose.range.first))
                    }
                    cursor = nextClose.range.last + 1
                }
            }
            searchFrom = cursor.coerceAtLeast(match.range.last + 1)
        }
        return results
    }
}
