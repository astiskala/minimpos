package app.minimpos.terminal

import app.minimpos.terminal.client.PaymentParams
import app.minimpos.terminal.client.PosApplication
import app.minimpos.terminal.client.RecoveryPolicy
import app.minimpos.terminal.client.TerminalClient
import app.minimpos.terminal.client.TerminalIdentity
import app.minimpos.terminal.client.TerminalMoney
import app.minimpos.terminal.client.TerminalTipOptions
import app.minimpos.terminal.client.TerminalTotalsOutcome
import app.minimpos.terminal.client.TransactionOutcome
import app.minimpos.terminal.simulator.SimulatedOutcome
import app.minimpos.terminal.simulator.SimulatorConfig
import app.minimpos.terminal.simulator.TerminalSimulator
import com.adyen.model.nexo.AmountsResp
import com.adyen.model.nexo.PaymentResponse
import com.adyen.model.nexo.PaymentResult
import com.adyen.model.nexo.Response
import com.adyen.model.nexo.ResultType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.math.BigDecimal

class SurchargeTest {
    @Test
    fun `authorized totals already include tips fees and cashback using Adyen precision`() {
        val money = TerminalMoney(testCurrencyDigits)
        listOf("AUD" to "1.23", "JPY" to "123", "BHD" to "1.234", "ISK" to "1.23").forEach { (currency, fee) ->
            val details = checkNotNull(TerminalClient.mapPayment(response(currency, BigDecimal(fee)), money::minor))
            assertThat(details.amountMinor).isEqualTo(money.minor(BigDecimal("200"), currency))
            assertThat(details.surchargeMinor).isEqualTo(money.minor(BigDecimal(fee), currency))
            assertThat(details.tipMinor).isEqualTo(money.minor(BigDecimal("5"), currency))
            assertThat(details.cashBackMinor).isEqualTo(money.minor(BigDecimal("10"), currency))
        }
        listOf(null, BigDecimal.ZERO).forEach { fee ->
            val details = checkNotNull(TerminalClient.mapPayment(response("AUD", fee), money::minor))
            assertThat(details.surchargeMinor).isEqualTo(fee?.toLong())
        }
    }

    private fun response(
        code: String,
        fee: BigDecimal?,
    ) = PaymentResponse().apply {
        response = Response().apply { result = ResultType.SUCCESS }
        paymentResult =
            PaymentResult().apply {
                amountsResp =
                    AmountsResp().apply {
                        currency = code
                        authorizedAmount = BigDecimal("200")
                        totalFeesAmount = fee
                        tipAmount = BigDecimal("5")
                        cashBackAmount = BigDecimal("10")
                    }
            }
    }

    @Test
    fun `configured fees survive tips partial approvals totals and missing answer recovery`() =
        runTest {
            var config = SimulatorConfig(delayMillis = 0, surchargeAmount = BigDecimal("0.37"))
            val simulator = TerminalSimulator({ config })
            val client =
                TerminalClient(
                    simulator,
                    TerminalIdentity("POS1", "SIMULATOR-1"),
                    PosApplication("Mini mPOS", "1", "Mini mPOS", "Android", "13"),
                    currencyDigits = testCurrencyDigits,
                    recovery = RecoveryPolicy(attempts = 2, intervalMillis = 10),
                )
            val request = PaymentParams(1200, "AUD", "FEE")
            val tipped = (client.pay(request.copy(terminalTip = TerminalTipOptions())) as TransactionOutcome.Completed).details
            assertThat(tipped.tipMinor).isEqualTo(120)
            assertThat(tipped.surchargeMinor).isEqualTo(37)
            assertThat(tipped.amountMinor).isEqualTo(1357)
            assertThat(tipped.customerReceipt.toString()).contains("Surcharge")
            config = config.copy(partialApprovalPercent = 50, outcome = SimulatedOutcome.TIMEOUT)
            val partial = (client.pay(request.copy(allowPartialAuthorisation = true)) as TransactionOutcome.Completed).details
            assertThat(partial.partial).isTrue()
            assertThat(partial.amountMinor).isEqualTo(637)
            assertThat(partial.surchargeMinor).isEqualTo(37)
            val totals = client.getTotals() as TerminalTotalsOutcome.Answered
            assertThat(totals.totals.single().debitMinor).isEqualTo(1994)
        }
}
