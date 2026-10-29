package app.minimpos.terminal

import app.minimpos.terminal.client.TerminalMoney
import app.minimpos.terminal.client.mapStoredValue
import com.adyen.model.nexo.Response
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.StoredValueResponse
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StoredValueReplyTest {
    @Test
    fun `successful undo can omit result balance and currency`() {
        val money = TerminalMoney(testCurrencyDigits)
        val response =
            StoredValueResponse().apply {
                this.response = Response().apply { result = ResultType.SUCCESS }
            }
        val details = checkNotNull(mapStoredValue(response, null, money::minor))
        assertThat(details.success).isTrue()
        assertThat(details.balanceMinor).isNull()
        assertThat(details.currency).isNull()
        assertThat(details.amountMinor).isNull()
    }
}
