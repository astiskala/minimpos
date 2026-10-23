package app.minimpos.app.payment

import app.minimpos.app.data.db.TaxRateEntity
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.terminal.TerminalState
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.tax.TaxMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class CheckoutPricingTest {
    @Test
    fun `form and terminal changes reuse prices while cart and tax edits invalidate them and stale starts`() =
        runTest {
            val session = SaleSession(newKey = { "line" })
            session.addCustom("Item", 1000, TaxRateEntity(name = "Tax", rateMilliPercent = 10_000))
            val settings = MutableStateFlow(AppSettings())
            val terminal = MutableStateFlow(TerminalState())
            val checkouts = mutableListOf<Checkout>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                session.checkout(settings, terminal, currency = { CurrencySpec("AUD", 2) }).collect { checkouts += it }
            }
            val original = checkouts.last()
            session.updateForm { it.copy(customerReference = "shopper") }
            assertThat(checkouts.last().totals).isSameInstanceAs(original.totals)
            assertThat(session.paymentStart(original, Instant.EPOCH, ZoneOffset.UTC)).isNull()
            terminal.value = terminal.value.copy(printerAvailable = true)
            assertThat(checkouts.last().totals).isSameInstanceAs(original.totals)
            settings.value = settings.value.copy(payment = settings.value.payment.copy(taxMode = TaxMode.EXCLUSIVE))
            val exclusive = checkouts.last()
            assertThat(exclusive.totals).isNotSameInstanceAs(original.totals)
            session.setQuantity("line", 2)
            assertThat(checkouts.last().totals).isNotSameInstanceAs(exclusive.totals)
            assertThat(
                checkouts
                    .last()
                    .totals.lines
                    .single()
                    .line.quantity,
            ).isEqualTo(2)
            settings.value = settings.value.copy(payment = settings.value.payment.copy(chargeTax = false))
            assertThat(
                checkouts
                    .last()
                    .totals.amounts.tax,
            ).isEqualTo(0)
        }
}
