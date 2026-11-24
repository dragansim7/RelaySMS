package com.example.data.remote

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.CookieManager
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
import kotlinx.serialization.json.contentOrNull

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
)

/**
 * Abstraction for headless browser DOM rendering so JVM unit tests stay Android-free
 * while the Android app uses real Chromium WebView to defeat JS-rendered, AJAX-hydrated,
 * or Base64-obfuscated public SMS portals.
 */
interface BrowserDomFetcher {
    suspend fun renderAndExtract(
        url: String,
        referer: String? = null,
        maxWaitMs: Long = 7_500L,
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

                // Give the offscreen WebView a real mobile viewport so Blink computes layout,
                // IntersectionObserver triggers, and CSS/innerText behave identically to Chrome.
                val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
                wv.measure(widthSpec, heightSpec)
                wv.layout(0, 0, 1080, 1920)

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadsImagesAutomatically = false
                    blockNetworkImage = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
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

                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        if (!pageFinished.isCompleted && loadedUrl != null && loadedUrl != "about:blank") {
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
                withTimeoutOrNull(minOf(4_000L, maxWaitMs)) {
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
                val deadline = started + maxWaitMs

                while (System.currentTimeMillis() < deadline) {
                    val snapshot = evaluateDomSnapshot(wv, url, started, mainFrameHttpCode)
                    if (snapshot != null) {
                        bestResult = snapshot
                        if (snapshot.messages.isNotEmpty()) {
                            return@withContext snapshot
                        }
                    }
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0L) break
                    delay(minOf(380L, remaining))
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
            visibleTextSnippet = visibleText.take(460),
            latencyMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(1L),
            httpCode = mainFrameHttpCode ?: 200,
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
         * Key invariants:
         * 1. Always uses textContent fallback (never relies solely on layout-dependent innerText).
         * 2. NEVER lets `.placeholder` match `<a class="placeholder ms-1">` or `<label>` as the message body.
         * 3. Keeps IPC payload compact (<= 28 KB stripped mainHtml) so evaluateJavascript never truncates or times out.
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
                  var low = body.toLowerCase();
                  if (low === 'from:' || low === 'from' || low === 'message' || low === 'messages') return true;
                  if (low.indexOf('privacy policy') === 0 || low.indexOf('terms of service') === 0) return true;
                  if (low.indexOf('cookie') === 0 && body.length < 40) return true;
                  return false;
                }
                function stripFromPrefix(s) {
                  return clean(s).replace(/^from\s*:?\s*/i, '').trim();
                }
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
                  var m = txt.match(/(\d{4}[-\/]\d{1,2}[-\/]\d{1,2}[ T]\d{1,2}:\d{2}(?::\d{2})?|\d+\s*(?:sec|min|hour|hr|day|week)[a-z]*\s*ago|(?:a|an|one)\s+(?:minute|hour|day|second)\s+ago|just now)/i);
                  return m ? m[1] : '';
                }
                function extractSender(container) {
                  if (!container) return '';
                  var labelEl = container.querySelector('label');
                  var linkEl = (labelEl && labelEl.querySelector('a')) ||
                    container.querySelector('a[href*="/messages/"], a[href*="/message/"], a.placeholder');
                  if (linkEl) {
                    var lt = stripFromPrefix(textOf(linkEl));
                    if (lt) return lt;
                  }
                  if (labelEl) {
                    var lbt = stripFromPrefix(textOf(labelEl));
                    if (lbt) return lbt;
                  }
                  var heading = container.querySelector('strong, b, h5, h6');
                  if (heading) {
                    var ht = stripFromPrefix(textOf(heading));
                    if (ht && ht.length <= 48) return ht;
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
                    if (cand.length >= 2 && !isNoise(cand) && cand.toLowerCase() !== (sender || '').toLowerCase()) {
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

                function collectFromDoc(doc) {
                  var strategy = 'none';
                  var results = [];

                  // Strategy 1: <dd> elements paired with preceding <dt> or [data-created] (SMS24.me primary)
                  var dds = doc.querySelectorAll('dd');
                  if (dds.length > 0) {
                    for (var i = 0; i < dds.length; i++) {
                      var dd = dds[i];
                      var prev = dd.previousElementSibling;
                      var timeStr = '';
                      if (prev) timeStr = getTimeFromEl(prev);
                      if (!timeStr) timeStr = getTimeFromEl(dd);
                      if (!timeStr && dd.parentElement) timeStr = getTimeFromEl(dd.parentElement);

                      var sender = extractSender(dd);
                      var body = extractBody(dd, sender);
                      if (body) results.push({ sender: sender, body: body, time: timeStr });
                    }
                    results = dedupe(results);
                    if (results.length > 0) return { strategy: 'chromium-dl-dd', results: results };
                  }

                  // Strategy 2: [data-created] blocks anywhere in DOM
                  var createdEls = doc.querySelectorAll('[data-created]');
                  if (createdEls.length > 0) {
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
                      if (body) results.push({ sender: sender, body: body, time: ts });
                    }
                    results = dedupe(results);
                    if (results.length > 0) return { strategy: 'chromium-data-created', results: results };
                  }

                  // Strategy 3: SMS24 /messages/ sender links or <label>From: ...</label> anchors
                  var anchors = doc.querySelectorAll('a[href*="/messages/"], a[href*="/message/"], label');
                  if (anchors.length > 0) {
                    for (var i = 0; i < anchors.length; i++) {
                      var a = anchors[i];
                      var aText = textOf(a);
                      if (a.tagName === 'LABEL' && !/^from\s*:/i.test(aText)) continue;
                      var sender = a.tagName === 'LABEL' ? stripFromPrefix(aText) : stripFromPrefix(aText);
                      if (!sender || sender.length > 48) continue;
                      var box = a.closest('dd, li, tr, article, .callout, .card, .shadow-sm, .border, .message-item, .sms-item, div');
                      if (!box || box === doc.body || textOf(box).length > 1600) continue;
                      var ts = (box.previousElementSibling ? getTimeFromEl(box.previousElementSibling) : '') || getTimeFromEl(box);
                      var body = extractBody(box, sender);
                      if (body) results.push({ sender: sender, body: body, time: ts });
                    }
                    results = dedupe(results);
                    if (results.length > 0) return { strategy: 'chromium-from-anchor', results: results };
                  }

                  // Strategy 4: HTML tables (rows with 2-4 cells)
                  var rows = doc.querySelectorAll('table tr');
                  if (rows.length > 0) {
                    for (var i = 0; i < rows.length; i++) {
                      var cells = rows[i].querySelectorAll('td');
                      if (cells.length < 2) continue;
                      var c0 = textOf(cells[0]);
                      var c1 = textOf(cells[1]);
                      var c2 = cells.length >= 3 ? textOf(cells[2]) : '';
                      if (/^(from|sender|time|date|number)$/i.test(c0)) continue;
                      var body = c2.length > c1.length ? c2 : c1;
                      var timeStr = c2.length > c1.length ? (getTimeFromEl(cells[1]) || c1) : (getTimeFromEl(cells[2]) || c2);
                      results.push({ sender: c0, body: body, time: timeStr });
                    }
                    results = dedupe(results);
                    if (results.length > 0) return { strategy: 'chromium-table', results: results };
                  }

                  // Strategy 5: Text-line "From: <Sender>" fallback using innerText || textContent
                  var fullText = textOf(doc.body);
                  if (fullText) {
                    var lines = fullText.split(/\r?\n/).map(clean).filter(function(l) { return l.length > 0; });
                    for (var i = 0; i < lines.length; i++) {
                      var fm = lines[i].match(/^from\s*:\s*(.+)$/i);
                      if (!fm) continue;
                      var sender = clean(fm[1]);
                      var prevTime = (i > 0 && /(\d+\s*(?:sec|min|hour|hr|day)[a-z]*\s*ago|(?:a|an)\s+(?:minute|hour|day)\s+ago|just now|\d{4}[-\/]\d{1,2}[-\/]\d{1,2})/i.test(lines[i - 1])) ? lines[i - 1] : '';
                      var bodyLines = [];
                      var nextTime = '';
                      for (var j = i + 1; j < Math.min(lines.length, i + 6); j++) {
                        if (/^from\s*:/i.test(lines[j])) break;
                        if (/^(\d+\s*(?:sec|min|hour|hr|day)[a-z]*\s*ago|(?:a|an)\s+(?:minute|hour|day)\s+ago|just now|\d{4}[-\/]\d{1,2}[-\/]\d{1,2}[ T]\d{1,2}:\d{2})$/i.test(lines[j])) {
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
                    if (results.length > 0) return { strategy: 'chromium-visible-text', results: results };
                  }

                  return { strategy: strategy, results: results };
                }

                var extracted = collectFromDoc(document);
                if (extracted.results.length === 0) {
                  var frames = document.querySelectorAll('iframe');
                  for (var f = 0; f < frames.length; f++) {
                    try {
                      var fdoc = frames[f].contentDocument;
                      if (fdoc && fdoc.body) {
                        var sub = collectFromDoc(fdoc);
                        if (sub.results.length > 0) {
                          extracted = { strategy: 'iframe+' + sub.strategy, results: sub.results };
                          break;
                        }
                      }
                    } catch (ignore) {}
                  }
                }

                var sampleNode = document.querySelector('dd, [data-created], .callout, .message-item, .sms-item');
                var sampleHtml = sampleNode ? clean(sampleNode.outerHTML).slice(0, 260) : '';
                var domSummary = 'dl=' + document.querySelectorAll('dl').length +
                  ' dd=' + document.querySelectorAll('dd').length +
                  ' created=' + document.querySelectorAll('[data-created]').length +
                  ' msgLinks=' + document.querySelectorAll('a[href*="/messages/"],a[href*="/message/"]').length +
                  ' ph=' + document.querySelectorAll('.placeholder,.text-break').length +
                  ' tbl=' + document.querySelectorAll('table tr').length +
                  (extracted.results.length === 0 && sampleHtml ? ' sample=' + sampleHtml : '');

                var visibleText = textOf(document.body).slice(0, 600);
                // Return compact stripped <main>/<body> HTML (<= 28 KB) so JNI/IPC never times out
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
                  strategy: extracted.strategy,
                  domSummary: domSummary,
                  visibleText: visibleText,
                  mainHtml: mainHtml,
                  messages: extracted.results.slice(0, 60)
                };
              } catch (e) {
                return {
                  title: '',
                  finalUrl: location.href || '',
                  strategy: 'js-error',
                  domSummary: 'err=' + String(e),
                  visibleText: String(e),
                  mainHtml: '',
                  messages: []
                };
              }
            })();
        """.trimIndent()
    }
}
