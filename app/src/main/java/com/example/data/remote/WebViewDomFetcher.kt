package com.example.data.remote

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

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
    val error: String? = null,
)

/**
 * Abstraction for headless browser DOM rendering so JVM unit tests can stay Android-free
 * while the Android app uses real Chromium WebView to defeat JS-rendered, AJAX-hydrated,
 * or Base64-obfuscated public SMS portals.
 */
interface BrowserDomFetcher {
    suspend fun renderAndExtract(
        url: String,
        referer: String? = null,
        maxWaitMs: Long = 6_500L,
    ): RenderedDomResult?
}

class AndroidWebViewDomFetcher(
    private val appContext: Context,
) : BrowserDomFetcher {

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

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

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    loadsImagesAutomatically = false
                    blockNetworkImage = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    userAgentString = BrowserHttpHeaders.CHROME_MOBILE_UA
                }

                val pageFinished = CompletableDeferred<Unit>()
                var mainFrameError: String? = null

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        if (!pageFinished.isCompleted && loadedUrl != null && loadedUrl != "about:blank") {
                            pageFinished.complete(Unit)
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

                // Wait for initial document load (capped so stalled third-party ads don't block extraction)
                withTimeoutOrNull(minOf(3_800L, maxWaitMs)) {
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
                        error = mainFrameError,
                    )
                }

                var bestResult: RenderedDomResult? = null
                val deadline = started + maxWaitMs

                while (System.currentTimeMillis() < deadline) {
                    val snapshot = evaluateDomSnapshot(wv, url, started)
                    if (snapshot != null) {
                        bestResult = snapshot
                        if (snapshot.messages.isNotEmpty()) {
                            return@withContext snapshot
                        }
                    }
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0L) break
                    delay(minOf(450L, remaining))
                }

                bestResult ?: RenderedDomResult(
                    finalUrl = wv.url ?: url,
                    title = wv.title.orEmpty(),
                    outerHtml = "",
                    messages = emptyList(),
                    domSummary = "timeout",
                    visibleTextSnippet = "WebView timed out before DOM became readable",
                    latencyMs = (System.currentTimeMillis() - started).coerceAtLeast(1L),
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

    private suspend fun evaluateDomSnapshot(
        wv: WebView,
        fallbackUrl: String,
        startedAtMs: Long,
    ): RenderedDomResult? {
        val rawDeferred = CompletableDeferred<String?>()
        wv.evaluateJavascript(DOM_EXTRACTOR_JS) { value ->
            if (!rawDeferred.isCompleted) {
                rawDeferred.complete(value)
            }
        }
        val raw = withTimeoutOrNull(1_500L) { rawDeferred.await() } ?: return null
        if (raw == "null" || raw.isBlank()) return null

        val rootObj = parseJsObject(raw) ?: return null
        val title = rootObj.str("title").orEmpty()
        val finalUrl = rootObj.str("finalUrl").orEmpty().ifBlank { fallbackUrl }
        val domSummary = rootObj.str("domSummary").orEmpty()
        val outerHtml = rootObj.str("outerHtml").orEmpty()
        val visibleText = rootObj.str("visibleText").orEmpty()
        val strategy = rootObj.str("strategy").orEmpty().ifBlank { "chromium-dom" }

        val msgs = mutableListOf<RenderedDomMessage>()
        val arr = rootObj["messages"] as? JsonArray
        if (arr != null) {
            for (el in arr) {
                val o = el as? JsonObject ?: continue
                val body = o.str("body").orEmpty().trim()
                if (body.length < 2) continue
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

        return RenderedDomResult(
            finalUrl = finalUrl,
            title = title,
            outerHtml = outerHtml,
            messages = msgs,
            domSummary = domSummary,
            visibleTextSnippet = visibleText.take(420),
            latencyMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(1L),
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
        /**
         * Runs inside real Android Chromium after page JS / AJAX / DOM hydration finishes.
         * Tries 5 progressively broader live DOM extraction strategies so SMS24.me and other
         * public portals continue working even when markup, CSS classes, or JS hydration changes.
         */
        private val DOM_EXTRACTOR_JS = """
            (function() {
              try {
                function clean(s) {
                  return (s || '').replace(/\u00a0/g, ' ').replace(/[ \t]+/g, ' ').replace(/\n{2,}/g, '\n').trim();
                }
                function isNoise(body) {
                  if (!body || body.length < 2) return true;
                  var low = body.toLowerCase();
                  if (low === 'from:' || low === 'message' || low === 'messages') return true;
                  if (low.indexOf('privacy policy') === 0 || low.indexOf('terms of service') === 0) return true;
                  if (low.indexOf('cookie') === 0 && body.length < 40) return true;
                  return false;
                }
                function getTimeFromEl(el) {
                  if (!el) return '';
                  var dc = el.getAttribute && (el.getAttribute('data-created') || el.getAttribute('datetime') || el.getAttribute('data-time') || el.getAttribute('data-timestamp'));
                  if (dc) return clean(dc);
                  var nested = el.querySelector && el.querySelector('[data-created],[datetime],[data-time],[data-timestamp],time,small,.text-muted,.text-secondary');
                  if (nested) {
                    var ndc = nested.getAttribute('data-created') || nested.getAttribute('datetime') || nested.getAttribute('data-time') || nested.getAttribute('data-timestamp');
                    if (ndc) return clean(ndc);
                    var nt = clean(nested.innerText || nested.textContent);
                    if (nt && nt.length <= 60) return nt;
                  }
                  var txt = clean(el.innerText || el.textContent);
                  var m = txt.match(/(\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}(?::\d{2})?|\d+\s*(?:sec|min|hour|hr|day|week)[a-z]*\s*ago|(?:a|an|one)\s+(?:minute|hour|day|second)\s+ago|just now)/i);
                  return m ? m[1] : '';
                }
                function stripFromPrefix(s) {
                  return clean(s).replace(/^from\s*:?\s*/i, '').trim();
                }
                function dedupe(list) {
                  var seen = {};
                  var out = [];
                  for (var i = 0; i < list.length; i++) {
                    var it = list[i];
                    var b = clean(it.body);
                    if (isNoise(b)) continue;
                    var s = stripFromPrefix(it.sender || '');
                    var key = (s.toLowerCase() + '|' + b.toLowerCase());
                    if (!seen[key]) {
                      seen[key] = true;
                      out.push({ sender: s, body: b, time: clean(it.time || '') });
                    }
                  }
                  return out;
                }

                var strategy = 'none';
                var results = [];

                // Strategy 1: <dd> elements paired with preceding <dt> or [data-created] (SMS24.me primary)
                var dds = document.querySelectorAll('dd');
                if (dds.length > 0) {
                  for (var i = 0; i < dds.length; i++) {
                    var dd = dds[i];
                    var prev = dd.previousElementSibling;
                    var timeStr = getTimeFromEl(dd);
                    if (!timeStr && prev) timeStr = getTimeFromEl(prev);
                    if (!timeStr && dd.parentElement) timeStr = getTimeFromEl(dd.parentElement);

                    var labelEl = dd.querySelector('label');
                    var linkEl = (labelEl && labelEl.querySelector('a')) || dd.querySelector('a[href*="/messages/"], a[href*="/message/"], a');
                    var sender = linkEl ? clean(linkEl.innerText || linkEl.textContent) : (labelEl ? clean(labelEl.innerText || labelEl.textContent) : '');

                    var bodyEl = dd.querySelector('.placeholder, .text-break, [class*="placeholder"], [class*="message"], p');
                    var body = '';
                    if (bodyEl) {
                      body = clean(bodyEl.innerText || bodyEl.textContent);
                    } else {
                      var clone = dd.cloneNode(true);
                      var rm = clone.querySelectorAll('label, small, time, [data-created]');
                      for (var r = 0; r < rm.length; r++) rm[r].remove();
                      body = clean(clone.innerText || clone.textContent);
                    }
                    if (body) results.push({ sender: sender, body: body, time: timeStr });
                  }
                  results = dedupe(results);
                  if (results.length > 0) strategy = 'chromium-dl-dd';
                }

                // Strategy 2: [data-created] blocks anywhere in DOM
                if (results.length === 0) {
                  var createdEls = document.querySelectorAll('[data-created]');
                  for (var i = 0; i < createdEls.length; i++) {
                    var ce = createdEls[i];
                    var ts = ce.getAttribute('data-created') || clean(ce.innerText);
                    var container = ce.closest('dd, li, tr, article, .callout, .card, .shadow-sm, .message-item, .sms-item') || ce.parentElement;
                    if (container && container.tagName === 'DT' && container.nextElementSibling) {
                      container = container.nextElementSibling;
                    }
                    if (!container || container === document.body) continue;
                    var linkEl = container.querySelector('label a, a[href*="/messages/"], a[href*="/message/"], strong, b, h5, h6');
                    var labelEl = container.querySelector('label');
                    var sender = linkEl ? clean(linkEl.innerText) : (labelEl ? clean(labelEl.innerText) : '');
                    var bodyEl = container.querySelector('.placeholder, .text-break, [class*="placeholder"], p');
                    var body = '';
                    if (bodyEl) {
                      body = clean(bodyEl.innerText || bodyEl.textContent);
                    } else {
                      var clone = container.cloneNode(true);
                      var rm = clone.querySelectorAll('label, [data-created], small, time, h4, h5, h6');
                      for (var r = 0; r < rm.length; r++) rm[r].remove();
                      body = clean(clone.innerText || clone.textContent);
                    }
                    if (body) results.push({ sender: sender, body: body, time: ts });
                  }
                  results = dedupe(results);
                  if (results.length > 0) strategy = 'chromium-data-created';
                }

                // Strategy 3: SMS24 /messages/ sender links or <label>From: ...</label> anchors
                if (results.length === 0) {
                  var anchors = document.querySelectorAll('a[href*="/messages/"], a[href*="/message/"], label');
                  for (var i = 0; i < anchors.length; i++) {
                    var a = anchors[i];
                    var aText = clean(a.innerText || a.textContent);
                    if (a.tagName === 'LABEL' && !/^from\s*:/i.test(aText)) continue;
                    var sender = a.tagName === 'LABEL' ? stripFromPrefix(aText) : aText;
                    if (!sender || sender.length > 48) continue;
                    var box = a.closest('dd, li, tr, article, .callout, .card, .shadow-sm, .border, .message-item, .sms-item, div');
                    if (!box || box === document.body || box.innerText.length > 1200) continue;
                    var ts = getTimeFromEl(box) || (box.previousElementSibling ? getTimeFromEl(box.previousElementSibling) : '');
                    var bodyEl = box.querySelector('.placeholder, .text-break, [class*="placeholder"], p');
                    var body = '';
                    if (bodyEl) {
                      body = clean(bodyEl.innerText || bodyEl.textContent);
                    } else {
                      var clone = box.cloneNode(true);
                      var rm = clone.querySelectorAll('label, small, time, [data-created]');
                      for (var r = 0; r < rm.length; r++) rm[r].remove();
                      body = clean(clone.innerText || clone.textContent);
                      if (body.indexOf(sender) === 0) body = clean(body.slice(sender.length));
                    }
                    if (body && body.toLowerCase() !== sender.toLowerCase()) {
                      results.push({ sender: sender, body: body, time: ts });
                    }
                  }
                  results = dedupe(results);
                  if (results.length > 0) strategy = 'chromium-from-anchor';
                }

                // Strategy 4: HTML tables (rows with 2-4 cells)
                if (results.length === 0) {
                  var rows = document.querySelectorAll('table tr');
                  for (var i = 0; i < rows.length; i++) {
                    var cells = rows[i].querySelectorAll('td');
                    if (cells.length < 2) continue;
                    var c0 = clean(cells[0].innerText);
                    var c1 = clean(cells[1].innerText);
                    var c2 = cells.length >= 3 ? clean(cells[2].innerText) : '';
                    if (/^(from|sender|time|date|number)$/i.test(c0)) continue;
                    var body = c2.length > c1.length ? c2 : c1;
                    var timeStr = c2.length > c1.length ? (getTimeFromEl(cells[1]) || c1) : (getTimeFromEl(cells[2]) || c2);
                    results.push({ sender: c0, body: body, time: timeStr });
                  }
                  results = dedupe(results);
                  if (results.length > 0) strategy = 'chromium-table';
                }

                // Strategy 5: Visible innerText "From: <Sender>" block fallback (class-agnostic!)
                if (results.length === 0 && document.body && document.body.innerText) {
                  var lines = document.body.innerText.split(/\r?\n/).map(clean).filter(function(l) { return l.length > 0; });
                  for (var i = 0; i < lines.length; i++) {
                    var fm = lines[i].match(/^from\s*:\s*(.+)$/i);
                    if (!fm) continue;
                    var sender = clean(fm[1]);
                    var prevTime = (i > 0 && /(\d+\s*(?:sec|min|hour|hr|day)[a-z]*\s*ago|(?:a|an)\s+(?:minute|hour|day)\s+ago|just now|\d{4}-\d{2}-\d{2})/i.test(lines[i - 1])) ? lines[i - 1] : '';
                    var bodyLines = [];
                    var nextTime = '';
                    for (var j = i + 1; j < Math.min(lines.length, i + 6); j++) {
                      if (/^from\s*:/i.test(lines[j])) break;
                      if (/^(\d+\s*(?:sec|min|hour|hr|day)[a-z]*\s*ago|(?:a|an)\s+(?:minute|hour|day)\s+ago|just now|\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2})$/i.test(lines[j])) {
                        if (bodyLines.length > 0) { nextTime = lines[j]; break; }
                        else { prevTime = lines[j]; continue; }
                      }
                      bodyLines.push(lines[j]);
                    }
                    var body = clean(bodyLines.join(' '));
                    if (body.length >= 3) {
                      results.push({ sender: sender, body: body, time: prevTime || nextTime });
                    }
                  }
                  results = dedupe(results);
                  if (results.length > 0) strategy = 'chromium-visible-text';
                }

                var domSummary = 'dl=' + document.querySelectorAll('dl').length +
                  ' dd=' + document.querySelectorAll('dd').length +
                  ' created=' + document.querySelectorAll('[data-created]').length +
                  ' msgLinks=' + document.querySelectorAll('a[href*="/messages/"],a[href*="/message/"]').length +
                  ' placeholder=' + document.querySelectorAll('.placeholder,.text-break').length +
                  ' tbl=' + document.querySelectorAll('table tr').length;

                var visibleText = clean(document.body ? document.body.innerText : '').slice(0, 600);
                var outerHtml = (document.documentElement ? document.documentElement.outerHTML : '').slice(0, 120000);

                return {
                  title: document.title || '',
                  finalUrl: location.href || '',
                  strategy: strategy,
                  domSummary: domSummary,
                  visibleText: visibleText,
                  outerHtml: outerHtml,
                  messages: results.slice(0, 60)
                };
              } catch (e) {
                return {
                  title: '',
                  finalUrl: location.href || '',
                  strategy: 'js-error',
                  domSummary: 'err=' + String(e),
                  visibleText: String(e),
                  outerHtml: '',
                  messages: []
                };
              }
            })();
        """.trimIndent()
    }
}
