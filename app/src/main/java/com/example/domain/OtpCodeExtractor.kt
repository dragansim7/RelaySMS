package com.example.domain

/**
 * Deterministic local extractor for SMS verification codes / OTPs.
 * Prioritizes explicit prefixed codes (e.g. G-184223), keyword-anchored codes,
 * grouped codes (e.g. 123-456), and standalone 4-8 digit tokens while filtering
 * out phone numbers, dates, timestamps, and currency amounts.
 */
object OtpCodeExtractor {

    private val prefixedCodeRegex = Regex(
        """\b([A-Z]{1,3}-\d{4,8})\b"""
    )

    private val keywordAfterRegex = Regex(
        """(?i)(?:verification\s+code|security\s+code|login\s+code|passcode|one[- ]time\s+(?:code|password|pin)|otp|code|pin)\s*(?:is|:|#|-|–)?\s*\b([0-9]{3}[- ][0-9]{3}|[A-Z0-9]{4,8})\b"""
    )

    private val keywordBeforeRegex = Regex(
        """(?i)\b([0-9]{3}[- ][0-9]{3}|\d{4,8})\b\s+(?:is\s+your\s+|is\s+the\s+)?(?:[a-z]+\s+)?(?:verification|security|login|confirmation|one[- ]time|otp|passcode|code|pin)"""
    )

    private val groupedSixDigitRegex = Regex(
        """(?<!\d)(\d{3}[- ]\d{3})(?!\d)"""
    )

    private val standaloneDigitsRegex = Regex(
        """(?<![+\d$€£¥.,/:@-])\b(\d{4,8})\b(?![\d.,/:%@-])"""
    )

    private val verificationContextHint = Regex(
        """(?i)\b(code|otp|verify|verification|pin|passcode|login|sign\s*in|auth|confirm|token|security)\b"""
    )

    fun extract(body: String): String? {
        if (body.isBlank()) return null

        prefixedCodeRegex.find(body)?.groupValues?.getOrNull(1)?.let { match ->
            return match
        }

        keywordAfterRegex.find(body)?.groupValues?.getOrNull(1)?.let { raw ->
            val candidate = raw.trim()
            if (candidate.any { it.isDigit() } && !isLikelyYearOnly(candidate)) {
                return candidate
            }
        }

        keywordBeforeRegex.find(body)?.groupValues?.getOrNull(1)?.let { raw ->
            val candidate = raw.trim()
            if (candidate.any { it.isDigit() } && !isLikelyYearOnly(candidate)) {
                return candidate
            }
        }

        groupedSixDigitRegex.find(body)?.groupValues?.getOrNull(1)?.let { grouped ->
            if (verificationContextHint.containsMatchIn(body)) {
                return grouped
            }
        }

        val candidates = standaloneDigitsRegex.findAll(body)
            .map { it.groupValues[1] }
            .filter { !isLikelyYearOnly(it) }
            .toList()

        if (candidates.isEmpty()) return null

        // Prefer 6-digit codes, then 5/4/7/8 digits
        return candidates.firstOrNull { it.length == 6 }
            ?: candidates.firstOrNull { it.length in 4..8 && verificationContextHint.containsMatchIn(body) }
            ?: candidates.firstOrNull { it.length in 5..6 }
    }

    /** Returns compact digits-only copy payload when code uses spaces/hyphens unless prefixed like G-184223. */
    fun copyValue(extracted: String): String {
        if (extracted.any { it.isLetter() }) return extracted
        return extracted.replace(" ", "").replace("-", "")
    }

    private fun isLikelyYearOnly(token: String): Boolean {
        if (token.length != 4) return false
        val year = token.toIntOrNull() ?: return false
        return year in 1990..2035
    }
}
