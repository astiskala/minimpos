package app.minimpos.terminal

import app.minimpos.terminal.client.TerminalClient
import app.minimpos.terminal.client.TerminalMoney
import com.adyen.model.nexo.AmountsResp
import com.adyen.model.nexo.PaymentInstrumentData
import com.adyen.model.nexo.PaymentResponse
import com.adyen.model.nexo.PaymentResult
import com.adyen.model.nexo.Response
import com.adyen.model.nexo.ResultType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.math.BigDecimal
import kotlin.io.encoding.Base64

class TerminalWalletReplyTest {
    @Test
    fun `wallet answers can report a stored token without card data or report no token`() {
        val money = TerminalMoney(testCurrencyDigits)
        listOf(null, "WALLET_TOKEN").forEach { token ->
            val saved = token?.let { ",\"tokenization.storedPaymentMethodId\":\"$it\"" }.orEmpty()
            val answer =
                PaymentResponse().apply {
                    response =
                        Response().apply {
                            result = ResultType.SUCCESS
                            additionalResponse =
                                Base64.encode("""{"additionalData":{"paymentMethod":"wechatpay_pos"$saved}}""".toByteArray())
                        }
                    paymentResult =
                        PaymentResult().apply {
                            paymentInstrumentData = PaymentInstrumentData()
                            amountsResp =
                                AmountsResp().apply {
                                    currency = "AUD"
                                    authorizedAmount = BigDecimal("4.01")
                                }
                        }
                }
            val details = checkNotNull(TerminalClient.mapPayment(answer, money::minor))
            assertThat(details.success).isTrue()
            assertThat(details.amountMinor).isEqualTo(401)
            assertThat(details.paymentBrand).isEqualTo("wechatpay_pos")
            assertThat(details.maskedPan).isNull()
            assertThat(details.tokenization?.storedPaymentMethodId).isEqualTo(token)
        }
    }
}
