package io.minimpos.app.feature.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.minimpos.app.R
import io.minimpos.app.data.db.RefundEntity
import io.minimpos.app.data.db.RefundStatus
import io.minimpos.app.data.db.SaleEntity
import io.minimpos.app.data.db.SaleKind
import io.minimpos.app.data.db.SaleStatus
import io.minimpos.app.data.repo.HistoryItem
import io.minimpos.app.feature.refund.RefundResultScreen
import io.minimpos.app.feature.refund.refundStatusKind
import io.minimpos.app.feature.refund.refundStatusTitle
import io.minimpos.app.feature.sale.ResultMessages
import io.minimpos.app.feature.sale.adviceText
import io.minimpos.app.feature.sale.statusKind
import io.minimpos.app.feature.sale.statusTitle
import io.minimpos.app.ui.components.ActionMessage
import io.minimpos.app.ui.components.Card
import io.minimpos.app.ui.components.ConfirmDialog
import io.minimpos.app.ui.components.EmailReceiptDialog
import io.minimpos.app.ui.components.EmptyState
import io.minimpos.app.ui.components.LabeledValue
import io.minimpos.app.ui.components.LocalAppContainer
import io.minimpos.app.ui.components.MiniScaffold
import io.minimpos.app.ui.components.OutcomeHeader
import io.minimpos.app.ui.components.OutcomeNote
import io.minimpos.app.ui.components.ReceiptPreview
import io.minimpos.app.ui.components.SecondaryButton
import io.minimpos.app.ui.components.StatusKind
import io.minimpos.app.ui.components.TertiaryButton
import io.minimpos.app.ui.components.TextInputDialog
import io.minimpos.app.ui.components.TransactionRow
import io.minimpos.app.ui.components.currentLocale
import io.minimpos.app.ui.components.rememberMoneyFormatter
import io.minimpos.app.ui.navigation.Navigator
import io.minimpos.app.ui.navigation.Route
import io.minimpos.app.ui.theme.LocalDimens
import io.minimpos.app.ui.theme.LocalStatusColors
import io.minimpos.core.money.CurrencySpec
import io.minimpos.core.money.MoneyFormatter
import io.minimpos.core.receipt.ReceiptCopy
import io.minimpos.core.shopper.ShopperReferences
import io.minimpos.terminal.client.RetryAdvice
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Sales and refunds by day, newest first, with each day's totals and filters for sales, refunds and issues.
 * Tapping an entry opens its detail.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    navigator: Navigator,
    modifier: Modifier = Modifier,
    vm: HistoryViewModel = historyViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val timeFormat = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }

    MiniScaffold(title = stringResource(R.string.history_title), onBack = navigator::back, modifier = modifier) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            HistoryFilters(state.filter, vm::setFilter)
            if (state.loaded && state.days.isEmpty()) {
                EmptyState(
                    Icons.AutoMirrored.Filled.ReceiptLong,
                    stringResource(R.string.history_empty),
                    stringResource(R.string.history_empty_hint),
                )
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize().testTag("historyList")) {
                state.days.forEach { day ->
                    stickyHeader(key = day.date.toString()) {
                        DayHeader(
                            day,
                            if (day.date == LocalDate.now()) {
                                stringResource(R.string.history_today)
                            } else {
                                dateFormat.format(day.date)
                            },
                        )
                    }
                    items(day.items, key = { it.id }) { item ->
                        HistoryRow(item, timeFormat) {
                            when (item) {
                                is HistoryItem.Sale -> navigator.push(Route.SaleDetail(item.sale.id))
                                is HistoryItem.Refund -> navigator.push(Route.RefundDetail(item.refund.id))
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryFilters(
    selected: HistoryFilter,
    onSelect: (HistoryFilter) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = if (LocalDimens.current.compact) 4.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(HistoryFilter.entries) { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = {
                    Text(
                        stringResource(
                            when (filter) {
                                HistoryFilter.ALL -> R.string.history_filter_all
                                HistoryFilter.SALES -> R.string.history_filter_sales
                                HistoryFilter.PRE_AUTHS -> R.string.history_filter_pre_auths
                                HistoryFilter.REFUNDS -> R.string.history_filter_refunds
                                HistoryFilter.ISSUES -> R.string.history_filter_issues
                            },
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun DayHeader(
    day: HistoryDay,
    title: String,
) {
    val locale = currentLocale()

    fun totals(values: Map<String, Long>) =
        values.entries.joinToString(" + ") { (currency, minor) ->
            MoneyFormatter(CurrencySpec.of(currency), locale).format(minor)
        }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        val parts =
            buildList {
                if (day.totals.saleCount > 0) {
                    add(stringResource(R.string.history_day_sales, totals(day.totals.salesMinor), day.totals.saleCount))
                }
                if (day.totals.refundCount > 0) {
                    add(stringResource(R.string.history_day_refunds, totals(day.totals.refundsMinor), day.totals.refundCount))
                }
                if (day.totals.preAuthCount > 0) {
                    add(stringResource(R.string.history_day_pre_auths, totals(day.totals.preAuthsMinor), day.totals.preAuthCount))
                }
            }
        if (parts.isNotEmpty()) {
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HistoryRow(
    item: HistoryItem,
    timeFormat: DateTimeFormatter,
    onClick: () -> Unit,
) {
    val time = timeFormat.format(Instant.ofEpochMilli(item.createdAt).atZone(ZoneId.systemDefault()))
    val row = historyRowText(item, time, currentLocale())
    TransactionRow(row.title, row.subtitle, row.amount, row.status, row.kind, onClick)
}

private data class HistoryRowText(
    val title: String,
    val subtitle: String,
    val amount: String,
    val status: String,
    val kind: StatusKind,
)

@Composable
@ReadOnlyComposable
private fun historyRowText(
    item: HistoryItem,
    time: String,
    locale: Locale,
): HistoryRowText =
    when (item) {
        is HistoryItem.Sale -> {
            val sale = item.sale
            HistoryRowText(
                sale.merchantReference,
                listOfNotNull(time, sale.paymentBrand?.uppercase(), sale.customerReference).joinToString(" · "),
                MoneyFormatter(CurrencySpec.of(sale.currency), locale).format(sale.totalMinor),
                statusTitle(sale),
                statusKind(sale),
            )
        }

        is HistoryItem.Refund -> {
            val refund = item.refund
            val amount = MoneyFormatter(CurrencySpec.of(refund.currency), locale).format(refund.amountMinor)
            HistoryRowText(
                stringResource(if (refund.cancellation) R.string.history_cancellation else R.string.history_refund),
                listOf(time, refund.originalReference ?: refund.originalTransactionId).joinToString(" · "),
                // A cancellation releases a hold rather than paying money back.
                if (refund.cancellation) amount else "−$amount",
                refundStatusTitle(refund.status, refund.cancellation),
                refundStatusKind(refund.status),
            )
        }
    }

/**
 * One sale from history: its items, payment details, refunds and receipt. It can be reprinted, emailed and
 * refunded, and a sale with an unknown outcome can be checked again with the terminal. A pre-authorisation is never
 * refunded here, but can be cancelled, after the operator confirms.
 */
@Composable
fun SaleDetailScreen(
    saleId: String,
    navigator: Navigator,
    modifier: Modifier = Modifier,
    vm: SaleDetailViewModel = saleDetailViewModel(saleId),
) {
    val container = LocalAppContainer.current
    val state by vm.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<DetailDialog?>(null) }
    val dimens = LocalDimens.current
    val preAuth = state.record?.sale?.kind == SaleKind.PRE_AUTHORISATION
    MiniScaffold(
        title = stringResource(if (preAuth) R.string.detail_pre_auth_title else R.string.detail_title),
        onBack = navigator::back,
        modifier = modifier,
    ) { padding ->
        val record = state.record ?: return@MiniScaffold
        val sale = record.sale
        val money = rememberMoneyFormatter(sale.currency)
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(dimens.screenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The outcome and actions come first, as on the result screen, so they show without scrolling past every detail.
            Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(dimens.spacing)) {
                SaleDetailOutcome(state, money, container.receiptFactory::formatDateTime)
                SaleDetailActions(
                    state = state,
                    onRecheck = vm::recheck,
                    onRefund = { navigator.push(Route.Refund(saleId = sale.id)) },
                    onCancel = { dialog = DetailDialog.CANCEL },
                    onPrint = vm::print,
                    onEmail = { dialog = DetailDialog.EMAIL },
                )
                SaleRefunds(state.refunds, money, preAuth, container.receiptFactory::formatDateTime) {
                    navigator.push(Route.RefundDetail(it))
                }
                PaymentDetailsCard(sale)
                ReceiptPreview(container.receiptFactory.sale(record, state.settings.receipt), Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
    state.record?.sale?.let { sale ->
        SaleDetailDialogs(sale, dialog, onEmail = vm::email, onDismiss = { dialog = null }) {
            if (vm.cancel() != null) navigator.push(Route.RefundProcessing)
        }
    }
}

/** The dialogs the sale detail screen opens. */
private enum class DetailDialog { EMAIL, CANCEL }

/**
 * The outcome badge, title and amount, with when it was paid, what was refunded and, for a pre-authorisation that can
 * still be cancelled, how to capture it.
 */
@Composable
private fun SaleDetailOutcome(
    state: SaleDetailUiState,
    money: MoneyFormatter,
    formatDateTime: (Long) -> String,
) {
    val sale = state.record?.sale ?: return
    OutcomeHeader(statusKind(sale), statusTitle(sale), money.format(sale.totalMinor), titleTag = "detailStatus") {
        OutcomeNote(
            listOfNotNull(
                formatDateTime(sale.createdAt),
                sale.refundedMinor
                    .takeIf { it > 0 && sale.kind == SaleKind.SALE }
                    ?.let { stringResource(R.string.detail_refunded, money.format(it)) },
            ).joinToString(" · "),
        )
        if (state.canCancel) OutcomeNote(stringResource(R.string.detail_pre_auth_note))
    }
}

/**
 * The [dialog] open over the detail of [sale], if any: the address to email the receipt to, or confirming the
 * cancellation of a pre-authorisation. Either closes ([onDismiss]) before [onEmail] or [onCancel] runs.
 */
@Composable
private fun SaleDetailDialogs(
    sale: SaleEntity,
    dialog: DetailDialog?,
    onEmail: (to: String) -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
) {
    if (dialog == DetailDialog.EMAIL) {
        EmailReceiptDialog(
            onSend = {
                onDismiss()
                onEmail(it)
            },
            onDismiss = onDismiss,
            initial = sale.shopperEmail.orEmpty(),
        )
    }
    if (dialog == DetailDialog.CANCEL) {
        ConfirmDialog(
            title = stringResource(R.string.pre_auth_cancel_title),
            message = stringResource(R.string.pre_auth_cancel_message, rememberMoneyFormatter(sale.currency).format(sale.totalMinor)),
            confirmLabel = stringResource(R.string.pre_auth_cancel_confirm),
            destructive = true,
            dismissLabel = stringResource(R.string.pre_auth_cancel_keep),
            onConfirm = {
                onDismiss()
                onCancel()
            },
            onDismiss = onDismiss,
        )
    }
}

/**
 * What can be done with the sale: the retry advice, a status check, refunding (or cancelling a pre-authorisation),
 * reprinting and emailing.
 */
@Composable
private fun ColumnScope.SaleDetailActions(
    state: SaleDetailUiState,
    onRecheck: () -> Unit,
    onRefund: () -> Unit,
    onCancel: () -> Unit,
    onPrint: (ReceiptCopy) -> Unit,
    onEmail: () -> Unit,
) {
    val sale = state.record?.sale ?: return
    sale.errorCondition?.takeIf { sale.status != SaleStatus.APPROVED }?.let {
        Text(
            adviceText(RetryAdvice.forPayment(it, sale.refusalReason)),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (sale.status == SaleStatus.UNKNOWN && sale.serviceId != null) {
        SecondaryButton(
            stringResource(R.string.result_check_again),
            onRecheck,
            loading = state.rechecking,
            icon = Icons.Default.Refresh,
        )
        state.recheckMessage?.let { Text(it, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
    }
    if (state.canRefund) {
        SecondaryButton(
            stringResource(R.string.detail_refund),
            onRefund,
            icon = Icons.AutoMirrored.Filled.Undo,
            modifier = Modifier.testTag("detailRefund"),
        )
    }
    if (state.canCancel) {
        SecondaryButton(
            stringResource(R.string.detail_cancel_pre_auth),
            onCancel,
            icon = Icons.Default.LockOpen,
            modifier = Modifier.testTag("cancelPreAuth"),
        )
    }
    if (sale.status != SaleStatus.APPROVED) return
    if (state.printerAvailable) {
        SecondaryButton(stringResource(R.string.detail_reprint), {
            onPrint(ReceiptCopy.CUSTOMER)
        }, loading = state.print.running, icon = Icons.Default.Print)
        TertiaryButton(stringResource(R.string.result_print_merchant), { onPrint(ReceiptCopy.MERCHANT) })
        state.print.message?.let { ActionMessage(it, state.print.isError) }
    }
    if (state.settings.email.isConfigured) {
        SecondaryButton(stringResource(R.string.result_email), onEmail, loading = state.email.running, icon = Icons.Default.Email)
        state.email.message?.let { ActionMessage(it, state.email.isError) }
    }
}

/**
 * The refunds made from this terminal against the sale (for a [preAuthorisation], its cancellations), if any; tapping
 * one opens it.
 */
@Composable
private fun ColumnScope.SaleRefunds(
    refunds: List<RefundEntity>,
    money: MoneyFormatter,
    preAuthorisation: Boolean,
    formatDateTime: (Long) -> String,
    onOpen: (refundId: String) -> Unit,
) {
    if (refunds.isEmpty()) return
    // Rows like the history list's, inside a card like the payment details.
    Card {
        Text(
            stringResource(if (preAuthorisation) R.string.detail_cancellations else R.string.detail_refunds),
            style = MaterialTheme.typography.titleSmall,
        )
        refunds.forEach { refund ->
            TransactionRow(
                title = refund.merchantReference,
                subtitle = formatDateTime(refund.createdAt),
                amount = if (refund.cancellation) money.format(refund.amountMinor) else "−${money.format(refund.amountMinor)}",
                status = refundStatusTitle(refund.status, refund.cancellation),
                kind = refundStatusKind(refund.status),
                onClick = { onOpen(refund.id) },
                contentPadding = PaddingValues(vertical = LocalDimens.current.rowPadding),
            )
        }
    }
}

/** Everything the terminal and checkout recorded about the payment; empty values are left out. */
@Composable
private fun PaymentDetailsCard(sale: SaleEntity) {
    Card {
        LabeledValue(stringResource(R.string.detail_reference), sale.merchantReference)
        LabeledValue(stringResource(R.string.checkout_customer_reference), sale.customerReference)
        LabeledValue(stringResource(R.string.checkout_email), sale.shopperEmail)
        LabeledValue(
            stringResource(R.string.detail_card),
            listOfNotNull(sale.paymentBrand?.uppercase(), sale.maskedPan).joinToString(" ").ifBlank {
                null
            },
        )
        LabeledValue(stringResource(R.string.detail_entry), sale.entryMode)
        LabeledValue(stringResource(R.string.detail_auth), sale.authCode)
        LabeledValue(stringResource(R.string.detail_psp), sale.pspReference)
        LabeledValue(stringResource(R.string.detail_shopper_reference), sale.shopperReference)
        LabeledValue(stringResource(R.string.detail_token), sale.storedPaymentMethodId)
        LabeledValue(stringResource(R.string.detail_terminal), sale.poiId)
        LabeledValue(stringResource(R.string.detail_message), sale.message)
        LabeledValue(stringResource(R.string.detail_error_condition), sale.errorCondition)
        LabeledValue(stringResource(R.string.detail_emailed), sale.emailedTo)
    }
}

@Composable
private fun historyViewModel(): HistoryViewModel {
    val container = LocalAppContainer.current
    return viewModel { HistoryViewModel(container.history) }
}

@Composable
private fun saleDetailViewModel(saleId: String): SaleDetailViewModel {
    val container = LocalAppContainer.current
    val printed = stringResource(R.string.result_printed)
    val emailed = stringResource(R.string.result_emailed)
    val stillUnknown = stringResource(R.string.detail_still_unknown)
    return viewModel(key = saleId) {
        SaleDetailViewModel(
            saleId,
            container.sales,
            container.receipts,
            container.payments,
            container.refunds,
            container.settingsState,
            container.terminalStatus.state,
            ResultMessages(printed, emailed, stillUnknown = stillUnknown),
        )
    }
}

/** One refund from history, shown like the result screen of a refund just made. */
@Composable
@NonRestartableComposable
fun RefundDetailScreen(
    refundId: String,
    navigator: Navigator,
) = RefundResultScreen(refundId, navigator, fromHistory = true)
