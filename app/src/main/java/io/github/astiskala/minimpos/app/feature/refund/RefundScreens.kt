package io.github.astiskala.minimpos.app.feature.refund

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import io.github.astiskala.minimpos.app.data.db.SaleLineEntity
import io.github.astiskala.minimpos.app.feature.OutcomeMessage
import io.github.astiskala.minimpos.app.feature.outcomeNote
import io.github.astiskala.minimpos.app.feature.sale.ReceiptToggle
import io.github.astiskala.minimpos.app.feature.sale.ShareReceiptButton
import io.github.astiskala.minimpos.app.payment.TransactionState
import io.github.astiskala.minimpos.app.refund.RefundInvalidReason
import io.github.astiskala.minimpos.app.refund.Refundability
import io.github.astiskala.minimpos.app.refund.RefundablePayment
import io.github.astiskala.minimpos.app.scan.ScanHint
import io.github.astiskala.minimpos.app.scan.ScanMode
import io.github.astiskala.minimpos.app.scan.ScannerView
import io.github.astiskala.minimpos.app.share.ShareEffect
import io.github.astiskala.minimpos.app.ui.components.ActionMessage
import io.github.astiskala.minimpos.app.ui.components.BottomActions
import io.github.astiskala.minimpos.app.ui.components.Card
import io.github.astiskala.minimpos.app.ui.components.ConfirmDialog
import io.github.astiskala.minimpos.app.ui.components.EmailReceiptDialog
import io.github.astiskala.minimpos.app.ui.components.EmptyState
import io.github.astiskala.minimpos.app.ui.components.LabeledValue
import io.github.astiskala.minimpos.app.ui.components.LocalAppContainer
import io.github.astiskala.minimpos.app.ui.components.MiniScaffold
import io.github.astiskala.minimpos.app.ui.components.NumericKeypad
import io.github.astiskala.minimpos.app.ui.components.OutcomeHeader
import io.github.astiskala.minimpos.app.ui.components.OutcomeNote
import io.github.astiskala.minimpos.app.ui.components.PrimaryButton
import io.github.astiskala.minimpos.app.ui.components.ProcessingContent
import io.github.astiskala.minimpos.app.ui.components.QuantityStepper
import io.github.astiskala.minimpos.app.ui.components.ReceiptPreview
import io.github.astiskala.minimpos.app.ui.components.SecondaryButton
import io.github.astiskala.minimpos.app.ui.components.StatusBadge
import io.github.astiskala.minimpos.app.ui.components.StatusKind
import io.github.astiskala.minimpos.app.ui.components.TextInputDialog
import io.github.astiskala.minimpos.app.ui.components.currentLocale
import io.github.astiskala.minimpos.app.ui.components.rememberMoneyFormatter
import io.github.astiskala.minimpos.app.ui.navigation.Navigator
import io.github.astiskala.minimpos.app.ui.navigation.Route
import io.github.astiskala.minimpos.app.ui.theme.LocalDimens
import io.github.astiskala.minimpos.core.codec.RefundQrPayload
import io.github.astiskala.minimpos.core.money.AmountEntry
import io.github.astiskala.minimpos.core.money.CurrencySpec
import io.github.astiskala.minimpos.core.money.MoneyFormatter
import io.github.astiskala.minimpos.core.receipt.ReceiptDocument
import io.github.astiskala.minimpos.core.shopper.ShopperReferences
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.Locale

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
    var confirm by remember { mutableStateOf(false) }
    val ready = state.load as? Refundability.Refundable

    MiniScaffold(
        title = stringResource(R.string.refund_title),
        onBack = navigator::back,
        modifier = modifier,
        bottomBar = {
            if (ready != null) {
                val money = rememberMoneyFormatter(ready.payment.currency)
                BottomActions {
                    PrimaryButton(
                        text = stringResource(R.string.refund_button, money.format(state.refundMinor)),
                        enabled = state.amountValid,
                        onClick = { confirm = true },
                        modifier = Modifier.testTag("startRefund"),
                    )
                }
            }
        },
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
                    onQuantity = vm::setQuantity,
                    onAmount = vm::updateAmount,
                    modifier = Modifier.padding(padding),
                )
                if (confirm) {
                    val money = rememberMoneyFormatter(load.payment.currency)
                    ConfirmDialog(
                        title = stringResource(R.string.refund_confirm_title),
                        message = stringResource(R.string.refund_confirm_message, money.format(state.refundMinor)),
                        confirmLabel = stringResource(R.string.refund_confirm),
                        onConfirm = {
                            confirm = false
                            if (vm.refund()) navigator.replace(Route.RefundProcessing)
                        },
                        onDismiss = { confirm = false },
                    )
                }
            }
        }
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
                },
            ),
        modifier = modifier,
        content = { SecondaryButton(stringResource(R.string.action_back), onBack) },
    )
}

/** The payment being refunded and the choice of what to refund: everything, items or an amount. */
@Composable
private fun RefundChoice(
    state: RefundUiState,
    original: RefundablePayment,
    formatDateTime: (Long) -> String,
    onSelectOption: (RefundOption) -> Unit,
    onQuantity: (lineId: Long, quantity: Int) -> Unit,
    onAmount: ((AmountEntry) -> AmountEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    val money = rememberMoneyFormatter(original.currency)
    val scroll = rememberScrollState()
    // On small screens the sale details push the keypad below the fold, so bring it into view.
    LaunchedEffect(state.option) {
        if (state.option == RefundOption.AMOUNT) {
            withFrameNanos { }
            scroll.animateScrollTo(scroll.maxValue)
        }
    }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(dimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(dimens.spacing)) {
            OriginalPaymentCard(original, money, formatDateTime)
            if (!original.itemsKnown) {
                Text(
                    stringResource(R.string.refund_not_local),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RefundOptions(
                options = listOfNotNull(RefundOption.FULL, RefundOption.ITEMS.takeIf { original.itemsKnown }, RefundOption.AMOUNT),
                selected = state.option,
                onSelect = onSelectOption,
            )
            RefundAmount(state.refundMinor, original.remainingMinor, money)
            when (state.option) {
                RefundOption.FULL -> {}

                RefundOption.ITEMS -> {
                    RefundLines(original.local?.sortedLines.orEmpty(), state.selection, money, onQuantity)
                }

                RefundOption.AMOUNT -> {
                    NumericKeypad(
                        onDigit = { digit -> onAmount { it.append(digit) } },
                        onDoubleZero = { onAmount { it.appendDoubleZero() } },
                        onBackspace = { onAmount { it.backspace() } },
                    )
                }
            }
        }
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
        if (original.refundedMinor > 0) {
            LabeledValue(stringResource(R.string.refund_already_refunded), money.format(original.refundedMinor))
        }
        original.local?.sale?.let { sale ->
            LabeledValue(
                stringResource(R.string.detail_card),
                listOfNotNull(sale.paymentBrand?.uppercase(), sale.maskedPan).joinToString(" "),
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

/** The sale's lines with steppers for the units to refund, up to what is left of each. */
@Composable
private fun ColumnScope.RefundLines(
    lines: List<SaleLineEntity>,
    selection: Map<Long, Int>,
    money: MoneyFormatter,
    onQuantity: (lineId: Long, quantity: Int) -> Unit,
) {
    lines.forEach { line ->
        val remaining = line.quantity - line.refundedQuantity
        val selected = selection[line.id] ?: 0
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(line.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.refund_line_hint, money.format(line.unitPriceMinor), line.quantity, line.refundedQuantity),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            QuantityStepper(selected, onChange = { onQuantity(line.id, it) }, max = remaining, increaseTag = "increase_${line.id}")
        }
        HorizontalDivider()
    }
}

@Composable
private fun refundViewModel(
    payload: String?,
    saleId: String?,
): RefundViewModel {
    val container = LocalAppContainer.current
    return viewModel(key = "${payload.orEmpty()}|${saleId.orEmpty()}") {
        RefundViewModel(payload, saleId, container.sales, container.refunds, container.settingsState)
    }
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
    val state by container.refunds.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        when (val current = state) {
            is TransactionState.Finished -> navigator.replace(Route.RefundResult(current.id))
            TransactionState.Idle -> navigator.home()
            is TransactionState.Processing -> Unit
        }
    }
    BackHandler(enabled = true) {}
    // The refund is stored before the terminal is called, so its amount can be shown as the payment's is.
    val refundId = (state as? TransactionState.Processing)?.id
    val refund by remember(refundId) { refundId?.let(container.refundRecords::observe) ?: flowOf(null) }
        .collectAsStateWithLifecycle(initialValue = null)
    val cancellation = refund?.cancellation == true
    MiniScaffold(
        title = stringResource(if (cancellation) R.string.cancellation_title else R.string.refund_title),
        onBack = null,
        modifier = modifier,
    ) { padding ->
        ProcessingContent(
            amount = refund?.let { rememberMoneyFormatter(it.currency).format(it.amountMinor) },
            message = stringResource(if (cancellation) R.string.cancellation_processing else R.string.refund_processing),
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * The outcome of refund [refundId], with printing, emailing and the receipt. [fromHistory] opens it for an older
 * refund: nothing is printed automatically and leaving goes back instead of home.
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
    var askEmail by remember { mutableStateOf(false) }
    var showReceipt by remember { mutableStateOf(fromHistory) }

    fun done() {
        vm.finish()
        if (fromHistory) navigator.back() else navigator.home()
    }
    BackHandler { done() }
    val dimens = LocalDimens.current
    MiniScaffold(
        title = stringResource(resultTitle(state.refund?.cancellation == true, fromHistory)),
        onBack = { done() },
        modifier = modifier,
        bottomBar = {
            if (state.refund != null) {
                BottomActions { PrimaryButton(stringResource(R.string.action_done), { done() }, modifier = Modifier.testTag("refundDone")) }
            }
        },
    ) { padding ->
        val refund = state.refund ?: return@MiniScaffold
        val money = rememberMoneyFormatter(refund.currency)
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(dimens.screenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 560.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(dimens.spacing),
            ) {
                RefundOutcome(refund, money)
                RefundDetailsCard(refund, container::formatDateTime)
                if (state.canRecheck) RefundRecheck(state, vm.transaction::recheck)
                if (state.accepted) {
                    RefundReceiptActions(
                        state = state,
                        receipt = state.transaction.receipt?.takeIf { showReceipt },
                        onPrint = vm.transaction::print,
                        onEmail = { askEmail = true },
                        onShare = vm.transaction::share,
                        onToggleReceipt = { showReceipt = !showReceipt },
                    )
                }
            }
        }
    }
    if (askEmail) {
        EmailReceiptDialog(
            onSend = {
                askEmail = false
                vm.transaction.email(it)
            },
            onDismiss = { askEmail = false },
        )
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
    refundStatusTitle(refund.status, refund.cancellation),
    money.format(refund.amountMinor),
    titleTag = "refundStatus",
) {
    if (refund.status == RefundStatus.REQUESTED) {
        OutcomeNote(stringResource(if (refund.cancellation) R.string.cancellation_async_note else R.string.refund_async_note))
    }
    refund.outcomeNote()?.let { OutcomeNote(it) }
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
            refund.originalReference ?: refund.originalTransactionId,
        )
        LabeledValue(stringResource(R.string.detail_date), formatDateTime(refund.createdAt))
        LabeledValue(stringResource(R.string.detail_psp), refund.pspReference)
    }
}

/** Checking an unknown refund outcome again with the terminal, and what the last check found. */
@Composable
private fun ColumnScope.RefundRecheck(
    state: RefundResultUiState,
    onRecheck: () -> Unit,
) {
    SecondaryButton(
        stringResource(R.string.result_check_again),
        onRecheck,
        loading = state.transaction.rechecking,
        icon = Icons.Default.Refresh,
        modifier = Modifier.testTag("refundRecheck"),
    )
    if (state.transaction.stillUnknown) {
        Text(stringResource(R.string.refund_still_unknown), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Printing, emailing and sharing (on phones and tablets) an accepted refund's receipt, and showing it ([receipt] is
 * null while hidden).
 */
@Composable
private fun ColumnScope.RefundReceiptActions(
    state: RefundResultUiState,
    receipt: ReceiptDocument?,
    onPrint: () -> Unit,
    onEmail: () -> Unit,
    onShare: () -> Unit,
    onToggleReceipt: () -> Unit,
) {
    if (state.transaction.canPrint) {
        SecondaryButton(
            stringResource(R.string.result_print),
            onPrint,
            loading = state.transaction.print.running,
            icon = Icons.Default.Print,
        )
        OutcomeMessage(state.transaction.print)
    }
    if (state.transaction.canEmail) {
        SecondaryButton(
            stringResource(R.string.result_email),
            onEmail,
            loading = state.transaction.email.running,
            icon = Icons.Default.Email,
        )
        OutcomeMessage(state.transaction.email)
    }
    if (state.transaction.canShare) ShareReceiptButton(onShare)
    ReceiptToggle(receipt, onToggleReceipt)
}

@Composable
private fun refundResultViewModel(
    refundId: String,
    fromHistory: Boolean,
): RefundResultViewModel {
    val container = LocalAppContainer.current
    return viewModel(key = refundId) {
        RefundResultViewModel(refundId, container.refundRecords, container.receipts, container.refunds, justMade = !fromHistory)
    }
}
