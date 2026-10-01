package io.minimpos.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.minimpos.app.data.db.AppDatabase
import io.minimpos.app.data.db.ProductEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Opens a database created from an exported older schema, so Room's auto-migrations run on real data. */
@RunWith(RobolectricTestRunner::class)
class DatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun createDatabase(
        name: String,
        version: Int,
        rows: SQLiteDatabase.() -> Unit,
    ) {
        context.deleteDatabase(name)
        val schema =
            listOf("schemas", "app/schemas")
                .map { File(it, "io.minimpos.app.data.db.AppDatabase/$version.json") }
                .first { it.exists() }
                .let { JSONObject(it.readText()).getJSONObject("database") }
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.rows()
            db.version = version
        }
    }

    private fun <T> migrated(
        name: String,
        block: suspend (AppDatabase) -> T,
    ): T {
        val db =
            AppDatabase
                .withMigrations(Room.databaseBuilder(context, AppDatabase::class.java, name), context)
                .allowMainThreadQueries()
                .build()
        try {
            return runBlocking { block(db) }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    private fun SQLiteDatabase.insertProduct(
        id: Long,
        name: String,
        taxRateId: Long?,
    ) = execSQL(
        "INSERT INTO products (id, name, priceMinor, taxRateId, categoryId, sku, sortOrder) VALUES (?, ?, 100, ?, NULL, NULL, 0)",
        arrayOf<Any?>(id, name, taxRateId),
    )

    @Test
    fun `version 2 products keep their tax rate`() {
        val name = "migration-v2.db"
        createDatabase(name, 2) {
            execSQL("INSERT INTO tax_rates (id, name, rateMilliPercent, sortOrder) VALUES (1, 'GST', 10000, 0)")
            execSQL(
                "INSERT INTO products (id, name, priceMinor, taxRateId, categoryId, sku, sortOrder) " +
                    "VALUES (1, 'Latte', 450, 1, NULL, 'L1', 0)",
            )
        }
        migrated(name) { db ->
            val dao = db.catalogDao()
            assertThat(dao.productsOnce().single()).isEqualTo(ProductEntity(1, "Latte", 450, 1, null, "L1", 0))
            assertThat(dao.productCountForTaxRate(1)).isEqualTo(1)
            assertThat(dao.taxRatesOnce()).hasSize(1)
        }
    }

    @Test
    fun `version 3 untaxed products move to a new zero rate`() {
        val name = "migration-v3-new.db"
        createDatabase(name, 3) {
            execSQL("INSERT INTO tax_rates (id, name, rateMilliPercent, sortOrder) VALUES (1, 'GST', 10000, 0)")
            insertProduct(1, "Latte", 1)
            insertProduct(2, "Stamp", null)
            insertProduct(3, "Card", null)
        }
        migrated(name) { db ->
            val dao = db.catalogDao()
            val zero = dao.taxRatesOnce().single { it.rateMilliPercent == 0 }
            assertThat(zero.name).isEqualTo("Zero rated")
            assertThat(zero.sortOrder).isEqualTo(1)
            assertThat(dao.productsOnce().associate { it.name to it.taxRateId })
                .containsExactly("Latte", 1L, "Stamp", zero.id, "Card", zero.id)
            // New products carry on after the existing ids.
            assertThat(dao.insert(ProductEntity(name = "Tea", priceMinor = 350, taxRateId = 1))).isEqualTo(4)
        }
    }

    @Test
    fun `version 3 untaxed products use an existing zero rate`() {
        val name = "migration-v3-existing.db"
        createDatabase(name, 3) {
            execSQL("INSERT INTO tax_rates (id, name, rateMilliPercent, sortOrder) VALUES (1, 'GST', 10000, 0)")
            execSQL("INSERT INTO tax_rates (id, name, rateMilliPercent, sortOrder) VALUES (2, 'GST-free', 0, 1)")
            insertProduct(1, "Stamp", null)
            insertProduct(2, "Water", 2)
        }
        migrated(name) { db ->
            val dao = db.catalogDao()
            assertThat(dao.taxRatesOnce().map { it.name }).containsExactly("GST", "GST-free")
            assertThat(dao.productsOnce().map { it.taxRateId }).containsExactly(2L, 2L)
        }
    }
}
