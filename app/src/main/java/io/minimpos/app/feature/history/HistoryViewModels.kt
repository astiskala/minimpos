package io.minimpos.app.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.minimpos.app.data.db.RefundEntity
import io.minimpos.app.data.db.RefundStatus
import io.minimpos.app.data.db.SaleStatus
import io.minimpos.app.data.db.SaleWithLines
import io.minimpos.app.data.repo.HistoryItem
import io.minimpos.app.data.repo.HistoryRepository
import io.minimpos.app.data.repo.SaleRepository
import io.minimpos.app.data.settings.AppSettings
import io.minimpos.app.feature.sale.ActionState
import io.minimpos.app.feature.sale.ResultMessages
import io.minimpos.app.feature.sale.toState
import io.minimpos.app.payment.PaymentStart
import io.minimpos.app.payment.ReceiptDelivery
import io.minimpos.app.payment.TransactionLifecycle
import io.minimpos.app.refund.Refundability
import io.minimpos.app.refund.RefundablePayment
import io.minimpos.app.terminal.TerminalState
import io.minimpos.core.receipt.ReceiptCopy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Totals per currency for one day: approved sales and accepted refunds, of the items the filter shows.
 *
 * @property salesMinor Approved sales' totals by ISO 4217 currency code, in minor units.
 * @property saleCount Number of approved sales.
 * @property refundsMinor Accepted refunds' amounts by currency code, in minor units.
 * @property refundCount Number of accepted refunds.
 */
data class DayTotals(
    val salesMinor: Map<String, Long>,
    val saleCount: Int,
    val refundsMinor: Map<String, Long>,
    val refundCount: Int,
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

    /** Sales only, whatever their status. */
    SALES,

    /** Refunds only, whatever their status. */
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
            HistoryUiState(loaded = true, filter = selected, days = group(items.filter { matches(it, selected) }))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    /** Shows the transactions matching [value]. */
    fun setFilter(value: HistoryFilter) {
        filter.value = value
    }

    private fun matches(
        item: HistoryItem,
        filter: HistoryFilter,
    ): Boolean =
        when (filter) {
            HistoryFilter.ALL -> {
                true
            }

            HistoryFilter.SALES -> {
                item is HistoryItem.Sale
            }

            HistoryFilter.REFUNDS -> {
                item is HistoryItem.Refund
            }

            HistoryFilter.ISSUES -> {
                when (item) {
                    is HistoryItem.Sale -> item.sale.status == SaleStatus.UNKNOWN || item.sale.status == SaleStatus.PENDING
                    is HistoryItem.Refund -> item.refund.status == RefundStatus.UNKNOWN || item.refund.status == RefundStatus.PENDING
                }
            }
        }

    private fun group(items: List<HistoryItem>): List<HistoryDay> =
        items
            .groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zone()).toLocalDate() }
            .map { (date, dayItems) ->
                val approved = dayItems.filterIsInstance<HistoryItem.Sale>().map { it.sale }.filter { it.status == SaleStatus.APPROVED }
                val refunds =
                    dayItems
                        .filterIsInstance<HistoryItem.Refund>()
                        .map {
                            it.refund
                        }.filter { it.status == RefundStatus.REQUESTED }
                HistoryDay(
                    date = date,
                    totals =
                        DayTotals(
                            salesMinor = approved.groupBy { it.currency }.mapValues { (_, list) -> list.sumOf { it.totalMinor } },
                            saleCount = approved.size,
                            refundsMinor = refunds.groupBy { it.currency }.mapValues { (_, list) -> list.sumOf { it.amountMinor } },
                            refundCount = refunds.size,
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
) {
    /** Whether a refund can be offered, as [RefundablePayment.check] decides. */
    val canRefund: Boolean get() = record?.let(RefundablePayment::check) is Refundability.Refundable
}

/** One sale from history: its receipt, refunds, reprinting and emailing, and re-checking an unknown outcome. */
class SaleDetailViewModel(
    private val saleId: String,
    sales: SaleRepository,
    private val receipts: ReceiptDelivery,
    private val payments: TransactionLifecycle<PaymentStart>,
    settings: StateFlow<AppSettings>,
    terminal: StateFlow<TerminalState>,
    private val messages: ResultMessages,
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
            ui.copy(record = record, refunds = refunds, settings = appSettings, printerAvailable = status.printerAvailable)
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

    /** Asks the terminal for the transaction status of a sale whose outcome is unknown. */
    fun recheck() {
        local.update { it.copy(rechecking = true, recheckMessage = null) }
        viewModelScope.launch {
            val settled = payments.recheck(saleId)
            local.update { it.copy(rechecking = false, recheckMessage = if (settled) null else messages.stillUnknown) }
        }
    }
}
