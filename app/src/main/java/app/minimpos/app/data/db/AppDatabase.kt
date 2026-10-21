package app.minimpos.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** The current Room database, `minimpos.db`: catalogue, sales and refunds. Its schema is exported to `app/schemas/`. */
@TypeConverters(StoredReasonConverter::class, PaymentContextConverter::class)
@Database(
    entities = [
        TaxRateEntity::class,
        CategoryEntity::class,
        ProductEntity::class,
        CatalogImportEntity::class,
        SaleEntity::class,
        SaleLineEntity::class,
        SaleTenderEntity::class,
        RefundEntity::class,
        HistorySwitchEntity::class,
    ],
    version = 14,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    /** Tax rates, categories and products. */
    abstract fun catalogDao(): CatalogDao

    /** Sales and their lines. */
    abstract fun saleDao(): SaleDao

    /** Refunds. */
    abstract fun refundDao(): RefundDao

    /** Opening the current database. */
    companion object {
        /** Preserves all released 1.0.x version-10 data when upgrading to version 11. */
        val MIGRATION_10_11: Migration =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `sales` ADD COLUMN `poiReconciliationId` TEXT")
                    db.execSQL("ALTER TABLE `sales` ADD COLUMN `shopperRating` INTEGER")
                    db.execSQL("ALTER TABLE `sales` ADD COLUMN `paidMinor` INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE `sales` ADD COLUMN `donationMinor` INTEGER")
                    db.execSQL("ALTER TABLE `sales` ADD COLUMN `offlineMode` TEXT")
                    db.execSQL("ALTER TABLE `sales` ADD COLUMN `unconfirmedBatchCount` INTEGER")
                    db.execSQL("ALTER TABLE `refunds` ADD COLUMN `poiReconciliationId` TEXT")
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `sale_tenders` (" +
                            "`id` TEXT NOT NULL, `saleId` TEXT NOT NULL, `sequence` INTEGER NOT NULL, " +
                            "`requestedMinor` INTEGER NOT NULL, `authorisedMinor` INTEGER, `status` TEXT NOT NULL, " +
                            "`poiTransactionId` TEXT, `poiTimestamp` TEXT, `pspReference` TEXT, " +
                            "`paymentBrand` TEXT, `maskedPan` TEXT, `createdAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`id`), FOREIGN KEY(`saleId`) REFERENCES `sales`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_sale_tenders_saleId` ON `sale_tenders` (`saleId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_sale_tenders_poiTransactionId` ON `sale_tenders` (`poiTransactionId`)")
                }
            }

        /**
         * Preserves all released 1.1.x version-11 data when upgrading to version 12, which records saved-card charges
         * and refunds sent through the Checkout API; every existing row is neither.
         */
        val MIGRATION_11_12: Migration =
            object : Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `sales` ADD COLUMN `savedCard` INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE `sales` ADD COLUMN `chargedCardId` TEXT")
                    db.execSQL("ALTER TABLE `sales` ADD COLUMN `chargeRecurringModel` TEXT")
                    db.execSQL("ALTER TABLE `refunds` ADD COLUMN `checkoutRefund` INTEGER NOT NULL DEFAULT 0")
                }
            }

        /** Preserves all released version-12 data when upgrading to version 13. */
        val MIGRATION_12_13: Migration =
            object : Migration(12, 13) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `sales` ADD COLUMN `busyServiceId` TEXT")
                }
            }

        /** Preserves version-13 history; legacy adjustments retain an unknown first-send time. */
        val MIGRATION_13_14: Migration =
            object : Migration(13, 14) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `sales` ADD COLUMN `adjustmentStartedAt` INTEGER")
                }
            }

        /** Opens lazily on first query with versioned migrations; no destructive fallback is configured. */
        fun create(context: Context): AppDatabase =
            Room
                .databaseBuilder(context, AppDatabase::class.java, "minimpos.db")
                .addMigrations(MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
                .build()
    }
}
