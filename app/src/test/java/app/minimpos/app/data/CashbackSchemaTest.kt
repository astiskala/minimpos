package app.minimpos.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.minimpos.app.data.db.GiftCardStatus
import app.minimpos.app.data.db.SaleStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
class CashbackSchemaTest {
    @Test
    fun `current schema retains unknown voucher and gift operations without inventing cash handovers`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "cashback-current-${Uuid.random()}"
        createSchemaDatabase(context, name, 24)
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL(
                "INSERT INTO sales (id, createdAt, currency, taxMode, netMinor, taxMinor, totalMinor, status, merchantReference, " +
                    "tokenizationRequested, signatureRequired, refundedMinor, adjustmentPending) VALUES " +
                    "('s24', 100, 'EUR', 'INCLUSIVE', 1000, 0, 1000, 'UNKNOWN', 'MP-24', 0, 0, 0, 0)",
            )
            raw.execSQL(
                "INSERT INTO sale_tenders (id, saleId, sequence, requestedMinor, status, createdAt, kind, serviceId, mealVoucher) " +
                    "VALUES ('t24', 's24', 1, 1000, 'UNKNOWN', 100, 'CARD', 'PAY24', 'EUROPE_CARD_SCHEME')",
            )
            raw.execSQL(
                "INSERT INTO gift_card_operations (id, groupId, stage, category, createdAt, status, currency, " +
                    "amountMinor, merchantReference, serviceId) VALUES ('g24', 'group24', 'LOAD', 'STORED_VALUE', " +
                    "100, 'UNKNOWN', 'EUR', 500, 'G-24', 'GIFT24')",
            )
            raw.execSQL(
                "INSERT INTO gift_card_operations (id, groupId, stage, createdAt, status, currency, " +
                    "amountMinor, merchantReference) VALUES ('h24', 'group24', 'CASH_HANDOVER', " +
                    "101, 'AWAITING', 'EUR', 500, 'G-24')",
            )
        }
        try {
            withMigratedDatabase(context, name) { db ->
                runBlocking {
                    val record = checkNotNull(db.saleDao().sale("s24"))
                    assertThat(record.sale.status).isEqualTo(SaleStatus.UNKNOWN)
                    assertThat(record.sale.requestedCashBackMinor).isNull()
                    assertThat(record.sale.cashHandedOverAt).isNull()
                    assertThat(record.tenders.single().serviceId).isEqualTo("PAY24")
                    assertThat(record.tenders.single().mealVoucher).isEqualTo("EUROPE_CARD_SCHEME")
                    assertThat(record.tenders.single().requestedCashBackMinor).isNull()
                    assertThat(db.giftCardDao().stage("g24")?.serviceId).isEqualTo("GIFT24")
                    assertThat(db.giftCardDao().stage("h24")?.status).isEqualTo(GiftCardStatus.AWAITING)
                    assertThat(db.openHelper.readableDatabase.version).isEqualTo(24)
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
