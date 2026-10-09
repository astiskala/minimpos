package app.minimpos.app.terminal

import app.minimpos.app.FakeStoreDetails
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.settings.ReceiptSettings
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.terminal.transport.MerchantLookup
import app.minimpos.terminal.transport.StoreDetails
import app.minimpos.terminal.transport.StoreLookup
import app.minimpos.terminal.transport.TerminalEnvironment
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReceiptBusinessDetailsTest {
    private val stores =
        FakeStoreDetails(
            StoreLookup.Found(StoreDetails("Cafe", "1 Main St", "+61212345678")),
            MerchantLookup.Found("Legal Shop"),
        )
    private val calls get() = stores.calls
    private val env = TestEnvironment(stores = stores)
    private val container = env.container

    @After
    fun tearDown() = env.close()

    private fun read() = await { container.receiptBusinessDetails.lookup() }

    @Test
    fun `simulator and missing setup never call Management`() {
        env.useSimulator()
        assertThat(read()).isEqualTo(ReceiptBusinesses.NotSetUp(SetupProblem.API_REQUIRED))
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.TERMINAL)) }
        assertThat(read()).isEqualTo(ReceiptBusinesses.NotSetUp(SetupProblem.MERCHANT_ACCOUNT))
        env.updateSettings { it.copy(terminal = it.terminal.copy(merchantAccount = "Merchant")) }
        assertThat(read()).isEqualTo(ReceiptBusinesses.NotSetUp(SetupProblem.API_KEY))
        await { container.secrets.set(Secret.ADYEN_API_KEY, "key") }
        assertThat(read()).isEqualTo(ReceiptBusinesses.NotSetUp(SetupProblem.ENVIRONMENT))
        assertThat(calls).isEmpty()
    }

    @Test
    fun `lookup uses the account and works without a Checkout live prefix, leaving settings untouched`() {
        env.useLinks()
        env.updateSettings {
            it.copy(
                terminal =
                    it.terminal.copy(
                        merchantAccount = " Merchant ",
                        storeId = " ST1 ",
                        environment = TerminalEnvironment.LIVE,
                        liveUrlPrefix = "",
                    ),
            )
        }
        val before = await { container.settings.current() }
        assertThat(read())
            .isEqualTo(ReceiptBusinesses.Found(ReceiptBusiness("Cafe", "1 Main St", "+61212345678"), fromStore = true))
        assertThat(calls).containsExactly("store:Merchant/ST1")
        assertThat(await { container.settings.current() }).isEqualTo(before)
        stores.storeAnswer = StoreLookup.Missing
        assertThat(read()).isEqualTo(ReceiptBusinesses.NotSetUp(SetupProblem.STORE_ACCESS))
        stores.storeAnswer = StoreLookup.Failed("Access denied")
        assertThat(read()).isEqualTo(ReceiptBusinesses.Failed("Access denied"))
    }

    @Test
    fun `merchant scope uses only merchant details and never reads stores`() {
        env.useLinks()
        env.updateSettings { it.copy(terminal = it.terminal.copy(merchantAccount = "Merchant")) }
        assertThat(read()).isEqualTo(ReceiptBusinesses.Found(ReceiptBusiness("Legal Shop", "", ""), fromStore = false))
        assertThat(calls).containsExactly("merchant:Merchant")
        stores.merchantAnswer = MerchantLookup.Failed("Account read denied")
        assertThat(read()).isEqualTo(ReceiptBusinesses.Failed("Account read denied"))
    }

    @Test
    fun `unreadable saved keys block the lookup`() {
        env.useLinks()
        env.cipher.fail = true
        assertThat(read()).isEqualTo(ReceiptBusinesses.NotSetUp(SetupProblem.UNREADABLE_API_KEY))
        assertThat(calls).isEmpty()
    }

    @Test
    fun `only supplied receipt fields replace merchant text`() {
        val receipt =
            ReceiptSettings(
                businessName = "Original",
                addressLines = "Old address",
                phone = "Old phone",
                taxId = "123",
                title = "Custom",
                footer = "Thanks",
            )
        val proposal = ReceiptBusiness("New name", "", "")
        assertThat(proposal.available).isTrue()
        assertThat(proposal.applyTo(receipt)).isEqualTo(receipt)
        val full = proposal.copy(address = "New address", phone = "New phone")
        assertThat(full.applyTo(receipt)).isEqualTo(receipt)
        assertThat(full.applyTo(receipt.copy(businessName = "", addressLines = "", phone = "")))
            .isEqualTo(receipt.copy(businessName = "New name", addressLines = "New address", phone = "New phone"))
        val empty = proposal.copy(name = " ")
        assertThat(empty.available).isFalse()
        assertThat(empty.applyTo(receipt)).isEqualTo(receipt)
        assertThat(empty.copy(address = "Address").available).isTrue()
        assertThat(empty.copy(phone = "Phone").available).isTrue()
    }
}
