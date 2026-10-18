package app.minimpos.terminal.client

import app.minimpos.terminal.parse.AdditionalResponseParser
import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import com.adyen.model.checkout.JSON
import com.adyen.model.nexo.AdminRequest
import com.adyen.model.nexo.BarcodeType
import com.adyen.model.nexo.DeviceType
import com.adyen.model.nexo.DisplayOutput
import com.adyen.model.nexo.DisplayRequest
import com.adyen.model.nexo.InfoQualifyType
import com.adyen.model.nexo.Input
import com.adyen.model.nexo.InputCommandType
import com.adyen.model.nexo.InputData
import com.adyen.model.nexo.InputRequest
import com.adyen.model.nexo.MessageCategoryType
import com.adyen.model.nexo.MessageClassType
import com.adyen.model.nexo.MessageReference
import com.adyen.model.nexo.OutputBarcode
import com.adyen.model.nexo.OutputContent
import com.adyen.model.nexo.OutputFormatType
import com.adyen.model.nexo.OutputText
import com.adyen.model.nexo.PredefinedContent
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.TransactionStatusRequest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URLEncoder
import kotlin.io.encoding.Base64

private const val DEFAULT_WAITING_TIMEOUT_MS = 15_000
private const val DEFAULT_INPUT_TIMEOUT_SECONDS = 60
private const val DEFAULT_QR_DISPLAY_SECONDS = 10
private const val MAX_RATING = 10

/**
 * One line item on a terminal virtual receipt (`DisplayRequest` with `OutputFormat=XHTML`).
 *
 * @property count Quantity of the item.
 * @property description Item name.
 * @property currency Currency symbol or code.
 * @property amountMinor Line gross amount in minor units.
 */
data class VirtualReceiptLine(
    val count: Int,
    val description: String,
    val currency: String,
    val amountMinor: Long,
)

/**
 * Virtual receipt shown on a customer-facing terminal screen before payment.
 *
 * @property header Heading above the item list.
 * @property lines Line items in cart order.
 * @property subtotalMinor Optional subtotal before tax in minor units.
 * @property taxMinor Optional total tax in minor units.
 * @property totalMinor Final total in minor units.
 * @property currency Currency symbol or ISO 4217 code.
 * @property totalLabel Label for the total line.
 */
data class VirtualReceipt(
    val header: String,
    val lines: List<VirtualReceiptLine>,
    val subtotalMinor: Long? = null,
    val taxMinor: Long? = null,
    val totalMinor: Long,
    val currency: String,
    val totalLabel: String = "Total",
) {
    /**
     * Renders the Adyen virtual-receipt XML document for terminal model [poiId], with amounts in major units of
     * [fractionDigits] decimals (the currency's Adyen decimals).
     */
    fun toXml(
        poiId: String = "",
        fractionDigits: Int,
    ): String {
        fun major(minor: Long) = BigDecimal.valueOf(minor, fractionDigits).toPlainString()
        val screen = if (poiId.startsWith("M400-", ignoreCase = true)) "virtual_receipt02.xslt" else "virtual-receipt.xslt"
        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<screen name="$screen"><receipt>""")
            append("<list-header>${escapeXml(header)}</list-header><lines>")
            lines.forEach { line ->
                append("<lineitem>")
                append("<count>${line.count}</count>")
                append("<description>${escapeXml(line.description)}</description>")
                append("<amount><currency>${escapeXml(line.currency)}</currency><value>${major(line.amountMinor)}</value></amount>")
                append("</lineitem>")
            }
            append("</lines>")
            subtotalMinor?.let {
                append("<subtotal><description>Subtotal</description>")
                append("<amount><currency>${escapeXml(currency)}</currency><value>${major(it)}</value></amount></subtotal>")
            }
            taxMinor?.let {
                append("<tax><taxtotal><description>Tax</description>")
                append("<amount><currency>${escapeXml(currency)}</currency><value>${major(it)}</value></amount></taxtotal></tax>")
            }
            append("<total><description>${escapeXml(totalLabel)}</description>")
            append("<amount><currency>${escapeXml(currency)}</currency><value>${major(totalMinor)}</value></amount></total>")
            append("</receipt></screen>")
        }
    }

    private fun escapeXml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}

/**
 * Configuration for chaining shopper-facing terminal display and input requests around a payment in one session.
 *
 * @property sessionId Positive identifier for the `AdminRequest` waiting-screen session.
 * @property virtualReceipt Basket preview shown on the terminal before payment; null skips it.
 * @property confirmationPrompt Title and body asking the shopper to confirm the basket before payment; null skips it.
 * @property confirmLabel Confirm button label for [confirmationPrompt].
 * @property cancelLabel Cancel button label for [confirmationPrompt].
 * @property emailPrompt Prompt asking the shopper to enter their email on the terminal before payment; null skips it.
 * @property qrReceipt Content of a QR code shown on the terminal after approval; null skips it.
 * @property qrHeader Optional header text shown above [qrReceipt].
 * @property ratingPrompt Title and optional subtitle asking the shopper for a 0–10 rating after approval; null skips it.
 */
data class ShopperSessionFlow(
    val sessionId: Long = 1L,
    val virtualReceipt: VirtualReceipt? = null,
    val confirmationPrompt: Pair<String, String>? = null,
    val confirmLabel: String = "Confirm",
    val cancelLabel: String = "Cancel",
    val emailPrompt: String? = null,
    val qrReceipt: String? = null,
    val qrHeader: String? = null,
    val ratingPrompt: Pair<String, String?>? = null,
) {
    init {
        require(sessionId > 0) { "Invalid shopper session id" }
    }
}

/** Outcome of a terminal `InputRequest`. */
private sealed interface InputOutcome<out T> {
    data class Entered<out T>(
        val value: T,
    ) : InputOutcome<T>

    data object Cancelled : InputOutcome<Nothing>

    data class Failed(
        val fault: Fault,
    ) : InputOutcome<Nothing>
}

/** Starts or ends a multi-request terminal waiting-screen session (`AdminRequest`). */
private suspend fun TerminalClient.shopperSession(
    sessionId: Long,
    begin: Boolean,
): Boolean {
    val payload =
        buildMap<String, Any> {
            put("Session", mapOf("Id" to sessionId, "Type" to if (begin) "Begin" else "End"))
            if (begin) {
                put(
                    "Operation",
                    listOf(
                        mapOf(
                            "Type" to "InputRequest",
                            "Variant" to "WaitingScreen",
                            "UserInterfaceData" to mapOf("WaitingScreenTimeoutMs" to DEFAULT_WAITING_TIMEOUT_MS),
                        ),
                        mapOf(
                            "Type" to "DisplayRequest",
                            "Variant" to "WaitingScreen",
                            "UserInterfaceData" to mapOf("WaitingScreenTimeoutMs" to DEFAULT_WAITING_TIMEOUT_MS),
                        ),
                    ),
                )
            }
        }
    val encoded = Base64.encode(JSON.getMapper().writeValueAsString(payload).toByteArray(Charsets.UTF_8))
    val delivery =
        sendShortMessage(MessageClassType.SERVICE, MessageCategoryType.ADMIN) {
            adminRequest = AdminRequest().apply { serviceIdentification = encoded }
        }
    val response = (readTerminalAnswer(delivery) { it.adminResponse?.response } as? TerminalAnswer.Read)?.body
    return response?.result == ResultType.SUCCESS
}

/** Displays a [VirtualReceipt] (`OutputFormat=XHTML`) on the customer display. */
private suspend fun TerminalClient.displayVirtualReceipt(receipt: VirtualReceipt): Boolean {
    val xmlBytes = receipt.toXml(identity.poiId, money.fractionDigits(receipt.currency)).toByteArray(Charsets.UTF_8)
    val output =
        DisplayOutput().apply {
            device = DeviceType.CUSTOMER_DISPLAY
            infoQualify = InfoQualifyType.DISPLAY
            outputContent =
                OutputContent().apply {
                    outputFormat = OutputFormatType.XHTML
                    outputXHTML = xmlBytes
                }
        }
    return sendDisplay(listOf(output))
}

/** Displays a QR code (`OutputFormat=BarCode`) with optional [header] text on the customer display. */
private suspend fun TerminalClient.displayQrCode(
    qrData: String,
    header: String?,
): Boolean {
    val outputs =
        buildList {
            if (!header.isNullOrBlank()) add(textDisplayOutput(header))
            add(
                DisplayOutput().apply {
                    device = DeviceType.CUSTOMER_DISPLAY
                    infoQualify = InfoQualifyType.DISPLAY
                    minimumDisplayTime = DEFAULT_QR_DISPLAY_SECONDS
                    setResponseRequiredFlag(true)
                    outputContent =
                        OutputContent().apply {
                            outputFormat = OutputFormatType.BAR_CODE
                            outputBarcode =
                                OutputBarcode().apply {
                                    barcodeType = BarcodeType.QRCODE
                                    barcodeValue =
                                        if (qrData.startsWith("http://") || qrData.startsWith("https://")) {
                                            qrData
                                        } else {
                                            URLEncoder.encode(qrData, "UTF-8")
                                        }
                                }
                        }
                },
            )
        }
    return sendDisplay(outputs)
}

/** Asks the shopper for confirmation (`GetConfirmation`) on the terminal display. */
private suspend fun TerminalClient.requestConfirmation(
    title: String,
    body: String,
    cancelLabel: String,
    confirmLabel: String,
): InputOutcome<Boolean> {
    val delivery =
        inputMessage(
            referenceId = "GetConfirmation",
            texts = listOf(title, body, cancelLabel, confirmLabel),
            command = InputCommandType.GET_CONFIRMATION,
            timeoutSeconds = DEFAULT_INPUT_TIMEOUT_SECONDS,
        )
    return parseInputDelivery(delivery) { it.isConfirmedFlag == true }
}

/** Asks the shopper to enter text (`GetText` / `TextString`), such as an email address, on the terminal display. */
private suspend fun TerminalClient.requestText(prompt: String): InputOutcome<String> {
    val delivery =
        inputMessage(
            referenceId = "GetText",
            texts = listOf(prompt),
            command = InputCommandType.TEXT_STRING,
            timeoutSeconds = DEFAULT_INPUT_TIMEOUT_SECONDS,
            defaultInput = "name@domain.com",
        )
    return parseInputDelivery(delivery) { it.textInput?.trim()?.takeIf(String::isNotEmpty) }
}

/** Asks the shopper for a 0–10 rating (`GetRating` / `DigitString`) on the terminal display. */
private suspend fun TerminalClient.requestRating(
    title: String,
    subtitle: String?,
): InputOutcome<Int> {
    val delivery =
        inputMessage(
            referenceId = "GetRating",
            texts = if (subtitle.isNullOrBlank()) listOf(title) else listOf(title, subtitle),
            command = InputCommandType.DIGIT_STRING,
            timeoutSeconds = DEFAULT_INPUT_TIMEOUT_SECONDS,
        )
    return parseInputDelivery(delivery) {
        it.digitInput
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { rating -> rating in 0..MAX_RATING }
    }
}

private suspend fun TerminalClient.sendDisplay(outputs: List<DisplayOutput>): Boolean {
    val delivery =
        sendShortMessage(MessageClassType.DEVICE, MessageCategoryType.DISPLAY) {
            displayRequest = DisplayRequest().apply { displayOutput += outputs }
        }
    val results = (readTerminalAnswer(delivery) { it.displayResponse?.outputResult } as? TerminalAnswer.Read)?.body
    return !results.isNullOrEmpty() && results.all { it.response?.result == ResultType.SUCCESS }
}

private fun textDisplayOutput(value: String): DisplayOutput =
    DisplayOutput().apply {
        device = DeviceType.CUSTOMER_DISPLAY
        infoQualify = InfoQualifyType.DISPLAY
        outputContent =
            OutputContent().apply {
                outputFormat = OutputFormatType.TEXT
                outputText = listOf(OutputText().apply { text = value })
            }
    }

private suspend fun TerminalClient.inputMessage(
    referenceId: String,
    texts: List<String>,
    command: InputCommandType,
    timeoutSeconds: Int,
    defaultInput: String? = null,
): Delivery =
    sendShortMessage(MessageClassType.DEVICE, MessageCategoryType.INPUT) {
        inputRequest =
            InputRequest().apply {
                displayOutput = inputDisplayOutput(referenceId, texts)
                inputData = inputRequestData(command, timeoutSeconds, defaultInput)
            }
    }

private fun inputDisplayOutput(
    referenceId: String,
    texts: List<String>,
): DisplayOutput =
    DisplayOutput().apply {
        device = DeviceType.CUSTOMER_DISPLAY
        infoQualify = InfoQualifyType.DISPLAY
        outputContent =
            OutputContent().apply {
                outputFormat = OutputFormatType.TEXT
                predefinedContent = PredefinedContent().apply { this.referenceID = referenceId }
                outputText = texts.map { line -> OutputText().apply { text = line } }
            }
    }

private fun inputRequestData(
    command: InputCommandType,
    timeoutSeconds: Int,
    defaultInput: String?,
): InputData =
    InputData().apply {
        device = DeviceType.CUSTOMER_INPUT
        infoQualify = InfoQualifyType.INPUT
        inputCommand = command
        maxInputTime = BigInteger.valueOf(timeoutSeconds.toLong())
        defaultInputString = defaultInput
    }

private inline fun <T : Any> parseInputDelivery(
    delivery: Delivery,
    extract: (Input) -> T?,
): InputOutcome<T> {
    val inputResult =
        when (val answer = readTerminalAnswer(delivery) { it.inputResponse?.inputResult }) {
            is TerminalAnswer.Unread -> return InputOutcome.Failed(answer.fault)
            is TerminalAnswer.Read -> answer.body
        }
    val response = inputResult.response
    if (response?.result == ResultType.SUCCESS) {
        val input = inputResult.input ?: return InputOutcome.Failed(Fault.UnreadableReply())
        return extract(input)?.let { InputOutcome.Entered(it) } ?: InputOutcome.Cancelled
    }
    if (Decline.of(succeeded = false, errorCondition = response?.errorCondition?.value(), refusalReason = null)?.cancelled == true) {
        return InputOutcome.Cancelled
    }
    val message = AdditionalResponseParser.parse(response?.additionalResponse)[NexoVocabulary.MESSAGE]
    return InputOutcome.Failed(Fault.TerminalRejected(ExternalText.of(message)))
}

/** Queries the two-step Adyen Giving donation status (`SaleID=AdyenGiving`) for [givingServiceId], returning the donated amount. */
private suspend fun TerminalClient.givingDonation(givingServiceId: String): Long? {
    val delivery =
        sendShortMessage(MessageClassType.SERVICE, MessageCategoryType.TRANSACTION_STATUS) {
            transactionStatusRequest =
                TransactionStatusRequest().apply {
                    setReceiptReprintFlag(true)
                    messageReference =
                        MessageReference().apply {
                            messageCategory = MessageCategoryType.PAYMENT
                            saleID = TerminalClient.GIVING_SALE_ID
                            serviceID = givingServiceId
                        }
                }
        }
    val repeated =
        (
            readTerminalAnswer(delivery) {
                it.transactionStatusResponse
                    ?.repeatedMessageResponse
                    ?.repeatedResponseMessageBody
                    ?.paymentResponse
            } as? TerminalAnswer.Read
        )?.body
    return repeated
        ?.takeIf { it.response?.result == ResultType.SUCCESS }
        ?.paymentResult
        ?.amountsResp
        ?.let { money.minor(it.authorizedAmount, it.currency) }
}

internal suspend fun TerminalClient.executeShopperFlow(
    params: PaymentParams,
    serviceId: String,
    payCore: suspend (PaymentParams) -> TransactionOutcome,
): TransactionOutcome {
    val flow = params.shopperFlow ?: return attachGivingDonation(payCore(params))
    shopperSession(flow.sessionId, begin = true)
    return try {
        attachGivingDonation(runShopperFlowSteps(flow, params, serviceId, payCore))
    } finally {
        withContext(NonCancellable) {
            shopperSession(flow.sessionId, begin = false)
        }
    }
}

private suspend fun TerminalClient.attachGivingDonation(outcome: TransactionOutcome): TransactionOutcome {
    if (outcome !is TransactionOutcome.Completed || !outcome.details.success) return outcome
    val givingId = outcome.details.givingServiceId ?: return outcome
    val donation = givingDonation(givingId) ?: return outcome
    return outcome.copy(details = outcome.details.copy(donationMinor = donation))
}

private suspend fun TerminalClient.confirmBasket(
    flow: ShopperSessionFlow,
    params: PaymentParams,
    serviceId: String,
): TransactionOutcome? {
    val prompt = flow.confirmationPrompt ?: return null
    return when (
        val confirmation =
            requestConfirmation(
                title = prompt.first,
                body = prompt.second,
                cancelLabel = flow.cancelLabel,
                confirmLabel = flow.confirmLabel,
            )
    ) {
        is InputOutcome.Entered -> {
            TransactionOutcome.Completed(serviceId, shopperCancelledDetails(params.merchantReference)).takeUnless { confirmation.value }
        }

        InputOutcome.Cancelled -> {
            TransactionOutcome.Completed(serviceId, shopperCancelledDetails(params.merchantReference))
        }

        is InputOutcome.Failed -> {
            TransactionOutcome.NotProcessed(serviceId, confirmation.fault)
        }
    }
}

private suspend fun TerminalClient.runShopperFlowSteps(
    flow: ShopperSessionFlow,
    params: PaymentParams,
    serviceId: String,
    payCore: suspend (PaymentParams) -> TransactionOutcome,
): TransactionOutcome {
    flow.virtualReceipt?.let { displayVirtualReceipt(it) }
    confirmBasket(flow, params, serviceId)?.let { return it }
    val collectedEmail =
        if (flow.emailPrompt != null && params.shopperEmail == null) {
            (requestText(flow.emailPrompt) as? InputOutcome.Entered)?.value
        } else {
            null
        }
    val effectiveParams = params.copy(shopperEmail = params.shopperEmail ?: collectedEmail)
    val outcome = payCore(effectiveParams)
    return if (outcome is TransactionOutcome.Completed && outcome.details.success) {
        flow.qrReceipt?.let { displayQrCode(it, header = flow.qrHeader) }
        val rating =
            flow.ratingPrompt?.let { (title, subtitle) ->
                (requestRating(title, subtitle) as? InputOutcome.Entered)?.value
            }
        outcome.copy(
            details =
                outcome.details.copy(
                    collectedEmail = collectedEmail,
                    shopperRating = rating,
                ),
        )
    } else {
        outcome
    }
}

private fun shopperCancelledDetails(merchantReference: String): TransactionDetails =
    TransactionDetails(
        success = false,
        errorCondition = "Cancel",
        message = null,
        refusalReason = null,
        poiTransactionId = null,
        poiTimestamp = null,
        pspReference = null,
        merchantReference = merchantReference,
        amountMinor = null,
        currency = null,
        paymentBrand = null,
        maskedPan = null,
        entryMode = null,
        approvalCode = null,
        customerReceipt = emptyList(),
        cashierReceipt = emptyList(),
        signatureRequired = false,
        additionalData = emptyMap(),
        tokenization = null,
    )
