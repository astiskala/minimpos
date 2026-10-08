package io.github.astiskala.minimpos.app.feature.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import io.github.astiskala.minimpos.app.R
import io.github.astiskala.minimpos.app.data.db.RefundEntity
import io.github.astiskala.minimpos.app.data.db.RefundStatus
import io.github.astiskala.minimpos.app.data.db.SaleEntity
import io.github.astiskala.minimpos.app.data.db.SaleKind
import io.github.astiskala.minimpos.app.data.db.SaleStatus
import io.github.astiskala.minimpos.app.data.repo.HistoryItem
import io.github.astiskala.minimpos.app.feature.OutcomeMessage
import io.github.astiskala.minimpos.app.feature.TransactionActionsState
import io.github.astiskala.minimpos.app.feature.lock.ManagerApproval
import io.github.astiskala.minimpos.app.feature.modificationNote
import io.github.astiskala.minimpos.app.feature.outcomeNote
import io.github.astiskala.minimpos.app.feature.refund.RefundResultScreen
import io.github.astiskala.minimpos.app.feature.refund.refundStatusKind
import io.github.astiskala.minimpos.app.feature.refund.refundStatusTitle
import io.github.astiskala.minimpos.app.feature.sale.EnterTipButton
import io.github.astiskala.minimpos.app.feature.sale.HoldNotes
import io.github.astiskala.minimpos.app.feature.sale.ShareReceiptButton
import io.github.astiskala.minimpos.app.feature.sale.adviceText
import io.github.astiskala.minimpos.app.feature.sale.statusKind
import io.github.astiskala.minimpos.app.feature.sale.statusTitle
import io.github.astiskala.minimpos.app.refund.PaymentAction
import io.github.astiskala.minimpos.app.refund.decline
import io.github.astiskala.minimpos.app.refund.methodCode
import io.github.astiskala.minimpos.app.refund.simulatedLink
import io.github.astiskala.minimpos.app.share.ShareEffect
import io.github.astiskala.minimpos.app.ui.components.ActionMessage
import io.github.astiskala.minimpos.app.ui.components.Card
import io.github.astiskala.minimpos.app.ui.components.ConfirmDialog
import io.github.astiskala.minimpos.app.ui.components.EmailReceiptDialog
import io.github.astiskala.minimpos.app.ui.components.EmptyState
import io.github.astiskala.minimpos.app.ui.components.LabeledValue
import io.github.astiskala.minimpos.app.ui.components.LocalAppContainer
import io.github.astiskala.minimpos.app.ui.components.MiniScaffold
import io.github.astiskala.minimpos.app.ui.components.OutcomeHeader
import io.github.astiskala.minimpos.app.ui.components.OutcomeNote
import io.github.astiskala.minimpos.app.ui.components.ReceiptPreview
import io.github.astiskala.minimpos.app.ui.components.SearchField
import io.github.astiskala.minimpos.app.ui.components.SearchMode
import io.github.astiskala.minimpos.app.ui.components.SearchToggle
import io.github.astiskala.minimpos.app.ui.components.SecondaryButton
import io.github.astiskala.minimpos.app.ui.components.StatusKind
import io.github.astiskala.minimpos.app.ui.components.TextInputDialog
import io.github.astiskala.minimpos.app.ui.components.TransactionRow
import io.github.astiskala.minimpos.app.ui.components.currentLocale
import io.github.astiskala.minimpos.app.ui.components.rememberMoneyFormatter
import io.github.astiskala.minimpos.app.ui.components.rememberSearchControl
import io.github.astiskala.minimpos.app.ui.navigation.Navigator
import io.github.astiskala.minimpos.app.ui.navigation.Route
import io.github.astiskala.minimpos.app.ui.theme.LocalDimens
import io.github.astiskala.minimpos.app.ui.theme.LocalStatusColors
import io.github.astiskala.minimpos.core.money.CurrencySpec
import io.github.astiskala.minimpos.core.money.MoneyFormatter
import io.github.astiskala.minimpos.core.payment.PaymentMethods
import io.github.astiskala.minimpos.core.payment.ScanWallet
import io.github.astiskala.minimpos.core.receipt.ReceiptCopy
import io.github.astiskala.minimpos.core.receipt.ReceiptDocument
import io.github.astiskala.minimpos.core.shopper.ShopperReferences
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Sales and refunds by day, newest first, with each day's totals, filters for sales, refunds and issues, a payment
 * method menu and a search field (behind an app-bar icon on compact screens). Tapping an entry opens its detail.
 */
@Composable
fun HistoryScreen(
    navigator: Navigator,
    modifier: Modifier = Modifier,
    vm: HistoryViewModel = historyViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val search = rememberSearchControl(available = state.hasHistory, onRequest = LocalDimens.current.compact, state.query, vm::setQuery)
    MiniScaffold(
        title = stringResource(R.string.history_title),
        onBack = navigator::back,
        modifier = modifier,
        actions = { if (search.onRequest) SearchToggle(search.mode, stringResource(R.string.history_search), search.toggle) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (search.mode != SearchMode.HIDDEN) {
                SearchField(
                    state.query,
                    vm::setQuery,
                    stringResource(R.string.history_search),
                    focus = search.mode == SearchMode.REQUESTED,
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
                )
            }
            HistoryFilters(state.filter, vm::setFilter, state.methods, state.method, vm::setMethod)
            OutcomeMessage(state.print)
            when {
                !state.loaded -> {}

                state.days.isNotEmpty() -> {
                    HistoryList(state.days, state.canPrint, state.print.running, vm::printDay) { item ->
                        when (item) {
                            is HistoryItem.Sale -> navigator.push(Route.SaleDetail(item.sale.id))
                            is HistoryItem.Refund -> navigator.push(Route.RefundDetail(item.refund.id))
                        }
                    }
                }

                state.hasHistory && state.narrowed -> {
                    EmptyState(
                        Icons.Default.SearchOff,
                        stringResource(R.string.history_no_match),
                        stringResource(R.string.history_no_match_hint),
                    ) { SecondaryButton(stringResource(R.string.history_show_all), vm::showAll, modifier = Modifier.testTag("showAll")) }
                }

                else -> {
                    EmptyState(
                        Icons.AutoMirrored.Filled.ReceiptLong,
                        stringResource(R.string.history_empty),
                        stringResource(R.string.history_empty_hint),
                    )
                }
            }
        }
    }
}

/** The [days] of history under sticky day headers; tapping an entry calls [onOpen]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryList(
    days: List<HistoryDay>,
    canPrint: Boolean,
    printing: Boolean,
    onPrint: (LocalDate) -> Unit,
    onOpen: (HistoryItem) -> Unit,
) {
    val timeFormat = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    LazyColumn(Modifier.fillMaxSize().testTag("historyList")) {
        days.forEach { day ->
            stickyHeader(key = day.date.toString()) {
                DayHeader(
                    day,
                    if (day.date == LocalDate.now()) {
                        stringResource(R.string.history_today)
                    } else {
                        dateFormat.format(day.date)
                    },
                    canPrint,
                    printing,
                    { onPrint(day.date) },
                )
            }
            items(day.items, key = { "${day.date}-${it.id}" }) { item ->
                HistoryRow(item, timeFormat, day.times[item.id] ?: item.createdAt, item.id in day.captures) { onOpen(item) }
                HorizontalDivider()
            }
        }
    }
}

/**
 * The payment method menu (when history has [methods] to choose from), then a chip per [HistoryFilter], in one row
 * that scrolls sideways.
 */
@Composable
private fun HistoryFilters(
    selected: HistoryFilter,
    onSelect: (HistoryFilter) -> Unit,
    methods: List<PaymentMethodFilter>,
    method: PaymentMethodFilter?,
    onMethod: (PaymentMethodFilter?) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = if (LocalDimens.current.compact) 4.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (methods.isNotEmpty() || method != null) item(key = "method") { PaymentMethodChip(methods, method, onMethod) }
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
                                HistoryFilter.AWAITING_TIP -> R.string.history_filter_awaiting_tip
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

/**
 * A chip naming the chosen payment [method] (or "Payment method" for any) that opens a menu: any payment method, then
 * the card brands and the wallets in [methods].
 */
@Composable
private fun PaymentMethodChip(
    methods: List<PaymentMethodFilter>,
    method: PaymentMethodFilter?,
    onMethod: (PaymentMethodFilter?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val choose = { value: PaymentMethodFilter? ->
        expanded = false
        onMethod(value)
    }
    Box {
        FilterChip(
            selected = method != null,
            onClick = { expanded = true },
            label = { Text(method?.label ?: stringResource(R.string.history_method)) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.testTag("paymentMethod"),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.history_method_any)) }, onClick = { choose(null) })
            val (brands, wallets) = methods.partition { it is PaymentMethodFilter.Brand }
            listOf(brands, wallets).filter { it.isNotEmpty() }.forEach { group ->
                HorizontalDivider()
                group.forEach { DropdownMenuItem(text = { Text(it.label) }, onClick = { choose(it) }) }
            }
        }
    }
}

@Composable
private fun DayHeader(
    day: HistoryDay,
    title: String,
    canPrint: Boolean,
    printing: Boolean,
    onPrint: () -> Unit,
) {
    val locale = currentLocale()

    fun totals(values: Map<String, Long>) =
        values.entries.joinToString(" + ") { (currency, minor) ->
            MoneyFormatter(CurrencySpec.of(currency), locale).format(minor)
        }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            val parts =
                buildList {
                    if (day.totals.saleCount > 0) {
                        add(stringResource(R.string.history_day_sales, totals(day.totals.salesMinor), day.totals.saleCount))
                    }
                    if (day.totals.tipsMinor.isNotEmpty()) add(stringResource(R.string.history_day_tips, totals(day.totals.tipsMinor)))
                    if (day.totals.refundCount > 0) {
                        add(stringResource(R.string.history_day_refunds, totals(day.totals.refundsMinor), day.totals.refundCount))
                    }
                    if (day.totals.preAuthCount > 0) {
                        add(stringResource(R.string.history_day_pre_auths, totals(day.totals.preAuthsMinor), day.totals.preAuthCount))
                    }
                }
            if (parts.isNotEmpty()) {
                Text(
                    parts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (canPrint) {
            IconButton(
                onClick = onPrint,
                enabled = !printing,
                modifier = Modifier.testTag("printDay_${day.date}"),
            ) {
                Icon(Icons.Default.Print, stringResource(R.string.history_print_day))
            }
        }
    }
}

@Composable
private fun HistoryRow(
    item: HistoryItem,
    timeFormat: DateTimeFormatter,
    at: Long,
    captured: Boolean,
    onClick: () -> Unit,
) {
    val stamp = timeFormat.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
    val time = if (captured) "$stamp · ${stringResource(R.string.history_capture_activity)}" else stamp
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
                listOfNotNull(
                    time,
                    stringResource(R.string.sample_label).takeIf { sale.sample },
                    paymentMethodText(sale),
                    sale.customerReference,
                ).joinToString(" · "),
                MoneyFormatter(CurrencySpec.of(sale.currency), locale).format(sale.amountMinor),
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

/** The card brand code in upper case and the wallet, such as "VISA Apple Pay"; null when the terminal reported neither. */
private fun paymentMethodText(sale: SaleEntity): String? =
    listOfNotNull(
        sale.methodCode?.let { if (ScanWallet.reported(it) != null) PaymentMethods.brandName(it) else it.uppercase() },
        PaymentMethods.wallet(sale.paymentMethodVariant)?.displayName,
    ).joinToString(" ")
        .ifBlank { null }

/**
 * One sale from history: its items, payment details, refunds and receipt. It can be reprinted, emailed and
 * refunded, and a sale with an unknown outcome can be checked again with the terminal. A payment that only holds its
 * amount is not refunded until it is captured: a sale awaiting its tip offers entering the tip, a pre-authorisation
 * capturing it (and adjusting what it holds), and both can be cancelled after the operator confirms. A capture whose
 * outcome is unknown, or that Adyen did not take for a tip, can be sent again.
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
    var approval by remember { mutableStateOf<(() -> Unit)?>(null) }
    if (approval != null) {
        return ManagerApproval(onApprove = {
            approval?.invoke()
            approval = null
        }, onCancel = { approval = null })
    }
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
                SaleDetailOutcome(state, money, container::formatDateTime)
                HoldActions(
                    state = state,
                    onEnterTip = { navigator.push(Route.Tip(sale.id)) },
                    onCapture = { navigator.push(Route.Capture(sale.id)) },
                    onAdjust = { navigator.push(Route.Capture(sale.id, adjustOnly = true)) },
                    onRetryCapture = { approval = vm::retryCapture },
                )
                SaleDetailActions(
                    state = state,
                    onRecheck = vm.transaction::recheck,
                    onRefund = { navigator.push(Route.Refund(saleId = sale.id)) },
                    onCancel = { approval = { dialog = DetailDialog.CANCEL } },
                    onPrint = vm.transaction::print,
                    onEmail = { dialog = DetailDialog.EMAIL },
                    onShare = vm.transaction::share,
                    onShowLink = { navigator.push(Route.PaymentLink(sale.id)) },
                )
                SaleRefunds(state.refunds, money, preAuth, container::formatDateTime) {
                    navigator.push(Route.RefundDetail(it))
                }
                SaleDetailsAndReceipt(sale, state.transaction.receipt)
            }
        }
    }
    SaleDetailDialogs(state.record?.sale, dialog, onEmail = vm.transaction::email, onDismiss = { dialog = null }) {
        if (vm.cancel() != null) navigator.push(Route.RefundProcessing)
    }
    ShareEffect(state.transaction.share, vm.transaction::shared)
}

@Composable
private fun ColumnScope.SaleDetailsAndReceipt(
    sale: SaleEntity,
    receipt: ReceiptDocument?,
) {
    PaymentDetailsCard(sale)
    receipt?.let { ReceiptPreview(it, Modifier.align(Alignment.CenterHorizontally)) }
}

/** The dialogs the sale detail screen opens. */
private enum class DetailDialog { EMAIL, CANCEL }

/**
 * The outcome badge, title and amount, with when it was paid, what was refunded, the tip, adjustments and capture of a
 * payment that held its amount, and that a link was paid online.
 */
@Composable
private fun SaleDetailOutcome(
    state: SaleDetailUiState,
    money: MoneyFormatter,
    formatDateTime: (Long) -> String,
) {
    val sale = state.record?.sale ?: return
    if (sale.sample) Text(stringResource(R.string.sample_history_hint), style = MaterialTheme.typography.bodySmall)
    OutcomeHeader(statusKind(sale), statusTitle(sale), money.format(sale.amountMinor), titleTag = "detailStatus") {
        OutcomeNote(
            listOfNotNull(
                formatDateTime(sale.createdAt),
                sale.refundedMinor
                    .takeIf { it > 0 }
                    ?.let { stringResource(R.string.detail_refunded, money.format(it)) },
            ).joinToString(" · "),
        )
        HoldNotes(sale, money)
        if (sale.simulatedLink) {
            OutcomeNote(stringResource(R.string.link_simulation_note))
        } else if (sale.paymentLink && sale.status == SaleStatus.APPROVED) {
            OutcomeNote(stringResource(R.string.link_paid_note))
        }
    }
}

/**
 * What finishes a payment that holds its amount: entering the tip, capturing or adjusting a pre-authorisation, and
 * sending a capture again, with the outcome of the last retry.
 */
@Composable
private fun ColumnScope.HoldActions(
    state: SaleDetailUiState,
    onEnterTip: () -> Unit,
    onCapture: () -> Unit,
    onAdjust: () -> Unit,
    onRetryCapture: () -> Unit,
) {
    if (PaymentAction.ENTER_TIP in state.actions) EnterTipButton(onEnterTip)
    if (PaymentAction.CAPTURE in state.actions) {
        SecondaryButton(
            stringResource(R.string.detail_capture),
            onCapture,
            icon = Icons.Default.Payments,
            modifier = Modifier.testTag("capture"),
        )
    }
    if (PaymentAction.ADJUST in
        state.actions
    ) {
        SecondaryButton(
            stringResource(R.string.detail_adjust),
            onAdjust,
            icon = Icons.Default.EditNote,
            modifier = Modifier.testTag("adjust"),
        )
    }
    if (PaymentAction.RETRY_CAPTURE in state.actions) {
        SecondaryButton(
            stringResource(R.string.detail_retry_capture),
            onRetryCapture,
            loading = state.retry.running,
            icon = Icons.Default.Refresh,
            modifier = Modifier.testTag("retryCapture"),
        )
    }
    OutcomeMessage(state.retry)
}

/**
 * The [dialog] open over the detail of [stored], if any: the address to email the receipt to, or confirming the
 * cancellation of a pre-authorisation. Either closes ([onDismiss]) before [onEmail] or [onCancel] runs.
 */
@Composable
private fun SaleDetailDialogs(
    stored: SaleEntity?,
    dialog: DetailDialog?,
    onEmail: (to: String) -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
) {
    val sale = stored ?: return
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
        val held = rememberMoneyFormatter(sale.currency).format(sale.heldMinor)
        ConfirmDialog(
            title = stringResource(if (sale.tipOnReceipt) R.string.tip_cancel_title else R.string.pre_auth_cancel_title),
            message =
                if (sale.tipOnReceipt) {
                    stringResource(R.string.tip_cancel_message, held)
                } else {
                    stringResource(R.string.pre_auth_cancel_message, held)
                },
            confirmLabel = stringResource(if (sale.tipOnReceipt) R.string.detail_cancel_payment else R.string.pre_auth_cancel_confirm),
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
 * What can be done with the sale: opening its payment link while it can still be paid (or its outcome is unknown), the
 * retry advice, a status check, refunding (or cancelling a pre-authorisation), then its receipt.
 */
@Composable
private fun ColumnScope.SaleDetailActions(
    state: SaleDetailUiState,
    onRecheck: () -> Unit,
    onRefund: () -> Unit,
    onCancel: () -> Unit,
    onPrint: (ReceiptCopy) -> Unit,
    onEmail: () -> Unit,
    onShare: () -> Unit,
    onShowLink: () -> Unit,
) {
    val sale = state.record?.sale ?: return
    if (sale.paymentLink && (sale.status == SaleStatus.AWAITING_PAYMENT || sale.status == SaleStatus.UNKNOWN)) {
        SecondaryButton(
            stringResource(R.string.detail_show_link),
            onShowLink,
            icon = Icons.Default.Link,
            modifier = Modifier.testTag("showLink"),
        )
    }
    sale.decline?.advice?.let {
        Text(
            adviceText(it),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (sale.status == SaleStatus.UNKNOWN && sale.serviceId != null) {
        SecondaryButton(
            stringResource(R.string.result_check_again),
            onRecheck,
            loading = state.transaction.rechecking,
            icon = Icons.Default.Refresh,
        )
        if (state.transaction.stillUnknown) {
            Text(stringResource(R.string.detail_still_unknown), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
    if (PaymentAction.REFUND in state.actions) {
        SecondaryButton(
            stringResource(R.string.detail_refund),
            onRefund,
            icon = Icons.AutoMirrored.Filled.Undo,
            modifier = Modifier.testTag("detailRefund"),
        )
    }
    if (PaymentAction.CANCEL in state.actions) {
        SecondaryButton(
            stringResource(if (sale.tipOnReceipt) R.string.detail_cancel_payment else R.string.detail_cancel_pre_auth),
            onCancel,
            icon = Icons.Default.LockOpen,
            modifier = Modifier.testTag("cancelPreAuth"),
        )
    }
    if (sale.status == SaleStatus.APPROVED) SaleReceiptActions(state.transaction, onPrint, onEmail, onShare)
}

/** Reprinting (either copy), emailing and sharing (on phones and tablets) an approved sale's receipt. */
@Composable
private fun ColumnScope.SaleReceiptActions(
    transaction: TransactionActionsState,
    onPrint: (ReceiptCopy) -> Unit,
    onEmail: () -> Unit,
    onShare: () -> Unit,
) {
    if (transaction.canPrint) {
        SecondaryButton(stringResource(R.string.detail_reprint), {
            onPrint(ReceiptCopy.CUSTOMER)
        }, loading = transaction.print.running, icon = Icons.Default.Print)
        SecondaryButton(
            stringResource(R.string.result_print_merchant),
            { onPrint(ReceiptCopy.MERCHANT) },
            loading = transaction.print.running,
            icon = Icons.Default.Print,
        )
        OutcomeMessage(transaction.print)
    }
    if (transaction.canEmail) {
        SecondaryButton(
            stringResource(R.string.result_email),
            onEmail,
            loading = transaction.email.running,
            icon = Icons.Default.Email,
        )
        OutcomeMessage(transaction.email)
    }
    if (transaction.canShare) ShareReceiptButton(onShare)
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
        LabeledValue(stringResource(R.string.detail_payment_link), sale.paymentLinkUrl)
        LabeledValue(stringResource(R.string.detail_shopper_reference), sale.shopperReference)
        LabeledValue(stringResource(R.string.detail_token), sale.storedPaymentMethodId)
        LabeledValue(stringResource(R.string.detail_terminal), sale.poiId)
        LabeledValue(stringResource(R.string.detail_message), sale.outcomeNote())
        LabeledValue(stringResource(R.string.detail_error_condition), sale.errorCondition)
        LabeledValue(stringResource(R.string.detail_modification_message), sale.modificationNote())
        LabeledValue(stringResource(R.string.detail_emailed), sale.emailedTo)
    }
}

@Composable
private fun historyViewModel(): HistoryViewModel {
    val container = LocalAppContainer.current
    return viewModel { HistoryViewModel(container.history, receipts = container.receipts) }
}

@Composable
private fun saleDetailViewModel(saleId: String): SaleDetailViewModel {
    val container = LocalAppContainer.current
    return viewModel(key = saleId) {
        SaleDetailViewModel(
            saleId,
            container.storedPayments,
            container.refundRecords,
            container.receipts,
            container.storedPaymentActions,
            container.payments,
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
