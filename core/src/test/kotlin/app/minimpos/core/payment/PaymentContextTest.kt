package app.minimpos.core.payment

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class PaymentContextTest {
    @Test
    fun `accounting environment follows original simulation flag rather than current destination`() {
        val context = PaymentContext("TERMINAL", "POI", "POS", "Merchant", "LIVE")
        assertThat(context.historyEnvironment).isEqualTo("LIVE")
        assertThat(context.copy(environment = "TEST").historyEnvironment).isEqualTo("TEST")
        assertThat(context.copy(environment = null).historyEnvironment).isNull()
        assertThat(context.copy(simulated = true).historyEnvironment).isEqualTo("SIMULATOR")
    }

    @Test
    fun `moving payment context preserves its stored JSON format`() {
        val stored =
            """{"destination":"TERMINAL","poiId":"POI","saleId":"POS","merchantAccount":"Merchant","environment":"LIVE"}"""
        val context = PaymentContext("TERMINAL", "POI", "POS", "Merchant", "LIVE")
        assertThat(Json.decodeFromString<PaymentContext>(stored)).isEqualTo(context)
        assertThat(Json.parseToJsonElement(Json.encodeToString(context))).isEqualTo(Json.parseToJsonElement(stored))
    }
}
