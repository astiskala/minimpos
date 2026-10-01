package io.minimpos.app.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 4: every product has a tax rate (the "Tax applies" switch is gone; a 0% rate does its job). Products that were
 * untaxed move to the first 0% rate, or to a new one called [zeroRateName], and the products table is rebuilt with a
 * required tax rate column.
 */
class TaxRateRequiredMigration(
    private val zeroRateName: String,
) : Migration(startVersion = 3, endVersion = 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val untaxed = db.query("SELECT COUNT(*) FROM products WHERE taxRateId IS NULL").use { it.moveToFirst() && it.getInt(0) > 0 }
        if (untaxed) {
            val zeroRateId =
                db.query("SELECT id FROM tax_rates WHERE rateMilliPercent = 0 ORDER BY sortOrder, id LIMIT 1").use {
                    if (it.moveToFirst()) it.getLong(0) else null
                } ?: db.insert(
                    "tax_rates",
                    SQLiteDatabase.CONFLICT_ABORT,
                    ContentValues().apply {
                        put("name", zeroRateName)
                        put("rateMilliPercent", 0)
                        put(
                            "sortOrder",
                            db.query("SELECT COALESCE(MAX(sortOrder) + 1, 0) FROM tax_rates").use {
                                it.moveToFirst()
                                it.getInt(0)
                            },
                        )
                    },
                )
            db.execSQL("UPDATE products SET taxRateId = ? WHERE taxRateId IS NULL", arrayOf<Any>(zeroRateId))
        }
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `_new_products` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                "`priceMinor` INTEGER NOT NULL, `taxRateId` INTEGER NOT NULL, `categoryId` INTEGER, `sku` TEXT, " +
                "`sortOrder` INTEGER NOT NULL, " +
                "FOREIGN KEY(`taxRateId`) REFERENCES `tax_rates`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , " +
                "FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        )
        db.execSQL(
            "INSERT INTO `_new_products` (`id`, `name`, `priceMinor`, `taxRateId`, `categoryId`, `sku`, `sortOrder`) " +
                "SELECT `id`, `name`, `priceMinor`, `taxRateId`, `categoryId`, `sku`, `sortOrder` FROM `products`",
        )
        db.execSQL("DROP TABLE `products`")
        db.execSQL("ALTER TABLE `_new_products` RENAME TO `products`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_products_taxRateId` ON `products` (`taxRateId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_products_categoryId` ON `products` (`categoryId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_products_sku` ON `products` (`sku`)")
    }
}
