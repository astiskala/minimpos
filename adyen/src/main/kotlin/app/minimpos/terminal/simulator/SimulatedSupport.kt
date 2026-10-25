package app.minimpos.terminal.simulator

import app.minimpos.terminal.client.CardEntryOverride
import app.minimpos.terminal.client.NexoVocabulary
import app.minimpos.terminal.client.OfflineProcessingMode
import app.minimpos.terminal.client.TerminalClient
import com.adyen.model.nexo.DeviceType
import com.adyen.model.nexo.DiagnosisResponse
import com.adyen.model.nexo.DisplayRequest
import com.adyen.model.nexo.DisplayResponse
import com.adyen.model.nexo.ErrorConditionType
import com.adyen.model.nexo.ForceEntryModeType
import com.adyen.model.nexo.GlobalStatusType
import com.adyen.model.nexo.InfoQualifyType
import com.adyen.model.nexo.Input
import com.adyen.model.nexo.InputCommandType
import com.adyen.model.nexo.InputRequest
import com.adyen.model.nexo.InputResponse
import com.adyen.model.nexo.InputResult
import com.adyen.model.nexo.OutputResult
import com.adyen.model.nexo.POIStatus
import com.adyen.model.nexo.PaymentInstrumentType
import com.adyen.model.nexo.PaymentRequest
import com.adyen.model.nexo.PaymentTotals
import com.adyen.model.nexo.PrinterStatusType
import com.adyen.model.nexo.Response
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.SaleData
import com.adyen.model.nexo.TransactionTotals
import com.adyen.model.nexo.TransactionType
import com.adyen.model.terminal.SaleToAcquirerData
import com.google.gson.JsonParser
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.io.encoding.Base64
import kotlin.random.Random

private const val PERCENT = 100L
private const val ADYEN_PARTIAL_TRIGGER_SUFFIX = "139"
private const val DEFAULT_PARTIAL_RATIO = 50L

/** The tender options a request carries (`SaleToAcquirerData.tenderOption`, comma-separated). */
private fun SaleData?.tenders(): List<String> =
    this
        ?.saleToAcquirerData
        ?.tenderOption
        ?.split(',')
        .orEmpty()

internal data class SimulatedCard(
    val brand: String,
    val variant: String,
    val label: String,
    val maskedPan: String,
    val aid: String,
    val alias: String,
)

internal data class SimulatedPayment(
    val card: SimulatedCard,
    val psp: String,
    val tender: String,
    val authCode: String,
    val merchantReference: String,
    val amountText: String,
    val approved: Boolean,
    val preAuthorisation: Boolean,
    val wallet: Boolean = false,
    val tipAmount: BigDecimal? = null,
    val surchargeAmount: BigDecimal = BigDecimal.ZERO,
    val partial: Boolean = false,
    val givingServiceId: String? = null,
    val entryOverride: CardEntryOverride = CardEntryOverride.NORMAL,
    val offlineMode: OfflineProcessingMode = OfflineProcessingMode.ONLINE,
    val unconfirmedBatchCount: Int = 0,
) {
    val isOffline: Boolean get() = approved && offlineMode != OfflineProcessingMode.ONLINE
    val transactionId: String get() = if (isOffline) tender else "$tender.$psp"
}

internal fun simulatedCardEntryOverride(request: PaymentRequest): CardEntryOverride {
    val tenders = request.saleData.tenders()
    val forceModes =
        request.paymentTransaction
            ?.transactionConditions
            ?.forceEntryMode
            .orEmpty()
    return when {
        TerminalClient.MOTO in tenders -> CardEntryOverride.MOTO
        ForceEntryModeType.KEYED in forceModes -> CardEntryOverride.MKE
        else -> CardEntryOverride.NORMAL
    }
}

internal fun simulatedGivingServiceId(
    saleData: SaleData?,
    approved: Boolean,
    partial: Boolean,
    settings: SimulatorConfig,
): String? {
    if (!approved || partial || settings.givingDonationAmount <= BigDecimal.ZERO) return null
    return "GIVINGSIM1".takeIf { TerminalClient.ASK_GIVING in saleData.tenders() }
}

internal fun simulatedPartialAmount(
    saleData: SaleData?,
    requestedAmount: BigDecimal,
    settings: SimulatorConfig,
): BigDecimal? {
    if (TerminalClient.ALLOW_PARTIAL_AUTHORISATION !in saleData.tenders()) return null
    val scale = requestedAmount.scale().coerceAtLeast(0)
    val minorString = requestedAmount.movePointRight(scale).toBigInteger().toString()
    val ratio =
        when {
            settings.partialApprovalPercent in 1..<PERCENT.toInt() -> settings.partialApprovalPercent.toLong()
            minorString.endsWith(ADYEN_PARTIAL_TRIGGER_SUFFIX) -> DEFAULT_PARTIAL_RATIO
            else -> return null
        }
    val partial =
        requestedAmount
            .multiply(BigDecimal.valueOf(ratio))
            .divide(BigDecimal.valueOf(PERCENT), scale, RoundingMode.HALF_UP)
    return partial.takeIf { it > BigDecimal.ZERO && it < requestedAmount }
}

internal fun simulatedTipAmount(
    saleData: SaleData?,
    requestedAmount: BigDecimal,
): BigDecimal? {
    if (TerminalClient.ASK_GRATUITY !in saleData.tenders()) return null
    val scale = requestedAmount.scale().coerceAtLeast(0)
    val poiData = saleData?.saleToPOIData
    if (!poiData.isNullOrBlank()) {
        val tip =
            runCatching {
                val decoded = String(Base64.decode(poiData), Charsets.UTF_8)
                val tipOptions =
                    JsonParser
                        .parseString(decoded)
                        .asJsonObject
                        .getAsJsonArray(NexoVocabulary.OPERATION)
                        .first()
                        .asJsonObject
                        .getAsJsonObject(NexoVocabulary.TIP_OPTIONS)
                val base = tipOptions.get(NexoVocabulary.AMOUNT_TO_TIP_ON)?.asBigDecimal ?: requestedAmount
                val suggestions = tipOptions.getAsJsonObject(NexoVocabulary.TIP_SUGGESTIONS)
                when {
                    suggestions.has(NexoVocabulary.FIRST_TIP_AMOUNT) -> {
                        BigDecimal.valueOf(suggestions.get(NexoVocabulary.FIRST_TIP_AMOUNT).asLong, scale)
                    }

                    else -> {
                        base
                            .multiply(BigDecimal.valueOf(suggestions.get(NexoVocabulary.FIRST_TIP_PERCENTAGE).asLong))
                            .divide(BigDecimal.valueOf(PERCENT), scale, RoundingMode.HALF_UP)
                    }
                }
            }.getOrNull()
        if (tip != null) return tip
    }
    return requestedAmount
        .multiply(BigDecimal.TEN)
        .divide(BigDecimal.valueOf(PERCENT), scale, RoundingMode.HALF_UP)
}

internal fun simulateInputResponse(
    request: InputRequest,
    settings: SimulatorConfig,
): InputResponse {
    val command = request.inputData?.inputCommand
    val simulatedInput =
        Input().apply {
            inputCommand = command
            if (command == InputCommandType.GET_CONFIRMATION) {
                setConfirmedFlag(settings.shopperConfirmed)
            } else if (command == InputCommandType.TEXT_STRING) {
                textInput = settings.shopperEmailInput
            } else {
                digitInput = settings.shopperRating.toString()
            }
        }
    return InputResponse().apply {
        inputResult =
            InputResult().apply {
                device = DeviceType.CUSTOMER_INPUT
                infoQualify = InfoQualifyType.INPUT
                response = Response().apply { result = ResultType.SUCCESS }
                input = simulatedInput
            }
    }
}

internal fun simulateDiagnosisResponse(settings: SimulatorConfig): DiagnosisResponse =
    DiagnosisResponse().apply {
        response = Response().apply { result = ResultType.SUCCESS }
        poiStatus =
            POIStatus().apply {
                globalStatus = GlobalStatusType.OK
                setPEDOKFlag(true)
                printerStatus = if (settings.hasPrinter) PrinterStatusType.OK else null
            }
    }

internal fun simulateDisplayResponse(request: DisplayRequest): DisplayResponse =
    DisplayResponse().apply {
        outputResult +=
            request.displayOutput.orEmpty().map {
                OutputResult().apply {
                    device = it.device ?: DeviceType.CUSTOMER_DISPLAY
                    infoQualify = it.infoQualify ?: InfoQualifyType.DISPLAY
                    response = Response().apply { result = ResultType.SUCCESS }
                }
            }
    }

/**
 * The simulator's running totals of one currency in its reconciliation period, in the major units nexo carries.
 *
 * @property currency ISO 4217 code.
 * @property debitAmount Approved payments.
 * @property debitCount How many payments were approved.
 * @property creditAmount Accepted refunds and reversals.
 * @property creditCount How many refunds and reversals were accepted.
 */
internal data class SimulatedPeriodTotals(
    val currency: String,
    val debitAmount: BigDecimal = BigDecimal.ZERO,
    val debitCount: Int = 0,
    val creditAmount: BigDecimal = BigDecimal.ZERO,
    val creditCount: Int = 0,
)

internal fun buildSimulatedTransactionTotals(entries: List<SimulatedPeriodTotals>): List<TransactionTotals> =
    entries.sortedBy { it.currency }.map { item ->
        TransactionTotals().apply {
            paymentInstrumentType = PaymentInstrumentType.CARD
            paymentCurrency = item.currency
            if (item.debitCount > 0) {
                paymentTotals +=
                    PaymentTotals().apply {
                        transactionType = TransactionType.DEBIT
                        transactionCount = BigInteger.valueOf(item.debitCount.toLong())
                        transactionAmount = item.debitAmount
                    }
            }
            if (item.creditCount > 0) {
                paymentTotals +=
                    PaymentTotals().apply {
                        transactionType = TransactionType.CREDIT
                        transactionCount = BigInteger.valueOf(item.creditCount.toLong())
                        transactionAmount = item.creditAmount
                    }
            }
        }
    }

internal fun buildPaymentAdditional(
    payment: SimulatedPayment,
    acquirer: SaleToAcquirerData?,
    refusal: Pair<ErrorConditionType, String>?,
    random: Random,
    randomToken: () -> String,
): Map<String, String> {
    val additional =
        linkedMapOf(
            NexoVocabulary.MERCHANT_REFERENCE to payment.merchantReference,
            NexoVocabulary.PAYMENT_METHOD to payment.card.brand,
            NexoVocabulary.PAYMENT_METHOD_VARIANT to payment.card.variant,
            NexoVocabulary.CARD_SUMMARY to payment.card.maskedPan.takeLast(4),
            NexoVocabulary.POS_ENTRY_MODE to
                when {
                    payment.wallet -> "Scanned"
                    payment.entryOverride == CardEntryOverride.MOTO -> "MOTO"
                    payment.entryOverride == CardEntryOverride.MKE -> "MKE"
                    else -> "CLESS_CHIP"
                },
        )
    if (!payment.isOffline) {
        additional[NexoVocabulary.PSP_REFERENCE] = payment.psp
    }
    if (payment.card.variant.endsWith(VOUCHER_VARIANT_SUFFIX)) additional[NexoVocabulary.FUNDING_SOURCE] = PREPAID
    if (refusal == null) {
        if (payment.isOffline) {
            additional[NexoVocabulary.OFFLINE] = "true"
            additional[NexoVocabulary.UNCONFIRMED_BATCH_COUNT] = payment.unconfirmedBatchCount.coerceAtLeast(1).toString()
            if (payment.offlineMode == OfflineProcessingMode.OFFLINE_EMV) {
                additional[NexoVocabulary.OFFLINE_AUTH_CODE] = payment.authCode
            }
        } else {
            additional[NexoVocabulary.AUTH_CODE] = payment.authCode
        }
        if (payment.preAuthorisation) additional[NexoVocabulary.ADJUST_AUTHORISATION_DATA] = SimulatedModifications.blob(random)
        if (!payment.wallet) additional += simulatedTokenization(payment.card, acquirer, randomToken)
        payment.givingServiceId?.let {
            additional[NexoVocabulary.ADYEN_GIVING_SALE_ID] = TerminalClient.GIVING_SALE_ID
            additional[NexoVocabulary.ADYEN_GIVING_SERVICE_ID] = it
        }
    } else {
        additional[NexoVocabulary.REFUSAL_REASON] = refusal.second
    }
    return additional
}

internal fun simulatedRefusal(outcome: SimulatedOutcome?): Pair<ErrorConditionType, String> =
    when (outcome) {
        SimulatedOutcome.CANCEL -> {
            ErrorConditionType.CANCEL to "Cancelled by shopper"
        }

        null -> {
            ErrorConditionType.ABORTED to "Transaction aborted"
        }

        SimulatedOutcome.APPROVE,
        SimulatedOutcome.DECLINE,
        SimulatedOutcome.BUSY,
        SimulatedOutcome.TIMEOUT,
        SimulatedOutcome.RANDOM,
        -> {
            ErrorConditionType.REFUSAL to "Not enough balance"
        }
    }

private fun simulatedTokenization(
    card: SimulatedCard,
    acquirerData: SaleToAcquirerData?,
    randomToken: () -> String,
): Map<String, String> {
    val shopperReference = acquirerData?.shopperReference
    val model = acquirerData?.recurringProcessingModel
    if (shopperReference == null || model == null) return emptyMap()
    val token = randomToken()
    return buildMap {
        put(NexoVocabulary.ALIAS, card.alias)
        put(NexoVocabulary.RECURRING_DETAIL_REFERENCE, token)
        put(NexoVocabulary.RECURRING_SHOPPER_REFERENCE, shopperReference)
        put(NexoVocabulary.RECURRING_PROCESSING_MODEL, model.toString())
        put(NexoVocabulary.TOKENIZATION_SHOPPER_REFERENCE, shopperReference)
        put(NexoVocabulary.TOKENIZATION_STORED_PAYMENT_METHOD_ID, token)
        put(NexoVocabulary.TOKENIZATION_OPERATION_TYPE, "created")
        acquirerData.shopperEmail?.let { put(NexoVocabulary.SHOPPER_EMAIL, it) }
    }
}

/** Reads the actual wire variant; the simulated terminal exits guidance after every outcome. */
internal fun simulatedAudioGuidance(saleData: SaleData?): Boolean =
    saleData?.saleToPOIData?.let { encoded ->
        runCatching {
            JsonParser
                .parseString(String(Base64.decode(encoded), Charsets.UTF_8))
                .asJsonObject
                .getAsJsonArray(NexoVocabulary.OPERATION)
                .any { element ->
                    element.asJsonObject.get(NexoVocabulary.VARIANT)?.asString == NexoVocabulary.ENABLE_ACCESSIBILITY
                }
        }.getOrDefault(false)
    } == true

/** Brazilian voucher variants Adyen documents end with this, e.g. `elo_voucher_br`. */
private const val VOUCHER_VARIANT_SUFFIX = "_voucher_br"

/** The `fundingSource` Adyen documents for a Brazilian meal voucher. */
private const val PREPAID = "PREPAID"
