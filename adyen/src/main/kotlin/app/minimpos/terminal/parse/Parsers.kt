package app.minimpos.terminal.parse

import com.adyen.model.nexo.CharacterStyleType
import com.adyen.model.nexo.DocumentQualifierType
import com.adyen.model.nexo.PaymentReceipt
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLDecoder
import java.util.Base64

/**
 * A decoded line of Adyen receipt data: `key=...&name=...&value=...`. Each part is null when the line leaves it out.
 *
 * @property key What the line is, for software (e.g. `totalAmount`, `approved`, or `filler` for an empty line).
 * @property name The label to print, e.g. `TOTAL`.
 * @property value The value to print next to the label, e.g. `EUR 12.50`.
 * @property bold True when the terminal asks for the line in bold.
 */
data class ReceiptField(
    val key: String?,
    val name: String?,
    val value: String?,
    val bold: Boolean,
)

/**
 * Reads the card receipts in a payment or reversal response (`PaymentReceipt`), which the terminal sends so the POS can
 * print them itself.
 */
object ReceiptParser {
    /**
     * The lines of the first receipt of the given kind, in print order.
     *
     * @param receipts The response's receipts; null is treated as none.
     * @param documentQualifier Which copy to read, e.g. the customer or the cashier receipt.
     * @return The decoded lines; empty if there is no such receipt.
     */
    fun fields(
        receipts: List<PaymentReceipt>?,
        documentQualifier: DocumentQualifierType,
    ): List<ReceiptField> {
        val receipt = receipts?.firstOrNull { it.documentQualifier == documentQualifier } ?: return emptyList()
        return receipt.outputContent?.outputText.orEmpty().map { text ->
            val values = FormEncoding.decode(text.text.orEmpty())
            ReceiptField(
                key = values["key"],
                name = values["name"],
                value = values["value"],
                bold = text.characterStyle == CharacterStyleType.BOLD,
            )
        }
    }

    /** True when any of [receipts] has `RequiredSignatureFlag` set: the shopper must sign the merchant copy. */
    fun signatureRequired(receipts: List<PaymentReceipt>?): Boolean = receipts.orEmpty().any { it.isRequiredSignatureFlag }
}

/** Decodes the URL form encoding (`a=1&b=two%20words`) the Terminal API uses in receipt lines and some responses. */
object FormEncoding {
    /**
     * Splits [text] into its key/value pairs and URL-decodes them. A pair without `=` maps to an empty value, a
     * repeated key keeps its last value, and an invalid percent escape leaves that part undecoded; it never throws.
     */
    fun decode(text: String): Map<String, String> =
        text
            .split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val separator = pair.indexOf('=')
                if (separator < 0) {
                    urlDecode(pair) to ""
                } else {
                    urlDecode(pair.substring(0, separator)) to urlDecode(pair.substring(separator + 1))
                }
            }

    private fun urlDecode(text: String): String = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)
}

/**
 * Terminal API `AdditionalResponse` comes back in the same format as the request's `SaleToAcquirerData`: form-encoded
 * key/value pairs, or Base64-encoded JSON (optionally with an `additionalData` object). Both are flattened to a map.
 */
object AdditionalResponseParser {
    /**
     * Flattens an `AdditionalResponse`. JSON objects nest with dotted keys (`tokenization.storedPaymentMethodId`), the
     * contents of `additionalData` move to the top level, arrays are kept as JSON text and nulls are dropped.
     *
     * @param raw The `AdditionalResponse` as received; null or blank gives an empty map.
     * @return The values by key, in the order received; never throws.
     */
    fun parse(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val trimmed = raw.trim()
        decodeBase64Json(trimmed)?.let { return flatten(it) }
        return FormEncoding.decode(trimmed)
    }

    private fun decodeBase64Json(text: String): JsonObject? {
        // Base64 can end in '=' padding but never contains '&'.
        if (text.contains('=') && text.contains('&')) return null
        val bytes = runCatching { Base64.getDecoder().decode(text) }.getOrNull() ?: return null
        val json = bytes.toString(Charsets.UTF_8).trim()
        if (!json.startsWith("{")) return null
        return runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
    }

    private fun flatten(json: JsonObject): Map<String, String> {
        val out = linkedMapOf<String, String>()

        fun visit(
            prefix: String,
            element: JsonElement,
        ) {
            when {
                element.isJsonObject -> {
                    element.asJsonObject.entrySet().forEach { (k, v) ->
                        visit(if (prefix.isEmpty()) k else "$prefix.$k", v)
                    }
                }

                element.isJsonPrimitive -> {
                    out[prefix] = element.asString
                }

                element.isJsonNull -> {}

                else -> {
                    out[prefix] = element.toString()
                }
            }
        }
        json.entrySet().forEach { (key, value) ->
            if (key == "additionalData" && value.isJsonObject) {
                value.asJsonObject.entrySet().forEach { (k, v) -> visit(k, v) }
            } else {
                visit(key, value)
            }
        }
        return out
    }
}
