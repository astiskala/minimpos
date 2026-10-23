package app.minimpos.app.data

import androidx.room.withTransaction
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.repo.HistoryItem
import app.minimpos.app.payment.SavedCardCharges
import app.minimpos.core.payment.PaymentContext
import app.minimpos.terminal.checkout.StoredCard
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoryFoldingTest {
    private val sale =
        SaleEntity(
            "sale",
            createdAt = 1,
            currency = "AUD",
            taxMode = "INCLUSIVE",
            netMinor = 100,
            taxMinor = 0,
            totalMinor = 100,
            status = SaleStatus.APPROVED,
            merchantReference = "sale",
            shopperReference = "shopper",
            storedPaymentMethodId = "token",
            customerReceiptJson = "[large-receipt]",
            context = PaymentContext("SIMULATOR", "SIM", "POS", "", null, simulated = true),
        )

    @Test
    fun `history folds are bounded and shopper batches deduplicate latest cards across page boundaries`() {
        val env = TestEnvironment()
        try {
            val container = env.container
            await {
                container.database.withTransaction {
                    repeat(520) { index ->
                        container.database.saleDao().insert(
                            sale.copy(
                                id = "sale-${index.toString().padStart(4, '0')}",
                                createdAt = (index / 2).toLong(),
                                storedPaymentMethodId = "token-${index % 3}",
                                maskedPan = "$index",
                            ),
                        )
                    }
                    container.database.saleDao().insert(sale.copy(id = "unrelated", shopperReference = "other"))
                    container.database.saleDao().insert(sale.copy(id = "no-token", storedPaymentMethodId = null))
                    container.database.saleDao().insert(
                        sale.copy(
                            id = "real",
                            createdAt = 1000,
                            context = PaymentContext("TERMINAL", "REAL", "POS", "merchant", "LIVE"),
                        ),
                    )
                }
                val count =
                    container.history.reader.fold(0) { count, batch ->
                        assertThat(batch.size).isAtMost(256)
                        assertThat(
                            batch
                                .filterIsInstance<HistoryItem.Sale>()
                                .any { it.sale.customerReceiptJson != null },
                        ).isFalse()
                        count + batch.size
                    }
                assertThat(count).isEqualTo(523)
                val seen = mutableListOf<String>()
                val cards =
                    container.history.reader.shopper("shopper", linkedMapOf<String, StoredCard>()) { cards, batch ->
                        assertThat(batch.size).isAtMost(256)
                        assertThat(batch.any { it.shopperReference != "shopper" || it.customerReceiptJson != null }).isFalse()
                        seen += batch.map { it.id }
                        SavedCardCharges.simulatedCards(batch, "shopper").forEach { cards.putIfAbsent(it.id, it) }
                        cards
                    }
                assertThat(seen.distinct()).hasSize(521)
                assertThat(cards.keys).containsExactly("token-0", "token-1", "token-2")
                assertThat(cards.getValue("token-0").lastFour).isEqualTo("519")
                assertThat(cards.getValue("token-1").lastFour).isEqualTo("517")
                assertThat(cards.getValue("token-2").lastFour).isEqualTo("518")
            }
        } finally {
            env.close()
        }
    }
}
