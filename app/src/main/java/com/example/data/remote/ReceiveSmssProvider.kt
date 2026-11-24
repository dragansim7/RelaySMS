package com.example.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Calendar
import java.util.TimeZone

class ReceiveSmssProvider(private val client: OkHttpClient) {

    private val mutex = Mutex()

    @Volatile
    private var lastFetchMs: Long = 0L

    suspend fun fetchMessages(
        activeIds: Set<String>,
        onlyNumberId: String? = null,
        force: Boolean = false,
    ): Result<List<ParsedRemoteSms>> {
        val targetIds = if (onlyNumberId != null) {
            SUPPORTED_IDS.filter { it == onlyNumberId && it in activeIds }
        } else {
            SUPPORTED_IDS.filter { it in activeIds }
        }
        if (targetIds.isEmpty()) return Result.success(emptyList())

        val now = System.currentTimeMillis()
        if (!force && onlyNumberId == null && (now - lastFetchMs) < MIN_GAP_MS) {
            return Result.success(emptyList())
        }
        if (!mutex.tryLock()) {
            return Result.success(emptyList())
        }

        return try {
            lastFetchMs = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                var firstError: Throwable? = null
                var anySuccess = false
                val allMessages = mutableListOf<ParsedRemoteSms>()

                val results = coroutineScope {
                    targetIds.map { id ->
                        async { id to fetchOne(id) }
                    }.awaitAll()
                }

                for ((_, result) in results) {
                    result.onSuccess { list ->
                        anySuccess = true
                        allMessages.addAll(list)
                    }.onFailure { err ->
                        if (firstError == null) firstError = err
                    }
                }

                if (!anySuccess && firstError != null) {
                    Result.failure(firstError!!)
                } else {
                    Result.success(allMessages)
                }
            }
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            mutex.unlock()
        }
    }

    private fun fetchOne(numberId: String): Result<List<ParsedRemoteSms>> {
        return runCatching {
            val request = Request.Builder()
                .url("https://receive-smss.live/sms/au/$numberId")
                .header("Accept", "text/html")
                .header("User-Agent", "Mozilla/5.0 (compatible; Relay/1.0)")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code} for $numberId")
                }
                val html = response.body?.string().orEmpty()
                parseHtml(numberId, html)
            }
        }
    }

    internal fun parseHtml(numberId: String, html: String): List<ParsedRemoteSms> {
        val text = html
            .replace("&amp;quot;", "\"")
            .replace("&quot;", "\"")

        val out = mutableListOf<ParsedRemoteSms>()
        for (match in messageRegex.findAll(text)) {
            val senderRaw = match.groupValues[1]
            val contentRaw = match.groupValues[2]
            val receivedRaw = match.groupValues[3]

            val body = HtmlEntityDecoder.decodeEscapedJsonFragment(contentRaw).take(640)
            val receivedMs = parseIso8601ToEpochMs(receivedRaw) ?: continue
            if (body.isEmpty()) continue

            val sender = HtmlEntityDecoder.decodeEscapedJsonFragment(senderRaw)
                .take(40)
                .ifEmpty { "Unknown" }

            out.add(
                ParsedRemoteSms(
                    numberId = numberId,
                    fromNumber = sender.take(32),
                    senderName = sender,
                    body = body,
                    receivedAtEpochMs = receivedMs,
                    source = "receivesmss",
                )
            )
        }
        return out
    }

    internal fun parseIso8601ToEpochMs(iso: String): Long? {
        val m = isoRegex.matchEntire(iso.trim()) ?: return null
        val year = m.groupValues[1].toIntOrNull() ?: return null
        val month = m.groupValues[2].toIntOrNull() ?: return null
        val day = m.groupValues[3].toIntOrNull() ?: return null
        val hour = m.groupValues[4].toIntOrNull() ?: return null
        val minute = m.groupValues[5].toIntOrNull() ?: return null
        val second = m.groupValues[6].toIntOrNull() ?: return null
        val millisRaw = m.groupValues[7]
        val millis = when {
            millisRaw.isEmpty() -> 0
            millisRaw.length >= 3 -> millisRaw.substring(0, 3).toIntOrNull() ?: 0
            millisRaw.length == 2 -> (millisRaw.toIntOrNull() ?: 0) * 10
            else -> (millisRaw.toIntOrNull() ?: 0) * 100
        }
        val tzRaw = m.groupValues[8]
        val tz = when {
            tzRaw.isEmpty() || tzRaw.equals("Z", ignoreCase = true) -> TimeZone.getTimeZone("UTC")
            else -> TimeZone.getTimeZone("GMT$tzRaw")
        }

        val cal = Calendar.getInstance(tz).apply {
            clear()
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, (month - 1).coerceIn(0, 11))
            set(Calendar.DAY_OF_MONTH, day.coerceIn(1, 31))
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, second.coerceIn(0, 59))
            set(Calendar.MILLISECOND, millis.coerceIn(0, 999))
        }
        return cal.timeInMillis
    }

    companion object {
        val SUPPORTED_IDS: Set<String> = setOf(
            "61468279945",
            "61485922587",
            "61483966924",
        )
        private const val MIN_GAP_MS = 8_000L

        private val messageRegex = Regex(
            """"sender":\[0,"((?:\\.|[^"\\])*)"\],"content":\[0,"((?:\\.|[^"\\])*)"\],"receivedAt":\[0,"([^"]+)"\]"""
        )

        private val isoRegex = Regex(
            """(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:?\d{2})?"""
        )
    }
}
