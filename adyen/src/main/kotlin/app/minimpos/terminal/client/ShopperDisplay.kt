package app.minimpos.terminal.client

import app.minimpos.terminal.parse.AdditionalResponseParser
import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.base64Json
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.math.BigInteger
import java.net.URLEncoder

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
 * @property currency ISO 4217 code; its Adyen decimals convert every amount, the lines' too.
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
     * Renders the Adyen virtual-receipt XML document for terminal model [poiId], with every amount in the major units of
     * [currency] that [money] converts it to.
     */
    internal fun toXml(
        poiId: String = "",
        money: TerminalMoney,
    ): String {
        fun major(minor: Long) = money.major(minor, currency).toPlainString()
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
    val delivery =
        sendShortMessage(MessageClassType.SERVICE, MessageCategoryType.ADMIN) {
            adminRequest = AdminRequest().apply { serviceIdentification = base64Json(payload) }
        }
    val response = (readTerminalAnswer(delivery) { it.adminResponse?.response } as? TerminalAnswer.Read)?.body
    return response?.result == ResultType.SUCCESS
}

/** Displays a [VirtualReceipt] (`OutputFormat=XHTML`) on the customer display. */
private suspend fun TerminalClient.displayVirtualReceipt(receipt: VirtualReceipt): Boolean {
    val xmlBytes = receipt.toXml(identity.poiId, money).toByteArray(Charsets.UTF_8)
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

/**
 * Follows the two-step Adyen Giving donation (`SaleID=AdyenGiving`) for [givingServiceId], returning the donated amount;
 * null when the shopper did not donate or the donation could not be confirmed within [TerminalClient.recovery].
 *
 * The payment answer arrives while the terminal still shows the donation choice (TEST: `InProgress` 1 s after the
 * payment, the completed donation 12 s later), so checks wait [RecoveryPolicy.intervalMillis] and continue while the
 * donation is in progress.
 */
private suspend fun TerminalClient.givingDonation(givingServiceId: String): Long? {
    var unanswered = 0
    var inProgress = 0
    while (unanswered < recovery.attempts && inProgress < recovery.maxInProgressChecks) {
        delay(recovery.intervalMillis)
        when (val check = givingStatus(givingServiceId)) {
            is GivingCheck.Settled -> return check.donationMinor
            GivingCheck.InProgress -> inProgress++
            GivingCheck.NoAnswer -> unanswered++
        }
    }
    return null
}

/** What one Adyen Giving donation status check found. */
private sealed interface GivingCheck {
    /** The donation finished; [donationMinor] is null when nothing was donated. */
    data class Settled(
        val donationMinor: Long?,
    ) : GivingCheck

    /** The shopper is still choosing or paying the donation. */
    data object InProgress : GivingCheck

    /** No usable status came back. */
    data object NoAnswer : GivingCheck
}

private suspend fun TerminalClient.givingStatus(givingServiceId: String): GivingCheck {
    val delivery =
        sendShortMessage(MessageClassType.SERVICE, MessageCategoryType.TRANSACTION_STATUS) {
            transactionStatusRequest =
                TransactionStatusRequest().apply {
                    messageReference =
                        MessageReference().apply {
                            messageCategory = MessageCategoryType.PAYMENT
                            saleID = TerminalClient.GIVING_SALE_ID
                            serviceID = givingServiceId
                        }
                }
        }
    val status =
        when (val answer = readTerminalAnswer(delivery) { it.transactionStatusResponse }) {
            is TerminalAnswer.Unread -> return GivingCheck.NoAnswer
            is TerminalAnswer.Read -> answer.body
        }
    return when {
        status.response?.result == ResultType.SUCCESS -> {
            val donation =
                status.repeatedMessageResponse
                    ?.repeatedResponseMessageBody
                    ?.paymentResponse ?: return GivingCheck.NoAnswer
            GivingCheck.Settled(
                donation
                    .takeIf { it.response?.result == ResultType.SUCCESS }
                    ?.paymentResult
                    ?.amountsResp
                    ?.let { money.minor(it.authorizedAmount, it.currency) },
            )
        }

        Decline.statusInProgress(status.response) -> {
            GivingCheck.InProgress
        }

        Decline.statusNotFound(status.response) -> {
            GivingCheck.Settled(null)
        }

        else -> {
            GivingCheck.NoAnswer
        }
    }
}

internal suspend fun TerminalClient.executeShopperFlow(
    params: PaymentParams,
    payCore: suspend (PaymentParams) -> TransactionOutcome,
): TransactionOutcome {
    val flow = params.shopperFlow ?: return attachGivingDonation(payCore(params))
    shopperSession(flow.sessionId, begin = true)
    return try {
        attachGivingDonation(runShopperFlowSteps(flow, params, payCore))
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
            TransactionOutcome.Completed(shopperCancelledDetails(params.merchantReference)).takeUnless { confirmation.value }
        }

        InputOutcome.Cancelled -> {
            TransactionOutcome.Completed(shopperCancelledDetails(params.merchantReference))
        }

        is InputOutcome.Failed -> {
            TransactionOutcome.NotProcessed(confirmation.fault)
        }
    }
}

private suspend fun TerminalClient.runShopperFlowSteps(
    flow: ShopperSessionFlow,
    params: PaymentParams,
    payCore: suspend (PaymentParams) -> TransactionOutcome,
): TransactionOutcome {
    flow.virtualReceipt?.let { displayVirtualReceipt(it) }
    confirmBasket(flow, params)?.let { return it }
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
