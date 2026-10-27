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
    fun `version 16 keeps every refund referenced including unfinished ones and allows unreferenced refunds`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "unreferenced-upgrade-${Uuid.random()}"
        createSchemaDatabase(context, name, 16)
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL(
                "INSERT INTO refunds (id, saleId, createdAt, merchantReference, originalTransactionId, originalTimestamp, " +
                    "originalReference, currency, amountMinor, full, status, serviceId, customerReceiptJson, linesJson, " +
                    "context, processedAt) VALUES ('r16', 's16', 100, 'R-16', 'TX.PSP16', '2026-10-01T10:00:00.000Z', 'MP-16', " +
                    "'EUR', 700, 0, 'UNKNOWN', 'REV16', '[original-receipt]', '[]', NULL, NULL)",
            )
            raw.execSQL(
                "INSERT INTO refunds (id, saleId, createdAt, merchantReference, originalTransactionId, originalTimestamp, " +
                    "currency, amountMinor, full, status, pspReference, processedAt, checkoutRefund) VALUES ('c16', NULL, 200, " +
                    "'R-24', '.PSP24', '2026-10-01T11:00:00.000Z', 'EUR', 900, 1, 'REQUESTED', 'REF24', 300, 1)",
            )
        }
        try {
            withMigratedDatabase(context, name) { db ->
                runBlocking {
                    val unknown = checkNotNull(db.refundDao().refund("r16"))
                    assertThat(unknown.status).isEqualTo(RefundStatus.UNKNOWN)
                    assertThat(unknown.serviceId).isEqualTo("REV16")
                    assertThat(unknown.originalTransactionId).isEqualTo("TX.PSP16")
                    assertThat(unknown.originalTimestamp).isEqualTo("2026-10-01T10:00:00.000Z")
                    assertThat(unknown.tenderId).isNull()
                    assertThat(unknown.tipMinor).isNull()
                    assertThat(unknown.surchargeMinor).isNull()
                    assertThat(unknown.cash).isFalse()
                    assertThat(unknown.originalReference).isEqualTo("MP-16")
                    assertThat(unknown.customerReceiptJson).isEqualTo("[original-receipt]")
                    assertThat(unknown.linesJson).isEqualTo("[]")
                    assertThat(unknown.unreferenced).isFalse()
                    assertThat(unknown.unreferencedReason).isNull()
                    listOf(
                        unknown.acquirerResponseCode,
                        unknown.cancelStatus,
                        unknown.cancelStartedAt,
                        unknown.cancelReason,
                        unknown.cancelPspReference,
                    ).forEach { assertThat(it).isNull() }
                    val checkout = checkNotNull(db.refundDao().refund("c16"))
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
