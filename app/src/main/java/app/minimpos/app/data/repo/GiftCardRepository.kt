package app.minimpos.app.data.repo

import androidx.room.withTransaction
import app.minimpos.app.data.db.AppDatabase
import app.minimpos.app.data.db.GiftCardOperationEntity
import app.minimpos.app.data.db.GiftCardStage
import app.minimpos.app.data.db.GiftCardStatus
import app.minimpos.app.data.db.StoredReason
import app.minimpos.core.giftcard.GiftCardProvider
import app.minimpos.core.payment.PaymentContext
import app.minimpos.terminal.client.TerminalClient
import app.minimpos.terminal.client.TransactionDetails
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Stored stored-value stages ([GiftCardOperationEntity]) and every write to them. A sent stage is stored PENDING before
 * anything is sent ([open]), then only changes through [sending], [recordContext] and [settle]; local stages are
 * stored by [open] as done, and a cash handover is confirmed by [recordCashHandover]. Writes that start a stage check
 * the activity's current stages inside the same transaction, so duplicate and stale starts store nothing. Settling an
 * approved cash-out also stores the cash handover awaiting staff, in the same transaction. All functions are main-safe.
 *
 * @param db The database.
 * @param now Epoch milliseconds stamped when an answer has no terminal timestamp and on local confirmations.
 */
class GiftCardRepository(
    private val db: AppDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.giftCardDao()

    /**
     * Stores [stage] (PENDING for a sent stage, SUCCEEDED or AWAITING for a local one) when [allowed] accepts the
     * activity's current stages (empty for a new activity); false, storing nothing, otherwise.
     */
    suspend fun open(
        stage: GiftCardOperationEntity,
        allowed: (GiftCardActivity?) -> Boolean = { it == null },
    ): Boolean =
        db.withTransaction {
            val current = dao.group(stage.groupId).takeIf { it.isNotEmpty() }?.let(::GiftCardActivity)
            if (!allowed(current)) return@withTransaction false
            dao.insert(stage)
            true
        }

    /** Records that sent stage [id] is being sent to terminal [poiId]. */
    suspend fun sending(
        id: String,
        poiId: String,
    ) = update(id) { it.copy(poiId = poiId) }

    /** Records the non-secret [context] stage [id] is sent in. */
    suspend fun recordContext(
        id: String,
        context: PaymentContext,
    ) = update(id) { it.copy(context = context) }

    /**
     * Stores [status] for stage [id] with the terminal's [details] (when it answered), its own words [message] and the
     * [reason] there is no answer. An approved cash-out also stores its cash handover, awaiting staff, once. False only
     * when the stage is gone; storage failures throw.
     */
    suspend fun settle(
        id: String,
        status: GiftCardStatus,
        message: String?,
        details: TransactionDetails?,
        reason: StoredReason?,
    ): Boolean =
        db.withTransaction {
            val stage = dao.stage(id) ?: return@withTransaction false
            val settled =
                stage.answered(details).copy(
                    status = status,
                    processedAt = if (status == GiftCardStatus.UNKNOWN) null else details?.answeredAt() ?: now(),
                    approvedMinor = if (status == GiftCardStatus.SUCCEEDED) details?.amountMinor ?: stage.amountMinor else null,
                    message = message,
                    errorCondition = details?.errorCondition,
                    reason = reason,
                )
            dao.update(settled)
            if (stage.stage == GiftCardStage.CASH_OUT && settled.movedMinor > 0 &&
                dao.group(stage.groupId).none { it.stage == GiftCardStage.CASH_HANDOVER }
            ) {
                dao.insert(handover(settled))
            }
            true
        }

    /** The cash handover an approved [cashOut] owes the shopper, awaiting staff. */
    private fun handover(cashOut: GiftCardOperationEntity) =
        GiftCardOperationEntity(
            id = "${cashOut.id}-cash",
            groupId = cashOut.groupId,
            stage = GiftCardStage.CASH_HANDOVER,
            category = null,
            createdAt = now(),
            status = GiftCardStatus.AWAITING,
            provider = cashOut.provider,
            cardLastFour = cashOut.cardLastFour,
            currency = cashOut.currency,
            amountMinor = cashOut.movedMinor,
            merchantReference = cashOut.merchantReference,
            originalId = cashOut.id,
            environment = cashOut.context?.historyEnvironment ?: cashOut.environment,
        )

    /**
     * Records that the cash of handover stage [id] was handed to the shopper, as staff confirmed; false, storing
     * nothing, when it is gone, not a handover or no longer awaiting. Only [app.minimpos.app.payment.CashTenders]
     * records cash.
     */
    suspend fun recordCashHandover(id: String): Boolean =
        db.withTransaction {
            val stage =
                dao.stage(id)?.takeIf { it.stage == GiftCardStage.CASH_HANDOVER && it.status == GiftCardStatus.AWAITING }
                    ?: return@withTransaction false
            dao.update(stage.copy(status = GiftCardStatus.SUCCEEDED, processedAt = now()))
            true
        }

    /** Stage [id], or null. */
    suspend fun get(id: String): GiftCardOperationEntity? = dao.stage(id)

    /** Activity [groupId], or null when it has no stages. */
    suspend fun activity(groupId: String): GiftCardActivity? = dao.group(groupId).takeIf { it.isNotEmpty() }?.let(::GiftCardActivity)

    /** Observes activity [groupId]; null while it has no stages. */
    fun observe(groupId: String): Flow<GiftCardActivity?> =
        dao.observeGroup(groupId).map { stages -> stages.takeIf { it.isNotEmpty() }?.let(::GiftCardActivity) }

    /** Observes activities begun since [since] (epoch milliseconds) or still unresolved, newest first. */
    fun recent(since: Long): Flow<List<GiftCardActivity>> =
        dao.observeRecent(since, RECENT_LIMIT).map { GiftCardActivity.of(it.asReversed()) }

    /** Activities begun since a day before [now] (epoch milliseconds) or still unresolved, for undo eligibility. */
    suspend fun recentActivities(now: Long): List<GiftCardActivity> =
        GiftCardActivity.of(dao.recentStages(now - GiftCardProvider.UNDO_WINDOW_MILLIS, RECENT_LIMIT).asReversed())

    private suspend fun update(
        id: String,
        change: (GiftCardOperationEntity) -> GiftCardOperationEntity,
    ) = db.withTransaction {
        dao.stage(id)?.let { dao.update(change(it)) }
    }

    private companion object {
        const val RECENT_LIMIT = 512
    }
}

/** When the terminal answered, from its transaction timestamp; null when it gave none. */
internal fun TransactionDetails.answeredAt(): Long? = poiTimestamp?.let(TerminalClient::instantOf)?.toEpochMilli()

/** This stage with what the terminal's [details] identify: card digits, balance, transaction and receipts. */
internal fun GiftCardOperationEntity.answered(details: TransactionDetails?): GiftCardOperationEntity {
    if (details == null) return this
    return copy(
        balanceMinor = details.balanceMinor ?: balanceMinor,
        cardLastFour =
            details.maskedPan
                ?.filter { it in '0'..'9' }
                ?.takeLast(LAST_FOUR_DIGITS)
                ?.ifEmpty { null } ?: cardLastFour,
        poiTransactionId = details.poiTransactionId ?: poiTransactionId,
        poiTimestamp = details.poiTimestamp ?: poiTimestamp,
        pspReference = details.pspReference ?: pspReference,
        poiReconciliationId = details.poiReconciliationId ?: poiReconciliationId,
        customerReceiptJson = ReceiptLinesJson.encodeFields(details.customerReceipt) ?: customerReceiptJson,
        cashierReceiptJson = ReceiptLinesJson.encodeFields(details.cashierReceipt) ?: cashierReceiptJson,
    )
}

private const val LAST_FOUR_DIGITS = 4
