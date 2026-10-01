package io.minimpos.app.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.minimpos.app.data.db.RefundEntity
import io.minimpos.app.data.db.RefundStatus
import io.minimpos.app.data.db.SaleKind
import io.minimpos.app.data.db.SaleStatus
import io.minimpos.app.data.db.SaleWithLines
import io.minimpos.app.data.repo.HistoryItem
import io.minimpos.app.data.repo.HistoryRepository
import io.minimpos.app.data.repo.SaleRepository
import io.minimpos.app.data.settings.AppSettings
import io.minimpos.app.feature.launchWrite
import io.minimpos.app.feature.sale.ActionState
import io.minimpos.app.feature.sale.ResultMessages
import io.minimpos.app.feature.sale.toState
import io.minimpos.app.payment.CaptureResult
import io.minimpos.app.payment.Captures
import io.minimpos.app.payment.PaymentStart
import io.minimpos.app.payment.ReceiptDelivery
import io.minimpos.app.payment.TransactionLifecycle
import io.minimpos.app.refund.PaymentHold
import io.minimpos.app.refund.RefundStart
import io.minimpos.app.refund.Refundability
import io.minimpos.app.refund.RefundablePayment
import io.minimpos.app.terminal.CaptureMode
import io.minimpos.app.terminal.TerminalState
import io.minimpos.core.receipt.ReceiptCopy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Totals per currency for one day, of the items the filter shows: approved sales (with their tips), accepted refunds
 * and the pre-authorisations still held. A pre-authorisation counts as a sale only once it is captured, and
 * cancellations of payments that only held their amount are neither sales nor refunds.
 *
 * @property salesMinor Approved sales' amounts (with tips, or what a pre-authorisation captured) by ISO 4217 currency
 *   code, in minor units.
 * @property saleCount Number of approved sales.
 * @property refundsMinor Accepted refunds' amounts by currency code, in minor units.
 * @property refundCount Number of accepted refunds.
 * @property preAuthsMinor Amounts held by approved pre-authorisations that were neither cancelled nor captured, by
 *   currency code, in minor units.
 * @property preAuthCount Number of those pre-authorisations.
 * @property tipsMinor The tips included in [salesMinor], by currency code, in minor units; only currencies with tips.
 */
data class DayTotals(
    val salesMinor: Map<String, Long>,
    val saleCount: Int,
    val refundsMinor: Map<String, Long>,
    val refundCount: Int,
    val preAuthsMinor: Map<String, Long> = emptyMap(),
    val preAuthCount: Int = 0,
    val tipsMinor: Map<String, Long> = emptyMap(),
)

/**
 * One day of history, in the device's time zone.
 *
 * @property date The local date.
 * @property totals The day's totals.
 * @property items The day's sales and refunds, newest first.
 */
data class HistoryDay(
    val date: LocalDate,
    val totals: DayTotals,
    val items: List<HistoryItem>,
)

/** Which transactions the history list shows. */
enum class HistoryFilter {
    /** Every sale and refund. */
    ALL,

    /** Sales only (not pre-authorisations), whatever their status. */
    SALES,

    /** Sales taken for tipping on the receipt whose tip has not been entered yet. */
    AWAITING_TIP,

    /** Pre-authorisations and their cancellations, whatever their status. */
    PRE_AUTHS,

    /** Refunds only (not cancellations of pre-authorisations), whatever their status. */
    REFUNDS,

    /** Sales and refunds whose outcome is still pending or unknown, which need checking. */
    ISSUES,
}

/**
 * What the history list shows.
 *
 * @property loaded False until history has been read.
 * @property filter The selected filter.
 * @property days The matching transactions grouped by day, newest day first.
 */
data class HistoryUiState(
    val loaded: Boolean = false,
    val filter: HistoryFilter = HistoryFilter.ALL,
    val days: List<HistoryDay> = emptyList(),
)

/** The history list: sales and refunds by day, with daily totals. */
class HistoryViewModel(
    history: HistoryRepository,
    /** The time zone days are counted in; read for each update, so it follows the device's setting. */
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : ViewModel() {
    private val filter = MutableStateFlow(HistoryFilter.ALL)

    /** The list state, updated whenever history or the filter changes. */
    val state: StateFlow<HistoryUiState> =
        combine(history.items(), filter) { items, selected ->
            val preAuths =
                items
                    .filterIsInstance<HistoryItem.Sale>()
                    .filter { it.sale.kind == SaleKind.PRE_AUTHORISATION }
                    .map { it.id }
                    .toSet()
            HistoryUiState(loaded = true, filter = selected, days = group(items.filter { matches(it, selected, preAuths) }))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    /** Shows the transactions matching [value]. */
    fun setFilter(value: HistoryFilter) {
        filter.value = value
    }

    /** Whether [item] shows with [filter]; [preAuths] are the IDs of the pre-authorisations, whose cancellations show with them. */
    private fun matches(
        item: HistoryItem,
        filter: HistoryFilter,
        preAuths: Set<String>,
    ): Boolean =
        when (filter) {
            HistoryFilter.ALL -> {
                true
            }

            HistoryFilter.SALES -> {
                item is HistoryItem.Sale && item.sale.kind == SaleKind.SALE
            }

            HistoryFilter.AWAITING_TIP -> {
                item is HistoryItem.Sale && PaymentHold.awaitingTip(item.sale)
            }

            HistoryFilter.PRE_AUTHS -> {
                when (item) {
                    is HistoryItem.Sale -> item.sale.kind == SaleKind.PRE_AUTHORISATION
                    is HistoryItem.Refund -> item.refund.cancellation && item.refund.saleId in preAuths
                }
            }

            HistoryFilter.REFUNDS -> {
                item is HistoryItem.Refund && !item.refund.cancellation
            }

            HistoryFilter.ISSUES -> {
                needsAttention(item)
            }
        }

    /** Whether [item]'s outcome is still pending or unknown. */
    private fun needsAttention(item: HistoryItem): Boolean =
        when (item) {
            is HistoryItem.Sale -> item.sale.status == SaleStatus.UNKNOWN || item.sale.status == SaleStatus.PENDING
            is HistoryItem.Refund -> item.refund.status == RefundStatus.UNKNOWN || item.refund.status == RefundStatus.PENDING
        }

    private fun group(items: List<HistoryItem>): List<HistoryDay> =
        items
            .groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zone()).toLocalDate() }
            .map { (date, dayItems) ->
                val approved =
                    dayItems
                        .filterIsInstance<HistoryItem.Sale>()
                        .map { it.sale }
                        .filter { it.status == SaleStatus.APPROVED }
                // A cancelled hold was never charged.
                val cancelled =
                    approved.filter {
                        (it.kind == SaleKind.PRE_AUTHORISATION || it.tipOnReceipt) && it.refundedMinor > 0 &&
                            !it.captured
                    }
                val (sales, held) =
                    (approved - cancelled.toSet())
                        .partition { it.kind == SaleKind.SALE || it.captured }
                val tipped = sales.filter { (it.tipMinor ?: 0) > 0 }
                val refunds =
                    dayItems
                        .filterIsInstance<HistoryItem.Refund>()
                        .map { it.refund }
                        .filter { it.status == RefundStatus.REQUESTED && !it.cancellation }
                HistoryDay(
                    date = date,
                    totals =
                        DayTotals(
                            salesMinor = sales.groupBy { it.currency }.mapValues { (_, list) -> list.sumOf { it.amountMinor } },
                            saleCount = sales.size,
                            refundsMinor = refunds.groupBy { it.currency }.mapValues { (_, list) -> list.sumOf { it.amountMinor } },
                            refundCount = refunds.size,
                            preAuthsMinor = held.groupBy { it.currency }.mapValues { (_, list) -> list.sumOf { it.heldMinor } },
                            preAuthCount = held.size,
                            tipsMinor = tipped.groupBy { it.currency }.mapValues { (_, list) -> list.sumOf { it.tipMinor ?: 0 } },
                        ),
                    items = dayItems,
                )
            }.sortedByDescending { it.date }
}

/**
 * What the sale detail screen shows.
 *
 * @property record The sale, or null until loaded (or when it has been pruned).
 * @property refunds Refunds made from this terminal against the sale, newest first.
 * @property settings The current settings.
 * @property printerAvailable Whether printing is offered.
 * @property print The latest print.
 * @property email The latest email.
 * @property rechecking Whether a transaction status check is running.
 * @property recheckMessage Shown when the check found the outcome still unknown; null otherwise.
 * @property captureMode Whether captures go through the Checkout API or are made in the Customer Area.
 * @property retry The latest retry of the capture.
 */
data class SaleDetailUiState(
    val record: SaleWithLines? = null,
    val refunds: List<RefundEntity> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val printerAvailable: Boolean = false,
    val print: ActionState = ActionState(),
    val email: ActionState = ActionState(),
    val rechecking: Boolean = false,
    val recheckMessage: String? = null,
    val captureMode: CaptureMode = CaptureMode.API,
    val retry: ActionState = ActionState(),
) {
    /** Whether a refund can be offered, as [RefundablePayment.check] decides. */
    val canRefund: Boolean get() = record?.let(RefundablePayment::check) is Refundability.Refundable

    /** Whether the payment can be cancelled (it only holds its amount), as [RefundablePayment.canCancel] decides. */
    val canCancel: Boolean get() = record?.let(RefundablePayment::canCancel) == true

    /** Whether the tip written on the receipt can be entered ([PaymentHold.canEnterTip]). */
    val canEnterTip: Boolean get() = record?.sale?.let(PaymentHold::canEnterTip) == true

    /** Whether the pre-authorisation can be captured ([PaymentHold.canCapture]). */
    val canCapture: Boolean get() = record?.sale?.let(PaymentHold::canCapture) == true

    /** Whether what the pre-authorisation holds can be adjusted, which needs the Checkout API. */
    val canAdjust: Boolean get() = canCapture && captureMode == CaptureMode.API

    /** Whether the capture can be sent again as it was ([PaymentHold.canRetryCapture]). */
    val canRetryCapture: Boolean get() = record?.sale?.let(PaymentHold::canRetryCapture) == true
}

/**
 * What the sale detail screen can do to a stored sale besides delivering its receipt.
 *
 * @property payments The payments' lifecycle, for re-checking an unknown outcome.
 * @property refunds The refunds' lifecycle, which also runs cancellations of payments that only hold their amount.
 * @property captures Sends a capture again.
 */
class SaleOperations(
    val payments: TransactionLifecycle<PaymentStart>,
    val refunds: TransactionLifecycle<RefundStart>,
    val captures: Captures,
)

/**
 * One sale (or pre-authorisation) from history: its receipt, refunds, reprinting and emailing, re-checking an unknown
 * outcome, cancelling a payment that only holds its amount, and sending a capture again.
 *
 * @param saleId The sale shown.
 * @param sales Where it is stored.
 * @param receipts Prints and emails its receipt.
 * @param operations Re-checks, cancels and captures.
 * @param settings The current settings.
 * @param terminal Whether printing is offered, and how captures are made.
 * @param messages Localised outcome messages.
 * @param clock Stamps the merchant reference of a cancellation.
 * @param zone The time zone of that reference.
 */
class SaleDetailViewModel(
    private val saleId: String,
    sales: SaleRepository,
    private val receipts: ReceiptDelivery,
    private val operations: SaleOperations,
    settings: StateFlow<AppSettings>,
    terminal: StateFlow<TerminalState>,
    private val messages: ResultMessages,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : ViewModel() {
    private val local = MutableStateFlow(SaleDetailUiState())

    /** The screen state, updated whenever the sale, its refunds, settings, printer or an action changes. */
    val state: StateFlow<SaleDetailUiState> =
        combine(sales.observe(saleId), sales.refundsForSale(saleId), settings, terminal, local) {
            record,
            refunds,
            appSettings,
            status,
            ui,
            ->
            ui.copy(
                record = record,
                refunds = refunds,
                settings = appSettings,
                printerAvailable = status.printerAvailable,
                captureMode = status.captureMode,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SaleDetailUiState())

    /** Reprints [copy] of the receipt. */
    fun print(copy: ReceiptCopy = ReceiptCopy.CUSTOMER) {
        local.update { it.copy(print = ActionState(running = true)) }
        viewModelScope.launch {
            val result = receipts.printSale(saleId, copy).result.toState(messages.printed)
            local.update { it.copy(print = result) }
        }
    }

    /** Emails the receipt to [to]. */
    fun email(to: String) {
        local.update { it.copy(email = ActionState(running = true)) }
        viewModelScope.launch {
            val result = receipts.emailSale(saleId, to).toState(messages.emailed.format(Locale.getDefault(), to))
            local.update { it.copy(email = result) }
        }
    }

    /**
     * Starts cancelling the pre-authorisation (a full reversal, see [RefundablePayment.cancellation]) and returns the
     * cancellation's ID, to follow it like a refund; null when it cannot be cancelled or a refund is already running.
     */
    fun cancel(): String? {
        val current = state.value
        val request =
            current.record?.let {
                RefundablePayment.cancellation(it, current.settings.payment.referencePrefix, clock.instant(), zone())
            } ?: return null
        return runCatching { operations.refunds.start(request) }.getOrNull()
    }

    /** Sends the capture again as it was (same amount and idempotency key); the sale updates with the outcome. */
    fun retryCapture() {
        if (local.value.retry.running) return
        local.update { it.copy(retry = ActionState(running = true)) }
        launchWrite({ operations.captures.retryCapture(saleId) }) { result ->
            val failure = (result as? CaptureResult.Failed)?.message
            local.update {
                it.copy(
                    retry =
                        if (failure == null) {
                            ActionState(done = true)
                        } else {
                            ActionState(message = messages.notCaptured.format(Locale.getDefault(), failure), isError = true)
                        },
                )
            }
        }
    }

    /** Asks the terminal for the transaction status of a sale whose outcome is unknown. */
    fun recheck() {
        local.update { it.copy(rechecking = true, recheckMessage = null) }
        viewModelScope.launch {
            val settled = operations.payments.recheck(saleId)
            local.update { it.copy(rechecking = false, recheckMessage = if (settled) null else messages.stillUnknown) }
        }
    }
}
