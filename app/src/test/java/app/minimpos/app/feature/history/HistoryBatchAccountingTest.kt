package app.minimpos.app.feature.history

import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.CaptureStatus
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.history.HistoryAccounting
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class HistoryBatchAccountingTest {
    @Test
    fun `each record is accounted once per batch even with several activity timestamps`() {
        val env = TestEnvironment()
        try {
            await {
                val history = env.container.history
                env.container.database.saleDao().insert(
                    SaleEntity(
                        "sale",
                        createdAt = 100,
                        processedAt = 200,
                        captureStartedAt = 300,
                        captureProcessedAt = 400,
                        currency = "AUD",
                        taxMode = "INCLUSIVE",
                        netMinor = 100,
                        taxMinor = 0,
                        totalMinor = 100,
                        status = SaleStatus.APPROVED,
                        merchantReference = "sale",
                        tipOnReceipt = true,
                        captureStatus = CaptureStatus.REQUESTED,
                        capturedMinor = 100,
                    ),
                )
                var calls = 0
                val pages =
                    HistoryPages(history.reader) { items, zone ->
                        calls++
                        HistoryAccounting.days(items, zone)
                    }
                val (days, older) = pages.read(null, HistoryFilter.ALL, HistorySearch(""), ZoneOffset.UTC)
                assertThat(days.single().items.map { it.id }).containsExactly("sale")
                assertThat(
                    days
                        .single()
                        .totals.salesMinor
                        .getValue("AUD"),
                ).isEqualTo(100)
                assertThat(older).isFalse()
                // One row batch and one whole-day summary batch, rather than one calculation per candidate timestamp.
                assertThat(calls).isEqualTo(2)
            }
        } finally {
            env.close()
        }
    }
}
