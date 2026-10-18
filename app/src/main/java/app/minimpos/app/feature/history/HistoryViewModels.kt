package app.minimpos.app.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleWithLines
import app.minimpos.app.data.repo.HistoryItem
import app.minimpos.app.data.repo.HistoryRepository
import app.minimpos.app.data.repo.RefundRepository
import app.minimpos.app.feature.ActionOutcome
import app.minimpos.app.feature.ActionState
import app.minimpos.app.feature.CaptureStep
import app.minimpos.app.feature.TransactionActions
import app.minimpos.app.feature.TransactionActionsState
import app.minimpos.app.feature.launchWrite
import app.minimpos.app.feature.toState
import app.minimpos.app.history.HistoryAccounting
import app.minimpos.app.history.HistoryDay
import app.minimpos.app.history.PaymentAction
import app.minimpos.app.history.PaymentStanding
import app.minimpos.app.history.RefundablePayment
import app.minimpos.app.history.StoredPayment
import app.minimpos.app.history.StoredPayments
import app.minimpos.app.history.TerminalReconciliationReport
import app.minimpos.app.history.standing
import app.minimpos.app.payment.FinancialOperations
import app.minimpos.app.payment.ReceiptDelivery
import app.minimpos.app.payment.ReconciliationOutcome
import app.minimpos.app.payment.StoredPaymentActions
import app.minimpos.app.payment.TerminalReconciliation
import app.minimpos.app.terminal.TerminalState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

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
 * @property canReconcile Whether the current destination supports terminal totals and reconciliation.
 * @property reconcile Latest terminal totals/reconciliation action state.
 * @property reconciliation Latest comparison between local records and terminal totals; null before running or after dismissal.
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
    val canReconcile: Boolean = false,
    val reconcile: ActionState = ActionState(),
    val reconciliation: TerminalReconciliationReport? = null,
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
    /** Terminal reconciliation, absent only in list-only tests. */
    private val reconciliation: TerminalReconciliation? = null,
    terminal: Flow<TerminalState> = flowOf(TerminalState()),
) : ViewModel() {
    private val print = MutableStateFlow(ActionState())
    private val reconcileState = MutableStateFlow<Pair<ActionState, TerminalReconciliationReport?>>(ActionState() to null)
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
                days = HistoryAccounting.days(shown, zone()),
                query = text,
                method = chosen,
                methods = PaymentMethodFilter.available(sales.values.toList()),
                hasHistory = items.isNotEmpty(),
            )
        }.combine(
            combine(canPrint, print, terminal, reconcileState) { available, action, term, (recAction, recReport) ->
                val canReconcile = reconciliation != null && term.capabilities.supportsTerminalFeatures
                ReconcileSlice(available, action, canReconcile, recAction, recReport)
            },
        ) { list, slice ->
            list.copy(
                canPrint = slice.canPrint,
                print = slice.print,
                canReconcile = slice.canReconcile,
                reconcile = slice.reconcile,
                reconciliation = slice.reconciliation,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    private data class ReconcileSlice(
        val canPrint: Boolean,
        val print: ActionState,
        val canReconcile: Boolean,
        val reconcile: ActionState,
        val reconciliation: TerminalReconciliationReport?,
    )

    /** Prints every retained activity for [date], irrespective of active list filters; duplicate taps are ignored. */
    fun printDay(date: LocalDate) {
        val delivery = receipts ?: return
        if (!state.value.canPrint || print.value.running) return
        print.value = ActionState(running = true)
        viewModelScope.launch { print.value = TransactionActions.printReport(delivery, date) }
    }

    /**
     * Runs one terminal reconciliation for [date] (`GetTotalsRequest`, or `SaleReconciliation` when [closePeriod] is
     * true) and shows its comparison, or why nothing could be compared. Closing a period rechecks Manager PIN approval
     * before sending; duplicate taps are ignored.
     */
    fun reconcileTerminal(
        date: LocalDate = LocalDate.now(zone()),
        closePeriod: Boolean = false,
    ) {
        val reconciliation = reconciliation ?: return
        if (reconcileState.value.first.running) return
        reconcileState.value = ActionState(running = true) to null
        launchWrite({ reconciliation.reconcile(date, closePeriod) }) { outcome ->
            reconcileState.value =
                when (outcome) {
                    is ReconciliationOutcome.Failed -> {
                        ActionState(outcome = ActionOutcome.Failed(outcome.failure), isError = true) to null
                    }

                    is ReconciliationOutcome.Reconciled -> {
                        ActionState(done = true) to outcome.report
                    }
                }
        }
    }

    /** Dismisses the displayed terminal reconciliation comparison card. */
    fun dismissReconciliation() {
        reconcileState.update { (action, _) -> action to null }
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
                HistoryAccounting.needsAttention(item)
            }
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
 * One sale (or pre-authorisation) from history: its receipt, refunds, reprinting and emailing and re-checking an
 * unknown outcome ([transaction]), cancelling a payment that only holds its amount, and sending a capture again.
 *
 * @param saleId The sale shown.
 * @param payments Follows it, with what can be done with it.
 * @param refunds Where its refunds and cancellations are stored.
 * @param receipts Prints and emails its receipt.
 * @param operations Cancels holds and retries captures.
 * @param financial Asks again about an unknown outcome, the way the payment was sent.
 */
class SaleDetailViewModel(
    private val saleId: String,
    payments: StoredPayments,
    refunds: RefundRepository,
    receipts: ReceiptDelivery,
    private val operations: StoredPaymentActions,
    financial: FinancialOperations,
) : ViewModel() {
    private val local = MutableStateFlow(SaleDetailUiState())

    /** The receipt, reprinting and emailing it, and re-checking an unknown outcome. */
    val transaction = TransactionActions.forSale(viewModelScope, saleId, receipts, financial, fresh = false)

    /** The screen state, updated whenever the sale, its refunds, the capture mode or an action changes. */
    val state: StateFlow<SaleDetailUiState> =
        combine(payments.observe(saleId), refunds.forSale(saleId), local, transaction.state) { payment, refunds, ui, actions ->
            ui.copy(payment = payment, refunds = refunds, transaction = actions)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SaleDetailUiState())

    /**
     * Starts cancelling the pre-authorisation (a full reversal, see [RefundablePayment.cancellation]) and returns the
     * cancellation's ID, to follow it like a refund; null when it cannot be cancelled or a refund is already running.
     */
    fun cancel(): String? = state.value.record?.let(operations::cancel)

    /** Sends the capture again as it was (same amount and idempotency key); the sale updates with the outcome. */
    fun retryCapture() {
        val sale = state.value.record?.sale ?: return
        if (local.value.retry.running) return
        local.update { it.copy(retry = ActionState(running = true)) }
        viewModelScope.launch {
            val result = operations.retryCapture(saleId)
            local.update { it.copy(retry = result.toState(CaptureStep.CAPTURE, sale.capturedMinor ?: 0, sale.currency)) }
        }
    }
}
