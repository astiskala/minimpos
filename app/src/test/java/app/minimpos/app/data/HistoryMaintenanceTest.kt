package app.minimpos.app.data

import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.SaleTenderEntity
import app.minimpos.app.data.repo.HistoryRepository
import app.minimpos.app.payment.ReceiptDelivery
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.days

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HistoryMaintenanceTest {
    @Test
    fun `cleanup notifications follow commit and include split tenders while unfinished receipts remain armed`() {
        val env = TestEnvironment()
        try {
            val container = env.container

            fun record(
                id: String,
                status: SaleStatus,
            ) = SaleEntity(
                id,
                createdAt = 0,
                currency = "AUD",
                taxMode = "INCLUSIVE",
                netMinor = 100,
                taxMinor = 0,
                totalMinor = 100,
                status = status,
                merchantReference = id,
            )
            await {
                container.sales.createPending(record("old", SaleStatus.APPROVED), emptyList())
                container.sales.createPending(record("unknown", SaleStatus.UNKNOWN), emptyList())
                container.database.saleDao().upsertTender(
                    SaleTenderEntity(
                        "tender",
                        "old",
                        1,
                        100,
                        100,
                        SaleStatus.APPROVED,
                        "poi",
                        "timestamp",
                        "psp",
                        "visa",
                        "1111",
                        0,
                    ),
                )
            }
            val removed = mutableListOf<String>()
            val history =
                HistoryRepository(container.database, onRemoved = { items, tenders ->
                    removed += items.map { it.id }
                    if (items.any { it.id == "old" }) {
                        assertThat(tenders.map { it.id }).containsExactly("tender")
                        container.database.openHelper.readableDatabase.query("SELECT id FROM sales WHERE id = 'old'").use {
                            assertThat(it.count).isEqualTo(0)
                        }
                    }
                })
            await { history.clear() }
            assertThat(removed).containsExactly("old")
            container.receipts.arm("unknown")
            await { container.history.clear() }
            val armed = ReceiptDelivery::class.java.getDeclaredField("armed").apply { isAccessible = true }
            assertThat(armed.get(container.receipts) as Set<*>).contains("unknown")
            await { container.history.purgeForEnvironmentSwitch(container.settings.current().terminal) }
            assertThat(armed.get(container.receipts) as Set<*>).isEmpty()
            assertThat(await { container.sales.get("unknown") }).isNull()
        } finally {
            env.close()
        }
    }

    @Test
    fun `a continuously running process prunes again without restarting`() {
        val dispatcher = UnconfinedTestDispatcher()
        val deletions = AtomicInteger()
        val env =
            TestEnvironment(dispatcher = dispatcher, queryCallback = { sql ->
                if (sql.startsWith("DELETE FROM sales WHERE id IN")) deletions.incrementAndGet()
            })
        try {
            env.updateSettings { it.copy(history = it.history.copy(retentionDays = 1)) }
            await { env.container.storage.awaitReady() }
            env.container.start()
            await { while (deletions.get() == 0) delay(10) }
            val old = System.currentTimeMillis() - 2.days.inWholeMilliseconds
            await {
                env.container.database.saleDao().insert(
                    SaleEntity(
                        "old",
                        createdAt = old,
                        currency = "AUD",
                        taxMode = "INCLUSIVE",
                        netMinor = 100,
                        taxMinor = 0,
                        totalMinor = 100,
                        status = SaleStatus.APPROVED,
                        merchantReference = "old",
                    ),
                )
                assertThat(env.container.sales.get("old")).isNotNull()
            }
            dispatcher.scheduler.advanceTimeBy(1.days)
            dispatcher.scheduler.runCurrent()
            await { while (env.container.sales.get("old") != null) delay(10) }
            assertThat(deletions.get()).isAtLeast(2)
        } finally {
            env.close()
        }
    }
}
