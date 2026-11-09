package app.minimpos.app.payment

import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.ProductEntity
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.TaxRateEntity
import app.minimpos.app.data.settings.SimulatorSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneOffset

/** The Settings › Simulator scenarios that stand in for Adyen configuration: a surcharge and partial approvals. */
@RunWith(RobolectricTestRunner::class)
class SimulatorScenariosTest {
    private val env = TestEnvironment()
    private val container = env.container
    private val session get() = container.session(SaleKind.SALE)

    @After
    fun tearDown() = env.close()

    /** Pays one 10.00 AUD item on the simulator set up by [simulator] and returns the stored sale. */
    private fun pay(simulator: (SimulatorSettings) -> SimulatorSettings): SaleEntity {
        env.useSimulator { it.copy(payment = it.payment.copy(currencyCode = "AUD"), simulator = simulator(it.simulator)) }
        val (tax, product) =
            await {
                val taxId = container.catalog.saveTaxRate(TaxRateEntity(name = "GST", rateMilliPercent = 10_000))
                val id = container.catalog.saveProduct(ProductEntity(name = "Tea", priceMinor = 1_000, taxRateId = taxId))
                TaxRateEntity(taxId, "GST", 10_000) to container.catalog.product(id)!!
            }
        session.addProduct(product, tax)
        val checkout =
            await {
                session
                    .checkout(
                        container.settingsState,
                        container.terminalStatus.state,
                        currency = container::currency,
                    ).first { it.canPay }
            }
        val id = checkNotNull(container.payments.start(checkNotNull(session.paymentStart(checkout, Instant.now(), ZoneOffset.UTC))))
        await { container.payments.state.first { it == TransactionState.Finished(id) } }
        container.payments.acknowledge()
        return await { checkNotNull(container.sales.get(id)) }.sale
    }

    @Test
    fun `the simulator reports its surcharge in the currency's Adyen decimals as part of the charged total`() {
        val sale = pay { it.copy(surchargeMinor = 50) }
        assertThat(sale.status).isEqualTo(SaleStatus.APPROVED)
        assertThat(sale.surchargeMinor).isEqualTo(50)
        assertThat(sale.amountMinor).isEqualTo(sale.totalMinor + 50)
    }

    @Test
    fun `below 100 percent the simulator approves that share of a payment that allows partial approval`() {
        val sale = pay { it.copy(partialApprovalPercent = 40) }
        assertThat(sale.isPartiallyPaid).isTrue()
        assertThat(sale.paidMinor).isEqualTo(sale.totalMinor * 40 / 100)
    }

    @Test
    fun `at 100 percent an ordinary amount is approved in full`() {
        val sale = pay { it }
        assertThat(sale.isPartiallyPaid).isFalse()
        assertThat(sale.surchargeMinor).isEqualTo(0)
    }
}
