package io.github.astiskala.minimpos.app.terminal

import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.data.db.SetupProblem
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.data.settings.ReceiptSettings
import io.github.astiskala.minimpos.app.data.settings.TerminalMode
import io.github.astiskala.minimpos.terminal.transport.StoreDetails
import io.github.astiskala.minimpos.terminal.transport.StoreDetailsApi
import io.github.astiskala.minimpos.terminal.transport.StoreListing
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReceiptBusinessDetailsTest {
    private val calls = mutableListOf<String>()
    private var result: StoreListing = StoreListing.Listed(listOf(StoreDetails("ST1", "cafe", "Cafe", "1 Main St", "+61212345678")))
    private val env =
        TestEnvironment(
            stores =
                StoreDetailsApi {
                    calls += it
                    result
                },
        )
    private val container = env.container

    @After
    fun tearDown() = env.close()

    private fun read() = await { container.receiptBusinessDetails.stores() }

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
            it.copy(terminal = it.terminal.copy(merchantAccount = " Merchant ", environment = TerminalEnvironment.LIVE, liveUrlPrefix = ""))
        }
        val before = await { container.settings.current() }
        assertThat(read()).isEqualTo(ReceiptBusinesses.Listed(listOf(ReceiptBusiness("ST1", "cafe", "Cafe", "1 Main St", "+61212345678"))))
        assertThat(calls).containsExactly("Merchant")
        assertThat(await { container.settings.current() }).isEqualTo(before)
        result = StoreListing.Failed("Access denied")
        assertThat(read()).isEqualTo(ReceiptBusinesses.Failed("Access denied"))
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
        val proposal = ReceiptBusiness("ST1", "cafe", "New name", "", "")
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
