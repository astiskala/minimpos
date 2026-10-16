package app.minimpos.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.minimpos.app.data.db.AppDatabase
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.ProductEntity
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.db.StoredReason
import app.minimpos.app.data.db.TaxRateEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class DatabaseSchemaTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun exportedSchema(version: Int = 11): JSONObject =
        listOf("schemas", "app/schemas")
            .map { File(it, "app.minimpos.app.data.db.AppDatabase/$version.json") }
            .first { it.exists() }
            .let { JSONObject(it.readText()).getJSONObject("database") }

    private fun createExportedDatabase(
        name: String,
        version: Int = 11,
    ) {
        val schema = exportedSchema(version)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices") ?: continue
                for (position in 0 until indices.length()) {
                    db.execSQL(
                        indices.getJSONObject(position).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")),
                    )
                }
            }
            val queries = schema.getJSONArray("setupQueries")
            for (index in 0 until queries.length()) db.execSQL(queries.getString(index))
            db.version = schema.getInt("version")
        }
    }

    private fun <T> withDatabase(
        name: String,
        block: (AppDatabase) -> T,
    ): T {
        val db =
            Room
                .databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_10_11)
                .allowMainThreadQueries()
                .build()
        try {
            return block(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun `the exported current schema opens and retains catalogue and typed sale outcomes`() {
        val name = "schema-${UUID.randomUUID()}"
        createExportedDatabase(name)
        try {
            withDatabase(name) { db ->
                runBlocking {
                    val taxId = db.catalogDao().insert(TaxRateEntity(name = "No tax", rateMilliPercent = 0))
                    db.catalogDao().insert(ProductEntity(name = "Item", priceMinor = 400, taxRateId = taxId, sample = true))
                    db.saleDao().insert(
                        SaleEntity(
                            id = "sale",
                            sample = true,
                            createdAt = 1,
                            currency = "AUD",
                            taxMode = "INCLUSIVE",
                            netMinor = 400,
                            taxMinor = 0,
                            totalMinor = 400,
                            status = SaleStatus.UNKNOWN,
                            merchantReference = "schema-sale",
                            requestedWallet = "wechatpay_pos",
                            reason = StoredReason.NotDone(Failure.NotSetUp(SetupProblem.API_KEY)),
                            processedAt = 10,
                            captureStartedAt = 20,
                            captureProcessedAt = 30,
                        ),
                    )
                }
            }
            withDatabase(name) { db ->
                runBlocking {
                    val product = db.catalogDao().productsOnce().single()
                    val sale = checkNotNull(db.saleDao().sale("sale")).sale
                    assertThat(product.sample).isTrue()
                    assertThat(product.name).isEqualTo("Item")
                    assertThat(sale.sample).isTrue()
                    assertThat(sale.reason).isEqualTo(StoredReason.NotDone(Failure.NotSetUp(SetupProblem.API_KEY)))
                    assertThat(sale.requestedWallet).isEqualTo("wechatpay_pos")
                    assertThat(sale.processedAt).isEqualTo(10)
                    assertThat(sale.captureStartedAt).isEqualTo(20)
                    assertThat(sale.captureProcessedAt).isEqualTo(30)
                    assertThat(db.openHelper.readableDatabase.version).isEqualTo(exportedSchema().getInt("version"))
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `migrates version 10 database to version 11 preserving existing sales and catalogue`() {
        val name = "schema-v10-${UUID.randomUUID()}"
        createExportedDatabase(name, version = 10)
        val file = context.getDatabasePath(name)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("INSERT INTO `tax_rates` (`id`, `name`, `rateMilliPercent`, `sortOrder`) VALUES (1, 'GST', 10000, 0)")
            raw.execSQL(
                "INSERT INTO `sales` (`id`, `sample`, `createdAt`, `currency`, `taxMode`, `netMinor`, `taxMinor`, `totalMinor`, " +
                    "`status`, `merchantReference`, `tokenizationRequested`, `signatureRequired`, `refundedMinor`, `kind`, " +
                    "`tipOnReceipt`, `holdCancelled`, `paymentLink`, `adjustmentPending`) " +
                    "VALUES ('s10', 0, 100, 'AUD', 'INCLUSIVE', 909, 91, 1000, 'APPROVED', 'MP-10', 0, 0, 0, 'SALE', 0, 0, 0, 0)",
            )
        }
        try {
            withDatabase(name) { db ->
                runBlocking {
                    val sale = checkNotNull(db.saleDao().sale("s10")).sale
                    assertThat(sale.totalMinor).isEqualTo(1_000)
                    assertThat(sale.merchantReference).isEqualTo("MP-10")
                    assertThat(sale.poiReconciliationId).isNull()
                    assertThat(db.openHelper.readableDatabase.version).isEqualTo(11)
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
