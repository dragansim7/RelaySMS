package com.example.data.remote

enum class ProviderHealthStatus {
    IDLE,
    SYNCING,
    LIVE,
    DEGRADED,
}

data class ProviderDiagnostic(
    val providerKey: String,
    val displayName: String,
    val status: ProviderHealthStatus = ProviderHealthStatus.IDLE,
    val lastSuccessEpochMs: Long? = null,
    val lastAttemptEpochMs: Long? = null,
    val lastLatencyMs: Long? = null,
    val lastNewCount: Int = 0,
    val lastError: String? = null,
)

enum class OverallSyncStatus {
    IDLE,
    SYNCING,
    LIVE,
    DEGRADED,
}

data class SyncHealthState(
    val providers: Map<String, ProviderDiagnostic> = mapOf(
        KEY_ANOSIM to ProviderDiagnostic(KEY_ANOSIM, "Anosim AU"),
        KEY_RECEIVESMSS to ProviderDiagnostic(KEY_RECEIVESMSS, "receive-smss.live"),
    ),
    val isManualRefreshing: Boolean = false,
) {
    val overallStatus: OverallSyncStatus
        get() {
            val all = providers.values
            if (isManualRefreshing || all.any { it.status == ProviderHealthStatus.SYNCING }) {
                return OverallSyncStatus.SYNCING
            }
            if (all.any { it.status == ProviderHealthStatus.LIVE }) {
                return if (all.any { it.status == ProviderHealthStatus.DEGRADED }) {
                    OverallSyncStatus.DEGRADED
                } else {
                    OverallSyncStatus.LIVE
                }
            }
            if (all.any { it.status == ProviderHealthStatus.DEGRADED }) {
                return OverallSyncStatus.DEGRADED
            }
            return OverallSyncStatus.IDLE
        }

    val lastSuccessfulSyncMs: Long?
        get() = providers.values.mapNotNull { it.lastSuccessEpochMs }.maxOrNull()

    companion object {
        const val KEY_ANOSIM = "anosim"
        const val KEY_RECEIVESMSS = "receivesmss"
    }
}

data class ParsedRemoteSms(
    val numberId: String,
    val fromNumber: String,
    val senderName: String,
    val body: String,
    val receivedAtEpochMs: Long,
    val source: String,
)

object HtmlEntityDecoder {

    private val hexEntity = Regex("""&#x([0-9a-fA-F]+);""")
    private val decEntity = Regex("""&#(\d+);""")
    private val unicodeEscape = Regex("""\\u([0-9a-fA-F]{4})""")
    private val htmlTag = Regex("""<[^>]+>""")
    private val multiSpace = Regex("""\s+""")

    fun decodeHtml(value: String, stripTags: Boolean = true): String {
        var result = value
        result = hexEntity.replace(result) { match ->
            val cp = match.groupValues[1].toIntOrNull(16) ?: return@replace match.value
            runCatching { String(Character.toChars(cp)) }.getOrDefault("")
        }
        result = decEntity.replace(result) { match ->
            val cp = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            runCatching { String(Character.toChars(cp)) }.getOrDefault("")
        }
        result = result
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
        if (stripTags) {
            result = htmlTag.replace(result, "")
        }
        return multiSpace.replace(result, " ").trim()
    }

    fun decodeEscapedJsonFragment(value: String): String {
        var result = value
            .replace("\\n", "\n")
            .replace("\\r", "")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
        result = unicodeEscape.replace(result) { match ->
            val code = match.groupValues[1].toIntOrNull(16) ?: return@replace match.value
            code.toChar().toString()
        }
        return decodeHtml(result, stripTags = false)
    }
}
