package app.minimpos.terminal.simulator

import app.minimpos.terminal.client.TerminalClient
import app.minimpos.terminal.client.TerminalCurrencyTotals
import com.adyen.model.nexo.DeviceType
import com.adyen.model.nexo.InfoQualifyType
import com.adyen.model.nexo.Input
import com.adyen.model.nexo.InputCommandType
import com.adyen.model.nexo.InputRequest
import com.adyen.model.nexo.InputResponse
import com.adyen.model.nexo.InputResult
import com.adyen.model.nexo.PaymentInstrumentType
import com.adyen.model.nexo.PaymentTotals
import com.adyen.model.nexo.Response
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.SaleData
import com.adyen.model.nexo.TransactionTotals
import com.adyen.model.nexo.TransactionType
import com.google.gson.JsonParser
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.Base64

private const val PERCENT = 100L

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
)

internal fun simulatedTipAmount(
    saleData: SaleData?,
    requestedAmount: BigDecimal,
): BigDecimal? {
    val tenders =
        saleData
            ?.saleToAcquirerData
            ?.tenderOption
            ?.split(',')
            .orEmpty()
    if (TerminalClient.ASK_GRATUITY !in tenders) return null
    val scale = requestedAmount.scale().coerceAtLeast(0)
    val poiData = saleData?.saleToPOIData
    if (!poiData.isNullOrBlank()) {
        val tip =
            runCatching {
                val decoded = String(Base64.getDecoder().decode(poiData), Charsets.UTF_8)
                val tipOptions =
                    JsonParser
                        .parseString(decoded)
                        .asJsonObject
                        .getAsJsonArray("Operation")
                        .first()
                        .asJsonObject
                        .getAsJsonObject("TipOptions")
                val base = tipOptions.get("AmountToTipOn")?.asBigDecimal ?: requestedAmount
                val suggestions = tipOptions.getAsJsonObject("TipSuggestions")
                when {
                    suggestions.has("Amount1") -> {
                        BigDecimal.valueOf(suggestions.get("Amount1").asLong, scale)
                    }

                    suggestions.has("Percentage1") -> {
                        base
                            .multiply(BigDecimal.valueOf(suggestions.get("Percentage1").asLong))
                            .divide(BigDecimal.valueOf(PERCENT), scale, RoundingMode.HALF_UP)
                    }

                    else -> {
                        null
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
            } else if (command == InputCommandType.DIGIT_STRING) {
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

internal fun buildSimulatedTransactionTotals(entries: List<TerminalCurrencyTotals>): List<TransactionTotals> =
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
