package io.minimpos.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 8: a cancelled hold is recorded as such (`sales.holdCancelled`) instead of as a refund of its whole amount.
 * Sales whose cancellation was accepted get the flag, and what that cancellation had marked as refunded (the amount and
 * every line's quantity) is cleared again, since a cancellation refunds nothing.
 */
object HoldCancelledMigration : Migration(startVersion = 7, endVersion = 8) {
    private const val CANCELLED = "SELECT saleId FROM refunds WHERE cancellation = 1 AND status = 'REQUESTED' AND saleId IS NOT NULL"

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `sales` ADD COLUMN `holdCancelled` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE `sales` SET `holdCancelled` = 1, `refundedMinor` = 0 WHERE `id` IN ($CANCELLED)")
        db.execSQL("UPDATE `sale_lines` SET `refundedQuantity` = 0 WHERE `saleId` IN ($CANCELLED)")
    }
}
