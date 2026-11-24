package com.example.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class InboundWebhookPayload(
    val to: String,
    val from: String,
    val body: String,
)

/**
 * Parses Twilio form-urlencoded payloads (To, From, Body), flat JSON,
 * and Telnyx nested webhook JSON payloads identically to the Relay web inbound route.
 */
object WebhookPayloadParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(rawInput: String): Result<InboundWebhookPayload> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Payload is empty"))
        }

        return if (trimmed.startsWith("{")) {
            parseJson(trimmed)
        } else {
            parseFormEncoded(trimmed)
        }
    }

    private fun parseJson(raw: String): Result<InboundWebhookPayload> {
        return runCatching {
            val root = json.parseToJsonElement(raw).jsonObject

            // Check Telnyx nested structure: data.payload.{to[0].phone_number, from.phone_number, text}
            val nestedPayload = root["data"]
                ?.let { it as? JsonObject }
                ?.get("payload")
                ?.let { it as? JsonObject }

            if (nestedPayload != null) {
                val to = nestedPayload["to"]
                    ?.jsonArray
                    ?.firstOrNull()
                    ?.jsonObject
                    ?.get("phone_number")
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
                val from = nestedPayload["from"]
                    ?.jsonObject
                    ?.get("phone_number")
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
                val text = nestedPayload["text"]?.jsonPrimitive?.content.orEmpty()
                if (to.isNotBlank() && text.isNotBlank()) {
                    return@runCatching InboundWebhookPayload(to = to, from = from, body = text)
                }
            }

            // Flat JSON fallback: to/To, from/From, body/Body/text
            val to = (root["to"] ?: root["To"])?.jsonPrimitive?.content.orEmpty()
            val from = (root["from"] ?: root["From"])?.jsonPrimitive?.content.orEmpty()
            val body = (root["body"] ?: root["Body"] ?: root["text"])?.jsonPrimitive?.content.orEmpty()

            require(to.isNotBlank() && body.isNotBlank()) {
                "Expected 'to' and 'body' (or Telnyx 'data.payload') fields"
            }
            InboundWebhookPayload(to = to, from = from, body = body)
        }
    }

    private fun parseFormEncoded(raw: String): Result<InboundWebhookPayload> {
        return runCatching {
            val pairs = raw.split("&")
                .mapNotNull { segment ->
                    val idx = segment.indexOf('=')
                    if (idx <= 0) return@mapNotNull null
                    val key = urlDecode(segment.substring(0, idx)).trim()
                    val value = urlDecode(segment.substring(idx + 1))
                    key to value
                }
                .toMap()

            val to = pairs["To"] ?: pairs["to"] ?: ""
            val from = pairs["From"] ?: pairs["from"] ?: ""
            val body = pairs["Body"] ?: pairs["body"] ?: ""

            require(to.isNotBlank() && body.isNotBlank()) {
                "Expected form fields To and Body (e.g. To=%2B61485841378&From=%2B42777&Body=Code+184920)"
            }
            InboundWebhookPayload(to = to, from = from, body = body)
        }
    }

    private fun urlDecode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
