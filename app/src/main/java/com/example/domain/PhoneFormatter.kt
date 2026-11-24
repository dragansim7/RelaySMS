package com.example.domain

data class SupportedCountry(
    val code: String,
    val name: String,
    val dialPrefix: String,
)

object PhoneFormatter {

    val SUPPORTED_COUNTRIES: List<SupportedCountry> = listOf(
        SupportedCountry("AU", "Australia", "+61"),
        SupportedCountry("GB", "United Kingdom", "+44"),
        SupportedCountry("US", "United States", "+1"),
        SupportedCountry("CA", "Canada", "+1"),
        SupportedCountry("NZ", "New Zealand", "+64"),
        SupportedCountry("DE", "Germany", "+49"),
        SupportedCountry("NL", "Netherlands", "+31"),
    )

    private val countryByCode: Map<String, SupportedCountry> =
        SUPPORTED_COUNTRIES.associateBy { it.code }

    private val e164Regex = Regex("""^\+[1-9]\d{7,14}$""")
    private val nonDigitRegex = Regex("""\D""")

    fun digitsOnly(input: String): String = nonDigitRegex.replace(input, "")

    fun normalizeE164(input: String): String {
        val digits = digitsOnly(input)
        return if (digits.isEmpty()) "" else "+$digits"
    }

    fun isE164(value: String): Boolean = e164Regex.matches(value)

    fun countryNameFor(code: String): String? = countryByCode[code.uppercase()]?.name

    fun displayE164(e164: String): String {
        if (e164.startsWith("+61") && e164.length == 12) {
            val d = e164.substring(3)
            return "+61 ${d.substring(0, 3)} ${d.substring(3, 6)} ${d.substring(6)}"
        }
        if (e164.startsWith("+44") && e164.length >= 12) {
            val d = e164.substring(3)
            return "+44 ${d.substring(0, 4)} ${d.substring(4)}"
        }
        if (e164.startsWith("+1") && e164.length == 12) {
            val d = e164.substring(2)
            return "+1 ${d.substring(0, 3)} ${d.substring(3, 6)} ${d.substring(6)}"
        }
        if (e164.startsWith("+64") && e164.length >= 11) {
            val d = e164.substring(3)
            return "+64 ${d.substring(0, 2)} ${d.substring(2, 5)} ${d.substring(5)}"
        }
        return e164
    }

    fun localFormat(e164: String): String? {
        if (e164.startsWith("+61") && e164.length == 12) {
            val d = e164.substring(3)
            return "0${d.substring(0, 3)} ${d.substring(3, 6)} ${d.substring(6)}"
        }
        return null
    }

    fun guessSender(from: String, body: String): String {
        val hay = "$from $body".lowercase()
        val known = listOf(
            "telegram",
            "google",
            "discord",
            "instagram",
            "signal",
            "whatsapp",
            "microsoft",
            "apple",
            "openai",
            "github",
            "uber",
            "amazon",
            "paypal",
            "stripe",
        )
        for (name in known) {
            if (hay.contains(name)) {
                return name.replaceFirstChar { it.uppercaseChar() }
            }
        }
        return ""
    }

    fun sanitizeBody(body: String, maxLen: Int): String {
        val cleaned = body.replace(Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F]"""), "")
            .trim()
        return if (cleaned.length > maxLen) cleaned.substring(0, maxLen) else cleaned
    }

    fun inferCountryFromE164(e164: String): Pair<String, String> {
        return when {
            e164.startsWith("+61") -> "AU" to "Australia"
            e164.startsWith("+44") -> "GB" to "United Kingdom"
            e164.startsWith("+1") -> "US" to "United States"
            e164.startsWith("+64") -> "NZ" to "New Zealand"
            e164.startsWith("+49") -> "DE" to "Germany"
            e164.startsWith("+31") -> "NL" to "Netherlands"
            e164.startsWith("+33") -> "FR" to "France"
            e164.startsWith("+34") -> "ES" to "Spain"
            e164.startsWith("+46") -> "SE" to "Sweden"
            e164.startsWith("+65") -> "SG" to "Singapore"
            e164.startsWith("+91") -> "IN" to "India"
            else -> "GL" to "International"
        }
    }

    fun computeDedupHash(
        numberId: String,
        sender: String,
        body: String,
        receivedAtEpochMs: Long,
    ): String {
        val bucket2Min = receivedAtEpochMs / 120_000L
        val canonical = "${digitsOnly(numberId)}|${sender.trim().lowercase()}|${body.trim().lowercase()}|$bucket2Min"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }
}
