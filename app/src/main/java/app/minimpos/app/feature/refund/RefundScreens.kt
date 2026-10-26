package app.minimpos.app.feature.refund

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.minimpos.app.R
import app.minimpos.app.data.db.RefundCancelStatus
import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SaleLineEntity
import app.minimpos.app.data.db.SaleTenderEntity
import app.minimpos.app.data.db.TenderKind
import app.minimpos.app.feature.MerchantCopy
import app.minimpos.app.feature.ReceiptActions
import app.minimpos.app.feature.ResultAction
import app.minimpos.app.feature.ResultBar
import app.minimpos.app.feature.StatusCheck
import app.minimpos.app.feature.lock.ManagerApproval
import app.minimpos.app.feature.lock.ManagerGate
import app.minimpos.app.feature.lock.rememberManagerAction
import app.minimpos.app.feature.outcomeNote
import app.minimpos.app.feature.text
import app.minimpos.app.history.RefundInvalidReason
import app.minimpos.app.history.Refundability
import app.minimpos.app.history.RefundablePayment
import app.minimpos.app.history.approvedSynchronously
import app.minimpos.app.payment.RefundCancelResult
import app.minimpos.app.payment.RefundLegSkip
import app.minimpos.app.payment.RefundLegState
import app.minimpos.app.payment.RefundStarted
import app.minimpos.app.payment.TransactionState
import app.minimpos.app.scan.ScanHint
import app.minimpos.app.scan.ScanMode
import app.minimpos.app.scan.ScannerView
import app.minimpos.app.share.ShareEffect
import app.minimpos.app.ui.components.BlockedReason
import app.minimpos.app.ui.components.BottomActions
import app.minimpos.app.ui.components.Card
import app.minimpos.app.ui.components.ConfirmDialog
import app.minimpos.app.ui.components.EmptyState
import app.minimpos.app.ui.components.LabeledValue
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.MiniScaffold
import app.minimpos.app.ui.components.NumericKeypad
import app.minimpos.app.ui.components.OutcomeHeader
import app.minimpos.app.ui.components.OutcomeNote
import app.minimpos.app.ui.components.PrimaryButton
import app.minimpos.app.ui.components.ProcessingContent
import app.minimpos.app.ui.components.QuantityStepper
import app.minimpos.app.ui.components.ScreenColumn
import app.minimpos.app.ui.components.SecondaryButton
import app.minimpos.app.ui.components.StatusKind
import app.minimpos.app.ui.components.currentSetupGate
import app.minimpos.app.ui.components.rememberMoneyFormatter
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.core.access.ManagerAction
import app.minimpos.core.codec.RefundQrPayload
import app.minimpos.core.giftcard.GiftCardProvider
import app.minimpos.core.money.AmountEntry
import app.minimpos.core.money.MoneyFormatter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Scans the refund QR code on a receipt and opens the refund for it; other codes show a message and scanning goes on. */
@Composable
fun RefundScanScreen(
    navigator: Navigator,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val invalid = stringResource(R.string.refund_scan_invalid)
    MiniScaffold(
        title = stringResource(R.string.refund_scan_title),
        onBack = navigator::back,
        modifier = modifier,
        snackbarHostState = snackbar,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            ScannerView(
                mode = ScanMode.QR,
                continuous = true,
                modifier = Modifier.fillMaxSize(),
                onResult = { code ->
                    if (RefundQrPayload.decode(code) != null) {
                        navigator.replace(Route.Refund(payload = code))
                    } else {
                        scope.launch { snackbar.showSnackbar(invalid) }
                    }
                },
            )
            ScanHint(stringResource(R.string.refund_scan_hint), Modifier.align(Alignment.BottomCenter))
        }
    }
}

/**
 * Choosing what to refund of the payment given by [payload] (a scanned code) or [saleId] (from history): the whole
 * payment, items or an amount. The refund starts after the operator confirms.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefundScreen(
    payload: String?,
    saleId: String?,
    navigator: Navigator,
    modifier: Modifier = Modifier,
    vm: RefundViewModel = refundViewModel(payload, saleId),
) {
    val container = LocalAppContainer.current
    val state by vm.state.collectAsStateWithLifecycle()
    var confirm by rememberSaveable { mutableStateOf(false) }
    val ready = state.load as? Refundability.Refundable
    val gate = currentSetupGate()
    val managerConfigured by container.managerAvailable(ManagerAction.CASH_REFUND).collectAsStateWithLifecycle(initialValue = false)
    val cashBlocked = state.returnsCash && !managerConfigured
    val approveRefund = rememberManagerAction()

    val permissions =
        buildSet {
            if (state.returnsCard) add(ManagerAction.REFERENCED_REFUND)
            if (state.returnsCash) add(ManagerAction.CASH_REFUND)
        }
    ManagerGate(permissions, navigator) {
        MiniScaffold(
            title = stringResource(R.string.refund_title),
            onBack = navigator::back,
            modifier = modifier,
            bottomBar = { if (ready != null) RefundBar(ready.payment.currency, state, cashBlocked) { gate.run { confirm = true } } },
        ) { padding ->
            when (val load = state.load) {
                null -> {
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                is Refundability.NotRefundable -> {
                    RefundUnavailable(load.reason, onBack = navigator::back, modifier = Modifier.padding(padding))
                }

                is Refundability.Refundable -> {
                    RemoteRefundReview(load.payment.local == null && !state.remoteReviewed, { vm.reviewRemote(true) }, navigator::back)
                    RefundChoice(
                        state = state,
                        original = load.payment,
                        formatDateTime = container::formatDateTime,
                        onSelectOption = vm::selectOption,
                        onEditLeg = vm::editLeg,
                        onLegAmount = vm::updateLeg,
                        onResetAllocation = vm::resetAllocation,
                        onQuantity = vm::setQuantity,
                        onAmount = vm::updateAmount,
                        modifier = Modifier.padding(padding),
                    )
                    if (confirm) {
                        RefundConfirmation(state, load.payment.currency, onDismiss = { confirm = false }) {
                            confirm = false
                            approveRefund(state.managerActions) { navigator.showRefundProgress(vm.refund()) }
                        }
                    }
                }
            }
        }
    }
}

private fun Navigator.showRefundProgress(started: RefundStarted) {
    when (started) {
        RefundStarted.SINGLE -> replace(Route.RefundProcessing)
        RefundStarted.LEGS -> replace(Route.RefundLegs)
        RefundStarted.NONE -> Unit
    }
}

/** Confirms returning the chosen amount, to one card or, split across original payments, cards first and cash last. */
@Composable
private fun RefundConfirmation(
    state: RefundUiState,
    currency: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val money = rememberMoneyFormatter(currency)
    val split = state.allocation.count { it.value > 0 } > 1 || state.returnsCash
    ConfirmDialog(
        title = stringResource(R.string.refund_confirm_title),
        message =
            stringResource(
                if (split) R.string.split_refund_confirm_message else R.string.refund_confirm_message,
                money.format(state.refundMinor),
            ),
        confirmLabel = stringResource(R.string.refund_confirm),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** "Refund" with the amount, pinned under the refund's choice, asking to confirm ([onRefund]) once the amount is valid. */
@Composable
private fun RefundBar(
    currency: String,
    state: RefundUiState,
    cashBlocked: Boolean,
    onRefund: () -> Unit,
) {
    val money = rememberMoneyFormatter(currency)
    BottomActions {
        PrimaryButton(
            text = stringResource(R.string.refund_button, money.format(state.refundMinor)),
            enabled = state.amountValid && !cashBlocked,
            onClick = onRefund,
            modifier = Modifier.testTag("startRefund"),
        )
        if (cashBlocked) BlockedReason(stringResource(R.string.split_cash_refund_manager))
    }
}

@Composable
private fun RemoteRefundReview(
    required: Boolean,
    confirm: () -> Unit,
    cancel: () -> Unit,
) {
    if (required) {
        ConfirmDialog(
            title = stringResource(R.string.refund_review_title),
            message = stringResource(R.string.refund_review_message),
            confirmLabel = stringResource(R.string.refund_review_confirm),
            onConfirm = confirm,
            onDismiss = cancel,
        )
    }
}

/** Why the payment cannot be refunded, with a way back. */
@Composable
private fun RefundUnavailable(
    reason: RefundInvalidReason,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        icon = Icons.Default.Remove,
        title = stringResource(R.string.refund_unavailable),
        message =
            stringResource(
                when (reason) {
                    RefundInvalidReason.NOT_A_RECEIPT -> R.string.refund_scan_invalid
                    RefundInvalidReason.NOT_REFUNDABLE -> R.string.refund_not_refundable
                    RefundInvalidReason.FULLY_REFUNDED -> R.string.refund_fully_refunded
                    RefundInvalidReason.PRE_AUTHORISATION -> R.string.refund_pre_authorisation
                    RefundInvalidReason.AWAITING_TIP -> R.string.refund_awaiting_tip
                    RefundInvalidReason.CHARGED_ELSEWHERE -> R.string.refund_charged_elsewhere
                    RefundInvalidReason.VOUCHER_NOT_REFUNDABLE -> R.string.refund_voucher_not_refundable
                },
            ),
        modifier = modifier,
        content = { SecondaryButton(stringResource(R.string.action_back), onBack) },
    )
}

/** What Titres-restaurant paid of [original], which is never returned through the app, as cash or otherwise. */
@Composable
private fun VoucherKept(
    original: RefundablePayment,
    money: MoneyFormatter,
) {
    val kept =
        original.local
            ?.tenders
            ?.filter(RefundablePayment.Companion::keepsVoucherValue)
            ?.sumOf { it.authorisedMinor ?: it.requestedMinor } ?: 0
    if (kept > 0) {
        Text(
            stringResource(R.string.refund_voucher_kept, money.format(kept)),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("refundVoucherKept"),
        )
    }
}

/** The payment being refunded and the choice of what to refund: everything, items or an amount. */
@Composable
private fun RefundChoice(
    state: RefundUiState,
    original: RefundablePayment,
    formatDateTime: (Long) -> String,
    onSelectOption: (RefundOption) -> Unit,
    onEditLeg: (String?) -> Unit,
    onLegAmount: ((AmountEntry) -> AmountEntry) -> Unit,
    onResetAllocation: () -> Unit,
    onQuantity: (lineId: Long, quantity: Int) -> Unit,
    onAmount: ((AmountEntry) -> AmountEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    val money = rememberMoneyFormatter(original.currency)
    val scroll = rememberLazyListState()
    val lines = remember(original.local) { original.local?.sortedLines.orEmpty() }
    // The amount keypad is one lazy item below the original payment and mode selector on small screens.
    LaunchedEffect(state.option, state.editingLeg) {
        if (state.option == RefundOption.AMOUNT || state.editingLeg != null) {
            withFrameNanos { }
            scroll.animateScrollToItem(1)
        }
    }
    LazyColumn(
        modifier.fillMaxSize().testTag("refundChoices"),
        state = scroll,
        contentPadding = PaddingValues(dimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item(key = "original", contentType = "original") {
            Column(
                Modifier.widthIn(max = dimens.contentMaxWidth).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(dimens.spacing),
            ) {
                OriginalPaymentCard(original, money, formatDateTime)
                RefundOriginNote(original.local != null)
                VoucherKept(original, money)
                RefundOptions(
                    options = listOfNotNull(RefundOption.FULL, RefundOption.ITEMS.takeIf { original.itemsKnown }, RefundOption.AMOUNT),
                    selected = state.option,
                    onSelect = onSelectOption,
                )
                RefundAmount(state.refundMinor, original.remainingMinor, money)
                RefundAllocation(state, original, money, onEditLeg, onResetAllocation)
                ReturnComponents(original, state, money)
            }
        }
        if (state.editingLeg != null) {
            keypad("legAmount", onLegAmount)
            return@LazyColumn
        }
        when (state.option) {
            RefundOption.FULL -> {}

            RefundOption.ITEMS -> {
                items(lines, key = { it.id }, contentType = { "line" }) { line ->
                    Column(Modifier.widthIn(max = dimens.contentMaxWidth).fillMaxWidth().padding(top = dimens.spacing)) {
                        RefundLine(line, state.selection[line.id] ?: 0, money, onQuantity)
                    }
                }
            }

            RefundOption.AMOUNT -> {
                keypad("amount", onAmount)
            }
        }
    }
}

/** The keypad editing an amount, as one lazy item [key] below the choices. */
private fun LazyListScope.keypad(
    key: String,
    onAmount: ((AmountEntry) -> AmountEntry) -> Unit,
) = item(key = key, contentType = "amount") {
    val dimens = LocalDimens.current
    Column(Modifier.widthIn(max = dimens.contentMaxWidth).fillMaxWidth().padding(top = dimens.spacing)) {
        NumericKeypad(
            onDigit = { digit -> onAmount { it.append(digit) } },
            onDoubleZero = { onAmount { it.appendDoubleZero() } },
            onBackspace = { onAmount { it.backspace() } },
        )
    }
}

/** Components included in this return, rather than an extra amount added to it. */
@Composable
private fun ReturnComponents(
    original: RefundablePayment,
    state: RefundUiState,
    money: MoneyFormatter,
) {
    val split = original.legs.isNotEmpty()
    // Local gift cards take payments only, so their share of the sale is not offered for return here.
    if (original.local
            ?.tenders
            .orEmpty()
            .any { it.approved && it.giftCard == GiftCardProvider.LOCAL.name }
    ) {
        OutcomeNote(stringResource(R.string.refund_gift_card_local))
    }
    LabeledValue(
        stringResource(R.string.surcharge_return_fee),
        (if (split) original.surchargeFor(state.allocation) else original.surchargeFor(state.choice))?.let(money::format)
            ?: stringResource(R.string.surcharge_unknown),
    )
    LabeledValue(
        stringResource(R.string.surcharge_return_tip),
        (if (split) original.tipFor(state.allocation) else original.tipFor(state.choice))?.let(money::format)
            ?: stringResource(R.string.surcharge_unknown),
    )
}

/**
 * What each original tender returns of the chosen refund, proposed automatically; selecting a tender lets staff adjust
 * its amount on the keypad within what it can still return, and the total must match before the refund starts.
 */
@Composable
private fun RefundAllocation(
    state: RefundUiState,
    original: RefundablePayment,
    money: MoneyFormatter,
    onEditLeg: (String?) -> Unit,
    onReset: () -> Unit,
) {
    if (original.legs.size <= 1) return
    Column(verticalArrangement = Arrangement.spacedBy(LocalDimens.current.spacing)) {
        AllocationLegs(state, original, money, onEditLeg, onReset)
    }
}

@Composable
private fun ColumnScope.AllocationLegs(
    state: RefundUiState,
    original: RefundablePayment,
    money: MoneyFormatter,
    onEditLeg: (String?) -> Unit,
    onReset: () -> Unit,
) {
    Text(stringResource(R.string.split_refund_tenders), style = MaterialTheme.typography.titleSmall)
    original.legs.forEach { leg ->
        val tender = leg.tender ?: return@forEach
        FilterChip(
            selected = state.editingLeg == tender.id,
            modifier = Modifier.fillMaxWidth().testTag("refundTender_${tender.id}"),
            onClick = { onEditLeg(tender.id.takeUnless { it == state.editingLeg }) },
            label = {
                Text(
                    stringResource(
                        R.string.split_refund_tender_line,
                        tenderLabel(tender),
                        money.format(state.allocation[tender.id] ?: 0),
                        money.format(leg.remainingMinor),
                    ),
                )
            },
        )
    }
    val allocated = state.allocation.values.sum()
    if (allocated != state.refundMinor) {
        Text(
            stringResource(R.string.split_refund_unallocated, money.format(allocated), money.format(state.refundMinor)),
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (state.adjusted != null) SecondaryButton(stringResource(R.string.split_refund_reset), onReset, Modifier.testTag("resetAllocation"))
}

@Composable
private fun RefundOriginNote(itemsKnown: Boolean) {
    if (!itemsKnown) {
        Text(
            stringResource(R.string.refund_not_local),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OriginalPaymentCard(
    original: RefundablePayment,
    money: MoneyFormatter,
    formatDateTime: (Long) -> String,
) {
    Card {
        LabeledValue(stringResource(R.string.detail_reference), original.reference)
        LabeledValue(stringResource(R.string.detail_date), formatDateTime(original.createdAt.toEpochMilli()))
        LabeledValue(stringResource(R.string.refund_original_amount), money.format(original.amountMinor))
        LabeledValue(
            stringResource(R.string.surcharge_purchase),
            original.purchaseMinor?.let(money::format) ?: stringResource(R.string.surcharge_unknown),
        )
        LabeledValue(
            stringResource(R.string.surcharge_label),
            original.surchargeMinor?.let(money::format) ?: stringResource(R.string.surcharge_unknown),
        )
        if (original.reservedMinor > 0) {
            LabeledValue(stringResource(R.string.surcharge_reserved), money.format(original.reservedMinor))
        }
        Text(stringResource(R.string.surcharge_refund_order), Modifier.padding(LocalDimens.current.spacing))
        if (original.refundedMinor > 0) {
            LabeledValue(stringResource(R.string.refund_already_refunded), money.format(original.refundedMinor))
        }
        original.local?.sale?.takeIf { original.legs.size <= 1 }?.let { sale ->
            LabeledValue(
                stringResource(R.string.detail_card),
                listOfNotNull(
                    (if (original.tender == null) sale.paymentBrand else original.tender.paymentBrand)?.uppercase(),
                    if (original.tender == null) sale.maskedPan else original.tender.maskedPan,
                ).joinToString(" ").ifBlank { stringResource(R.string.surcharge_unknown) },
            )
        }
        LabeledValue(stringResource(R.string.detail_psp), original.transactionId.substringAfter('.'))
    }
}

@Composable
private fun RefundOptions(
    options: List<RefundOption>,
    selected: RefundOption,
    onSelect: (RefundOption) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = selected == option,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                modifier = Modifier.testTag("option_${option.name}"),
            ) {
                Text(
                    stringResource(
                        when (option) {
                            RefundOption.FULL -> R.string.refund_option_full
                            RefundOption.ITEMS -> R.string.refund_option_items
                            RefundOption.AMOUNT -> R.string.refund_option_amount
                        },
                    ),
                )
            }
        }
    }
}

/** The amount to refund, above the keypad or item list as on a calculator; in red with a note when it is too much. */
@Composable
private fun ColumnScope.RefundAmount(
    amountMinor: Long,
    remainingMinor: Long,
    money: MoneyFormatter,
) {
    val tooMuch = amountMinor > remainingMinor
    Text(
        money.format(amountMinor),
        style = LocalDimens.current.amountStyle,
        textAlign = TextAlign.Center,
        color = if (tooMuch) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().testTag("refundAmount"),
    )
    if (tooMuch) {
        Text(
            stringResource(R.string.refund_too_much, money.format(remainingMinor)),
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** One refundable line, composed only while its lazy row is visible. */
@Composable
private fun ColumnScope.RefundLine(
    line: SaleLineEntity,
    selected: Int,
    money: MoneyFormatter,
    onQuantity: (lineId: Long, quantity: Int) -> Unit,
) {
    val remaining = line.quantity - line.refundedQuantity
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(line.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.refund_line_hint, money.format(line.unitPriceMinor), line.quantity, line.refundedQuantity),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        QuantityStepper(
            selected,
            line.name,
            onChange = { onQuantity(line.id, it) },
            max = remaining,
            increaseTag = "increase_${line.id}",
        )
    }
    HorizontalDivider()
}

@Composable
private fun refundViewModel(
    payload: String?,
    saleId: String?,
): RefundViewModel {
    val container = LocalAppContainer.current
    return viewModel(key = "${payload.orEmpty()}|${saleId.orEmpty()}") {
        RefundViewModel(payload, saleId, container.sales, container.storedPaymentActions)
    }
}

/**
 * A return across several original tenders: each leg as it is sent and stored, cards first and cash last, then their
 * outcomes, each opening its own refund. Back waits until every leg was attempted; Home then leaves the run.
 */
@Composable
fun RefundLegsScreen(
    navigator: Navigator,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val run by container.refundLegs.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val wait = stringResource(R.string.processing_wait)
    val finished = run?.finished != false

    fun done() {
        container.refundLegs.acknowledge()
        navigator.home()
    }
    BackHandler { if (finished) done() else scope.launch { snackbar.showSnackbar(wait) } }
    MiniScaffold(
        title = stringResource(R.string.split_refund_title),
        onBack = { if (finished) done() else scope.launch { snackbar.showSnackbar(wait) } },
        modifier = modifier,
        snackbarHostState = snackbar,
        bottomBar = { if (finished) ResultBar(onHome = { done() }, next = null) },
    ) { padding ->
        val current = run ?: return@MiniScaffold
        ScreenColumn(padding, horizontalAlignment = Alignment.CenterHorizontally) {
            if (!current.finished) CircularProgressIndicator(Modifier.testTag("progress"))
            current.legs.forEachIndexed { index, leg ->
                RefundLegRow(index, leg) { id -> navigator.push(Route.RefundDetail(id)) }
            }
            if (current.finished && current.legs.any { it.status != RefundStatus.REQUESTED }) {
                OutcomeNote(stringResource(R.string.split_refund_incomplete))
            }
        }
    }
}

@Composable
private fun RefundLegRow(
    index: Int,
    leg: RefundLegState,
    onOpen: (String) -> Unit,
) {
    val money = rememberMoneyFormatter(leg.amount.currency)
    val status =
        when {
            leg.skipped != null -> stringResource(R.string.split_refund_not_sent)
            leg.status == null -> stringResource(R.string.status_pending)
            leg.cash && leg.status == RefundStatus.REQUESTED -> stringResource(R.string.split_cash_returned)
            else -> refundStatusTitle(leg.status)
        }
    Card(Modifier.testTag("refundLeg_$index")) {
        LabeledValue(
            stringResource(if (leg.cash) R.string.split_tender_cash else R.string.split_tender_card),
            money.format(leg.amount.amountMinor),
            emphasize = true,
        )
        LabeledValue(stringResource(R.string.split_leg_status), status)
        if (leg.cash && leg.status == RefundStatus.REQUESTED) OutcomeNote(stringResource(R.string.split_cash_hand_back))
        if (leg.skipped == RefundLegSkip.MANAGER_APPROVAL) OutcomeNote(stringResource(R.string.split_cash_refund_manager))
        if (leg.skipped == RefundLegSkip.BALANCE_CHANGED) OutcomeNote(stringResource(R.string.split_balance_changed))
        leg.refundId?.let { id -> SecondaryButton(stringResource(R.string.split_refund_open), { onOpen(id) }) }
    }
}

/** How an original tender is named where staff choose what it returns. */
@Composable
@ReadOnlyComposable
internal fun tenderLabel(tender: SaleTenderEntity): String =
    if (tender.kind == TenderKind.CASH) {
        stringResource(R.string.split_tender_cash)
    } else {
        listOf(
            stringResource(if (tender.giftCard != null) R.string.split_tender_gift_card else R.string.split_tender_card),
            tender.paymentBrand?.uppercase(),
            tender.maskedPan,
        ).filterNotNull().joinToString(" ")
    }

/**
 * Waits while the terminal handles the refund (or the cancellation of a pre-authorisation), then shows its result. Back
 * is disabled until then.
 */
@Composable
fun RefundProcessingScreen(
    navigator: Navigator,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    // A saved-card charge's refund runs through the Checkout API rather than the terminal; this follows whichever runs.
    val state by container.operations.refundProgress.collectAsStateWithLifecycle(initialValue = container.operations.currentRefund)
    LaunchedEffect(state) {
        when (val current = state) {
            is TransactionState.Finished -> navigator.replace(Route.RefundResult(current.id))
            TransactionState.Idle -> navigator.home()
            is TransactionState.Processing -> Unit
        }
    }
    // The request is stored and runs on whatever screen is shown, but this one shows its result: Back says to wait.
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val wait = stringResource(R.string.processing_wait)
    BackHandler { scope.launch { snackbar.showSnackbar(wait) } }
    val processing = state as? TransactionState.Processing
    // The refund is stored before the terminal is called, so whether it is a cancellation can be shown straight away.
    val refund by remember(processing?.id) { processing?.id?.let(container.refundRecords::observe) ?: flowOf(null) }
        .collectAsStateWithLifecycle(initialValue = null)
    val cancellation = refund?.cancellation == true
    MiniScaffold(
        title = stringResource(if (cancellation) R.string.cancellation_title else R.string.refund_title),
        onBack = null,
        modifier = modifier,
        snackbarHostState = snackbar,
    ) { padding ->
        ProcessingContent(
            amount = processing?.amount?.let { rememberMoneyFormatter(it.currency).format(it.amountMinor) },
            message = stringResource(if (cancellation) R.string.cancellation_processing else R.string.refund_processing),
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * The outcome of refund [refundId], with a status check while it is unknown and the receipt actions once accepted.
 * Just made, Home sits beside "New refund" (Home alone after a cancellation). [fromHistory] opens it as an older
 * refund's detail: nothing is printed automatically, there is no bottom bar and leaving goes back instead of home.
 */
@Composable
fun RefundResultScreen(
    refundId: String,
    navigator: Navigator,
    modifier: Modifier = Modifier,
    fromHistory: Boolean = false,
    vm: RefundResultViewModel = refundResultViewModel(refundId, fromHistory),
) {
    val container = LocalAppContainer.current
    val state by vm.state.collectAsStateWithLifecycle()
    val gate = currentSetupGate()

    var approveCancel by rememberSaveable { mutableStateOf(false) }
    if (approveCancel) {
        return ManagerApproval(actions = setOf(ManagerAction.UNREFERENCED_REFUND), onApprove = {
            approveCancel = false
            gate.run { vm.cancelRefund() }
        }, onCancel = { approveCancel = false })
    }

    fun done() {
        vm.finish()
        if (fromHistory) navigator.back() else navigator.home()
    }
    BackHandler { done() }
    MiniScaffold(
        title = stringResource(resultTitle(state.refund?.cancellation == true, fromHistory)),
        onBack = { done() },
        modifier = modifier,
        bottomBar = {
            val refund = state.refund
            if (refund != null && !fromHistory) {
                ResultBar(
                    onHome = { done() },
                    next =
                        ResultAction(stringResource(R.string.refund_new), "newRefund") {
                            gate.run {
                                vm.finish()
                                navigator.replace(Route.RefundStart)
                            }
                        }.takeUnless { refund.cancellation },
                )
            }
        },
    ) { padding ->
        val refund = state.refund ?: return@MiniScaffold
        val money = rememberMoneyFormatter(refund.currency)
        ScreenColumn(padding, horizontalAlignment = Alignment.CenterHorizontally) {
            RefundOutcome(refund, money)
            RefundDetailsCard(refund, container::formatDateTime)
            if (state.canRecheck) StatusCheck(state.transaction, vm.transaction::recheck, vm.transaction::dismissCheckResult)
            if (state.accepted) ReceiptActions(state.transaction, vm.transaction, MerchantCopy.NONE)
            RefundCancellation(state, onConfirm = { approveCancel = true }, onDismiss = vm::dismissCancel)
        }
    }
    ShareEffect(state.transaction.share, vm.transaction::shared)
}

/** The result screen's title: a refund or a [cancellation], just made or opened [fromHistory]. */
@StringRes
private fun resultTitle(
    cancellation: Boolean,
    fromHistory: Boolean,
): Int =
    when {
        cancellation -> if (fromHistory) R.string.cancellation_detail_title else R.string.cancellation_title
        fromHistory -> R.string.refund_detail_title
        else -> R.string.refund_title
    }

/** The outcome badge and title, the amount and what happens next. */
@Composable
private fun RefundOutcome(
    refund: RefundEntity,
    money: MoneyFormatter,
) = OutcomeHeader(
    refundStatusKind(refund.status),
    if (refund.approvedSynchronously) {
        stringResource(
            R.string.refund_status_approved,
        )
    } else {
        refundStatusTitle(refund.status, refund.cancellation)
    },
    money.format(refund.amountMinor),
    titleTag = "refundStatus",
) {
    when {
        refund.approvedSynchronously -> {
            OutcomeNote(stringResource(R.string.unreferenced_approved_note))
        }

        refund.status == RefundStatus.REQUESTED -> {
            OutcomeNote(stringResource(if (refund.cancellation) R.string.cancellation_async_note else R.string.refund_async_note))
        }
    }
    refund.outcomeNote()?.let { OutcomeNote(it) }
}

/**
 * Cancelling a delayed unreferenced refund: what Adyen was last asked, and the action while it can still be asked,
 * confirmed and approved with the Manager PIN when one is set. A received request never claims the refund stopped.
 */
@Composable
private fun RefundCancellation(
    state: RefundResultUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val refund = state.refund ?: return
    var confirm by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        refund.cancelStatus?.let { OutcomeNote(stringResource(refundCancelNote(it)), Modifier.testTag("refundCancelStatus")) }
        when (val result = state.cancel) {
            is RefundCancelResult.NotDone -> OutcomeNote(result.failure.text())
            is RefundCancelResult.Unconfirmed -> OutcomeNote(result.failure.text())
            RefundCancelResult.Requested, RefundCancelResult.NotAllowed, null -> Unit
        }
        if (state.canCancel || state.cancelling) {
            SecondaryButton(
                stringResource(if (refund.cancelStatus?.unresolved == true) R.string.refund_cancel_retry else R.string.refund_cancel),
                {
                    onDismiss()
                    confirm = true
                },
                Modifier.testTag("cancelUnreferencedRefund"),
                loading = state.cancelling,
            )
        }
        if (confirm) {
            ConfirmDialog(
                title = stringResource(R.string.refund_cancel),
                message = stringResource(R.string.refund_cancel_confirm),
                confirmLabel = stringResource(R.string.refund_cancel),
                onConfirm = {
                    confirm = false
                    onConfirm()
                },
                onDismiss = { confirm = false },
            )
        }
    }
}

/** What Adyen was last asked about cancelling a delayed refund in [status]. */
@StringRes
private fun refundCancelNote(status: RefundCancelStatus): Int =
    when (status) {
        RefundCancelStatus.REQUESTED -> R.string.refund_cancel_requested
        RefundCancelStatus.FAILED -> R.string.refund_cancel_failed
        RefundCancelStatus.SENDING, RefundCancelStatus.UNKNOWN -> R.string.refund_cancel_unknown
    }

/** How a refund in [status] is shown: accepted is a success, failed an error, and the rest warnings. */
fun refundStatusKind(status: RefundStatus): StatusKind =
    when (status) {
        RefundStatus.REQUESTED -> StatusKind.SUCCESS
        RefundStatus.FAILED -> StatusKind.ERROR
        RefundStatus.UNKNOWN, RefundStatus.PENDING -> StatusKind.WARNING
    }

/**
 * The heading for a refund in [status], such as "Refund requested", or for the [cancellation] of a pre-authorisation,
 * such as "Cancellation requested".
 */
@Composable
@ReadOnlyComposable
fun refundStatusTitle(
    status: RefundStatus,
    cancellation: Boolean = false,
): String =
    stringResource(
        when (status) {
            RefundStatus.REQUESTED -> if (cancellation) R.string.cancellation_status_requested else R.string.refund_status_requested
            RefundStatus.FAILED -> if (cancellation) R.string.cancellation_status_failed else R.string.refund_status_failed
            RefundStatus.UNKNOWN -> if (cancellation) R.string.cancellation_status_unknown else R.string.refund_status_unknown
            RefundStatus.PENDING -> R.string.status_pending
        },
    )

/** The refund's reference, the sale (or pre-authorisation) it refunds, when it was made and its PSP reference. */
@Composable
private fun RefundDetailsCard(
    refund: RefundEntity,
    formatDateTime: (Long) -> String,
) {
    Card {
        LabeledValue(stringResource(R.string.detail_reference), refund.merchantReference)
        LabeledValue(
            stringResource(if (refund.cancellation) R.string.receipt_cancelled_reference else R.string.receipt_original_sale),
            if (refund.unreferenced) {
                stringResource(R.string.unreferenced_none)
            } else {
                refund.originalReference
                    ?: refund.originalTransactionId
            },
        )
        refund.unreferencedReason?.let {
            LabeledValue(
                stringResource(R.string.unreferenced_reason),
                stringResource(unreferencedReasonLabel(it)),
            )
        }
        refund.acquirerResponseCode?.let { LabeledValue(stringResource(R.string.unreferenced_acquirer), it) }
        LabeledValue(stringResource(R.string.detail_date), formatDateTime(refund.createdAt))
        LabeledValue(stringResource(R.string.detail_psp), refund.pspReference)
    }
}

@Composable
private fun refundResultViewModel(
    refundId: String,
    fromHistory: Boolean,
): RefundResultViewModel {
    val container = LocalAppContainer.current
    return viewModel(key = refundId) {
        RefundResultViewModel(
            refundId,
            container.refundRecords,
            container.receipts,
            container.operations,
            justMade = !fromHistory,
            cancellations = container.refundCancellations,
            delayed = container.settingsState.map { it.payment.unreferencedRefunds.delayed },
        )
    }
}
