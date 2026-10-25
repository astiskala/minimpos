package app.minimpos.app.feature.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.EditNote
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.minimpos.app.R
import app.minimpos.app.data.db.GiftCardStatus
import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.repo.HistoryItem
import app.minimpos.app.feature.MerchantCopy
import app.minimpos.app.feature.OutcomeMessage
import app.minimpos.app.feature.ReceiptActions
import app.minimpos.app.feature.StatusCheck
import app.minimpos.app.feature.giftcard.giftCardStageLabel
import app.minimpos.app.feature.giftcard.statusLabel
import app.minimpos.app.feature.lock.ManagerApproval
import app.minimpos.app.feature.mealVoucherFacts
import app.minimpos.app.feature.mexicanPlan
import app.minimpos.app.feature.modificationNote
import app.minimpos.app.feature.outcomeNote
import app.minimpos.app.feature.refund.RefundResultScreen
import app.minimpos.app.feature.refund.refundStatusKind
import app.minimpos.app.feature.refund.refundStatusTitle
import app.minimpos.app.feature.sale.EnterTipButton
import app.minimpos.app.feature.sale.HoldNotes
import app.minimpos.app.feature.sale.SplitProgress
import app.minimpos.app.feature.sale.adviceText
import app.minimpos.app.feature.sale.statusKind
import app.minimpos.app.feature.sale.statusTitle
import app.minimpos.app.history.DayTotals
import app.minimpos.app.history.HistoryDay
import app.minimpos.app.history.PaymentAction
import app.minimpos.app.history.decline
import app.minimpos.app.history.linkUnsettled
import app.minimpos.app.history.methodCode
import app.minimpos.app.history.paidOnline
import app.minimpos.app.history.simulatedLink
import app.minimpos.app.share.ShareEffect
import app.minimpos.app.ui.components.Card
import app.minimpos.app.ui.components.ConfirmDialog
import app.minimpos.app.ui.components.EmptyState
import app.minimpos.app.ui.components.LabeledValue
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.MiniScaffold
import app.minimpos.app.ui.components.MoneyFormatters
import app.minimpos.app.ui.components.OutcomeHeader
import app.minimpos.app.ui.components.OutcomeNote
import app.minimpos.app.ui.components.ScreenColumn
import app.minimpos.app.ui.components.SearchField
import app.minimpos.app.ui.components.SearchMode
import app.minimpos.app.ui.components.SearchToggle
import app.minimpos.app.ui.components.SecondaryButton
import app.minimpos.app.ui.components.StatusKind
import app.minimpos.app.ui.components.TransactionRow
import app.minimpos.app.ui.components.currentLocale
import app.minimpos.app.ui.components.currentSetupGate
import app.minimpos.app.ui.components.rememberMoneyFormatter
import app.minimpos.app.ui.components.rememberMoneyFormatters
import app.minimpos.app.ui.components.rememberSearchControl
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.core.money.MoneyFormatter
import app.minimpos.core.payment.PaymentMethods
import app.minimpos.core.payment.ScanWallet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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
    val search = rememberSearchControl(available = state.hasHistory, onRequest = LocalDimens.current.compact, vm::setQuery)
    MiniScaffold(
        title = stringResource(R.string.history_title),
        // Beside a detail in an expanded window, Back leaves History rather than closing that detail.
        onBack = { navigator.leave(Route.History) },
        modifier = modifier,
        actions = {
            if (search.onRequest) SearchToggle(search.mode, stringResource(R.string.history_search), search.toggle)
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (search.mode != SearchMode.HIDDEN) {
                SearchField(
                    search.text,
                    stringResource(R.string.history_search),
                    focus = search.mode == SearchMode.REQUESTED,
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
                )
            }
            HistoryFilters(state.filter, vm::setFilter, state.methods, state.method, vm::setMethod)
            if (state.hasNewer || state.hasOlder) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    SecondaryButton(
                        stringResource(R.string.action_previous),
                        vm::newer,
                        modifier = Modifier.weight(1f),
                        enabled = state.hasNewer,
                    )
                    SecondaryButton(
                        stringResource(R.string.action_next),
                        vm::older,
                        modifier = Modifier.weight(1f),
                        enabled = state.hasOlder,
                    )
                }
            }
            OutcomeMessage(state.print)
            val gate = currentSetupGate()
            val showAll = {
                search.clear()
                vm.showAll()
            }
            HistoryBody(state, { day -> gate.run { vm.printDay(day) } }, showAll, navigator)
        }
    }
}

@Composable
private fun ColumnScope.HistoryBody(
    state: HistoryUiState,
    onPrintDay: (LocalDate) -> Unit,
    onShowAll: () -> Unit,
    navigator: Navigator,
) {
    when {
        !state.loaded -> {}

        state.days.isNotEmpty() -> {
            HistoryList(state.days, state.canPrint, state.print.running, onPrintDay) { item ->
                when (item) {
                    is HistoryItem.Sale -> navigator.openFrom(Route.History, Route.SaleDetail(item.sale.id))
                    is HistoryItem.Refund -> navigator.openFrom(Route.History, Route.RefundDetail(item.refund.id))
                    is HistoryItem.GiftCard -> navigator.openFrom(Route.History, Route.GiftCardDetail(item.activity.groupId))
                }
            }
        }

        state.hasHistory && state.narrowed -> {
            EmptyState(
                Icons.Default.SearchOff,
                stringResource(R.string.history_no_match),
                stringResource(R.string.history_no_match_hint),
            ) { SecondaryButton(stringResource(R.string.history_show_all), onShowAll, modifier = Modifier.testTag("showAll")) }
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
    // In the app's language, which may differ from the device's (the formatters' default).
    val locale = currentLocale()
    val timeFormat = remember(locale) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale) }
    val dateFormat = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    val money = rememberMoneyFormatters()
    val today = LocalDate.now()
    LazyColumn(Modifier.fillMaxSize().testTag("historyList")) {
        days.forEach { day ->
            stickyHeader(key = day.date.toString(), contentType = "day-header") {
                DayHeader(
                    day,
                    if (day.date == today) stringResource(R.string.history_today) else dateFormat.format(day.date),
                    canPrint,
                    printing,
                    money,
                ) { onPrint(day.date) }
            }
            items(day.items, key = { "${day.date}-${it.id}" }, contentType = { "transaction-row" }) { item ->
                HistoryRow(item, timeFormat, day.times[item.id] ?: item.createdAt, item.id in day.captures, money) { onOpen(item) }
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
        items(HistoryFilter.entries, key = { it.name }) { filter ->
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
    money: MoneyFormatters,
    onPrint: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = LocalDimens.current.rowHorizontalPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            DaySummary(day.totals, money)
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

/** Wraps between transaction types; a count stays attached to its amount even for unusually long totals. */
@Composable
private fun DaySummary(
    day: DayTotals,
    money: MoneyFormatters,
) {
    fun totals(values: Map<String, Long>) = values.entries.joinToString(" + ") { (currency, minor) -> money[currency].format(minor) }
    val parts =
        buildList {
            if (day.saleCount > 0) add(stringResource(R.string.history_day_sales, totals(day.salesMinor), day.saleCount))
            if (day.tipsMinor.isNotEmpty()) add(stringResource(R.string.history_day_tips, totals(day.tipsMinor)))
            if (day.surchargesMinor.isNotEmpty()) add(stringResource(R.string.surcharge_label) + ": " + totals(day.surchargesMinor))
            if (day.unknownSurchargeCount.isNotEmpty()) add(stringResource(R.string.surcharge_unknown))
            if (day.refundCount > 0) add(stringResource(R.string.history_day_refunds, totals(day.refundsMinor), day.refundCount))
            if (day.preAuthCount > 0) add(stringResource(R.string.history_day_pre_auths, totals(day.preAuthsMinor), day.preAuthCount))
        }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        parts.forEach { part ->
            Text(
                part.replace(" (", "\u00a0("),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HistoryRow(
    item: HistoryItem,
    timeFormat: DateTimeFormatter,
    at: Long,
    captured: Boolean,
    money: MoneyFormatters,
    onClick: () -> Unit,
) {
    val stamp = timeFormat.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
    val time = if (captured) "$stamp · ${stringResource(R.string.history_capture_activity)}" else stamp
    val row = historyRowText(item, time, money)
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
    money: MoneyFormatters,
): HistoryRowText =
    when (item) {
        is HistoryItem.Sale -> {
            val sale = item.sale
            HistoryRowText(
                sale.merchantReference,
                listOfNotNull(
                    time,
                    stringResource(R.string.sample_label).takeIf { sale.sample },
                    stringResource(R.string.history_offline_label).takeIf { sale.offlineMode != null },
                    paymentMethodText(sale),
                    sale.customerReference,
                    item.tenders
                        .sortedBy { it.sequence }
                        .flatMap {
                            val context = androidx.compose.ui.platform.LocalContext.current
                            context.mexicanPlan(it) + context.mealVoucherFacts(it)
                        }.joinToString(" · ")
                        .ifBlank { null },
                ).joinToString(" · "),
                money[sale.currency].format(sale.amountMinor),
                statusTitle(sale),
                statusKind(sale),
            )
        }

        is HistoryItem.Refund -> {
            val refund = item.refund
            val amount = money[refund.currency].format(refund.amountMinor)
            HistoryRowText(
                stringResource(if (refund.cancellation) R.string.history_cancellation else R.string.history_refund),
                listOf(time, refund.originalReference ?: refund.originalTransactionId).joinToString(" · "),
                // A cancellation releases a hold rather than paying money back.
                if (refund.cancellation) amount else "−$amount",
                refundStatusTitle(refund.status, refund.cancellation),
                refundStatusKind(refund.status),
            )
        }

        is HistoryItem.GiftCard -> {
            val activity = item.activity
            val main = activity.change ?: activity.funding ?: activity.stages.first()
            HistoryRowText(
                stringResource(R.string.history_gift_card, stringResource(giftCardStageLabel(activity.kind))),
                listOfNotNull(time, activity.cardLastFour?.let { "**** $it" }, main.merchantReference).joinToString(" · "),
                money[activity.currency].format(main.amountMinor),
                stringResource(
                    when {
                        activity.needsAttention -> R.string.gift_card_needs_attention
                        else -> statusLabel(main.status)
                    },
                ),
                when {
                    activity.needsAttention || activity.unresolved -> StatusKind.WARNING
                    main.status == GiftCardStatus.SUCCEEDED -> StatusKind.SUCCESS
                    else -> StatusKind.ERROR
                },
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
    var cancelling by rememberSaveable { mutableStateOf(false) }
    var approval by remember { mutableStateOf<(() -> Unit)?>(null) }
    if (approval != null) {
        return ManagerApproval(onApprove = {
            approval?.invoke()
            approval = null
        }, onCancel = { approval = null })
    }
    val gate = currentSetupGate()
    val preAuth = state.record?.sale?.kind == SaleKind.PRE_AUTHORISATION
    MiniScaffold(
        title = stringResource(if (preAuth) R.string.detail_pre_auth_title else R.string.detail_title),
        onBack = navigator::back,
        modifier = modifier,
    ) { padding ->
        val record = state.record ?: return@MiniScaffold
        val sale = record.sale
        val money = rememberMoneyFormatter(sale.currency)
        // The outcome and actions come first, as on the result screen, so they show without scrolling past every detail.
        ScreenColumn(padding) {
            SaleDetailOutcome(state, money, container::formatDateTime)
            HoldActions(
                state = state,
                onEnterTip = { gate.run { navigator.push(Route.Tip(sale.id)) } },
                onCapture = { gate.run { navigator.push(Route.Capture(sale.id)) } },
                onAdjust = { gate.run { navigator.push(Route.Capture(sale.id, adjustOnly = true)) } },
                onRetryCapture = { gate.run { approval = vm::retryCapture } },
            )
            SaleDetailActions(
                state = state,
                onRecheck = vm.transaction::recheck,
                onCheckDismiss = vm.transaction::dismissCheckResult,
                onRefund = { gate.run { navigator.push(Route.Refund(saleId = sale.id)) } },
                onCancel = { gate.run { approval = { cancelling = true } } },
                onShowLink = { navigator.push(Route.PaymentLink(sale.id)) },
            )
            SplitProgress(record, money) { navigator.push(Route.Split(saleId = sale.id)) }
            if (sale.approved) {
                ReceiptActions(state.transaction, vm.transaction, MerchantCopy.ALWAYS, email = sale.shopperEmail.orEmpty())
            }
            SaleRefunds(state.refunds, money, preAuth, container::formatDateTime) {
                navigator.push(Route.RefundDetail(it))
            }
            PaymentDetailsCard(sale)
        }
    }
    if (cancelling) {
        state.record?.sale?.let { sale ->
            CancelHoldDialog(sale, onDismiss = { cancelling = false }) {
                cancelling = false
                if (vm.cancel() != null) navigator.push(Route.RefundProcessing)
            }
        }
    }
    ShareEffect(state.transaction.share, vm.transaction::shared)
}

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
        } else if (sale.paidOnline) {
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

/** Confirms cancelling the hold of [sale] (a pre-authorisation, or a sale awaiting its tip); [onConfirm] cancels it. */
@Composable
private fun CancelHoldDialog(
    sale: SaleEntity,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
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
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * What can be done with the sale: opening its payment link while it can still be paid (or its outcome is unknown), the
 * retry advice, a status check, refunding (or cancelling a pre-authorisation), then its receipt.
 */
@Composable
private fun ColumnScope.SaleDetailActions(
    state: SaleDetailUiState,
    onRecheck: () -> Unit,
    onCheckDismiss: () -> Unit,
    onRefund: () -> Unit,
    onCancel: () -> Unit,
    onShowLink: () -> Unit,
) {
    val sale = state.record?.sale ?: return
    if (sale.linkUnsettled) {
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
    if (PaymentAction.CHECK_AGAIN in state.actions) StatusCheck(state.transaction, onRecheck, onCheckDismiss)
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
        sale.offlineMode?.let { mode ->
            LabeledValue(
                stringResource(R.string.detail_offline_mode),
                stringResource(if (mode == "OFFLINE_EMV") R.string.detail_offline_emv else R.string.detail_offline_saf),
            )
        }
        sale.unconfirmedBatchCount?.takeIf { it > 0 }?.let {
            LabeledValue(stringResource(R.string.detail_unconfirmed_batches), it.toString())
        }
        sale.shopperRating?.let { LabeledValue(stringResource(R.string.detail_shopper_rating), "$it / 10") }
        LabeledValue(stringResource(R.string.detail_message), sale.outcomeNote())
        LabeledValue(stringResource(R.string.detail_error_condition), sale.errorCondition)
        LabeledValue(stringResource(R.string.detail_modification_message), sale.modificationNote())
        LabeledValue(stringResource(R.string.detail_emailed), sale.emailedTo)
    }
}

@Composable
internal fun historyViewModel(): HistoryViewModel {
    val container = LocalAppContainer.current
    return viewModel {
        HistoryViewModel(
            history = container.history,
            receipts = container.receipts,
            reconciliation = container.reconciliation,
        )
    }
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
            container.operations,
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
