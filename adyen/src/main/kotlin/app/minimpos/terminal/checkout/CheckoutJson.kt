package app.minimpos.terminal.checkout

import app.minimpos.terminal.transport.AdyenReply
import app.minimpos.terminal.transport.adyenField
import app.minimpos.terminal.transport.decodeAdyenModel
import com.adyen.model.applicationinfo.ApplicationInfo
import com.adyen.model.checkout.JSON
import com.adyen.model.checkout.ServiceError

/** [text] as the official Checkout error model; null when it is not readable. */
internal fun parseError(text: String): ServiceError? = decodeAdyenModel(text, ServiceError::class.java)

/** The original status text for diagnostics when the library cannot map it to a known enum. */
internal fun unexpectedStatus(text: String): String = "Unexpected status from Adyen: ${adyenField(text, "status").orEmpty()}"

/** Adyen's error message and code for a failed request, e.g. "Invalid amount (HTTP 422, code 137)". */
internal fun adyenError(reply: AdyenReply.Answered): String {
    val error = parseError(reply.body)
    val message = error?.message ?: "Adyen returned an error"
    val code = error?.errorCode?.let { ", code $it" }.orEmpty()
    return "$message (HTTP ${reply.code}$code)"
}

/** Whether an HTTP error answer leaves the outcome unknown: a timeout (408), throttling (429) or a server error (5xx). */
internal fun AdyenReply.Answered.outcomeUnknown(): Boolean = code in RETRYABLE || code >= HTTP_SERVER_ERROR

/** Request timeout and rate limit, after which a request is sent again with the same idempotency key. */
private val RETRYABLE = setOf(408, 429)

private const val HTTP_SERVER_ERROR = 500

internal fun ApplicationInfo.checkoutInfo(): com.adyen.model.checkout.ApplicationInfo =
    JSON.getMapper().convertValue(this, com.adyen.model.checkout.ApplicationInfo::class.java)
