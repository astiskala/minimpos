package app.minimpos.app.terminal

import app.minimpos.app.FakeStoreDetails
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.security.Secret
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.MerchantLookup
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MerchantCurrencyTest {
    private val stores = FakeStoreDetails()
    private val env = TestEnvironment(stores = stores)
    private val container = env.container

    @After
    fun tearDown() = env.close()

    @Test
    fun `real merchant currency is an optional reading and never changes saved pricing`() {
        env.useLinks()
        stores.merchantAnswer = MerchantLookup.Found("USD Shop", "AUD")
        val before = await { container.settings.current() }
        val proposal = checkNotNull(await { container.merchantCurrency.find() })
        assertThat(proposal.code).isEqualTo("AUD")
        assertThat(await { proposal.current() }).isTrue()
        assertThat(stores.calls).containsExactly("merchant:HarbourCoffeeCOM")
        assertThat(await { container.settings.current() }).isEqualTo(before)
        await { container.secrets.set(Secret.ADYEN_API_KEY, "replacement") }
        assertThat(await { proposal.current() }).isFalse()
    }

    @Test
    fun `changed account invalidates a previously offered currency`() {
        env.useLinks()
        stores.merchantAnswer = MerchantLookup.Found("Shop", "JPY")
        val proposal = checkNotNull(await { container.merchantCurrency.find() })
        env.updateSettings { it.copy(terminal = it.terminal.copy(merchantAccount = "Other")) }
        assertThat(await { proposal.current() }).isFalse()
    }

    @Test
    fun `simulator incomplete and unreadable setup never request merchant details`() {
        env.useSimulator()
        assertThat(await { container.merchantCurrency.find() }).isNull()
        assertThat(stores.calls).isEmpty()
        env.useLinks()
        env.cipher.fail = true
        assertThat(await { container.merchantCurrency.find() }).isNull()
        assertThat(stores.calls).isEmpty()
    }

    @Test
    fun `unavailable unknown and denied settlement currencies offer nothing without failing setup`() {
        env.useLinks()
        for (answer in listOf(
            MerchantLookup.Found("AUD merchant", ""),
            MerchantLookup.Found("AUD merchant", "ZZZ"),
            MerchantLookup.Failed(Fault.Permission(ApiKey.ADYEN, "Management API - Account read")),
        )) {
            stores.merchantAnswer = answer
            assertThat(await { container.merchantCurrency.find() }).isNull()
            assertThat(await { container.settings.current() }.payment.currencyCode).isEmpty()
        }
    }
}
