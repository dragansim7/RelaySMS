package com.example

import com.example.data.local.CustomSourceEntity
import com.example.data.local.TempNumberEntity
import com.example.data.remote.AnosimProvider
import com.example.data.remote.BotWallDetector
import com.example.data.remote.BrowserDomFetcher
import com.example.data.remote.CustomSourceAdapter
import com.example.data.remote.FlexibleTimestampParser
import com.example.data.remote.GetVirtualPhoneApiAdapter
import com.example.data.remote.HtmlEntityDecoder
import com.example.data.remote.ProbeOutcome
import com.example.data.remote.PublicSmsMirrorAdapter
import com.example.data.remote.QuackrAdapter
import com.example.data.remote.ReceiveSmsFreeAdapter
import com.example.data.remote.ReceiveSmsOnlineAdapter
import com.example.data.remote.ReceiveSmssMirrorAdapter
import com.example.data.remote.ReceiveSmssProvider
import com.example.data.remote.RenderedDomMessage
import com.example.data.remote.RenderedDomResult
import com.example.data.remote.Sms24ApiResponse
import com.example.data.remote.Sms24HtmlAdapter
import com.example.data.remote.TempFetchResult
import com.example.data.remote.TempSmssAdapter
import com.example.data.remote.TempSourceEngine
import com.example.data.remote.convertRenderedMessages
import com.example.data.repository.NumberCardModel
import com.example.domain.OtpCodeExtractor
import com.example.domain.PhoneFormatter
import com.example.domain.RelativeTimeFormatter
import com.example.domain.WebhookPayloadParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayCoreUnitTest {

    @Test
    fun otpExtractor_detectsStandardVerificationFormats() {
        assertEquals("184920", OtpCodeExtractor.extract("Telegram code: 184920"))
        assertEquals("G-184223", OtpCodeExtractor.extract("G-184223 is your Google verification code."))
        assertEquals("091284", OtpCodeExtractor.extract("Your Discord verification code is 091284"))
        assertEquals("629-410", OtpCodeExtractor.extract("Your Signal verification code is 629-410"))
        assertEquals("629410", OtpCodeExtractor.copyValue("629-410"))
        assertEquals("G-184223", OtpCodeExtractor.copyValue("G-184223"))
        assertNull(OtpCodeExtractor.extract("Hey! Let's meet in 2026 for coffee."))
    }

    @Test
    fun phoneFormatter_normalizesAndFormatsNumbers() {
        assertEquals("+61485841378", PhoneFormatter.normalizeE164("+61 485 841 378"))
        assertEquals("+61402380367", PhoneFormatter.normalizeE164("61-402-380-367"))
        assertTrue(PhoneFormatter.isE164("+61485841378"))
        assertFalse(PhoneFormatter.isE164("+0123456"))
        assertEquals("+61 485 841 378", PhoneFormatter.displayE164("+61485841378"))
        assertEquals("0485 841 378", PhoneFormatter.localFormat("+61485841378"))
        assertEquals("AU" to "Australia", PhoneFormatter.inferCountryFromE164("+61402380367"))
        assertEquals("GB" to "United Kingdom", PhoneFormatter.inferCountryFromE164("+447700900123"))
        assertEquals("Telegram", PhoneFormatter.guessSender("+42777", "Your Telegram code is 123456"))

        val h1 = PhoneFormatter.computeDedupHash("61402380367", "Google", "Code 123456", 1_700_000_000_000L)
        val h2 = PhoneFormatter.computeDedupHash("61402380367", "google", "code 123456", 1_700_000_010_000L)
        val h3 = PhoneFormatter.computeDedupHash("61402380367", "Google", "Code 999999", 1_700_000_000_000L)
        assertEquals(h1, h2)
        assertNotEquals(h1, h3)
    }

    @Test
    fun webhookParser_supportsTwilioFormAndTelnyxJson() {
        val twilio = WebhookPayloadParser.parse(
            "To=%2B61485841378&From=%2B42777&Body=Your+code+is+184920"
        ).getOrThrow()
        assertEquals("+61485841378", twilio.to)
        assertEquals("+42777", twilio.from)
        assertEquals("Your code is 184920", twilio.body)

        val telnyx = WebhookPayloadParser.parse(
            """{"data":{"payload":{"to":[{"phone_number":"+61485841378"}],"from":{"phone_number":"+14155550199"},"text":"Code 992811"}}}"""
        ).getOrThrow()
        assertEquals("+61485841378", telnyx.to)
        assertEquals("+14155550199", telnyx.from)
        assertEquals("Code 992811", telnyx.body)
    }

    @Test
    fun providerParsers_decodeHtmlAndJsonFragments() {
        assertEquals("Code <1234> & OK", HtmlEntityDecoder.decodeHtml("Code &lt;1234&gt; &amp; OK", stripTags = false))

        val anosimHtml = """
            <div class="list-group-item message-item">
              <h6>Telegram</h6>
              <small>11/23/2026 04:15 PM</small>
              <p class="mb-0">Telegram code: 551902</p>
            </div>
        """.trimIndent()
        val anosimParsed = AnosimProvider(OkHttpClient()).parseHtml(anosimHtml)
        assertEquals(1, anosimParsed.size)
        assertEquals("Telegram", anosimParsed.first().senderName)
        assertEquals("Telegram code: 551902", anosimParsed.first().body)

        val rsHtml = """&quot;sender&quot;:[0,&quot;Google&quot;],&quot;content&quot;:[0,&quot;G-884120 is your code&quot;],&quot;receivedAt&quot;:[0,&quot;2026-11-23T12:00:00Z&quot;]"""
        val rsParsed = ReceiveSmssProvider(OkHttpClient()).parseHtml("61468279945", rsHtml)
        assertEquals(1, rsParsed.size)
        assertEquals("Google", rsParsed.first().senderName)
        assertEquals("G-884120 is your code", rsParsed.first().body)
    }

    @Test
    fun tempSourceAdapters_parseJsonApiSms24AndCustomSources() {
        val client = OkHttpClient()

        // 1. Primary GetVirtualPhoneNumber JSON API
        val gvpnJson = """
            [
              {
                "from": "+42777",
                "text": "Telegram code: 774209",
                "code": "774209",
                "received": "2026-11-23T18:30:00Z",
                "service": "Telegram"
              }
            ]
        """.trimIndent()
        val gvpnParsed = GetVirtualPhoneApiAdapter(client).parseJson(gvpnJson)
        assertEquals(1, gvpnParsed.size)
        assertEquals("Telegram", gvpnParsed.first().senderName)
        assertEquals("774209", gvpnParsed.first().explicitCode)
        assertEquals("Telegram", gvpnParsed.first().serviceName)

        // 2. Fallback SMS24.me HTML scraper
        val sms24Html = """
            <dl>
              <dt>5 minutes ago</dt>
              <dd>
                <label>From: <a href="#">Discord</a></label>
                <span class="placeholder">Your Discord code is 901244</span>
              </dd>
            </dl>
        """.trimIndent()
        val sms24Parsed = Sms24HtmlAdapter(client).parseHtml(sms24Html, nowMs = 1_700_000_600_000L)
        assertEquals(1, sms24Parsed.size)
        assertEquals("Discord", sms24Parsed.first().senderName)
        assertEquals("Your Discord code is 901244", sms24Parsed.first().body)
        assertEquals(1_700_000_300_000L, sms24Parsed.first().receivedAtEpochMs)

        // 3. Custom JSON mapping source
        val customJsonSource = CustomSourceEntity(
            id = 7L,
            name = "MyAPI",
            urlTemplate = "https://example.test/inbox?n={E164}",
            parserType = CustomSourceEntity.PARSER_JSON,
            jsonArrayPath = "payload.items",
            jsonFromKey = "author",
            jsonTextKey = "msg",
            jsonCodeKey = "pin",
            jsonTimeKey = "ts",
            jsonServiceKey = "vendor",
        )
        val customAdapter = CustomSourceAdapter(client)
        assertEquals("https://example.test/inbox?n=%2B61402380367", customAdapter.resolveUrl(customJsonSource.urlTemplate, "+61402380367"))
        val customJsonParsed = customAdapter.parseCustomJson(
            """{"payload":{"items":[{"author":"GitHub","msg":"Auth pin 443210","pin":"443210","ts":"2026-11-23T10:00:00Z","vendor":"GitHub"}]}}""",
            customJsonSource,
        )
        assertEquals(1, customJsonParsed.size)
        assertEquals("443210", customJsonParsed.first().explicitCode)

        // 4. Custom HTML regex source
        val customHtmlSource = CustomSourceEntity(
            id = 8L,
            name = "CustomHTML",
            urlTemplate = "https://example.test/n/{NUMBER}",
            parserType = CustomSourceEntity.PARSER_HTML_REGEX,
            htmlRegexPattern = """<span class="s">(.*?)</span><span class="b">(.*?)</span><span class="t">(.*?)</span>""",
        )
        val customHtmlParsed = customAdapter.parseCustomHtmlRegex(
            """<span class="s">Stripe</span><span class="b">Stripe OTP: 512908</span><span class="t">2 mins ago</span>""",
            customHtmlSource,
            nowMs = 1_700_000_120_000L,
        )
        assertEquals(1, customHtmlParsed.size)
        assertEquals("Stripe", customHtmlParsed.first().senderName)
        assertEquals("Stripe OTP: 512908", customHtmlParsed.first().body)
        assertEquals(1_700_000_000_000L, FlexibleTimestampParser.parseToEpochMs("2 mins ago", 1_700_000_120_000L))
    }

    @Test
    fun sms24Hardening_handlesDataCreatedNestedSpansAndBotDetection() {
        val client = OkHttpClient()
        val sms24 = Sms24HtmlAdapter(client)

        val nestedHtml = """
            <dl>
              <dt><div class="text-muted" data-created="2026-11-23 12:30:00"></div></dt>
              <dd>
                <label>From: <a href="/en/messages/Google">Google</a></label>
                <span class="placeholder text-break">G-<span class="fw-bold">948210</span> is your Google code. <a href="#">Help</a></span>
              </dd>
            </dl>
        """.trimIndent()
        val (parsed, strategy) = sms24.parseHtmlWithStrategy(nestedHtml, nowMs = 1_800_000_000_000L)
        assertEquals("dl-dt-dd-balanced", strategy)
        assertEquals(1, parsed.size)
        assertEquals("Google", parsed.first().senderName)
        assertTrue(parsed.first().body.contains("G-948210"))
        assertTrue(parsed.first().body.contains("is your Google code."))
        assertTrue(parsed.first().hasExplicitTimestamp)

        // Natural language timestamps without explicit date mark hasExplicitTimestamp = false
        val now = 1_700_000_120_000L
        val aMinuteAgo = FlexibleTimestampParser.parse("a minute ago", now)
        assertEquals(now - 60_000L, aMinuteAgo.epochMs)
        assertFalse(aMinuteAgo.isExplicit)

        val anHourAgo = FlexibleTimestampParser.parse("an hour ago", now)
        assertEquals(now - 3_600_000L, anHourAgo.epochMs)
        assertFalse(anHourAgo.isExplicit)

        // Cloudflare / anti-bot detection
        val cfHtml = "<html><head><title>Just a moment...</title></head><body>Checking your browser via cf_chl_opt</body></html>"
        assertTrue(BotWallDetector.detect(200, cfHtml).isBlocked)
        assertTrue(BotWallDetector.detect(403, "<html><title>Access Denied</title></html>").isBlocked)
        assertFalse(BotWallDetector.detect(200, nestedHtml).isBlocked)

        // Fallback mirrors
        val receiveMirror = ReceiveSmssMirrorAdapter(client)
        val mirrorJsonHtml = """{\"sender\":\"Uber\",\"content\":\"Your Uber code is 551092\",\"received_at\":\"2026-11-23T08:00:00Z\"}"""
        val (mirrorMsgs, mirrorStrategy) = receiveMirror.parseHtmlWithStrategy(mirrorJsonHtml, nowMs = 1_800_000_000_000L)
        assertEquals("embedded-json", mirrorStrategy)
        assertEquals(1, mirrorMsgs.size)
        assertEquals("Uber", mirrorMsgs.first().senderName)

        val publicMirror = PublicSmsMirrorAdapter(client)
        val tableHtml = """
            <table>
              <tr><td>From</td><td>Message</td><td>Time</td></tr>
              <tr><td>Discord</td><td>Your Discord verification code is 771829</td><td>2026-11-23 09:15:00</td></tr>
            </table>
        """.trimIndent()
        val (tableMsgs, tableStrategy) = publicMirror.parseMirrorHtml(tableHtml, "quackr.io", nowMs = 1_800_000_000_000L)
        assertEquals("html-table", tableStrategy)
        assertEquals(1, tableMsgs.size)
        assertEquals("Discord", tableMsgs.first().senderName)
        assertTrue(tableMsgs.first().hasExplicitTimestamp)
    }

    @Test
    fun anosimHardening_timezoneCalibrationFlexibleMarkupAnd24hActiveWindow() {
        val client = OkHttpClient()
        val anosim = AnosimProvider(client)

        // 1. Auto-calibrated timezone: UTC clock 5 minutes before nowMs must resolve to ~5m ago (NOT 11h ago)
        val calNowUtc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, java.util.Calendar.NOVEMBER, 23, 14, 20, 0)
        }
        val nowMs = calNowUtc.timeInMillis
        val parsedTs = anosim.parseAnosimTimestamp("11/23/2026 02:15 PM", nowMs)
        assertTrue(parsedTs.isExplicit)
        val deltaMinutes = (nowMs - parsedTs.epochMs) / 60_000L
        assertEquals(5L, deltaMinutes)

        // 2. Flexible HTML with extra classes on message-item and nested spans
        val flexibleHtml = """
            <div class="list-group-item message-item py-3 border-bottom">
              <div class="d-flex justify-content-between">
                <h6 class="fw-bold mb-1">WhatsApp</h6>
                <small class="text-muted">11/23/2026 02:15 PM</small>
              </div>
              <p class="mb-0 text-break">Your WhatsApp code: <span class="fw-bold">492-180</span></p>
            </div>
        """.trimIndent()
        val (flexMsgs, flexStrategy) = anosim.parseHtmlWithStrategy(flexibleHtml, nowMs)
        assertEquals("anosim-message-items", flexStrategy)
        assertEquals(1, flexMsgs.size)
        assertEquals("WhatsApp", flexMsgs.first().senderName)
        assertTrue(flexMsgs.first().body.contains("492-180"))
        assertEquals(5L, (nowMs - flexMsgs.first().receivedAtEpochMs) / 60_000L)

        // 3. HTML table fallback on Anosim direct page
        val anosimTableHtml = """
            <table class="table">
              <tr><td>From</td><td>Message</td><td>Received</td></tr>
              <tr><td>PayPal</td><td>PayPal security code: 882104</td><td>3 minutes ago</td></tr>
            </table>
        """.trimIndent()
        val (tableMsgs, tableStrategy) = anosim.parseHtmlWithStrategy(anosimTableHtml, nowMs)
        assertEquals("html-table", tableStrategy)
        assertEquals(1, tableMsgs.size)
        assertEquals("PayPal", tableMsgs.first().senderName)
        assertEquals(nowMs - 3 * 60_000L, tableMsgs.first().receivedAtEpochMs)

        // 4. 24-hour Active window
        assertTrue(RelativeTimeFormatter.isLineActive(nowMs - 6L * 3_600_000L, nowMs))
        assertTrue(RelativeTimeFormatter.isLineActive(nowMs - 23L * 3_600_000L, nowMs))
        assertFalse(RelativeTimeFormatter.isLineActive(nowMs - 25L * 3_600_000L, nowMs))
        assertFalse(RelativeTimeFormatter.isLineActive(null, nowMs))
    }

    @Test
    fun australianPlatforms_parseCardsTablesAndEmbeddedJson() {
        val client = OkHttpClient()
        val nowMs = 1_800_000_000_000L

        // Quackr.io embedded JSON payload
        val quackr = QuackrAdapter(client)
        val (qMsgs, qStrat) = quackr.parseHostHtml(
            """{"from":"Uber","message":"Your Uber code is 319044","received":"2026-11-23T12:00:00Z"}""",
            nowMs,
        )
        assertEquals("embedded-json", qStrat)
        assertEquals(1, qMsgs.size)
        assertEquals("quackr", qMsgs.first().sourceTag)
        assertEquals("Uber", qMsgs.first().senderName)

        // TempSMSS.com card layout
        val tempSmss = TempSmssAdapter(client)
        val (tsMsgs, tsStrat) = tempSmss.parseHostHtml(
            """
            <div class="sms-item card">
              <h5>Telegram</h5>
              <p>Login code: 662910</p>
              <small>4 minutes ago</small>
            </div>
            """.trimIndent(),
            nowMs,
        )
        assertEquals("html-cards-balanced", tsStrat)
        assertEquals(1, tsMsgs.size)
        assertEquals("tempsmss", tsMsgs.first().sourceTag)
        assertEquals("Telegram", tsMsgs.first().senderName)

        // Receive-SMS-Free.cc & Receive-SMS-Online.info table layouts
        val rsf = ReceiveSmsFreeAdapter(client)
        val (rsfMsgs, rsfStrat) = rsf.parseHostHtml(
            """
            <table>
              <tr><td>Sender</td><td>SMS</td><td>Time</td></tr>
              <tr><td>LinkedIn</td><td>Your LinkedIn verification code is 718293</td><td>2026-11-23 11:00:00</td></tr>
            </table>
            """.trimIndent(),
            nowMs,
        )
        assertEquals("html-table", rsfStrat)
        assertEquals(1, rsfMsgs.size)
        assertEquals("receive_sms_free", rsfMsgs.first().sourceTag)

        val rso = ReceiveSmsOnlineAdapter(client)
        val (rsoMsgs, rsoStrat) = rso.parseHostHtml(
            """
            <table>
              <tr><td>From</td><td>Message</td><td>Date</td></tr>
              <tr><td>Stripe</td><td>Stripe code: 441209</td><td>10 minutes ago</td></tr>
            </table>
            """.trimIndent(),
            nowMs,
        )
        assertEquals("html-table", rsoStrat)
        assertEquals(1, rsoMsgs.size)
        assertEquals("receive_sms_online", rsoMsgs.first().sourceTag)
    }

    @Test
    fun sms24TwoStage_relaxedDomAnchorsUnquotedAttrsAndChromiumConversion() {
        val sms24 = Sms24HtmlAdapter(OkHttpClient())
        val nowMs = 1_800_000_000_000L

        // 1. Non-adjacent <dt> and <dd> separated by comment/div + unquoted attributes
        val relaxedHtml = """
            <dl>
              <dt><div data-created=2026-11-23T12:00:00Z>5 minutes ago</div></dt>
              <!-- ad separator -->
              <div class="wrapper">
                <dd class=shadow-sm>
                  <label>From: <a href=/en/messages/Telegram>Telegram</a></label>
                  <span class=placeholder>Telegram login code: 551029</span>
                </dd>
              </div>
            </dl>
        """.trimIndent()
        val (relaxedMsgs, relaxedStrat) = sms24.parseHtmlWithStrategy(relaxedHtml, nowMs)
        assertEquals("dd-relaxed-balanced", relaxedStrat)
        assertEquals(1, relaxedMsgs.size)
        assertEquals("Telegram", relaxedMsgs.first().senderName)
        assertEquals("Telegram login code: 551029", relaxedMsgs.first().body)
        assertTrue(relaxedMsgs.first().hasExplicitTimestamp)

        // 2. Layout-agnostic From: + /en/messages/ anchor without any <dl>/<dd>
        val anchorHtml = """
            <section class="feed">
              <div class="item">
                <small>2 minutes ago</small>
                <a href="/en/messages/Google">Google</a>
                <p>G-774102 is your Google verification code.</p>
              </div>
            </section>
        """.trimIndent()
        val (anchorMsgs, anchorStrat) = sms24.parseHtmlWithStrategy(anchorHtml, nowMs)
        assertEquals("from-messages-anchor", anchorStrat)
        assertEquals(1, anchorMsgs.size)
        assertEquals("Google", anchorMsgs.first().senderName)
        assertTrue(anchorMsgs.first().body.contains("G-774102"))

        // 3. Stage 2 Chromium WebView DOM output conversion + deduplication
        val converted = convertRenderedMessages(
            rendered = listOf(
                RenderedDomMessage("From: WhatsApp", "Your WhatsApp code is 312-908", "2026-11-23 12:15:00", "chromium-dl-dd"),
                RenderedDomMessage("WhatsApp", "Your WhatsApp code is 312-908", "2026-11-23 12:15:00", "chromium-dl-dd"),
            ),
            sourceTag = "sms24",
            fallbackSender = "SMS24",
            nowMs = nowMs,
        )
        assertEquals(1, converted.size)
        assertEquals("WhatsApp", converted.first().senderName)
        assertTrue(converted.first().hasExplicitTimestamp)

        // 4. DOM-fingerprinted diagnostic snippet skips <nav> and reports element counts
        val snippet = BotWallDetector.buildSnippet(
            "<html><head><title>SMS24</title></head><body><nav>Home Menu</nav><main><dd data-created=\"2026-11-23 10:00:00\">Hello OTP</dd></main></body></html>"
        )
        assertTrue(snippet.contains("dd=1"))
        assertTrue(snippet.contains("created=1"))
        assertTrue(snippet.contains("Hello OTP"))
    }

    @Test
    fun sms24Regressions_placeholderCollisionPlainSpanUnclosedHtml5AndDtAttrs() {
        val sms24 = Sms24HtmlAdapter(OkHttpClient())
        val nowMs = 1_795_507_200_000L // ~2026-11-24

        // 1. Real SMS24.me Bootstrap 5 markup: <a class="placeholder ms-1"> appears BEFORE <span class="placeholder text-break">
        //    AND body contains "valid for 7 days" (must NOT backdate the message by 7 days!)
        val realBootstrapHtml = """
            <dl class="row">
              <dt class="col-3" data-created="2026-11-24 07:45:12">
                <div class="placeholder" data-created="2026-11-24 07:45:12">12 minutes ago</div>
              </dt>
              <dd class="col-9">
                <label>From: <a class="placeholder ms-1" title="1415555123" href="/en/messages/Google">Google</a></label>
                <span class="placeholder text-break">G-842910 is your Google code, valid for 7 days.</span>
              </dd>
            </dl>
        """.trimIndent()
        val (msgs1, strat1) = sms24.parseHtmlWithStrategy(realBootstrapHtml, nowMs)
        assertEquals("dl-dt-dd-balanced", strat1)
        assertEquals(1, msgs1.size)
        assertEquals("Google", msgs1.first().senderName)
        assertEquals("G-842910 is your Google code, valid for 7 days.", msgs1.first().body)
        assertTrue(msgs1.first().hasExplicitTimestamp)
        // Must parse 2026-11-24 07:45:12, NOT 2014 (from title="1415555123") and NOT nowMs - 7 days
        assertTrue(msgs1.first().receivedAtEpochMs > 1_795_000_000_000L)

        // 2. Plain <span> without .placeholder class, preceded by a label <span>
        val plainSpanHtml = """
            <dl class="row">
              <dt class="col-3" data-created="2026/11/24 7:05:12">15 mins ago</dt>
              <dd class="col-9">
                <label><span class="placeholder">From:</span> <a href="https://sms24.me/en/messages/Uber">Uber</a></label>
                <span>Your Uber code is 519204. Never share this code.</span>
              </dd>
            </dl>
        """.trimIndent()
        val (msgs2, _) = sms24.parseHtmlWithStrategy(plainSpanHtml, nowMs)
        assertEquals(1, msgs2.size)
        assertEquals("Uber", msgs2.first().senderName)
        assertEquals("Your Uber code is 519204. Never share this code.", msgs2.first().body)
        assertTrue(msgs2.first().hasExplicitTimestamp)

        // 3. HTML5 minified <dl> with omitted </dt> and </dd> closing tags
        val unclosedHtml5 = """
            <dl class="row">
              <dt class="col-3" data-created="2026-11-24 08:00:00">2 mins ago
              <dd class="col-9">
                <label>From: <a class="placeholder ms-1" href="/en/messages/Discord">Discord</a></label>
                <span class="placeholder text-break">Your Discord verification code is 774120</span>
              <dt class="col-3" data-created="2026-11-24 07:50:00">12 mins ago
              <dd class="col-9">
                <label>From: <a class="placeholder ms-1" href="/en/messages/Signal">Signal</a></label>
                <span class="placeholder text-break">Signal code: 331-890</span>
            </dl>
        """.trimIndent()
        val (msgs3, strat3) = sms24.parseHtmlWithStrategy(unclosedHtml5, nowMs)
        assertEquals("dd-unclosed-html5", strat3)
        assertEquals(2, msgs3.size)
        assertEquals("Discord", msgs3[0].senderName)
        assertEquals("Your Discord verification code is 774120", msgs3[0].body)
        assertEquals("Signal", msgs3[1].senderName)
        assertEquals("Signal code: 331-890", msgs3[1].body)

        // 4. Script tags containing "no messages yet" must NOT trigger genuine empty-inbox detection
        val scriptEmptyHtml = "<html><body><script>const emptyText = 'No messages yet';</script><main><div>Loading</div></main></body></html>"
        assertFalse(sms24.isGenuinelyEmptySms24Page(scriptEmptyHtml))
        val visibleEmptyHtml = "<html><body><main><p>No messages yet for this number.</p></main></body></html>"
        assertTrue(sms24.isGenuinelyEmptySms24Page(visibleEmptyHtml))
    }

    @Test
    fun timestampHardening_rejectsPhoneNumbersAndBodyDurationsAndParsesNamedMonths() {
        val nowMs = 1_795_507_200_000L

        // 10-digit phone number in title attribute must NOT be treated as a 2014 Unix timestamp
        val phoneTitle = FlexibleTimestampParser.extractFromHtmlOrText(
            """<a title="1415555123" href="/en/messages/Bank">Bank</a>""",
            nowMs,
        )
        assertFalse(phoneTitle.isExplicit)
        assertEquals(nowMs, phoneTitle.epochMs)

        // Duration inside SMS body without "ago" must NOT backdate message
        val bodyDuration = FlexibleTimestampParser.parse("Your OTP 123456 expires in 7 days", nowMs)
        assertFalse(bodyDuration.isExplicit)
        assertEquals(nowMs, bodyDuration.epochMs)

        // Named-month and slash-date formats must parse explicitly
        val dmy = FlexibleTimestampParser.parse("24 Nov 2026 07:05", nowMs)
        assertTrue(dmy.isExplicit)
        val mdy = FlexibleTimestampParser.parse("Nov 24, 2026 05:05 PM", nowMs)
        assertTrue(mdy.isExplicit)
        val slashSingleHour = FlexibleTimestampParser.parse("2026/11/24 7:05:12", nowMs)
        assertTrue(slashSingleHour.isExplicit)
    }

    @Test
    fun relayPipeline_noFakeGreenOnEmptyInboxAndLockContentionSkipsCleanly() = runBlocking {
        val nowMs = 1_795_507_200_000L

        // 1. Auto temp number with STATUS_ONLINE and 0 messages (lastAtEpochMs == null) must NOT be Active (green)
        val emptyOnlineCard = NumberCardModel(
            id = "61400667111",
            e164 = "+61400667111",
            countryCode = "AU",
            countryName = "Australia",
            label = "SMS24 AU",
            carrier = "SMS24.me",
            providerType = "temp_auto",
            dayCount = 0,
            lastAtEpochMs = null,
            preview = null,
            senderName = null,
            unseenCount = 0,
            isTemp = true,
            tempMode = TempNumberEntity.MODE_AUTO,
            tempStatus = TempNumberEntity.STATUS_ONLINE,
            tempLastSyncEpochMs = nowMs - 5_000L,
        )
        assertFalse(emptyOnlineCard.isActive(nowMs))

        // Same card WITH a message received 20 minutes ago MUST be Active (green)
        val activeCard = emptyOnlineCard.copy(dayCount = 1, lastAtEpochMs = nowMs - 20L * 60_000L)
        assertTrue(activeCard.isActive(nowMs))

        // 2. Concurrent non-force poll while a fetch is in flight MUST return SkippedInProgress (never fake Success(emptyList()))
        val requestStarted = CompletableDeferred<Unit>()
        val allowResponse = CompletableDeferred<Unit>()
        val blockingBrowser = object : BrowserDomFetcher {
            override suspend fun renderAndExtract(url: String, referer: String?, maxWaitMs: Long): RenderedDomResult? = null
            override suspend fun fetchSms24ApiInPage(
                digits: String,
                page: Int,
                forceReload: Boolean,
                maxWaitMs: Long,
            ): Sms24ApiResponse {
                requestStarted.complete(Unit)
                allowResponse.await()
                return Sms24ApiResponse(
                    status = 200,
                    body = """{"messages":[{"from":"Google","senderUrl":null,"created":"2026-11-24 07:45:12","content":"Code 123456","kind":"sms","number":"$digits"}],"page":1,"pages":1}""",
                    finalUrl = "https://sms24.me/en/numbers/$digits",
                    sitekeyFound = true,
                    tokenObtained = true,
                )
            }
        }

        val engine = TempSourceEngine(OkHttpClient(), browserFetcher = blockingBrowser)
        val temp = TempNumberEntity(
            numberId = "61400667111",
            e164 = "+61400667111",
            mode = TempNumberEntity.MODE_AUTO,
            sourceType = TempNumberEntity.SOURCE_SMS24_ME,
            label = "SMS24 AU",
        )

        val firstFetch = async { engine.fetchForTempNumber(temp, customSource = null, force = true) }
        requestStarted.await()
        assertTrue(engine.isSyncInProgress(temp.numberId))

        // Overlapping non-force poll must return SkippedInProgress, NOT Success(emptyList())
        val overlappingResult = engine.fetchForTempNumber(temp, customSource = null, force = false)
        assertEquals(TempFetchResult.SkippedInProgress, overlappingResult)

        allowResponse.complete(Unit)
        val finalResult = firstFetch.await()
        assertTrue(finalResult is TempFetchResult.Success)
        assertEquals(1, (finalResult as TempFetchResult.Success).messages.size)
        assertEquals("Code 123456", finalResult.messages.first().body)
    }

    @Test
    fun sms24VerifiedPostApiContract_dedupeCallAudio403RecoveryAndBackoffs() = runBlocking {
        assertEquals("in_page", Sms24HtmlAdapter.TOKEN_TRANSPORT)
        assertEquals("0x4AAAAAAFQHYrfMKXcEi0gA", Sms24HtmlAdapter.DEFAULT_TURNSTILE_SITEKEY)

        // Ensure Sms24HtmlAdapter NEVER issues plain HTTP GET requests against /en/numbers/{number}
        var rawHttpCalls = 0
        val guardClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                rawHttpCalls += 1
                throw AssertionError("Sms24HtmlAdapter must not issue plain HTTP calls: ${chain.request().url}")
            })
            .build()

        // 1. Forced 403 on first attempt recovers within the SAME cycle via reload (forceReload = true)
        val callFlags = mutableListOf<Boolean>()
        val apiJson = """
            {
              "messages": [
                {
                  "from": "Google",
                  "senderUrl": "/en/messages/Google",
                  "created": "2026-11-24 22:45:00",
                  "content": "G-519204 is your Google verification code.",
                  "kind": "sms",
                  "number": "61480001107",
                  "country": "Australia"
                },
                {
                  "from": "",
                  "senderUrl": null,
                  "created": "2026-11-24 22:40:00",
                  "content": "https://cdn.sms24.me/calls/rec-991.mp3",
                  "kind": "call",
                  "number": "61480001107",
                  "country": "Australia"
                },
                {
                  "from": "Google",
                  "senderUrl": "/en/messages/Google",
                  "created": "2026-11-24 22:45:00",
                  "content": "G-519204 is your Google verification code.",
                  "kind": "sms",
                  "number": "61480001107",
                  "country": "Australia"
                }
              ],
              "page": 1,
              "pages": 1
            }
        """.trimIndent()

        val recoveringBrowser = object : BrowserDomFetcher {
            override suspend fun renderAndExtract(url: String, referer: String?, maxWaitMs: Long): RenderedDomResult? = null
            override suspend fun fetchSms24ApiInPage(
                digits: String,
                page: Int,
                forceReload: Boolean,
                maxWaitMs: Long,
            ): Sms24ApiResponse {
                callFlags.add(forceReload)
                return if (!forceReload) {
                    Sms24ApiResponse(
                        status = 403,
                        body = """{"error":"turnstile token rejected"}""",
                        finalUrl = "https://sms24.me/en/numbers/$digits",
                        sitekeyFound = true,
                        tokenObtained = true,
                    )
                } else {
                    Sms24ApiResponse(
                        status = 200,
                        body = apiJson,
                        finalUrl = "https://sms24.me/en/numbers/$digits",
                        sitekeyFound = true,
                        tokenObtained = true,
                        pageReloaded = true,
                    )
                }
            }
        }

        val sms24 = Sms24HtmlAdapter(guardClient, recoveringBrowser)
        val result = sms24.fetch("+61480001107", force = true)
        assertEquals(0, rawHttpCalls)
        assertEquals(listOf(false, true), callFlags)
        assertTrue(result is TempFetchResult.Success)
        val msgs = (result as TempFetchResult.Success).messages
        // Exact SHA-1(from|created|kind|content) deduplicated the duplicate Google message
        assertEquals(2, msgs.size)
        assertEquals("Google", msgs[0].senderName)
        assertEquals("sms", msgs[0].messageKind)
        assertEquals("G-519204 is your Google verification code.", msgs[0].body)
        assertEquals(
            Sms24HtmlAdapter.computeDedupeKey("Google", "2026-11-24 22:45:00", "sms", "G-519204 is your Google verification code."),
            msgs[0].overrideDedupHash,
        )

        // Empty from -> "Unknown sender"; kind == "call" stores MP3 URL in audioUrl, NEVER in body
        assertEquals("Unknown sender", msgs[1].senderName)
        assertEquals("call", msgs[1].messageKind)
        assertEquals("https://cdn.sms24.me/calls/rec-991.mp3", msgs[1].audioUrl)
        assertFalse(msgs[1].body.contains("http"))
        assertFalse(msgs[1].body.contains(".mp3"))
        assertEquals(
            Sms24HtmlAdapter.computeDedupeKey("", "2026-11-24 22:40:00", "call", "https://cdn.sms24.me/calls/rec-991.mp3"),
            msgs[1].overrideDedupHash,
        )

        // 2. Immediate non-forced poll hits the jittered 90–180s cache without calling WebView again
        val cachedRes = sms24.fetch("+61480001107", force = false)
        assertTrue(cachedRes is TempFetchResult.Success && cachedRes.fromCache)
        assertEquals(2, callFlags.size)

        // 3. Empty inbox `{"messages": []}` returns confirmed Success(emptyList) with EMPTY_INBOX outcome
        val emptyBrowser = object : BrowserDomFetcher {
            override suspend fun renderAndExtract(url: String, referer: String?, maxWaitMs: Long): RenderedDomResult? = null
            override suspend fun fetchSms24ApiInPage(digits: String, page: Int, forceReload: Boolean, maxWaitMs: Long) =
                Sms24ApiResponse(200, """{"messages":[],"page":1,"pages":1}""", "https://sms24.me/en/numbers/$digits", sitekeyFound = true, tokenObtained = true)
        }
        val emptyRes = Sms24HtmlAdapter(guardClient, emptyBrowser).fetch("+61400667111", force = true)
        assertTrue(emptyRes is TempFetchResult.Success)
        assertTrue((emptyRes as TempFetchResult.Success).messages.isEmpty())
        assertEquals(ProbeOutcome.EMPTY_INBOX, emptyRes.attempts.last().outcome)

        // 4. HTTP 429 triggers 120s backoff and HTTP 503 triggers 60s backoff
        val throttleBrowser = object : BrowserDomFetcher {
            override suspend fun renderAndExtract(url: String, referer: String?, maxWaitMs: Long): RenderedDomResult? = null
            override suspend fun fetchSms24ApiInPage(digits: String, page: Int, forceReload: Boolean, maxWaitMs: Long) =
                Sms24ApiResponse(429, "Too Many Requests", "https://sms24.me/en/numbers/$digits")
        }
        val throttleAdapter = Sms24HtmlAdapter(guardClient, throttleBrowser)
        val throttleRes = throttleAdapter.fetch("+61480001107", force = true)
        assertTrue(throttleRes is TempFetchResult.Failure)
        assertEquals(TempNumberEntity.STATUS_RATE_LIMITED, (throttleRes as TempFetchResult.Failure).status)
        assertEquals(120_000L, throttleRes.retryAfterMs)

        val err503Browser = object : BrowserDomFetcher {
            override suspend fun renderAndExtract(url: String, referer: String?, maxWaitMs: Long): RenderedDomResult? = null
            override suspend fun fetchSms24ApiInPage(digits: String, page: Int, forceReload: Boolean, maxWaitMs: Long) =
                Sms24ApiResponse(503, "Service Unavailable", "https://sms24.me/en/numbers/$digits")
        }
        val err503Res = Sms24HtmlAdapter(guardClient, err503Browser).fetch("+61480001107", force = true)
        assertTrue(err503Res is TempFetchResult.Failure)
        assertEquals(60_000L, (err503Res as TempFetchResult.Failure).retryAfterMs)

        // 5. HTTP 405 Method Not Allowed fails cleanly with POST diagnostic
        val err405Browser = object : BrowserDomFetcher {
            override suspend fun renderAndExtract(url: String, referer: String?, maxWaitMs: Long): RenderedDomResult? = null
            override suspend fun fetchSms24ApiInPage(digits: String, page: Int, forceReload: Boolean, maxWaitMs: Long) =
                Sms24ApiResponse(405, "Method Not Allowed", "https://sms24.me/en/numbers/$digits")
        }
        val err405Res = Sms24HtmlAdapter(guardClient, err405Browser).fetch("+61480001107", force = true)
        assertTrue(err405Res is TempFetchResult.Failure)
        assertTrue((err405Res as TempFetchResult.Failure).errorMessage.contains("405"))
    }
}
