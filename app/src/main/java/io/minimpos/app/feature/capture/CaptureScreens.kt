package io.minimpos.app.feature.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.minimpos.app.R
import io.minimpos.app.feature.OutcomeMessage
import io.minimpos.app.refund.PaymentStanding
import io.minimpos.app.ui.components.BottomActions
import io.minimpos.app.ui.components.Card
import io.minimpos.app.ui.components.ConfirmDialog
import io.minimpos.app.ui.components.LabeledValue
import io.minimpos.app.ui.components.LocalAppContainer
import io.minimpos.app.ui.components.MiniScaffold
import io.minimpos.app.ui.components.NumericKeypad
import io.minimpos.app.ui.components.PrimaryButton
import io.minimpos.app.ui.components.SecondaryButton
import io.minimpos.app.ui.components.rememberMoneyFormatter
import io.minimpos.app.ui.navigation.Navigator
import io.minimpos.app.ui.theme.LocalDimens
import io.minimpos.core.money.AmountEntry
import io.minimpos.core.money.MoneyFormatter

/**
 * Entering the tip the customer wrote on the receipt of sale [saleId], as the tip or as the total, then capturing the
 * bill plus the tip after the operator confirms ("No tip" captures the bill). Closes once the capture went through; a
 * refusal or failure is shown here.
 */
@Composable
fun TipScreen(
    saleId: String,
    navigator: Navigator,
    modifier: Modifier = Modifier,
    vm: TipViewModel = tipViewModel(saleId),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.submission.done) { if (state.submission.done) navigator.back() }
    MiniScaffold(
        title = stringResource(R.string.tip_title),
        onBack = navigator::back,
        modifier = modifier,
        bottomBar = {
            if (state.sale != null) {
                BottomActions {
                    Row(horizontalArrangement = Arrangement.spacedBy(LocalDimens.current.spacing)) {
                        SecondaryButton(
                            stringResource(R.string.tip_no_tip),
                            { confirming = 0 },
                            enabled = state.canSubmit,
                            modifier = Modifier.weight(1f).testTag("noTip"),
                        )
                        PrimaryButton(
                            stringResource(R.string.tip_add),
                            { confirming = state.tipMinor },
                            enabled = state.canAddTip,
                            loading = state.submission.running,
                            modifier = Modifier.weight(1f).testTag("addTip"),
                        )
                    }
                }
            }
        },
    ) { padding ->
        val sale = state.sale ?: return@MiniScaffold
        val money = rememberMoneyFormatter(sale.currency)
        TipEntry(state, money, onInput = vm::setInput, onEntry = vm::updateEntry, modifier = Modifier.padding(padding))
        confirming?.let { tip ->
            TipConfirmDialog(
                state = state,
                tipMinor = tip,
                money = money,
                onConfirm = {
                    confirming = null
                    vm.submit(tip)
                },
                onDismiss = { confirming = null },
            )
        }
    }
}

/** The choice of tip or total, the amount typed with the sum it makes, notes and problems, and the keypad. */
@Composable
private fun TipEntry(
    state: TipUiState,
    money: MoneyFormatter,
    onInput: (TipInput) -> Unit,
    onEntry: ((AmountEntry) -> AmountEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    KeypadColumn(onEntry, modifier) {
        TipInputChoice(state.input, onInput)
        AmountText(state.entry.minor, money, "tipAmount")
        val tip = state.tipMinor
        when {
            state.belowBill -> Note(stringResource(R.string.tip_below_bill), isError = true)
            tip != null -> Note(sum(state.billMinor, tip, money))
        }
        if (state.needsAdjustment) Note(stringResource(R.string.tip_adjustment_note, PaymentStanding.TIP_ADJUSTMENT_PERCENT))
        OutcomeMessage(state.submission)
    }
}

/** Tip or total, as a segmented choice. */
@Composable
private fun TipInputChoice(
    selected: TipInput,
    onSelect: (TipInput) -> Unit,
) {
    val options = TipInput.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = selected == option,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                modifier = Modifier.testTag("tipInput_${option.name}"),
            ) {
                Text(stringResource(if (option == TipInput.TIP) R.string.tip_option_tip else R.string.tip_option_total))
            }
        }
    }
}

/** Confirms the tip ([tipMinor], 0 for none) with the sum it makes and what happens next. */
@Composable
private fun TipConfirmDialog(
    state: TipUiState,
    tipMinor: Long,
    money: MoneyFormatter,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val total = money.format(state.billMinor + tipMinor)
    ConfirmDialog(
        title =
            if (tipMinor > 0) {
                stringResource(R.string.tip_confirm_title, money.format(tipMinor))
            } else {
                stringResource(R.string.tip_confirm_no_tip_title)
            },
        message =
            sum(state.billMinor, tipMinor, money) + "\n\n" +
                stringResource(R.string.tip_confirm_capture, total),
        confirmLabel = stringResource(R.string.capture_title),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * Capturing the pre-authorisation [saleId] (or with [adjustOnly] changing what it holds): the amount, starting at what
 * it holds, is sent after the operator confirms. Closes once it went through; a refusal or failure is shown here.
 */
@Composable
fun CaptureScreen(
    saleId: String,
    adjustOnly: Boolean,
    navigator: Navigator,
    modifier: Modifier = Modifier,
    vm: CaptureViewModel = captureViewModel(saleId, adjustOnly),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(state.submission.done) { if (state.submission.done) navigator.back() }
    val sale = state.sale
    MiniScaffold(
        title = stringResource(if (adjustOnly) R.string.adjust_title else R.string.capture_title),
        onBack = navigator::back,
        modifier = modifier,
        bottomBar = {
            if (sale != null) {
                val amount = rememberMoneyFormatter(sale.currency).format(state.amountMinor)
                BottomActions {
                    PrimaryButton(
                        stringResource(if (adjustOnly) R.string.adjust_button else R.string.capture_button, amount),
                        { confirming = true },
                        enabled = state.canSubmit,
                        loading = state.submission.running,
                        modifier = Modifier.testTag("submitCapture"),
                    )
                }
            }
        },
    ) { padding ->
        if (sale == null) return@MiniScaffold
        val money = rememberMoneyFormatter(sale.currency)
        CaptureEntry(state, money, onEntry = vm::updateEntry, modifier = Modifier.padding(padding))
        if (confirming) {
            CaptureConfirmDialog(
                state = state,
                money = money,
                onConfirm = {
                    confirming = false
                    vm.submit()
                },
                onDismiss = { confirming = false },
            )
        }
    }
}

/** What the pre-authorisation holds, the amount typed, what sending it does, problems and the keypad. */
@Composable
private fun CaptureEntry(
    state: CaptureUiState,
    money: MoneyFormatter,
    onEntry: ((AmountEntry) -> AmountEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sale = state.sale ?: return
    KeypadColumn(onEntry, modifier) {
        Card {
            LabeledValue(stringResource(R.string.capture_original), money.format(sale.totalMinor))
            if (state.heldMinor != sale.totalMinor) LabeledValue(stringResource(R.string.capture_held), money.format(state.heldMinor))
        }
        AmountText(state.amountMinor, money, "captureAmount")
        val amount = state.amountMinor
        when {
            state.adjustOnly -> Note(stringResource(R.string.adjust_note))
            amount > state.heldMinor -> Note(stringResource(R.string.capture_more_note, money.format(amount)))
            amount in 1..<state.heldMinor -> Note(stringResource(R.string.capture_less_note))
        }
        OutcomeMessage(state.submission)
    }
}

/** Confirms the capture or adjustment with what it does. */
@Composable
private fun CaptureConfirmDialog(
    state: CaptureUiState,
    money: MoneyFormatter,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val amount = money.format(state.amountMinor)
    val (title, message, confirm) =
        if (state.adjustOnly) {
            Triple(
                stringResource(R.string.adjust_confirm_title, amount),
                stringResource(R.string.adjust_confirm_message, amount, money.format(state.heldMinor)),
                stringResource(R.string.detail_adjust),
            )
        } else {
            val more = if (state.amountMinor > state.heldMinor) "\n\n" + stringResource(R.string.capture_confirm_more) else ""
            Triple(
                stringResource(R.string.capture_confirm_title, amount),
                stringResource(R.string.capture_confirm_message, amount) + more,
                stringResource(R.string.capture_title),
            )
        }
    ConfirmDialog(title = title, message = message, confirmLabel = confirm, onConfirm = onConfirm, onDismiss = onDismiss)
}

/**
 * A screen of [content] above a cash-register keypad that types into the entry through [onEntry]. On compact screens
 * the keypad fills the height left, so nothing scrolls; elsewhere the whole column scrolls.
 */
@Composable
private fun KeypadColumn(
    onEntry: ((AmountEntry) -> AmountEntry) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dimens = LocalDimens.current
    Column(
        modifier.fillMaxSize().then(if (dimens.compact) Modifier else Modifier.verticalScroll(rememberScrollState())),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.widthIn(max = 560.dp).padding(dimens.screenPadding).then(if (dimens.compact) Modifier.weight(1f) else Modifier),
            verticalArrangement = Arrangement.spacedBy(dimens.spacing),
        ) {
            content()
            NumericKeypad(
                onDigit = { digit -> onEntry { it.append(digit) } },
                onDoubleZero = { onEntry { it.appendDoubleZero() } },
                onBackspace = { onEntry { it.backspace() } },
                modifier = if (dimens.compact) Modifier.weight(1f) else Modifier,
                fill = dimens.compact,
            )
        }
    }
}

/** [minor] in large type, tagged [tag]. */
@Composable
private fun AmountText(
    minor: Long,
    money: MoneyFormatter,
    tag: String,
) = Text(
    money.format(minor),
    style = LocalDimens.current.amountStyle,
    textAlign = TextAlign.Center,
    modifier = Modifier.fillMaxWidth().testTag(tag),
)

/** A centred note under the amount; an error is in the error colour. */
@Composable
private fun Note(
    text: String,
    isError: Boolean = false,
) = Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign = TextAlign.Center,
    modifier = Modifier.fillMaxWidth(),
)

/** "Bill … + tip … = …". */
@Composable
@ReadOnlyComposable
private fun sum(
    billMinor: Long,
    tipMinor: Long,
    money: MoneyFormatter,
): String = stringResource(R.string.tip_confirm_sum, money.format(billMinor), money.format(tipMinor), money.format(billMinor + tipMinor))

@Composable
private fun tipViewModel(saleId: String): TipViewModel {
    val container = LocalAppContainer.current
    return viewModel(key = "tip-$saleId") { TipViewModel(saleId, container.storedPayments, container.captures) }
}

@Composable
private fun captureViewModel(
    saleId: String,
    adjustOnly: Boolean,
): CaptureViewModel {
    val container = LocalAppContainer.current
    return viewModel(key = "capture-$saleId-$adjustOnly") {
        CaptureViewModel(saleId, adjustOnly, container.storedPayments, container.captures)
    }
}
