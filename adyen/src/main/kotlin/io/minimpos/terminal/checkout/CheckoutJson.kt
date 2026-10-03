package io.minimpos.terminal.checkout

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.minimpos.terminal.transport.AdyenReply

/** [text] as a JSON object; null when it is not one. */
internal fun parseObject(text: String): JsonObject? = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()

/** The string (or number, as text) under [name]; null when it is missing or not a primitive. */
internal fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

/** Adyen's error message and code for a failed request, e.g. "Invalid amount (HTTP 422, code 137)". */
internal fun adyenError(reply: AdyenReply.Answered): String {
    val json = parseObject(reply.body)
    val message = json?.string("message") ?: "Adyen returned an error"
    val code = json?.string("errorCode")?.let { ", code $it" }.orEmpty()
    return "$message (HTTP ${reply.code}$code)"
}

/** Whether an HTTP error answer leaves the outcome unknown: a timeout (408), throttling (429) or a server error (5xx). */
internal fun AdyenReply.Answered.outcomeUnknown(): Boolean = code in RETRYABLE || code >= HTTP_SERVER_ERROR

/** Request timeout and rate limit, after which a request is sent again with the same idempotency key. */
private val RETRYABLE = setOf(408, 429)

private const val HTTP_SERVER_ERROR = 500
