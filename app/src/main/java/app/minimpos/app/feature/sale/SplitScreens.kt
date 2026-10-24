package app.minimpos.app.feature.sale

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.minimpos.app.R
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleWithLines
import app.minimpos.app.data.db.TenderKind
import app.minimpos.app.data.settings.PaymentSettings
import app.minimpos.app.history.SaleResultNext
import app.minimpos.app.history.saleResultNext
import app.minimpos.app.payment.Checkout
import app.minimpos.app.terminal.WalletAvailability
import app.minimpos.app.ui.components.BlockedReason
import app.minimpos.app.ui.components.BottomActions
import app.minimpos.app.ui.components.Card
import app.minimpos.app.ui.components.LabeledValue
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.MiniScaffold
import app.minimpos.app.ui.components.NumericKeypad
import app.minimpos.app.ui.components.OutcomeNote
import app.minimpos.app.ui.components.PrimaryButton
import app.minimpos.app.ui.components.QuantityStepper
import app.minimpos.app.ui.components.SecondaryButton
import app.minimpos.app.ui.components.currentSetupGate
import app.minimpos.app.ui.components.rememberMoneyFormatter
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.core.money.AmountEntry
import app.minimpos.core.money.MoneyFormatter
import kotlinx.coroutines.flow.flowOf

/**
 * Splitting a sale: choosing what the next tender pays (an amount, an equal share or sold items), then collecting it on
 * the terminal or confirming cash received and change. [saleId] null splits the sale being rung up; otherwise the
 * stored partially paid sale. A card tender goes through the setup gate; cash reaches neither Adyen nor a terminal.
 */
@Composable
fun SplitScreen(
    saleId: String?,
    navigator: Navigator,
    modifier: Modifier = Modifier,
    vm: SplitViewModel = splitViewModel(saleId),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val money = rememberMoneyFormatter(state.currency.ifEmpty { PaymentSettings.FALLBACK_CURRENCY })
    val gate = currentSetupGate()
    MiniScaffold(
        title = stringResource(R.string.split_title),
        onBack = { if (state.cashStage) vm.enterCash(false) else navigator.back() },
        modifier = modifier,
        bottomBar = {
            if (state.balance != null) {
                SplitActions(
                    state,
                    money,
                    onCard = { gate.run { if (vm.payByCard()) navigator.replace(Route.Payment(SaleKind.SALE)) } },
                    onCash = { vm.enterCash(true) },
                    onConfirmCash = { vm.confirmCash { id -> navigator.replace(Route.SaleResult(id)) } },
                )
            }
        },
    ) { padding ->
        when {
            !state.loaded -> {
                CircularProgressIndicator(Modifier.padding(padding).padding(LocalDimens.current.screenPadding))
            }

            state.balance == null -> {
                OutcomeNote(stringResource(R.string.split_unavailable), Modifier.padding(padding))
            }

            else -> {
                SplitChoices(
                    state,
                    money,
                    SplitEvents(vm::selectMode, vm::updateAmount, vm::setShares, vm::setQuantity),
                    Modifier.fillMaxSize().padding(padding),
                )
            }
        }
    }
}

/** Card for the chosen amount and Cash under it, or, while entering cash, the confirmation of cash received. */
@Composable
private fun SplitActions(
    state: SplitUiState,
    money: MoneyFormatter,
    onCard: () -> Unit,
    onCash: () -> Unit,
    onConfirmCash: () -> Unit,
) {
    BottomActions {
        if (state.cashStage) {
            PrimaryButton(
                stringResource(R.string.split_confirm_cash, money.format(state.received.minor)),
                onConfirmCash,
                enabled = state.changeMinor != null && state.valid,
                loading = state.recording,
                modifier = Modifier.testTag("confirmCash"),
            )
        } else {
            PrimaryButton(
                stringResource(R.string.split_pay_card, money.format(state.amountMinor)),
                onCard,
                enabled = state.valid,
                icon = Icons.Default.CreditCard,
                modifier = Modifier.testTag("splitCard"),
            )
            SecondaryButton(
                stringResource(R.string.split_pay_cash),
                onCash,
                enabled = state.valid,
                icon = Icons.Default.Payments,
                modifier = Modifier.testTag("splitCash"),
            )
        }
    }
}

/**
 * What the split choices do: choose the allocation mode, change the typed amount (or cash received), the share count,
 * or the units of one sold line.
 */
private class SplitEvents(
    val onMode: (SplitMode) -> Unit,
    val onAmount: ((AmountEntry) -> AmountEntry) -> Unit,
    val onShares: (Int) -> Unit,
    val onQuantity: (position: Int, quantity: Int) -> Unit,
)

@Composable
private fun SplitChoices(
    state: SplitUiState,
    money: MoneyFormatter,
    events: SplitEvents,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    LazyColumn(modifier, contentPadding = PaddingValues(dimens.screenPadding), horizontalAlignment = Alignment.CenterHorizontally) {
        item(key = "summary", contentType = "summary") {
            Column(
                Modifier.widthIn(max = dimens.contentMaxWidth).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(dimens.spacing),
            ) { SplitSummary(state, money, events) }
        }
        if (state.cashStage || state.mode == SplitMode.AMOUNT) {
            item(key = "keypad", contentType = "keypad") {
                Column(Modifier.widthIn(max = dimens.contentMaxWidth).fillMaxWidth().padding(top = dimens.spacing)) {
                    NumericKeypad(
                        onDigit = { digit -> events.onAmount { it.append(digit) } },
                        onDoubleZero = { events.onAmount { it.appendDoubleZero() } },
                        onBackspace = { events.onAmount { it.backspace() } },
                    )
                }
            }
        } else if (state.mode == SplitMode.ITEMS) {
            items(state.items, key = { it.position }, contentType = { "item" }) { item ->
                SplitItemRow(item, state.selection[item.position] ?: 0, events.onQuantity)
            }
        }
    }
}

/** What is paid and due, then cash entry, or how the next tender is chosen and what it collects. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.SplitSummary(
    state: SplitUiState,
    money: MoneyFormatter,
    events: SplitEvents,
) {
    val balance = checkNotNull(state.balance)
    Card {
        LabeledValue(stringResource(R.string.split_total), money.format(balance.totalMinor))
        LabeledValue(stringResource(R.string.split_paid), money.format(balance.paidMinor))
        LabeledValue(stringResource(R.string.split_unpaid), money.format(balance.unpaidMinor), emphasize = true)
    }
    if (state.rejected) OutcomeNote(stringResource(R.string.split_balance_changed))
    if (state.cashStage) {
        CashEntry(state, money)
        return
    }
    val modes =
        listOfNotNull(SplitMode.AMOUNT, SplitMode.SHARES.takeIf { balance.sharesOpen }, SplitMode.ITEMS.takeIf { balance.itemsOpen })
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = state.mode == mode,
                onClick = { events.onMode(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                modifier = Modifier.testTag("splitMode_${mode.name}"),
            ) { Text(stringResource(modeLabel(mode)), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
    Text(
        money.format(state.amountMinor),
        style = LocalDimens.current.amountStyle,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().testTag("splitAmount"),
    )
    if (state.mode == SplitMode.SHARES) Shares(state, money, events.onShares)
}

/** One sold line with what earlier tenders already pay for and the units this tender pays for. */
@Composable
private fun SplitItemRow(
    item: SplitItem,
    selected: Int,
    onQuantity: (position: Int, quantity: Int) -> Unit,
) {
    val dimens = LocalDimens.current
    Column(Modifier.widthIn(max = dimens.contentMaxWidth).fillMaxWidth().padding(top = dimens.spacing)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.split_item_hint, item.quantity, item.allocated),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            QuantityStepper(
                selected,
                item.name,
                onChange = { onQuantity(item.position, it) },
                max = item.quantity - item.allocated,
                increaseTag = "splitIncrease_${item.position}",
            )
        }
        HorizontalDivider()
    }
}

/** The equal shares of the split, the one this tender collects marked, and their count while it can still change. */
@Composable
private fun ColumnScope.Shares(
    state: SplitUiState,
    money: MoneyFormatter,
    onShares: (Int) -> Unit,
) {
    if (!state.sharesFixed) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.split_share_count), Modifier.weight(1f))
            QuantityStepper(
                state.shares,
                stringResource(R.string.split_share_count),
                onShares,
                min = 2,
                max = 20,
                increaseTag = "splitMoreShares",
            )
        }
    }
    state.shareAmounts.forEachIndexed { index, amount ->
        val current = index == state.shareIndex
        LabeledValue(
            stringResource(R.string.split_share_line, index + 1, state.shareAmounts.size),
            money.format(amount),
            emphasize = current,
        )
    }
}

/** The cash received and the change to hand back, entered on the keypad below. */
@Composable
private fun ColumnScope.CashEntry(
    state: SplitUiState,
    money: MoneyFormatter,
) {
    Card {
        LabeledValue(stringResource(R.string.split_cash_due), money.format(state.amountMinor))
        LabeledValue(stringResource(R.string.split_cash_received), money.format(state.received.minor), emphasize = true)
        LabeledValue(
            stringResource(R.string.split_change),
            state.changeMinor?.let(money::format) ?: stringResource(R.string.split_cash_short),
        )
    }
    Text(stringResource(R.string.split_cash_note), style = MaterialTheme.typography.bodySmall)
}

private fun modeLabel(mode: SplitMode): Int =
    when (mode) {
        SplitMode.AMOUNT -> R.string.split_mode_amount
        SplitMode.SHARES -> R.string.split_mode_shares
        SplitMode.ITEMS -> R.string.split_mode_items
    }

/** Checkout's way into a split, or why an option chosen at checkout prevents one. */
@Composable
internal fun SplitEntry(
    state: Checkout,
    onSplit: () -> Unit,
) {
    val dimens = LocalDimens.current
    Column(Modifier.widthIn(max = dimens.contentMaxWidth).fillMaxWidth().padding(top = dimens.spacing)) {
        SecondaryButton(
            stringResource(R.string.split_open),
            onSplit,
            enabled = state.canSplit,
            icon = Icons.AutoMirrored.Filled.CallSplit,
            modifier = Modifier.testTag("splitPayment"),
        )
        if (state.splitBlocked) BlockedReason(stringResource(R.string.split_blocked))
    }
}

/**
 * A stored sale's tenders and, while a balance can be collected, the way back into its split ([onCollect]), also after
 * a restart; nothing for a sale paid with one card.
 */
@Composable
internal fun ColumnScope.SplitProgress(
    record: SaleWithLines,
    money: MoneyFormatter,
    onCollect: () -> Unit,
) {
    if (saleResultNext(record.sale) == SaleResultNext.PAY_REMAINING) {
        SecondaryButton(
            stringResource(R.string.split_collect_balance),
            onCollect,
            icon = Icons.AutoMirrored.Filled.CallSplit,
            modifier = Modifier.testTag("splitBalance"),
        )
    }
    SaleTenders(record, money)
}

/**
 * The approved tenders of a split or cash sale in payment order, with cash received and change, and the balance
 * still due; nothing for a sale paid with one card.
 */
@Composable
private fun SaleTenders(
    record: SaleWithLines,
    money: MoneyFormatter,
) {
    val tenders = record.tenders.filter { it.approved }.sortedBy { it.sequence }
    if (tenders.size <= 1 && tenders.none { it.kind == TenderKind.CASH } && !record.sale.isPartiallyPaid) return
    Card(Modifier.testTag("saleTenders")) {
        tenders.forEach { tender ->
            val label =
                if (tender.kind == TenderKind.CASH) {
                    stringResource(R.string.split_tender_cash)
                } else {
                    listOfNotNull(
                        stringResource(R.string.split_tender_card),
                        tender.paymentBrand?.uppercase(),
                        tender.maskedPan,
                    ).joinToString(" ")
                }
            LabeledValue(label, money.format(tender.authorisedMinor ?: tender.requestedMinor))
            tender.cashReceivedMinor?.let { LabeledValue(stringResource(R.string.split_cash_received), money.format(it)) }
            tender.changeMinor?.let { LabeledValue(stringResource(R.string.split_change), money.format(it)) }
        }
        if (record.sale.isPartiallyPaid) {
            LabeledValue(
                stringResource(R.string.split_unpaid),
                money.format(record.sale.remainingMinor),
                emphasize = true,
            )
        }
    }
}

@Composable
private fun splitViewModel(saleId: String?): SplitViewModel {
    val container = LocalAppContainer.current
    return viewModel(key = "split|${saleId.orEmpty()}") {
        val session = container.session(SaleKind.SALE)
        SplitViewModel(
            saleId,
            session,
            session.checkout(
                settings = container.settingsState,
                terminal = container.terminalStatus.state,
                wallets = flowOf(WalletAvailability()),
                offersLinks = false,
                currency = container::currency,
            ),
            container.storedPayments,
            container.payments,
            container.cashTenders,
        )
    }
}
