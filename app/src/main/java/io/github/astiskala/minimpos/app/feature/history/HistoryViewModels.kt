package io.github.astiskala.minimpos.app.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.astiskala.minimpos.app.data.db.RefundEntity
import io.github.astiskala.minimpos.app.data.db.RefundStatus
import io.github.astiskala.minimpos.app.data.db.SaleKind
import io.github.astiskala.minimpos.app.data.db.SaleStatus
import io.github.astiskala.minimpos.app.data.db.SaleWithLines
import io.github.astiskala.minimpos.app.data.repo.HistoryItem
import io.github.astiskala.minimpos.app.data.repo.HistoryRepository
import io.github.astiskala.minimpos.app.data.repo.RefundRepository
import io.github.astiskala.minimpos.app.data.settings.AppSettings
import io.github.astiskala.minimpos.app.feature.ActionState
import io.github.astiskala.minimpos.app.feature.CaptureStep
import io.github.astiskala.minimpos.app.feature.TransactionActions
import io.github.astiskala.minimpos.app.feature.TransactionActionsState
import io.github.astiskala.minimpos.app.feature.launchWrite
import io.github.astiskala.minimpos.app.feature.toState
import io.github.astiskala.minimpos.app.payment.Captures
import io.github.astiskala.minimpos.app.payment.PaymentStart
import io.github.astiskala.minimpos.app.payment.ReceiptDelivery
import io.github.astiskala.minimpos.app.payment.TransactionLifecycle
import io.github.astiskala.minimpos.app.refund.HistoryAccounting
import io.github.astiskala.minimpos.app.refund.HistoryActivityKind
import io.github.astiskala.minimpos.app.refund.PaymentAction
import io.github.astiskala.minimpos.app.refund.PaymentStanding
import io.github.astiskala.minimpos.app.refund.RefundStart
import io.github.astiskala.minimpos.app.refund.RefundablePayment
import io.github.astiskala.minimpos.app.refund.StoredPayment
import io.github.astiskala.minimpos.app.refund.StoredPayments
import io.github.astiskala.minimpos.app.refund.standing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Processing-day totals per currency for the activities the filter shows: approved sales, accepted captures with
 * their tips, accepted refunds and newly authorized holds. Later capture or cancellation does not erase original
 * hold activity. Manual-capture bills count as sales only on dated capture acceptance.
 *
 * @property salesMinor Approved sales' amounts (with tips, or what a pre-authorisation captured) by ISO 4217 currency
 *   code, in minor units.
 * @property saleCount Number of approved sales.
 * @property refundsMinor Accepted refunds' amounts by currency code, in minor units.
 * @property refundCount Number of accepted refunds.
 * @property preAuthsMinor Newly authorized manual-capture amounts by currency code, in minor units;
 *   unchanged by later capture or cancellation.
 * @property preAuthCount Number of newly authorized holds.
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
 * @property items Original transactions with dated activity on this day, newest activity first.
 * @property captures Sale IDs whose accepted capture occurred on this day.
 * @property times Most recent activity per original transaction, in epoch milliseconds.
 */
data class HistoryDay(
    val date: LocalDate,
    val totals: DayTotals,
    val items: List<HistoryItem>,
    /** Sales whose accepted capture occurred on this day. */
    val captures: Set<String> = emptySet(),
    /** Most recent dated activity per original transaction, in epoch milliseconds. */
    val times: Map<String, Long> = emptyMap(),
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
 * @property query The search field's text; see [HistorySearch] for what it matches.
 * @property method The payment method chosen, or null for any.
 * @property methods The payment methods to choose from: those of the sales in history.
 * @property hasHistory Whether history holds any transaction at all, matching or not.
 * @property canPrint Whether daily reports can print on the current destination.
 * @property print Latest whole-day print outcome.
 */
data class HistoryUiState(
    val loaded: Boolean = false,
    val filter: HistoryFilter = HistoryFilter.ALL,
    val days: List<HistoryDay> = emptyList(),
    val query: String = "",
    val method: PaymentMethodFilter? = null,
    val methods: List<PaymentMethodFilter> = emptyList(),
    val hasHistory: Boolean = false,
    /** Whether daily reports can print on the current destination. */
    val canPrint: Boolean = false,
    /** Latest whole-day print outcome. */
    val print: ActionState = ActionState(),
) {
    /** Whether the filter, the search or the payment method leaves transactions out. */
    val narrowed: Boolean get() = filter != HistoryFilter.ALL || HistorySearch(query, method).isActive
}

/** The history list: sales and refunds by day, with daily totals, filtered and searched. */
class HistoryViewModel(
    history: HistoryRepository,
    /** The time zone days are counted in; read for each update, so it follows the device's setting. */
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** Report delivery, absent only in list-only tests. */
    private val receipts: ReceiptDelivery? = null,
) : ViewModel() {
    private val print = MutableStateFlow(ActionState())
    private val canPrint = receipts?.let(TransactionActions::canPrintReports) ?: flowOf(false)
    private val filter = MutableStateFlow(HistoryFilter.ALL)
    private val query = MutableStateFlow("")
    private val method = MutableStateFlow<PaymentMethodFilter?>(null)

    /** The list state, updated whenever history, the filter, the search or the payment method changes. */
    val state: StateFlow<HistoryUiState> =
        combine(history.items(), filter, query, method) { items, selected, text, chosen ->
            val sales = items.filterIsInstance<HistoryItem.Sale>().associate { it.id to it.sale }
            val preAuths =
                sales.values
                    .filter { it.kind == SaleKind.PRE_AUTHORISATION }
                    .map { it.id }
                    .toSet()
            val search = HistorySearch(text, chosen)
            val shown =
                items.filter { item ->
                    val sale =
                        when (item) {
                            is HistoryItem.Sale -> item.sale
                            is HistoryItem.Refund -> item.refund.saleId?.let(sales::get)
                        }
                    matches(item, selected, preAuths) && search.matches(item, sale)
                }
            HistoryUiState(
                loaded = true,
                filter = selected,
                days = group(shown),
                query = text,
                method = chosen,
                methods = PaymentMethodFilter.available(sales.values.toList()),
                hasHistory = items.isNotEmpty(),
            )
        }.combine(combine(canPrint, print) { available, action -> available to action }) { list, (available, action) ->
            list.copy(canPrint = available, print = action)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    /** Prints every retained activity for [date], irrespective of active list filters; duplicate taps are ignored. */
    fun printDay(date: LocalDate) {
        val delivery = receipts ?: return
        if (!state.value.canPrint || print.value.running) return
        print.value = ActionState(running = true)
        viewModelScope.launch { print.value = TransactionActions.printReport(delivery, date) }
    }

    /** Shows the transactions matching [value]. */
    fun setFilter(value: HistoryFilter) {
        filter.value = value
    }

    /** Shows the transactions matching every word of [value] ([HistorySearch]). */
    fun setQuery(value: String) {
        query.value = value
    }

    /** Shows only the transactions paid with [value]; null shows every payment method. */
    fun setMethod(value: PaymentMethodFilter?) {
        method.value = value
    }

    /** Shows every transaction again: no filter, search or payment method. */
    fun showAll() {
        filter.value = HistoryFilter.ALL
        query.value = ""
        method.value = null
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
                item is HistoryItem.Sale && item.sale.standing == PaymentStanding.AWAITING_TIP
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
            is HistoryItem.Sale -> {
                item.sale.status == SaleStatus.UNKNOWN || item.sale.status == SaleStatus.PENDING || item.sale.adjustmentPending ||
                    item.sale.standing in
                    setOf(PaymentStanding.CAPTURE_FAILED, PaymentStanding.CAPTURE_UNKNOWN, PaymentStanding.CAPTURE_SENDING)
            }

            is HistoryItem.Refund -> {
                item.refund.status == RefundStatus.UNKNOWN || item.refund.status == RefundStatus.PENDING
            }
        }

    private fun group(items: List<HistoryItem>): List<HistoryDay> {
        val currentZone = zone()
        return HistoryAccounting
            .activities(items)
            .filter { it.at != null }
            .groupBy { Instant.ofEpochMilli(checkNotNull(it.at)).atZone(currentZone).toLocalDate() }
            .map { (date, activities) ->
                val totals = HistoryAccounting.totals(activities)
                val sorted = activities.sortedByDescending { it.at }
                HistoryDay(
                    date = date,
                    totals =
                        DayTotals(
                            salesMinor = totals.filterValues { it.saleCount > 0 }.mapValues { it.value.salesMinor },
                            saleCount = totals.values.sumOf { it.saleCount },
                            refundsMinor = totals.filterValues { it.refundCount > 0 }.mapValues { it.value.refundsMinor },
                            refundCount = totals.values.sumOf { it.refundCount },
                            preAuthsMinor = totals.filterValues { it.holdCount > 0 }.mapValues { it.value.heldMinor },
                            preAuthCount = totals.values.sumOf { it.holdCount },
                            tipsMinor = totals.filterValues { it.tipsMinor > 0 }.mapValues { it.value.tipsMinor },
                        ),
                    items = sorted.map { it.item }.distinctBy { it.id },
                    captures = activities.filter { it.kind == HistoryActivityKind.CAPTURE }.map { it.item.id }.toSet(),
                    times = sorted.distinctBy { it.item.id }.associate { it.item.id to checkNotNull(it.at) },
                )
            }.sortedByDescending { it.date }
    }
}

/**
 * What the sale detail screen shows.
 *
 * @property payment The sale with what can be done with it now, or null until loaded (or when it has been pruned).
 * @property refunds Refunds made from this terminal against the sale, newest first.
 * @property transaction The receipt, reprinting and emailing it, and re-checking an unknown outcome.
 * @property retry The latest retry of the capture.
 */
data class SaleDetailUiState(
    val payment: StoredPayment? = null,
    val refunds: List<RefundEntity> = emptyList(),
    val transaction: TransactionActionsState = TransactionActionsState(),
    val retry: ActionState = ActionState(),
) {
    /** The sale and its lines, or null until loaded. */
    val record: SaleWithLines? get() = payment?.record

    /** What can be done with the payment now (refund, cancel, enter the tip, capture, adjust, retry the capture). */
    val actions: Set<PaymentAction> get() = payment?.actions.orEmpty()
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
 * One sale (or pre-authorisation) from history: its receipt, refunds, reprinting and emailing and re-checking an
 * unknown outcome ([transaction]), cancelling a payment that only holds its amount, and sending a capture again.
 *
 * @param saleId The sale shown.
 * @param payments Follows it, with what can be done with it.
 * @param refunds Where its refunds and cancellations are stored.
 * @param receipts Prints and emails its receipt.
 * @param operations Re-checks, cancels and captures.
 * @param settings The current settings, for the reference prefix of a cancellation.
 * @param clock Stamps the merchant reference of a cancellation.
 * @param zone The time zone of that reference.
 */
class SaleDetailViewModel(
    private val saleId: String,
    payments: StoredPayments,
    refunds: RefundRepository,
    receipts: ReceiptDelivery,
    private val operations: SaleOperations,
    private val settings: StateFlow<AppSettings>,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : ViewModel() {
    private val local = MutableStateFlow(SaleDetailUiState())

    /** The receipt, reprinting and emailing it, and re-checking an unknown outcome. */
    val transaction = TransactionActions.forSale(viewModelScope, saleId, receipts, operations.payments, fresh = false)

    /** The screen state, updated whenever the sale, its refunds, the capture mode or an action changes. */
    val state: StateFlow<SaleDetailUiState> =
        combine(payments.observe(saleId), refunds.forSale(saleId), local, transaction.state) { payment, refunds, ui, actions ->
            ui.copy(payment = payment, refunds = refunds, transaction = actions)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SaleDetailUiState())

    /**
     * Starts cancelling the pre-authorisation (a full reversal, see [RefundablePayment.cancellation]) and returns the
     * cancellation's ID, to follow it like a refund; null when it cannot be cancelled or a refund is already running.
     */
    fun cancel(): String? {
        val request =
            state.value.record?.let {
                RefundablePayment.cancellation(it, settings.value.payment.referencePrefix, clock.instant(), zone())
            } ?: return null
        return runCatching { operations.refunds.start(request) }.getOrNull()
    }

    /** Sends the capture again as it was (same amount and idempotency key); the sale updates with the outcome. */
    fun retryCapture() {
        val sale = state.value.record?.sale ?: return
        if (local.value.retry.running) return
        local.update { it.copy(retry = ActionState(running = true)) }
        launchWrite({ operations.captures.retryCapture(saleId) }) { result ->
            local.update { it.copy(retry = result.toState(CaptureStep.CAPTURE, sale.capturedMinor ?: 0, sale.currency)) }
        }
    }
}
