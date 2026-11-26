package com.example.data.remote

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class RenderedDomMessage(
    val sender: String,
    val body: String,
    val timestampRaw: String,
    val strategy: String = "chromium-dom",
)

data class RenderedDomResult(
    val finalUrl: String,
    val title: String,
    val outerHtml: String,
    val messages: List<RenderedDomMessage>,
    val domSummary: String,
    val visibleTextSnippet: String,
    val latencyMs: Long,
    val httpCode: Int? = null,
    val error: String? = null,
    val networkPayloads: List<String> = emptyList(),
)

data class Sms24ApiResponse(
    val status: Int,
    val body: String,
    val finalUrl: String,
    val title: String = "",
    val latencyMs: Long = 0L,
    val sitekeyFound: Boolean = false,
    val tokenObtained: Boolean = false,
    val pageReloaded: Boolean = false,
    val error: String? = null,
)

/**
 * Abstraction for browser DOM rendering and verified in-page API calls so JVM unit tests
 * stay Android-free while the Android app uses real Chromium WebView.
 */
interface BrowserDomFetcher {
    suspend fun renderAndExtract(
        url: String,
        referer: String? = null,
        maxWaitMs: Long = 12_000L,
    ): RenderedDomResult?

    suspend fun fetchSms24ApiInPage(
        digits: String,
        page: Int = 1,
        forceReload: Boolean = false,
        maxWaitMs: Long = 45_000L,
    ): Sms24ApiResponse? = null
}

class AndroidWebViewDomFetcher(
    private val appContext: Context,
) : BrowserDomFetcher {

    private class Sms24WebSession(
        val webView: WebView,
        var loadedDigits: String = "",
        var isLoaded: Boolean = false,
        var mainFrameHttpCode: Int? = null,
        var mainFrameError: String? = null,
        var pageFinished: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private inner class Sms24JsBridge {
        @JavascriptInterface
        fun onResult(rawJson: String) {
            val obj = parseJsObject(rawJson)
            val callId = obj?.str("callId")
            if (callId != null) {
                pendingSms24Calls.remove(callId)?.complete(rawJson)
            }
        }
    }

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val pendingSms24Calls = ConcurrentHashMap<String, CompletableDeferred<String>>()
    // Long-lived per-number WebView sessions (LRU capped at 3) so cookies & clearance persist across polls
    private val sms24Sessions = LinkedHashMap<String, Sms24WebSession>(4, 0.75f, true)

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override suspend fun fetchSms24ApiInPage(
        digits: String,
        page: Int,
        forceReload: Boolean,
        maxWaitMs: Long,
    ): Sms24ApiResponse = mutex.withLock {
        val started = System.currentTimeMillis()
        val pageUrl = "https://sms24.me/en/numbers/$digits"
        val apiPath = "/api/messages/$digits"

        withContext(Dispatchers.Main.immediate) {
            var reloaded = false
            try {
                val session = getOrCreateSms24Session(digits)
                val wv = session.webView
                wv.onResume()
                wv.resumeTimers()

                val currentUrl = wv.url.orEmpty()
                val needsLoad = forceReload ||
                    !session.isLoaded ||
                    !currentUrl.contains("sms24.me", ignoreCase = true) ||
                    !currentUrl.contains(digits)

                if (needsLoad) {
                    reloaded = true
                    session.mainFrameError = null
                    session.mainFrameHttpCode = null
                    session.isLoaded = false
                    session.pageFinished = CompletableDeferred()
                    wv.loadUrl(
                        pageUrl,
                        mapOf(
                            "Accept-Language" to "en-AU,en;q=0.9",
                            "Referer" to "https://sms24.me/en/numbers",
                        ),
                    )
                    // Wait for document load + any Cloudflare managed challenge interstitial to clear
                    val readyDeadline = System.currentTimeMillis() + minOf(18_000L, maxWaitMs / 2)
                    withTimeoutOrNull(minOf(6_000L, maxWaitMs / 3)) {
                        session.pageFinished.await()
                    }
                    while (System.currentTimeMillis() < readyDeadline && session.mainFrameError == null) {
                        val readyState = evaluateSms24PageReady(wv)
                        if (readyState == PAGE_READY_SITEKEY) {
                            session.isLoaded = true
                            break
                        }
                        if (readyState == PAGE_READY_REDIRECTED) {
                            val finalUrl = wv.url ?: pageUrl
                            return@withContext Sms24ApiResponse(
                                status = 404,
                                body = "",
                                finalUrl = finalUrl,
                                title = wv.title.orEmpty(),
                                latencyMs = (System.currentTimeMillis() - started).coerceAtLeast(1L),
                                pageReloaded = reloaded,
                                error = "SMS24.me redirected away from +$digits to $finalUrl",
                            )
                        }
                        delay(450L)
                    }
                    if (session.mainFrameError != null) {
                        session.isLoaded = false
                        return@withContext Sms24ApiResponse(
                            status = session.mainFrameHttpCode ?: 0,
                            body = "",
                            finalUrl = wv.url ?: pageUrl,
                            title = wv.title.orEmpty(),
                            latencyMs = (System.currentTimeMillis() - started).coerceAtLeast(1L),
                            pageReloaded = reloaded,
                            error = session.mainFrameError,
                        )
                    }
                    val loadedUrl = wv.url.orEmpty()
                    if (loadedUrl.contains("sms24.me", ignoreCase = true) && !loadedUrl.contains(digits)) {
                        session.isLoaded = false
                        return@withContext Sms24ApiResponse(
                            status = 404,
                            body = "",
                            finalUrl = loadedUrl,
                            title = wv.title.orEmpty(),
                            latencyMs = (System.currentTimeMillis() - started).coerceAtLeast(1L),
                            pageReloaded = reloaded,
                            error = "SMS24.me redirected away from +$digits to $loadedUrl",
                        )
                    }
                    // If still stuck on Cloudflare challenge after waiting, report 403 challenge clearly
                    val titleLower = wv.title.orEmpty().lowercase()
                    if (titleLower.contains("just a moment") || titleLower.contains("attention required") || titleLower.contains("checking your browser")) {
                        session.isLoaded = false
                        return@withContext Sms24ApiResponse(
                            status = 403,
                            body = "cf-mitigated: challenge (${wv.title.orEmpty()})",
                            finalUrl = loadedUrl.ifBlank { pageUrl },
                            title = wv.title.orEmpty(),
                            latencyMs = (System.currentTimeMillis() - started).coerceAtLeast(1L),
                            pageReloaded = reloaded,
                            error = "Cloudflare page challenge did not resolve in WebView",
                        )
                    }
                    session.isLoaded = true
                }

                // Execute verified in-page Turnstile mint + POST /api/messages/{digits}
                val callId = UUID.randomUUID().toString()
                val deferred = CompletableDeferred<String>()
                pendingSms24Calls[callId] = deferred

                val remainingMs = (started + maxWaitMs - System.currentTimeMillis()).coerceAtLeast(10_000L)
                val turnstileTimeoutMs = (remainingMs - 4_000L).coerceIn(8_000L, 30_000L)
                val script = buildSms24InPagePollJs(
                    callId = callId,
                    apiPath = apiPath,
                    page = page,
                    fallbackSitekey = Sms24HtmlAdapter.DEFAULT_TURNSTILE_SITEKEY,
                    turnstileTimeoutMs = turnstileTimeoutMs,
                )
                wv.evaluateJavascript(script, null)

                val rawResult = withTimeoutOrNull(remainingMs) { deferred.await() }
                pendingSms24Calls.remove(callId)
                runCatching { CookieManager.getInstance().flush() }

                if (rawResult.isNullOrBlank()) {
                    session.isLoaded = false
                    return@withContext Sms24ApiResponse(
                        status = 0,
                        body = "",
                        finalUrl = wv.url ?: pageUrl,
                        title = wv.title.orEmpty(),
                        latencyMs = (System.currentTimeMillis() - started).coerceAtLeast(1L),
                        pageReloaded = reloaded,
                        error = "Timed out waiting for Turnstile token + POST $apiPath",
                    )
                }

                val obj = parseJsObject(rawResult)
                if (obj == null) {
                    return@withContext Sms24ApiResponse(
                        status = 0,
                        body = rawResult.take(400),
                        finalUrl = wv.url ?: pageUrl,
                        title = wv.title.orEmpty(),
                        latencyMs = (System.currentTimeMillis() - started).coerceAtLeast(1L),
                        pageReloaded = reloaded,
                        error = "Malformed JS bridge payload",
                    )
                }

                val status = (obj["status"] as? JsonPrimitive)?.intOrNull ?: 0
                val body = obj.str("body").orEmpty()
                val finalUrl = obj.str("finalUrl").orEmpty().ifBlank { wv.url ?: pageUrl }
                val title = obj.str("title").orEmpty().ifBlank { wv.title.orEmpty() }
                val sitekeyFound = (obj["sitekeyFound"] as? JsonPrimitive)?.booleanOrNull == true
                val tokenObtained = (obj["tokenObtained"] as? JsonPrimitive)?.booleanOrNull == true
                val err = obj.str("error")?.ifBlank { null }

                if (status == 403 || status == 422 || status == 0) {
                    session.isLoaded = false
                }

                Sms24ApiResponse(
                    status = status,
                    body = body,
                    finalUrl = finalUrl,
                    title = title,
                    latencyMs = (System.currentTimeMillis() - started).coerceAtLeast(1L),
                    sitekeyFound = sitekeyFound,
                    tokenObtained = tokenObtained,
                    pageReloaded = reloaded,
                    error = err,
                )
            } catch (t: Throwable) {
                destroySms24Session(digits)
                Sms24ApiResponse(
                    status = 0,
                    body = "",
                    finalUrl = pageUrl,
                    latencyMs = (System.currentTimeMillis() - started).coerceAtLeast(1L),
                    pageReloaded = reloaded,
                    error = "WebView exception: ${t.message ?: t.javaClass.simpleName}",
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun getOrCreateSms24Session(digits: String): Sms24WebSession {
        sms24Sessions[digits]?.let { return it }

        // Evict oldest persistent WebView session if pool exceeds 3 numbers
        while (sms24Sessions.size >= MAX_SMS24_SESSIONS) {
            val eldestKey = sms24Sessions.entries.firstOrNull()?.key ?: break
            destroySms24Session(eldestKey)
        }

        val wv = WebView(appContext.applicationContext)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        wv.measure(widthSpec, heightSpec)
        wv.layout(0, 0, 1080, 1920)
        wv.onResume()
        wv.resumeTimers()

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(wv, true)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            blockNetworkImage = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            val nativeUa = userAgentString.orEmpty()
            if (nativeUa.isNotBlank()) {
                userAgentString = nativeUa.replace("; wv)", ")").replace("Version/4.0 ", "")
            }
        }

        val session = Sms24WebSession(webView = wv, loadedDigits = digits)
        wv.addJavascriptInterface(Sms24JsBridge(), "__relaySms24Bridge")
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val scheme = request?.url?.scheme?.lowercase()
                return scheme != "http" && scheme != "https"
            }

            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                val titleLower = view?.title.orEmpty().lowercase()
                val isChallenge = titleLower.contains("just a moment") ||
                    titleLower.contains("attention required") ||
                    titleLower.contains("checking your browser")
                if (!isChallenge && loadedUrl != null && loadedUrl != "about:blank") {
                    if (!session.pageFinished.isCompleted) {
                        session.pageFinished.complete(Unit)
                    }
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                if (request?.isForMainFrame == true && errorResponse != null) {
                    session.mainFrameHttpCode = errorResponse.statusCode
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    session.mainFrameError = error?.description?.toString() ?: "WebView load error"
                    if (!session.pageFinished.isCompleted) {
                        session.pageFinished.complete(Unit)
                    }
                }
            }
        }

        sms24Sessions[digits] = session
        return session
    }

    private fun destroySms24Session(digits: String) {
        val removed = sms24Sessions.remove(digits) ?: return
        runCatching {
            removed.webView.stopLoading()
            removed.webView.loadUrl("about:blank")
            removed.webView.removeJavascriptInterface("__relaySms24Bridge")
            removed.webView.removeAllViews()
            removed.webView.destroy()
        }
    }

    private suspend fun evaluateSms24PageReady(wv: WebView): Int {
        val deferred = CompletableDeferred<String?>()
        wv.evaluateJavascript(
            """
            (function(){
              try {
                var title = (document.title || '').toLowerCase();
                if (title.indexOf('just a moment') >= 0 || title.indexOf('attention required') >= 0 || title.indexOf('checking your browser') >= 0) return 'challenge';
                if (document.querySelector('meta[name="turnstile-site-key"]')) return 'sitekey';
                var href = String(location.href || '');
                if (href.indexOf('sms24.me') >= 0 && href.indexOf('/numbers/') < 0 && document.readyState === 'complete') return 'redirected';
                return 'waiting';
              } catch(e) { return 'waiting'; }
            })();
            """.trimIndent()
        ) { value ->
            if (!deferred.isCompleted) deferred.complete(value)
        }
        val raw = withTimeoutOrNull(1_200L) { deferred.await() }?.trim('"')
        return when (raw) {
            "sitekey" -> PAGE_READY_SITEKEY
            "redirected" -> PAGE_READY_REDIRECTED
            else -> PAGE_READY_WAITING
        }
    }

    private fun buildSms24InPagePollJs(
        callId: String,
        apiPath: String,
        page: Int,
        fallbackSitekey: String,
        turnstileTimeoutMs: Long,
    ): String {
        val safeCallId = callId.replace("'", "")
        val safeApiPath = apiPath.replace("'", "")
        val safeFallbackKey = fallbackSitekey.replace("'", "")
        return """
            (async function() {
              function emit(obj) {
                try {
                  obj.callId = '$safeCallId';
                  obj.finalUrl = String(location.href || '');
                  obj.title = String(document.title || '');
                  if (window.__relaySms24Bridge && typeof window.__relaySms24Bridge.onResult === 'function') {
                    window.__relaySms24Bridge.onResult(JSON.stringify(obj));
                  }
                } catch (e) {}
              }
              try {
                if (String(location.origin || '').indexOf('sms24.me') < 0) {
                  emit({ status: 0, body: '', sitekeyFound: false, tokenObtained: false, error: 'Wrong origin: ' + location.origin });
                  return;
                }
                if (!window.turnstile) {
                  await new Promise(function(res, rej) {
                    var timer = setTimeout(function() { rej(new Error('turnstile script timeout')); }, 15000);
                    var s = document.createElement('script');
                    s.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
                    s.async = true;
                    s.onload = function() { clearTimeout(timer); res(); };
                    s.onerror = function() { clearTimeout(timer); rej(new Error('turnstile load failed')); };
                    document.head.appendChild(s);
                  });
                }
                for (var w = 0; w < 25 && (!window.turnstile || typeof window.turnstile.render !== 'function'); w++) {
                  await new Promise(function(r) { setTimeout(r, 150); });
                }
                if (!window.turnstile || typeof window.turnstile.render !== 'function') {
                  emit({ status: 0, body: '', sitekeyFound: false, tokenObtained: false, error: 'window.turnstile unavailable' });
                  return;
                }
                var meta = document.querySelector('meta[name="turnstile-site-key"]');
                var key = (meta && meta.content) ? meta.content.trim() : '$safeFallbackKey';
                var sitekeyFound = !!(meta && meta.content);
                if (!key) {
                  emit({ status: 0, body: '', sitekeyFound: false, tokenObtained: false, error: 'sitekey missing' });
                  return;
                }
                var box = document.createElement('div');
                box.style.cssText = 'position:fixed;left:0;bottom:0;width:320px;height:80px;z-index:2147483647;';
                document.body.appendChild(box);
                var widgetId = null;
                var token = null;
                try {
                  token = await new Promise(function(res, rej) {
                    var t = setTimeout(function() { rej(new Error('turnstile timeout')); }, $turnstileTimeoutMs);
                    widgetId = window.turnstile.render(box, {
                      sitekey: key,
                      action: 'messages',
                      appearance: 'interaction-only',
                      callback: function(tok) { clearTimeout(t); res(tok); },
                      'error-callback': function(code) { clearTimeout(t); rej(new Error('turnstile error' + (code ? ' (' + code + ')' : ''))); },
                      'expired-callback': function() {}
                    });
                  });
                } finally {
                  try { if (widgetId !== null && window.turnstile && typeof window.turnstile.remove === 'function') window.turnstile.remove(widgetId); } catch (e) {}
                  try { box.remove(); } catch (e) {}
                }
                var resp = await fetch('$safeApiPath', {
                  method: 'POST',
                  credentials: 'same-origin',
                  headers: {
                    'Accept': 'application/json',
                    'Content-Type': 'application/json',
                    'Accept-Language': 'en',
                    'X-Turnstile-Token': token
                  },
                  body: JSON.stringify({ page: $page, locale: 'en' })
                });
                var text = await resp.text();
                emit({
                  status: resp.status,
                  body: text,
                  sitekeyFound: sitekeyFound,
                  tokenObtained: true,
                  error: ''
                });
              } catch (err) {
                emit({
                  status: 0,
                  body: '',
                  sitekeyFound: !!document.querySelector('meta[name="turnstile-site-key"]'),
                  tokenObtained: false,
                  error: String(err && err.message ? err.message : err)
                });
              }
            })();
        """.trimIndent()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun renderAndExtract(
        url: String,
        referer: String?,
        maxWaitMs: Long,
    ): RenderedDomResult = mutex.withLock {
        val started = System.currentTimeMillis()
        withContext(Dispatchers.Main.immediate) {
            var webView: WebView? = null
            try {
                val wv = WebView(appContext.applicationContext)
                webView = wv

                // Give the offscreen WebView a real mobile viewport and resume Chromium timers
                // so Blink computes layout, IntersectionObserver triggers, and JS/AJAX run normally.
                val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
                wv.measure(widthSpec, heightSpec)
                wv.layout(0, 0, 1080, 1920)
                wv.onResume()
                wv.resumeTimers()

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(wv, true)

                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadsImagesAutomatically = true
                    blockNetworkImage = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    // Keep the device's genuine Chromium major version so Sec-CH-UA matches User-Agent,
                    // stripping only the "; wv" WebView marker.
                    val nativeUa = userAgentString.orEmpty()
                    if (nativeUa.isNotBlank()) {
                        userAgentString = nativeUa.replace("; wv)", ")").replace("Version/4.0 ", "")
                    }
                }

                val pageFinished = CompletableDeferred<Unit>()
                var mainFrameError: String? = null
                var mainFrameHttpCode: Int? = null

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val scheme = request?.url?.scheme?.lowercase()
                        return scheme != "http" && scheme != "https"
                    }

                    override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, pageUrl, favicon)
                        view?.evaluateJavascript(NETWORK_HOOK_JS, null)
                    }

                    override fun onPageCommitVisible(view: WebView?, pageUrl: String?) {
                        super.onPageCommitVisible(view, pageUrl)
                        view?.evaluateJavascript(NETWORK_HOOK_JS, null)
                    }

                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        view?.evaluateJavascript(NETWORK_HOOK_JS, null)
                        val titleLower = view?.title.orEmpty().lowercase()
                        val isChallengeInterstitial = titleLower.contains("just a moment") ||
                            titleLower.contains("attention required") ||
                            titleLower.contains("checking your browser")
                        if (!isChallengeInterstitial && !pageFinished.isCompleted && loadedUrl != null && loadedUrl != "about:blank") {
                            pageFinished.complete(Unit)
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?,
                    ) {
                        if (request?.isForMainFrame == true && errorResponse != null) {
                            mainFrameHttpCode = errorResponse.statusCode
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            mainFrameError = error?.description?.toString() ?: "WebView load error"
                            if (!pageFinished.isCompleted) {
                                pageFinished.complete(Unit)
                            }
                        }
                    }
                }

                val headers = buildMap {
                    put("Accept-Language", "en-US,en;q=0.9")
                    if (!referer.isNullOrBlank()) {
                        put("Referer", referer)
                    }
                }
                wv.loadUrl(url, headers)

                // Wait for initial document load (capped so stalled ad scripts don't block extraction)
                withTimeoutOrNull(minOf(5_000L, maxWaitMs)) {
                    pageFinished.await()
                }

                if (mainFrameError != null) {
                    return@withContext RenderedDomResult(
                        finalUrl = wv.url ?: url,
                        title = wv.title.orEmpty(),
                        outerHtml = "",
                        messages = emptyList(),
                        domSummary = "error",
                        visibleTextSnippet = mainFrameError!!,
                        latencyMs = (System.currentTimeMillis() - started).coerceAtLeast(1L),
                        httpCode = mainFrameHttpCode,
                        error = mainFrameError,
                    )
                }

                var bestResult: RenderedDomResult? = null
                var bestScore = -1
                var stableHits = 0
                var scrolledOnce = false
                val deadline = started + maxWaitMs

                while (System.currentTimeMillis() < deadline) {
                    val elapsed = System.currentTimeMillis() - started
                    // Wake up lazy/IntersectionObserver-gated feeds if nothing loaded after 1.8s
                    if (!scrolledOnce && elapsed >= 1_800L && bestScore <= 0) {
                        scrolledOnce = true
                        wv.evaluateJavascript(
                            "(function(){try{window.scrollTo(0,480);window.dispatchEvent(new Event('scroll'));}catch(e){}})();",
                            null,
                        )
                    }

                    val snapshot = evaluateDomSnapshot(wv, url, started, mainFrameHttpCode)
                    if (snapshot != null) {
                        val score = scoreRenderedSnapshot(snapshot)
                        if (score > bestScore || bestResult == null) {
                            bestScore = score
                            bestResult = snapshot
                            stableHits = 0
                        } else if (score > 0) {
                            stableHits += 1
                        }

                        // Only exit early once genuine SMS messages have appeared AND stabilized
                        // across an extra poll cycle (or >= 3 confident messages arrived).
                        val hasConfidentMessages = snapshot.messages.size >= 3 ||
                            (snapshot.messages.isNotEmpty() && stableHits >= 2)
                        if (hasConfidentMessages && score >= 18) {
                            return@withContext bestResult
                        }
                    }

                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0L) break
                    delay(minOf(420L, remaining))
                }

                bestResult ?: RenderedDomResult(
                    finalUrl = wv.url ?: url,
                    title = wv.title.orEmpty(),
                    outerHtml = "",
                    messages = emptyList(),
                    domSummary = "timeout",
                    visibleTextSnippet = "WebView timed out before DOM became readable",
                    latencyMs = (System.currentTimeMillis() - started).coerceAtLeast(1L),
                    httpCode = mainFrameHttpCode,
                    error = "Timed out waiting for rendered DOM",
                )
            } catch (t: Throwable) {
                RenderedDomResult(
                    finalUrl = url,
                    title = "",
                    outerHtml = "",
                    messages = emptyList(),
                    domSummary = "exception",
                    visibleTextSnippet = t.message ?: t.javaClass.simpleName,
                    latencyMs = (System.currentTimeMillis() - started).coerceAtLeast(1L),
                    error = "WebView error: ${t.message ?: t.javaClass.simpleName}",
                )
            } finally {
                runCatching { CookieManager.getInstance().flush() }
                runCatching {
                    webView?.stopLoading()
                    webView?.loadUrl("about:blank")
                    webView?.clearHistory()
                    webView?.removeAllViews()
                    webView?.destroy()
                }
            }
        }
    }

    private fun scoreRenderedSnapshot(snapshot: RenderedDomResult): Int {
        var pts = 0
        for (m in snapshot.messages) {
            if (Sms24NoiseFilter.isNonSmsPlaceholder(m.body)) continue
            pts += 10
            if (m.sender.isNotBlank() && !m.sender.equals("SMS24", ignoreCase = true)) pts += 8
            if (m.timestampRaw.isNotBlank()) pts += 6
        }
        if (pts == 0 && snapshot.networkPayloads.isNotEmpty()) {
            pts = 1
        }
        return pts
    }

    private suspend fun evaluateDomSnapshot(
        wv: WebView,
        fallbackUrl: String,
        startedAtMs: Long,
        mainFrameHttpCode: Int?,
    ): RenderedDomResult? {
        val rawDeferred = CompletableDeferred<String?>()
        wv.evaluateJavascript(DOM_EXTRACTOR_JS) { value ->
            if (!rawDeferred.isCompleted) {
                rawDeferred.complete(value)
            }
        }
        val raw = withTimeoutOrNull(2_500L) { rawDeferred.await() } ?: return null
        if (raw == "null" || raw.isBlank()) return null

        val rootObj = parseJsObject(raw) ?: return null
        val title = rootObj.str("title").orEmpty()
        val finalUrl = rootObj.str("finalUrl").orEmpty().ifBlank { fallbackUrl }
        val domSummary = rootObj.str("domSummary").orEmpty()
        val outerHtml = rootObj.str("mainHtml").orEmpty()
        val visibleText = rootObj.str("visibleText").orEmpty()
        val strategy = rootObj.str("strategy").orEmpty().ifBlank { "chromium-dom" }

        val msgs = mutableListOf<RenderedDomMessage>()
        val arr = rootObj["messages"] as? JsonArray
        if (arr != null) {
            for (el in arr) {
                val o = el as? JsonObject ?: continue
                val body = o.str("body").orEmpty().trim()
                if (body.length < 2 || Sms24NoiseFilter.isNonSmsPlaceholder(body)) continue
                val sender = o.str("sender").orEmpty().trim()
                val ts = o.str("time").orEmpty().trim()
                msgs.add(
                    RenderedDomMessage(
                        sender = sender,
                        body = body,
                        timestampRaw = ts,
                        strategy = strategy,
                    )
                )
            }
        }

        val payloads = mutableListOf<String>()
        val netArr = rootObj["networkPayloads"] as? JsonArray
        if (netArr != null) {
            for (el in netArr) {
                val p = (el as? JsonPrimitive)?.contentOrNull
                if (!p.isNullOrBlank()) payloads.add(p)
            }
        }

        return RenderedDomResult(
            finalUrl = finalUrl,
            title = title,
            outerHtml = outerHtml,
            messages = msgs,
            domSummary = domSummary,
            visibleTextSnippet = visibleText.take(460),
            latencyMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(1L),
            httpCode = mainFrameHttpCode ?: 200,
            networkPayloads = payloads,
        )
    }

    private fun parseJsObject(raw: String): JsonObject? {
        val el = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
        return when (el) {
            is JsonObject -> el
            is JsonPrimitive -> {
                val inner = el.contentOrNull ?: return null
                runCatching { json.parseToJsonElement(inner) as? JsonObject }.getOrNull()
            }
            else -> null
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        private const val MAX_SMS24_SESSIONS = 3
        private const val PAGE_READY_WAITING = 0
        private const val PAGE_READY_SITEKEY = 1
        private const val PAGE_READY_REDIRECTED = 2

        /**
         * Injected on page start & commit to intercept same-origin fetch(), XMLHttpRequest,
         * EventSource (SSE), and WebSocket messages carrying dynamically loaded SMS feeds.
         */
        private val NETWORK_HOOK_JS = """
            (function() {
              try {
                if (window.__relayNetHooked) return;
                window.__relayNetHooked = true;
                window.__relayNetPayloads = window.__relayNetPayloads || [];
                window.__relayNetUrls = window.__relayNetUrls || [];
                window.__relayReplayedUrls = window.__relayReplayedUrls || {};
                function shouldSkipUrl(u) {
                  if (!u) return true;
                  var s = String(u).toLowerCase();
                  if (/\.(png|jpg|jpeg|gif|webp|svg|ico|woff2?|ttf|css)(\?|$)/.test(s)) return true;
                  if (s.indexOf('google') >= 0 || s.indexOf('doubleclick') >= 0 || s.indexOf('ads') >= 0 || s.indexOf('analytics') >= 0 || s.indexOf('cloudflareinsights') >= 0) return true;
                  return false;
                }
                function recordPayload(url, text) {
                  if (!text || typeof text !== 'string') return;
                  var trimmed = text.trim();
                  if (trimmed.length < 12) return;
                  if (window.__relayNetPayloads.length >= 12) window.__relayNetPayloads.shift();
                  window.__relayNetPayloads.push({ url: String(url || '').slice(0, 160), body: trimmed.slice(0, 24000) });
                  if (url && window.__relayNetUrls.indexOf(String(url)) < 0 && window.__relayNetUrls.length < 12) {
                    window.__relayNetUrls.push(String(url).slice(0, 140));
                  }
                }
                if (typeof window.fetch === 'function') {
                  var origFetch = window.fetch;
                  window.fetch = function(input, init) {
                    var reqUrl = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                    return origFetch.apply(this, arguments).then(function(resp) {
                      try {
                        if (!shouldSkipUrl(reqUrl) && resp && resp.clone) {
                          resp.clone().text().then(function(txt) { recordPayload(reqUrl, txt); }).catch(function(){});
                        }
                      } catch (e) {}
                      return resp;
                    });
                  };
                }
                if (typeof XMLHttpRequest !== 'undefined') {
                  var origOpen = XMLHttpRequest.prototype.open;
                  var origSend = XMLHttpRequest.prototype.send;
                  XMLHttpRequest.prototype.open = function(method, url) {
                    this.__relayUrl = url;
                    return origOpen.apply(this, arguments);
                  };
                  XMLHttpRequest.prototype.send = function() {
                    try {
                      this.addEventListener('load', function() {
                        try {
                          if (!shouldSkipUrl(this.__relayUrl) && typeof this.responseText === 'string') {
                            recordPayload(this.__relayUrl, this.responseText);
                          }
                        } catch (e) {}
                      });
                    } catch (e) {}
                    return origSend.apply(this, arguments);
                  };
                }
                if (typeof window.EventSource === 'function') {
                  var OrigES = window.EventSource;
                  window.EventSource = function(url, cfg) {
                    var es = new OrigES(url, cfg);
                    try {
                      es.addEventListener('message', function(ev) {
                        if (ev && typeof ev.data === 'string') recordPayload(url, ev.data);
                      });
                    } catch (e) {}
                    return es;
                  };
                }
                if (typeof window.WebSocket === 'function') {
                  var OrigWS = window.WebSocket;
                  window.WebSocket = function(url, protocols) {
                    var ws = protocols !== undefined ? new OrigWS(url, protocols) : new OrigWS(url);
                    try {
                      ws.addEventListener('message', function(ev) {
                        if (ev && typeof ev.data === 'string') recordPayload(url, ev.data);
                      });
                    } catch (e) {}
                    return ws;
                  };
                  window.WebSocket.prototype = OrigWS.prototype;
                }
              } catch (e) {}
            })();
        """.trimIndent()

        /**
         * Runs inside real Android Chromium after page JS / AJAX / SSE / DOM hydration.
         * Key invariants:
         * 1. Rejects non-SMS UI status/placeholder text ("SMS inbox is ready", "Loading...", etc.).
         * 2. Walks Shadow DOM, same-origin iframes, AND intercepted fetch/XHR/SSE/WS payloads.
         * 3. Evaluates ALL strategies and picks the HIGHEST-SCORING real SMS result instead of
         *    blindly returning on the first strategy that matched 1 status box.
         */
        private val DOM_EXTRACTOR_JS = """
            (function() {
              try {
                function clean(s) {
                  return (s || '').replace(/\u00a0/g, ' ').replace(/[ \t]+/g, ' ').replace(/\n{2,}/g, '\n').trim();
                }
                function textOf(el) {
                  if (!el) return '';
                  var t = clean(el.innerText);
                  if (t) return t;
                  return clean(el.textContent);
                }
                function isNoise(body) {
                  if (!body || body.length < 2) return true;
                  var low = clean(body).toLowerCase().replace(/[.!…]+$/, '');
                  if (low.length < 2) return true;
                  var blocked = [
                    'sms inbox is ready', 'inbox is ready', 'your sms inbox is ready', 'this sms inbox is ready',
                    'waiting for sms', 'waiting for messages', 'waiting for new messages',
                    'loading', 'loading messages', 'loading sms', 'please wait',
                    'refresh', 'refresh messages', 'refresh inbox', 'update messages',
                    'no messages', 'no messages yet', 'no sms', 'no sms yet', 'no sms received',
                    'copy number', 'copied', 'from:', 'from', 'message', 'messages'
                  ];
                  for (var b = 0; b < blocked.length; b++) {
                    if (low === blocked[b]) return true;
                  }
                  if (/^(\d+\s*(?:sec|min|hour|hr|day|wk|week)[a-z]*\s*ago|(?:a|an|one)\s+(?:few\s+seconds?|sec(?:ond)?|min(?:ute)?|hour|hr|day|week)\s+ago|just now|\d{4}[-\/]\d{1,2}[-\/]\d{1,2}[ t]\d{1,2}:\d{2}(?::\d{2})?)$/i.test(low)) return true;
                  if (low.indexOf('sms inbox is ready') === 0) return true;
                  if (low.indexOf('privacy policy') === 0 || low.indexOf('terms of service') === 0) return true;
                  if (low.indexOf('all messages are public') === 0 || low.indexOf('receive sms online') === 0) return true;
                  if (low.indexOf('cookie') === 0 && body.length < 40) return true;
                  return false;
                }
                function stripFromPrefix(s) {
                  return clean(s).replace(/^from\s*:?\s*/i, '').trim();
                }
                function queryAllDeep(root, selector) {
                  var out = [];
                  if (!root || !root.querySelectorAll) return out;
                  try {
                    var direct = root.querySelectorAll(selector);
                    for (var i = 0; i < direct.length; i++) out.push(direct[i]);
                    var all = root.querySelectorAll('*');
                    for (var j = 0; j < all.length; j++) {
                      if (all[j].shadowRoot) {
                        var sub = queryAllDeep(all[j].shadowRoot, selector);
                        for (var k = 0; k < sub.length; k++) out.push(sub[k]);
                      }
                    }
                  } catch (e) {}
                  return out;
                }
                var TIME_RE = /(\d{4}[-\/]\d{1,2}[-\/]\d{1,2}[ T]\d{1,2}:\d{2}(?::\d{2})?|\d+\s*(?:sec|min|hour|hr|day|wk|week)[a-z]*\s*ago|(?:a|an|one)\s+(?:minute|hour|day|second)\s+ago|just now)/i;
                function getTimeFromEl(el) {
                  if (!el) return '';
                  var dc = el.getAttribute && (el.getAttribute('data-created') || el.getAttribute('datetime') || el.getAttribute('data-time') || el.getAttribute('data-timestamp'));
                  if (dc) return clean(dc);
                  var nested = el.querySelector && el.querySelector('[data-created],[datetime],[data-time],[data-timestamp],time,small,.text-muted,.text-secondary');
                  if (nested) {
                    var ndc = nested.getAttribute('data-created') || nested.getAttribute('datetime') || nested.getAttribute('data-time') || nested.getAttribute('data-timestamp');
                    if (ndc) return clean(ndc);
                    var nt = textOf(nested);
                    if (nt && nt.length <= 60) return nt;
                  }
                  var txt = textOf(el);
                  var m = txt.match(TIME_RE);
                  return m ? m[1] : '';
                }
                function extractSender(container) {
                  if (!container) return '';
                  var labelEl = container.querySelector && container.querySelector('label');
                  var linkEl = (labelEl && labelEl.querySelector('a')) ||
                    (container.querySelector && container.querySelector('a[href*="/messages/"], a[href*="/message/"], a.placeholder'));
                  if (linkEl) {
                    var lt = stripFromPrefix(textOf(linkEl));
                    if (lt) return lt;
                  }
                  if (labelEl) {
                    var lbt = stripFromPrefix(textOf(labelEl));
                    if (lbt) return lbt;
                  }
                  var heading = container.querySelector && container.querySelector('strong, b, h4, h5, h6, [class*="sender"], [class*="from"]');
                  if (heading) {
                    var ht = stripFromPrefix(textOf(heading));
                    if (ht && ht.length <= 48 && !TIME_RE.test(ht)) return ht;
                  }
                  return '';
                }
                function extractBody(container, sender) {
                  if (!container) return '';
                  var clone = container.cloneNode(true);
                  var rm = clone.querySelectorAll('label, a[href*="/messages/"], a[href*="/message/"], a.placeholder, [data-created], time, small, script, style, button, ins, .adsbygoogle');
                  for (var r = 0; r < rm.length; r++) rm[r].remove();

                  var preferred = clone.querySelectorAll('span.text-break, div.text-break, span.placeholder, div.placeholder, span[class*="message"], div[class*="message"], p, span');
                  for (var p = 0; p < preferred.length; p++) {
                    var cand = clean(preferred[p].textContent);
                    if (cand.length >= 2 && !isNoise(cand) && cand.toLowerCase() !== (sender || '').toLowerCase() && !TIME_RE.test(cand)) {
                      return cand;
                    }
                  }
                  var fallback = clean(clone.textContent);
                  if (sender && fallback.toLowerCase().indexOf(sender.toLowerCase()) === 0) {
                    fallback = clean(fallback.slice(sender.length));
                  }
                  return isNoise(fallback) ? '' : fallback;
                }
                function dedupe(list) {
                  var seen = {};
                  var out = [];
                  for (var i = 0; i < list.length; i++) {
                    var it = list[i];
                    var b = clean(it.body);
                    if (isNoise(b)) continue;
                    var s = stripFromPrefix(it.sender || '');
                    if (b.toLowerCase() === s.toLowerCase()) continue;
                    var key = (s.toLowerCase() + '|' + b.toLowerCase());
                    if (!seen[key]) {
                      seen[key] = true;
                      out.push({ sender: s, body: b, time: clean(it.time || '') });
                    }
                  }
                  return out;
                }
                function scoreList(list) {
                  var pts = 0;
                  for (var i = 0; i < list.length; i++) {
                    var it = list[i];
                    if (isNoise(it.body)) continue;
                    pts += 10;
                    if (it.sender && it.sender.toLowerCase() !== 'sms24') pts += 8;
                    if (it.time) pts += 6;
                    if (it.body.length >= 12) pts += 3;
                  }
                  return pts;
                }
                function pickBetter(best, candStrategy, candList) {
                  var d = dedupe(candList);
                  var sc = scoreList(d);
                  if (sc > best.score) {
                    return { strategy: candStrategy, results: d, score: sc };
                  }
                  return best;
                }

                // Recursive JSON message finder for intercepted fetch/XHR/SSE/WS payloads
                function extractFromJsonNode(node, out) {
                  if (!node || out.length >= 80) return;
                  if (Array.isArray(node)) {
                    for (var i = 0; i < node.length; i++) extractFromJsonNode(node[i], out);
                    return;
                  }
                  if (typeof node === 'object') {
                    var body = node.text || node.body || node.message || node.content || node.sms || node.msg || '';
                    if (typeof body === 'string' && body.trim().length >= 2) {
                      var sender = node.from || node.sender || node.originator || node.author || node.phone || node.service || '';
                      var time = node.created_at || node.received_at || node.received || node.created || node.date || node.time || node.timestamp || '';
                      if (typeof sender === 'number') sender = String(sender);
                      if (typeof time === 'number') time = String(time);
                      if (typeof sender === 'string' && typeof time === 'string') {
                        out.push({ sender: sender, body: body, time: time });
                        return;
                      }
                    }
                    var keys = Object.keys(node);
                    for (var k = 0; k < keys.length; k++) {
                      var val = node[keys[k]];
                      if (val && typeof val === 'object') extractFromJsonNode(val, out);
                    }
                  }
                }

                function collectFromDoc(doc, prefix) {
                  var pfx = prefix || '';
                  var best = { strategy: 'none', results: [], score: 0 };

                  // Strategy 1: <dd> elements paired with preceding <dt> or [data-created]
                  var dds = queryAllDeep(doc, 'dd');
                  if (dds.length > 0) {
                    var r1 = [];
                    for (var i = 0; i < dds.length; i++) {
                      var dd = dds[i];
                      var prev = dd.previousElementSibling;
                      var timeStr = '';
                      if (prev) timeStr = getTimeFromEl(prev);
                      if (!timeStr) timeStr = getTimeFromEl(dd);
                      if (!timeStr && dd.parentElement) timeStr = getTimeFromEl(dd.parentElement);
                      var sender = extractSender(dd);
                      var body = extractBody(dd, sender);
                      if (body) r1.push({ sender: sender, body: body, time: timeStr });
                    }
                    best = pickBetter(best, pfx + 'chromium-dl-dd', r1);
                  }

                  // Strategy 2: [data-created] blocks anywhere in DOM / Shadow DOM
                  var createdEls = queryAllDeep(doc, '[data-created]');
                  if (createdEls.length > 0) {
                    var r2 = [];
                    for (var i = 0; i < createdEls.length; i++) {
                      var ce = createdEls[i];
                      var ts = ce.getAttribute('data-created') || textOf(ce);
                      var container = ce.closest('dd, li, tr, article, .callout, .card, .shadow-sm, .message-item, .sms-item') || ce.parentElement;
                      if (container && container.tagName === 'DT' && container.nextElementSibling) {
                        container = container.nextElementSibling;
                      }
                      if (!container || container === doc.body) continue;
                      var sender = extractSender(container);
                      var body = extractBody(container, sender);
                      if (body) r2.push({ sender: sender, body: body, time: ts });
                    }
                    best = pickBetter(best, pfx + 'chromium-data-created', r2);
                  }

                  // Strategy 3: SMS24 /messages/ sender links or <label>From: ...</label> anchors
                  var anchors = queryAllDeep(doc, 'a[href*="/messages/"], a[href*="/message/"], label');
                  if (anchors.length > 0) {
                    var r3 = [];
                    for (var i = 0; i < anchors.length; i++) {
                      var a = anchors[i];
                      var aText = textOf(a);
                      if (a.tagName === 'LABEL' && !/^from\s*:/i.test(aText)) continue;
                      var sender = stripFromPrefix(aText);
                      if (!sender || sender.length > 48) continue;
                      var box = a.closest('dd, li, tr, article, .callout, .card, .shadow-sm, .border, .message-item, .sms-item, div');
                      if (!box || box === doc.body || textOf(box).length > 1600) continue;
                      var ts = (box.previousElementSibling ? getTimeFromEl(box.previousElementSibling) : '') || getTimeFromEl(box);
                      var body = extractBody(box, sender);
                      if (body) r3.push({ sender: sender, body: body, time: ts });
                    }
                    best = pickBetter(best, pfx + 'chromium-from-anchor', r3);
                  }

                  // Strategy 4: HTML tables (rows with 2-4 cells)
                  var rows = queryAllDeep(doc, 'table tr');
                  if (rows.length > 0) {
                    var r4 = [];
                    for (var i = 0; i < rows.length; i++) {
                      var cells = rows[i].querySelectorAll('td');
                      if (cells.length < 2) continue;
                      var c0 = textOf(cells[0]);
                      var c1 = textOf(cells[1]);
                      var c2 = cells.length >= 3 ? textOf(cells[2]) : '';
                      if (/^(from|sender|time|date|number)$/i.test(c0)) continue;
                      var body = c2.length > c1.length ? c2 : c1;
                      var timeStr = c2.length > c1.length ? (getTimeFromEl(cells[1]) || c1) : (getTimeFromEl(cells[2]) || c2);
                      r4.push({ sender: c0, body: body, time: timeStr });
                    }
                    best = pickBetter(best, pfx + 'chromium-table', r4);
                  }

                  // Strategy 5: Generic timestamp-anchored cards (handles modern Tailwind/Bootstrap cards without <dd> or /messages/)
                  var rootMain = doc.querySelector('main') || doc.body;
                  if (rootMain) {
                    var allLeaf = queryAllDeep(rootMain, 'time, small, span, div, p, dt');
                    var r5 = [];
                    var seenCard = [];
                    for (var i = 0; i < allLeaf.length && r5.length < 60; i++) {
                      var el = allLeaf[i];
                      if (el.children && el.children.length > 2) continue;
                      var ownTxt = textOf(el);
                      if (!ownTxt || ownTxt.length > 48 || !TIME_RE.test(ownTxt)) continue;
                      var card = el.parentElement;
                      for (var up = 0; up < 3 && card && card !== rootMain && card !== doc.body; up++) {
                        var ct = textOf(card);
                        if (ct.length >= 18 && ct.length <= 1200) break;
                        card = card.parentElement;
                      }
                      if (!card || card === rootMain || card === doc.body) continue;
                      if (seenCard.indexOf(card) >= 0) continue;
                      var cardTxt = textOf(card);
                      if (cardTxt.length < 18 || cardTxt.length > 1200) continue;
                      seenCard.push(card);
                      var sender = extractSender(card);
                      var body = extractBody(card, sender);
                      if (body) r5.push({ sender: sender, body: body, time: ownTxt });
                    }
                    best = pickBetter(best, pfx + 'chromium-timestamp-cards', r5);
                  }

                  // Strategy 6: Text-line "From: <Sender>" fallback using innerText || textContent
                  var fullText = textOf(doc.body);
                  if (fullText) {
                    var lines = fullText.split(/\r?\n/).map(clean).filter(function(l) { return l.length > 0; });
                    var r6 = [];
                    for (var i = 0; i < lines.length; i++) {
                      var fm = lines[i].match(/^from\s*:\s*(.+)$/i);
                      if (!fm) continue;
                      var sender = clean(fm[1]);
                      var prevTime = (i > 0 && TIME_RE.test(lines[i - 1])) ? lines[i - 1] : '';
                      var bodyLines = [];
                      var nextTime = '';
                      for (var j = i + 1; j < Math.min(lines.length, i + 6); j++) {
                        if (/^from\s*:/i.test(lines[j])) break;
                        if (TIME_RE.test(lines[j]) && lines[j].length <= 40) {
                          if (bodyLines.length > 0) { nextTime = lines[j]; break; }
                          else { prevTime = lines[j]; continue; }
                        }
                        bodyLines.push(lines[j]);
                      }
                      var body = clean(bodyLines.join(' '));
                      if (body.length >= 3) {
                        r6.push({ sender: sender, body: body, time: prevTime || nextTime });
                      }
                    }
                    best = pickBetter(best, pfx + 'chromium-visible-text', r6);
                  }

                  return best;
                }

                var best = collectFromDoc(document, '');

                // Also inspect same-origin iframes
                var frames = document.querySelectorAll('iframe');
                for (var f = 0; f < frames.length; f++) {
                  try {
                    var fdoc = frames[f].contentDocument;
                    if (fdoc && fdoc.body) {
                      var sub = collectFromDoc(fdoc, 'iframe+');
                      if (sub.score > best.score) best = sub;
                    }
                  } catch (ignore) {}
                }

                // Also inspect intercepted fetch/XHR/SSE/WS network payloads
                var rawNetPayloads = [];
                var netItems = window.__relayNetPayloads || [];
                for (var n = 0; n < netItems.length; n++) {
                  var pBody = netItems[n] && netItems[n].body ? String(netItems[n].body) : '';
                  if (!pBody) continue;
                  if (rawNetPayloads.length < 6) rawNetPayloads.push(pBody.slice(0, 6000));
                  var firstCh = pBody.trim().charAt(0);
                  if (firstCh === '{' || firstCh === '[') {
                    try {
                      var parsedJson = JSON.parse(pBody);
                      var jsonMsgs = [];
                      extractFromJsonNode(parsedJson, jsonMsgs);
                      best = pickBetter(best, 'chromium-net-json', jsonMsgs);
                    } catch (ignore) {}
                  } else if (pBody.indexOf('<') >= 0) {
                    try {
                      var fragDoc = new DOMParser().parseFromString(pBody, 'text/html');
                      var fragRes = collectFromDoc(fragDoc, 'net-html+');
                      if (fragRes.score > best.score) best = fragRes;
                    } catch (ignore) {}
                  }
                }

                // If still 0 results, replay any early same-origin XHR/fetch URLs recorded in Performance API
                if (best.results.length === 0 && typeof performance !== 'undefined' && performance.getEntriesByType) {
                  try {
                    window.__relayReplayedUrls = window.__relayReplayedUrls || {};
                    var perfEntries = performance.getEntriesByType('resource') || [];
                    for (var pe = 0; pe < perfEntries.length; pe++) {
                      var entry = perfEntries[pe];
                      var initType = (entry.initiatorType || '').toLowerCase();
                      var eUrl = entry.name || '';
                      if ((initType === 'fetch' || initType === 'xmlhttprequest') &&
                          eUrl.indexOf(location.origin) === 0 &&
                          !window.__relayReplayedUrls[eUrl]) {
                        window.__relayReplayedUrls[eUrl] = true;
                        fetch(eUrl, { credentials: 'include' }).catch(function(){});
                      }
                    }
                  } catch (ignore) {}
                }

                var sampleNode = document.querySelector('dd, [data-created], .callout, .message-item, .sms-item');
                var sampleHtml = sampleNode ? clean(sampleNode.outerHTML).slice(0, 220) : '';
                var netUrls = (window.__relayNetUrls || []).slice(0, 3).join(',');
                var domSummary = 'dl=' + document.querySelectorAll('dl').length +
                  ' dd=' + document.querySelectorAll('dd').length +
                  ' created=' + document.querySelectorAll('[data-created]').length +
                  ' msgLinks=' + document.querySelectorAll('a[href*="/messages/"],a[href*="/message/"]').length +
                  ' ph=' + document.querySelectorAll('.placeholder,.text-break').length +
                  ' tbl=' + document.querySelectorAll('table tr').length +
                  ' net=' + netItems.length +
                  (netUrls ? ' netUrls=' + netUrls : '') +
                  (best.results.length === 0 && sampleHtml ? ' sample=' + sampleHtml : '');

                var visibleText = textOf(document.querySelector('main') || document.body).slice(0, 600);
                var rootEl = document.querySelector('main') || document.body;
                var mainHtml = '';
                if (rootEl) {
                  var bodyClone = rootEl.cloneNode(true);
                  var junk = bodyClone.querySelectorAll('script, style, svg, noscript, iframe, nav, footer');
                  for (var k = 0; k < junk.length; k++) junk[k].remove();
                  mainHtml = (bodyClone.innerHTML || '').slice(0, 28000);
                }

                return {
                  title: document.title || '',
                  finalUrl: location.href || '',
                  strategy: best.strategy,
                  domSummary: domSummary,
                  visibleText: visibleText,
                  mainHtml: mainHtml,
                  networkPayloads: rawNetPayloads,
                  messages: best.results.slice(0, 60)
                };
              } catch (e) {
                return {
                  title: '',
                  finalUrl: location.href || '',
                  strategy: 'js-error',
                  domSummary: 'err=' + String(e),
                  visibleText: String(e),
                  mainHtml: '',
                  networkPayloads: [],
                  messages: []
                };
              }
            })();
        """.trimIndent()
    }
}
