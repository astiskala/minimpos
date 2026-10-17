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

    @Test
    fun `matchesApi and matchesTerminal require the same non-simulated credentials and terminal identity`() {
        val base = PaymentContext("TERMINAL", "POI-1", "SALE-1", "MerchantA", "TEST", "192.168.1.10")
        assertThat(base.destination).isEqualTo("TERMINAL")
        assertThat(base.poiId).isEqualTo("POI-1")
        assertThat(base.saleId).isEqualTo("SALE-1")
        assertThat(base.merchantAccount).isEqualTo("MerchantA")
        assertThat(base.environment).isEqualTo("TEST")
        assertThat(base.host).isEqualTo("192.168.1.10")
        assertThat(base.simulated).isFalse()

        val defaults =
            Json.decodeFromString<PaymentContext>(
                """{"destination":"SIMULATOR","poiId":"","saleId":"","merchantAccount":"",""" +
                    """"environment":null,"host":"h","simulated":true}""",
            )
        assertThat(defaults.poiId).isEmpty()
        assertThat(defaults.saleId).isEmpty()
        assertThat(defaults.merchantAccount).isEmpty()
        assertThat(defaults.environment).isNull()
        assertThat(defaults.host).isEqualTo("h")
        assertThat(defaults.simulated).isTrue()
        assertThat(Json.decodeFromString<PaymentContext>(Json.encodeToString(defaults))).isEqualTo(defaults)

        assertThat(base.matchesApi(base.copy(host = "10.0.0.2"))).isTrue()
        assertThat(base.copy(simulated = true).matchesApi(base.copy(simulated = true, environment = null))).isTrue()
        assertThat(base.copy(simulated = true).matchesApi(base)).isFalse()
        assertThat(base.matchesApi(base.copy(simulated = true))).isFalse()
        assertThat(base.copy(environment = null).matchesApi(base.copy(environment = null))).isFalse()
        assertThat(base.matchesApi(base.copy(environment = "LIVE"))).isFalse()
        assertThat(base.matchesApi(base.copy(merchantAccount = "MerchantB"))).isFalse()

        assertThat(base.matchesTerminal(base.copy(host = "10.0.0.2"))).isTrue()
        assertThat(base.matchesTerminal(base.copy(destination = "CLOUD"))).isFalse()
        assertThat(base.matchesTerminal(base.copy(poiId = "POI-2"))).isFalse()
        assertThat(base.matchesTerminal(base.copy(saleId = "SALE-2"))).isFalse()
        assertThat(base.matchesTerminal(base.copy(merchantAccount = "MerchantB"))).isFalse()
    }
}
