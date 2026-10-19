package app.minimpos.terminal.transport

/**
 * Selected, already decoded remote [messages] and support diagnostics, never a dump of the payment response.
 * Null/blank fields are omitted and repeated messages appear once. Protocol keys stay untranslated; the remote
 * values are not reworded. A valid W3C version-00 [traceParent] also exposes its nonzero 32-character `traceId`;
 * malformed parents remain visible without claiming a usable ID. No fields means null.
 * [requestId] is retained only when supplied by the response, never derived from a callback URL.
 */
internal fun responseText(
    messages: List<String?>,
    errorCode: String?,
    traceParent: String?,
    requestId: String? = null,
): ExternalText? {
    val lines = messages.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.distinct().toMutableList()
    errorCode?.trim()?.takeIf(String::isNotEmpty)?.let { lines += "errorCode=$it" }
    requestId?.trim()?.takeIf(String::isNotEmpty)?.let { lines += "requestId=$it" }
    traceParent?.trim()?.takeIf(String::isNotEmpty)?.let { parent ->
        lines += "traceparent=$parent"
        val match = TRACE_PARENT.matchEntire(parent)
        if (match != null) {
            val (traceId, spanId) = match.destructured
            if (traceId.any { it != '0' } && spanId.any { it != '0' }) lines += "traceId=$traceId"
        }
    }
    return ExternalText.of(lines.joinToString("\n"))
}

private val TRACE_PARENT = Regex("00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}")
