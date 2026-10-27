package app.minimpos.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.minimpos.app.data.db.SaleStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.uuid.Uuid

/** The released version-16 upgrade adds meal-voucher facts without changing what earlier sales, lines or products mean. */
@RunWith(RobolectricTestRunner::class)
class MealVoucherSchemaTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `version 16 keeps unfinished tenders lines and products without inventing voucher facts or eligibility`() {
        val name = "schema-v16-voucher-${Uuid.random()}"
        createSchemaDatabase(context, name, 16)
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("INSERT INTO tax_rates (id, name, rateMilliPercent, sortOrder) VALUES (1, 'TVA', 10000, 0)")
            raw.execSQL(
                "INSERT INTO products (id, name, priceMinor, taxRateId, sortOrder, kind, sample) VALUES " +
                    "(7, 'Salade', 1200, 1, 0, 'SALE', 0)",
            )
            raw.execSQL(
                "INSERT INTO sales (id, createdAt, currency, taxMode, netMinor, taxMinor, totalMinor, status, merchantReference, " +
                    "tokenizationRequested, signatureRequired, refundedMinor, adjustmentPending) VALUES " +
                    "('s16', 100, 'EUR', 'INCLUSIVE', 1091, 109, 1200, 'UNKNOWN', 'MP-16', 0, 0, 0, 0)",
            )
            raw.execSQL(
                "INSERT INTO sale_lines (saleId, position, productId, name, unitPriceMinor, quantity, taxName, " +
                    "taxRateMilliPercent, netMinor, taxMinor, grossMinor, refundedQuantity) VALUES " +
                    "('s16', 0, 7, 'Salade', 1200, 1, 'TVA', 10000, 1091, 109, 1200, 0)",
            )
            raw.execSQL(
                "INSERT INTO sale_tenders (id, saleId, sequence, requestedMinor, status, createdAt, poiTransactionId) " +
                    "VALUES ('t16', 's16', 1, 1200, 'UNKNOWN', 100, 'T.PSP16')",
            )
        }
        try {
            withMigratedDatabase(context, name) { db ->
                runBlocking {
                    val record = checkNotNull(db.saleDao().sale("s16"))
                    val tender = record.tenders.single()
                    assertThat(record.sale.status).isEqualTo(SaleStatus.UNKNOWN)
                    assertThat(tender.status).isEqualTo(SaleStatus.UNKNOWN)
                    assertThat(tender.serviceId).isNull()
                    assertThat(tender.poiTransactionId).isEqualTo("T.PSP16")
                    assertThat(tender.mealVoucher).isNull()
                    assertThat(tender.fundingSource).isNull()
                    assertThat(tender.paymentMethodVariant).isNull()
                    assertThat(record.lines.single().mealVoucherEligible).isFalse()
                    assertThat(record.lines.single().grossMinor).isEqualTo(1200)
                    val product = db.catalogDao().productsOnce().single()
                    assertThat(product.name).isEqualTo("Salade")
                    assertThat(product.mealVoucherEligible).isFalse()
                    assertThat(db.openHelper.readableDatabase.version).isEqualTo(24)
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `the current schema stores voucher eligibility and tender facts`() {
        val schema = schemaJson(24)
        val tables =
            (0..<schema.getJSONArray("entities").length())
                .map { schema.getJSONArray("entities").getJSONObject(it) }
                .associate { it.getString("tableName") to it.getString("createSql") }
        assertThat(tables.getValue("products")).contains("`mealVoucherEligible` INTEGER NOT NULL DEFAULT 0")
        assertThat(tables.getValue("sale_lines")).contains("`mealVoucherEligible` INTEGER NOT NULL DEFAULT 0")
        listOf("mealVoucher", "fundingSource", "paymentMethodVariant").forEach {
            assertThat(tables.getValue("sale_tenders")).contains("`$it` TEXT")
        }
    }
}
