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
        GiftCardOperationEntity::class,
    ],
    version = 24,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    /** Tax rates, categories and products. */
    abstract fun catalogDao(): CatalogDao

    /** Sales and their lines. */
    abstract fun saleDao(): SaleDao

    /** Refunds. */
    abstract fun refundDao(): RefundDao

    /** Stored-value (gift-card) stages. */
    abstract fun giftCardDao(): GiftCardDao

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

        /** Adds covering activity and method indexes without changing retained financial records. */
        val MIGRATION_14_15: Migration =
            object : Migration(14, 15) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("DROP INDEX IF EXISTS `index_sales_createdAt`")
                    db.execSQL("DROP INDEX IF EXISTS `index_refunds_createdAt`")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_sales_createdAt_id` ON `sales` (`createdAt`, `id`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_sales_processedAt_id` ON `sales` (`processedAt`, `id`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_sales_captureProcessedAt_id` ON `sales` (`captureProcessedAt`, `id`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_sales_captureStartedAt_id` ON `sales` (`captureStartedAt`, `id`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_refunds_createdAt_id` ON `refunds` (`createdAt`, `id`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_refunds_processedAt_id` ON `refunds` (`processedAt`, `id`)")
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_sales_paymentBrand_paymentMethodVariant_requestedWallet` " +
                            "ON `sales` (`paymentBrand`, `paymentMethodVariant`, `requestedWallet`)",
                    )
                }
            }

        /** Adds shopper token lookup ordering without changing stored payment or recovery facts. */
        val MIGRATION_15_16: Migration =
            object : Migration(15, 16) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_sales_shopperReference_createdAt_id` " +
                            "ON `sales` (`shopperReference`, `createdAt`, `id`)",
                    )
                }
            }

        /** Keeps all version-16 operations and history; older payments did not request POS audio guidance. */
        val MIGRATION_16_17: Migration =
            object : Migration(16, 17) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `sales` ADD COLUMN `audioGuidance` INTEGER NOT NULL DEFAULT 0")
                }
            }

        /** Preserves every existing amount and recovery identity; historical fee components remain unknown. */
        val MIGRATION_17_18: Migration =
            object : Migration(17, 18) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    listOf("surchargeMinor", "terminalPaidMinor", "cashBackMinor", "tenderRequestedMinor").forEach {
                        db.execSQL("ALTER TABLE `sales` ADD COLUMN `$it` INTEGER")
                    }
                    listOf("tipMinor", "surchargeMinor", "cashBackMinor").forEach {
                        db.execSQL("ALTER TABLE `sale_tenders` ADD COLUMN `$it` INTEGER")
                    }
                    listOf("serviceId", "context", "poiReconciliationId", "customerReceiptJson", "cashierReceiptJson").forEach {
                        db.execSQL("ALTER TABLE `sale_tenders` ADD COLUMN `$it` TEXT")
                    }
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_sale_tenders_createdAt_saleId` ON `sale_tenders` (`createdAt`, `saleId`)")
                    db.execSQL("ALTER TABLE `refunds` ADD COLUMN `tenderId` TEXT")
                    db.execSQL("ALTER TABLE `refunds` ADD COLUMN `surchargeMinor` INTEGER")
                    db.execSQL("ALTER TABLE `refunds` ADD COLUMN `tipMinor` INTEGER")
                }
            }

        /**
         * Preserves every version-18 sale, tender and refund: existing tenders are approved cards with no allocation,
         * and existing refunds returned no cash.
         */
        val MIGRATION_18_19: Migration =
            object : Migration(18, 19) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `sale_tenders` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'CARD'")
                    db.execSQL("ALTER TABLE `sale_tenders` ADD COLUMN `allocationJson` TEXT")
                    db.execSQL("ALTER TABLE `sale_tenders` ADD COLUMN `cashReceivedMinor` INTEGER")
                    db.execSQL("ALTER TABLE `sale_tenders` ADD COLUMN `changeMinor` INTEGER")
                    db.execSQL("ALTER TABLE `sale_tenders` ADD COLUMN `environment` TEXT")
                    db.execSQL("ALTER TABLE `refunds` ADD COLUMN `cash` INTEGER NOT NULL DEFAULT 0")
                }
            }

        /**
         * Preserves every version-19 sale, tender and refund: existing tenders were not paid by gift card, and the new
         * stored-value table starts empty.
         */
        val MIGRATION_19_20: Migration =
            object : Migration(19, 20) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `sale_tenders` ADD COLUMN `giftCard` TEXT")
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `gift_card_operations` (`id` TEXT NOT NULL, `groupId` TEXT NOT NULL, " +
                            "`stage` TEXT NOT NULL, `category` TEXT, `createdAt` INTEGER NOT NULL, `processedAt` INTEGER, " +
                            "`status` TEXT NOT NULL, `provider` TEXT, `providerCode` TEXT, `entry` TEXT, `cardLastFour` TEXT, " +
                            "`currency` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, `approvedMinor` INTEGER, " +
                            "`balanceMinor` INTEGER, `merchantReference` TEXT NOT NULL, `serviceId` TEXT, `poiId` TEXT, " +
                            "`poiTransactionId` TEXT, `poiTimestamp` TEXT, `pspReference` TEXT, `poiReconciliationId` TEXT, " +
                            "`funds` TEXT, `originalId` TEXT, `message` TEXT, `errorCondition` TEXT, `reason` TEXT, `context` TEXT, " +
                            "`environment` TEXT, `customerReceiptJson` TEXT, `cashierReceiptJson` TEXT, PRIMARY KEY(`id`))",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_gift_card_operations_createdAt_id` " +
                            "ON `gift_card_operations` (`createdAt`, `id`)",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_gift_card_operations_groupId` ON `gift_card_operations` (`groupId`)")
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_gift_card_operations_processedAt_id` " +
                            "ON `gift_card_operations` (`processedAt`, `id`)",
                    )
                }
            }

        /** Adds nullable request/answer facts without inventing plans for existing or unfinished tenders. */
        val MIGRATION_20_21: Migration =
            object : Migration(20, 21) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `sale_tenders` ADD COLUMN `mexicanPayment` TEXT")
                    db.execSQL("ALTER TABLE `sale_tenders` ADD COLUMN `returnedPaymentType` TEXT")
                    db.execSQL("ALTER TABLE `sale_tenders` ADD COLUMN `returnedInstallments` TEXT")
                    db.execSQL("ALTER TABLE `sale_tenders` ADD COLUMN `returnedPlanId` TEXT")
                }
            }

        /**
         * Adds meal-voucher facts: product and sold-line eligibility (false for every existing row, so nothing becomes
         * voucher-payable by itself) and nullable tender request/answer facts, preserving unfinished tenders.
         */
        val MIGRATION_21_22: Migration =
            object : Migration(21, 22) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `products` ADD COLUMN `mealVoucherEligible` INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE `sale_lines` ADD COLUMN `mealVoucherEligible` INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE `sale_tenders` ADD COLUMN `mealVoucher` TEXT")
                    db.execSQL("ALTER TABLE `sale_tenders` ADD COLUMN `fundingSource` TEXT")
                    db.execSQL("ALTER TABLE `sale_tenders` ADD COLUMN `paymentMethodVariant` TEXT")
                }
            }

        /** Preserves purchase and unfinished identities; old rows acquire no requested cash or handover. */
        val MIGRATION_22_23: Migration =
            object : Migration(22, 23) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    listOf("sales", "sale_tenders").forEach { table ->
                        db.execSQL("ALTER TABLE `$table` ADD COLUMN `requestedCashBackMinor` INTEGER")
                        if (table == "sales") db.execSQL("ALTER TABLE `$table` ADD COLUMN `cashHandedOverAt` INTEGER")
                    }
                }
            }

        /**
         * Makes a refund's original payment optional for unreferenced refunds. SQLite cannot relax NOT NULL in place, so
         * `refunds` is rebuilt with every row, unfinished ones included, and its indices; existing rows stay referenced.
         */
        val MIGRATION_23_24: Migration =
            object : Migration(23, 24) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `refunds_new` (`id` TEXT NOT NULL, `saleId` TEXT, `createdAt` INTEGER NOT NULL, " +
                            "`merchantReference` TEXT NOT NULL, `originalTransactionId` TEXT, `originalTimestamp` TEXT, " +
                            "`originalReference` TEXT, `currency` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, " +
                            "`full` INTEGER NOT NULL, `status` TEXT NOT NULL, `serviceId` TEXT, `pspReference` TEXT, `message` TEXT, " +
                            "`customerReceiptJson` TEXT, " +
                            "`linesJson` TEXT, `cancellation` INTEGER NOT NULL DEFAULT 0, `reason` TEXT, `context` TEXT, " +
                            "`processedAt` INTEGER, `poiReconciliationId` TEXT, `checkoutRefund` INTEGER NOT NULL DEFAULT 0, " +
                            "`tenderId` TEXT, `surchargeMinor` INTEGER, `tipMinor` INTEGER, `cash` INTEGER NOT NULL DEFAULT 0, " +
                            "`unreferenced` INTEGER NOT NULL DEFAULT 0, `unreferencedReason` TEXT, `acquirerResponseCode` TEXT, " +
                            "`cancelStatus` TEXT, `cancelStartedAt` INTEGER, `cancelReason` TEXT, `cancelPspReference` TEXT, " +
                            "PRIMARY KEY(`id`))",
                    )
                    val columns =
                        "`id`, `saleId`, `createdAt`, `merchantReference`, `originalTransactionId`, `originalTimestamp`, " +
                            "`originalReference`, `currency`, `amountMinor`, `full`, `status`, `serviceId`, `pspReference`, " +
                            "`message`, `customerReceiptJson`, `linesJson`, `cancellation`, `reason`, `context`, `processedAt`, " +
                            "`poiReconciliationId`, `checkoutRefund`, `tenderId`, `surchargeMinor`, `tipMinor`, `cash`"
                    db.execSQL("INSERT INTO `refunds_new` ($columns) SELECT $columns FROM `refunds`")
                    db.execSQL("DROP TABLE `refunds`")
                    db.execSQL("ALTER TABLE `refunds_new` RENAME TO `refunds`")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_refunds_createdAt_id` ON `refunds` (`createdAt`, `id`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_refunds_saleId` ON `refunds` (`saleId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_refunds_processedAt_id` ON `refunds` (`processedAt`, `id`)")
                }
            }

        /** Opens lazily on first query with versioned migrations; no destructive fallback is configured. */
        fun create(context: Context): AppDatabase =
            Room
                .databaseBuilder(context, AppDatabase::class.java, "minimpos.db")
                .addMigrations(
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                    MIGRATION_21_22,
                    MIGRATION_22_23,
                    MIGRATION_23_24,
                ).build()
    }
}
