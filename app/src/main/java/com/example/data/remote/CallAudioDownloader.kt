package com.example.data.remote

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Downloads `kind == "call"` MP3 voicemail recordings to app-private storage so
 * call messages are played/stored as audio rather than printed as raw URL text.
 */
interface CallAudioDownloader {
    suspend fun downloadCallAudio(url: String, dedupHash: String): String?
}

class AndroidCallAudioDownloader(
    private val appContext: Context,
    private val client: OkHttpClient,
) : CallAudioDownloader {

    override suspend fun downloadCallAudio(url: String, dedupHash: String): String? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://", ignoreCase = true) &&
            !trimmed.startsWith("http://", ignoreCase = true)
        ) {
            return null
        }
        val safeName = dedupHash.replace(Regex("[^a-fA-F0-9_-]"), "").take(48).ifBlank { "call_${System.currentTimeMillis()}" }
        return withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(appContext.filesDir, "voicemails")
                if (!dir.exists()) dir.mkdirs()
                val outFile = File(dir, "$safeName.mp3")
                if (outFile.exists() && outFile.length() > 128L) {
                    return@runCatching outFile.absolutePath
                }
                val req = BrowserHttpHeaders.applyHtmlHeaders(
                    Request.Builder().url(trimmed).get(),
                    referer = "https://sms24.me/",
                )
                    .header("Accept", "audio/mpeg,audio/*;q=0.9,*/*;q=0.8")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    val bytes = resp.body?.bytes() ?: return@runCatching null
                    if (bytes.size < 128 || bytes.size > MAX_AUDIO_BYTES) return@runCatching null
                    outFile.writeBytes(bytes)
                    outFile.absolutePath
                }
            }.getOrNull()
        }
    }

    companion object {
        private const val MAX_AUDIO_BYTES = 10 * 1024 * 1024 // 10 MB safety cap
    }
}
