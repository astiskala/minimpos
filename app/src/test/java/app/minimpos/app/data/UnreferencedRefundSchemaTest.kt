package app.minimpos.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.minimpos.app.data.db.AppDatabase
import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.UnreferencedRefundReason
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
class UnreferencedRefundSchemaTest {
    @Test
    fun `23 to 24 keeps every refund referenced, unfinished ones included, and then allows none`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "unreferenced-upgrade-${Uuid.random()}"
        createSchemaDatabase(context, name, 23)
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL(
                "INSERT INTO refunds (id, saleId, createdAt, merchantReference, originalTransactionId, originalTimestamp, " +
                    "originalReference, currency, amountMinor, full, status, serviceId, tenderId, surchargeMinor, tipMinor, cash, " +
                    "context, processedAt) VALUES ('r23', 's23', 100, 'R-23', 'TX.PSP23', '2026-10-01T10:00:00.000Z', 'MP-23', " +
                    "'EUR', 700, 0, 'UNKNOWN', 'REV23', 's23-1', 0, 50, 0, NULL, NULL)",
            )
            raw.execSQL(
                "INSERT INTO refunds (id, saleId, createdAt, merchantReference, originalTransactionId, originalTimestamp, " +
                    "currency, amountMinor, full, status, pspReference, processedAt, checkoutRefund) VALUES ('c23', NULL, 200, " +
                    "'R-24', '.PSP24', '2026-10-01T11:00:00.000Z', 'EUR', 900, 1, 'REQUESTED', 'REF24', 300, 1)",
            )
        }
        try {
            withMigratedDatabase(context, name) { db ->
                runBlocking {
                    val unknown = checkNotNull(db.refundDao().refund("r23"))
                    assertThat(unknown.status).isEqualTo(RefundStatus.UNKNOWN)
                    assertThat(unknown.serviceId).isEqualTo("REV23")
                    assertThat(unknown.originalTransactionId).isEqualTo("TX.PSP23")
                    assertThat(unknown.originalTimestamp).isEqualTo("2026-10-01T10:00:00.000Z")
                    assertThat(unknown.tenderId).isEqualTo("s23-1")
                    assertThat(unknown.tipMinor).isEqualTo(50)
                    assertThat(unknown.unreferenced).isFalse()
                    assertThat(unknown.unreferencedReason).isNull()
                    assertThat(unknown.cancelStatus).isNull()
                    val checkout = checkNotNull(db.refundDao().refund("c23"))
                    assertThat(checkout.checkoutRefund).isTrue()
                    assertThat(checkout.pspReference).isEqualTo("REF24")
                    assertThat(checkout.processedAt).isEqualTo(300)
                    assertThat(checkout.unreferenced).isFalse()
                    assertUnreferencedAllowed(db)
                    assertThat(db.openHelper.readableDatabase.version).isEqualTo(24)
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private suspend fun assertUnreferencedAllowed(db: AppDatabase) {
        db.refundDao().insert(
            RefundEntity(
                id = "u24",
                saleId = null,
                createdAt = 400,
                merchantReference = "R-25",
                originalTransactionId = null,
                originalTimestamp = null,
                originalReference = null,
                currency = "EUR",
                amountMinor = 100,
                full = false,
                status = RefundStatus.PENDING,
                serviceId = "PAY25",
                unreferenced = true,
                unreferencedReason = UnreferencedRefundReason.PAID_ELSEWHERE,
            ),
        )
        val unreferenced = checkNotNull(db.refundDao().refund("u24"))
        assertThat(unreferenced.originalTransactionId).isNull()
        assertThat(unreferenced.unreferencedReason).isEqualTo(UnreferencedRefundReason.PAID_ELSEWHERE)
    }
}
