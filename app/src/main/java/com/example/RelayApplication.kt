package com.example

import android.app.Application
import android.webkit.CookieManager
import com.example.data.local.RelayDatabase
import com.example.data.preferences.RelayPreferences
import com.example.data.remote.AndroidWebViewDomFetcher
import com.example.data.remote.AnosimProvider
import com.example.data.remote.ReceiveSmssProvider
import com.example.data.remote.TempSourceEngine
import com.example.data.repository.RelayRepository
import com.example.worker.TempSyncScheduler
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class RelayApplication : Application() {

    lateinit var repository: RelayRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val httpClient = OkHttpClient.Builder()
            .cookieJar(WebViewCookieJar())
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val database = RelayDatabase.getInstance(this)
        val preferences = RelayPreferences(this)
        val anosimProvider = AnosimProvider(httpClient)
        val receiveSmssProvider = ReceiveSmssProvider(httpClient)
        val webViewDomFetcher = AndroidWebViewDomFetcher(this)
        val tempSourceEngine = TempSourceEngine(httpClient, anosimProvider, webViewDomFetcher)
        val tempSyncScheduler = TempSyncScheduler(this)
        val callAudioDownloader = com.example.data.remote.AndroidCallAudioDownloader(this, httpClient)

        repository = RelayRepository(
            dao = database.relayDao(),
            preferences = preferences,
            anosimProvider = anosimProvider,
            receiveSmssProvider = receiveSmssProvider,
            tempSourceEngine = tempSourceEngine,
            tempSyncScheduler = tempSyncScheduler,
            callAudioDownloader = callAudioDownloader,
        )
    }
}

/**
 * Bridges Android Chromium WebView's CookieManager with OkHttp so session and anti-bot
 * clearance cookies solved during Stage 2 WebView rendering are automatically shared
 * with subsequent Stage 1 HTTP requests.
 */
private class WebViewCookieJar : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        runCatching {
            val manager = CookieManager.getInstance()
            val urlStr = url.toString()
            for (cookie in cookies) {
                manager.setCookie(urlStr, cookie.toString())
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return runCatching {
            val raw = CookieManager.getInstance().getCookie(url.toString()) ?: return emptyList()
            raw.split(';')
                .mapNotNull { part ->
                    val trimmed = part.trim()
                    if (trimmed.isEmpty()) null else Cookie.parse(url, trimmed)
                }
        }.getOrDefault(emptyList())
    }
}
