package app.minimpos.app.payment

import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.terminal.ApiSetup
import app.minimpos.app.terminal.ApiTarget
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FinancialOperationsTest {
    @get:Rule val env = TestEnvironment()
    private val container get() = env.container

    private fun operations(
        payments: TransactionLifecycle<PaymentStart> = container.payments,
        captures: Captures? = null,
        refundRun: () -> RefundRun? = { null },
    ) = FinancialOperations(
        payments,
        container.refunds,
        container.savedCardCharges,
        CheckoutRefunds(env.scope, container.refundRecords, container.sales, container.api::target),
        container.links,
        container.sales,
        container.refundRecords,
        captures = captures,
        refundRun = refundRun,
    )

    @Test
    fun `a terminal recovery holds the running guard until its stored lookup finishes`() =
        await {
            lateinit var operations: FinancialOperations
            val sales = SaleBook(container.sales)
            val book =
                object : TransactionBook<PaymentStart> by sales {
                    override suspend fun unsettledServiceId(id: String): String? {
                        assertThat(operations.running).isTrue()
                        return null
                    }
                }
            operations = operations(TransactionLifecycle(env.scope, container.gateway, book))
            assertThat(operations.running).isFalse()
            assertThat(operations.recheck(StoredTransaction.Sale("missing"))).isEqualTo(Recheck.Settled)
            // Use the runner directly because routing intentionally ignores a record that no longer exists.
            val lifecycle = TransactionLifecycle(env.scope, container.gateway, book)
            operations = operations(lifecycle)
            lifecycle.recheck("unknown")
            assertThat(operations.running).isFalse()
        }

    @Test
    fun `captures hold the running guard before asynchronous target resolution`() =
        await {
            var operations: FinancialOperations? = null
            val captures =
                Captures(container.sales, {
                    assertThat(checkNotNull(operations).running).isTrue()
                    ApiTarget(ApiSetup.Incomplete(SetupProblem.API_KEY))
                })
            operations = operations(captures = captures)
            assertThat(captures.capture("missing", 100)).isEqualTo(CaptureResult.NotAllowed)
            assertThat(operations.running).isFalse()
        }

    @Test
    fun `multi-leg returns hold the running guard between legs until the whole run finishes`() {
        var run: RefundRun? = null
        val operations = operations(refundRun = { run })
        assertThat(operations.running).isFalse()
        run = RefundRun(listOf(RefundLegState(OperationAmount(100, "EUR"), cash = true)))
        assertThat(operations.running).isTrue()
        run = run.copy(finished = true)
        assertThat(operations.running).isFalse()
    }
}
