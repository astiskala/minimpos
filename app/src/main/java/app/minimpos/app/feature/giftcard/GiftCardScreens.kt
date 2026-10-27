package app.minimpos.app.feature.giftcard

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.minimpos.app.R
import app.minimpos.app.data.db.GiftCardStage
import app.minimpos.app.data.db.GiftCardStatus
import app.minimpos.app.data.repo.GiftCardAction
import app.minimpos.app.data.repo.GiftCardActivity
import app.minimpos.app.data.settings.GiftCardSetting
import app.minimpos.app.data.settings.PaymentSettings
import app.minimpos.app.feature.GatedButton
import app.minimpos.app.feature.MerchantCopy
import app.minimpos.app.feature.ReceiptActions
import app.minimpos.app.feature.ResultAction
import app.minimpos.app.feature.ResultBar
import app.minimpos.app.feature.StatusCheck
import app.minimpos.app.feature.TransactionActions
import app.minimpos.app.feature.lock.ManagerApproval
import app.minimpos.app.feature.lock.rememberManagerAction
import app.minimpos.app.feature.outcomeNote
import app.minimpos.app.feature.text
import app.minimpos.app.payment.GiftCardBalanceResult
import app.minimpos.app.payment.GiftCardFunding
import app.minimpos.app.payment.TransactionState
import app.minimpos.app.scan.ScanMode
import app.minimpos.app.scan.ScannerDialog
import app.minimpos.app.ui.components.ActionMessage
import app.minimpos.app.ui.components.BottomActions
import app.minimpos.app.ui.components.Card
import app.minimpos.app.ui.components.ConfirmDialog
import app.minimpos.app.ui.components.EmptyState
import app.minimpos.app.ui.components.LabeledValue
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.MiniScaffold
import app.minimpos.app.ui.components.Notice
import app.minimpos.app.ui.components.NoticeKind
import app.minimpos.app.ui.components.NumericKeypad
import app.minimpos.app.ui.components.OutcomeHeader
import app.minimpos.app.ui.components.OutcomeNote
import app.minimpos.app.ui.components.PrimaryButton
import app.minimpos.app.ui.components.ProcessingContent
import app.minimpos.app.ui.components.ScreenColumn
import app.minimpos.app.ui.components.SecondaryButton
import app.minimpos.app.ui.components.SectionHeader
import app.minimpos.app.ui.components.StatusKind
import app.minimpos.app.ui.components.Subheading
import app.minimpos.app.ui.components.TertiaryButton
import app.minimpos.app.ui.components.currentSetupGate
import app.minimpos.app.ui.components.rememberMoneyFormatter
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.core.access.ManagerAction
import app.minimpos.core.giftcard.GiftCardEntry
import app.minimpos.core.giftcard.GiftCardOperation
import app.minimpos.core.giftcard.GiftCardProvider
import app.minimpos.core.money.MoneyFormatter
import kotlinx.coroutines.flow.map
import app.minimpos.core.money.AmountEntry as AmountValue

/**
 * Gift cards from Home: choose a configured provider and how its card is presented, then read the balance or start a
 * value change. Value changes are confirmed, then approved with the Manager PIN before they start; a balance inquiry
 * is an ordinary request. Below, activities that still need a check, a confirmation or a repair, and loads that may
 * still be undone, open their own screen.
 */
@Composable
fun GiftCardsScreen(
    navigator: Navigator,
    modifier: Modifier = Modifier,
    vm: GiftCardsViewModel = giftCardsViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val approveBalance = rememberManagerAction()
    val money = rememberMoneyFormatter(state.currency)
    var confirm by rememberSaveable { mutableStateOf(false) }
    var approve by rememberSaveable { mutableStateOf(false) }
    if (approve) {
        return ManagerApproval(
            actions = setOf(ManagerAction.GIFT_CARD_CHANGE),
            onApprove = {
                approve = false
                vm.start()?.let { navigator.push(Route.GiftCardActivity(it)) }
            },
            onCancel = { approve = false },
        )
    }
    if (confirm) {
        StartDialog(state, money, onConfirm = { approve = true }, onDismiss = { confirm = false })
    }
    MiniScaffold(
        title = stringResource(R.string.gift_card_title),
        onBack = navigator::back,
        modifier = modifier,
        bottomBar = {
            StartBar(state, money, onStart = { confirm = true })
        },
    ) { padding ->
        if (state.loaded && state.settings.isEmpty()) {
            EmptyState(
                Icons.Default.CardGiftcard,
                stringResource(R.string.gift_card_none_title),
                stringResource(R.string.gift_card_none_message),
                Modifier.padding(padding),
            )
            return@MiniScaffold
        }
        ScreenColumn(padding) {
            Column(verticalArrangement = Arrangement.spacedBy(LocalDimens.current.spacing / 2)) {
                GiftCardPicker(
                    state.settings,
                    state.card,
                    onProvider = vm::selectProvider,
                    onEntry = vm::selectEntry,
                    onScan = vm::scanned,
                    onExpiry = vm::setExpiry,
                )
            }
            Operations(state, vm::selectOperation) {
                vm.selectOperation(GiftCardOperation.BALANCE)
                approveBalance(setOf(ManagerAction.GIFT_CARD_BALANCE)) { vm.readBalance() }
            }
            OperationDetails(state, money, AmountEvents(vm::updateAmount, vm::selectFunding, vm::confirmExternal))
            Activities(state, money) { navigator.push(Route.GiftCardActivity(it)) }
        }
    }
}

/** The pinned secondary action for reviewing a value change; balance runs directly from its list button. */
@Composable
private fun StartBar(
    state: GiftCardsUiState,
    money: MoneyFormatter,
    onStart: () -> Unit,
) {
    if (state.settings.isEmpty() || !state.changesValue) return
    BottomActions {
        GatedButton(
            text = startLabel(state, money),
            onClick = onStart,
            blocked = state.reason,
            icon = operationIcon(state.operation),
            enabled = state.ready,
            loading = state.reading,
            requiresSetup = true,
            modifier = Modifier.testTag("giftCardStart"),
        )
    }
}

/** Confirms the chosen value change, saying what it does, before Manager approval. */
@Composable
private fun StartDialog(
    state: GiftCardsUiState,
    money: MoneyFormatter,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) = ConfirmDialog(
    title = stringResource(operationLabel(state.operation)),
    message = confirmation(state, money),
    confirmLabel = startLabel(state, money),
    onConfirm = {
        onDismiss()
        onConfirm()
    },
    onDismiss = onDismiss,
    destructive = state.operation == GiftCardOperation.DEACTIVATE,
)

/** Below the actions: the amount to enter, or what deactivation means; balance results stay beside their action. */
@Composable
private fun ColumnScope.OperationDetails(
    state: GiftCardsUiState,
    money: MoneyFormatter,
    events: AmountEvents,
) {
    when {
        state.takesAmount -> AmountEntry(state, money, events)
        state.changesValue -> Notice(stringResource(R.string.gift_card_deactivate_note), kind = NoticeKind.WARNING)
    }
}

/**
 * Choosing a configured provider (when there is more than one) and how its card is presented; a scanned card is read
 * with the camera and needs its expiry date. Shared by Gift cards, sale splits and presenting a card again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColumnScope.GiftCardPicker(
    settings: List<GiftCardSetting>,
    card: CardChoice,
    onProvider: (GiftCardProvider) -> Unit,
    onEntry: (GiftCardEntry) -> Unit,
    onScan: (String) -> Unit,
    onExpiry: (String) -> Unit,
) {
    var scanning by remember { mutableStateOf(false) }
    if (scanning) {
        ScannerDialog(ScanMode.BARCODE, onDismiss = { scanning = false }) {
            scanning = false
            onScan(it)
        }
    }
    if (settings.size > 1) {
        Subheading(stringResource(R.string.gift_card_provider))
        Choices(settings.map { it.provider }, card.provider, "giftCardProvider", onProvider) { providerName(it) }
    }
    val entries =
        settings
            .firstOrNull { it.provider == card.provider }
            ?.entries
            ?.sorted()
            .orEmpty()
    if (entries.size > 1) {
        Subheading(stringResource(R.string.gift_card_entry))
        Choices(entries, card.entry, "giftCardEntry", onEntry) { stringResource(entryLabel(it)) }
    }
    if (card.entry == GiftCardEntry.SCANNED) {
        val scanned = card.scanned
        SecondaryButton(
            if (scanned ==
                null
            ) {
                stringResource(R.string.gift_card_scan)
            } else {
                stringResource(R.string.gift_card_scanned, scanned.takeLast(LAST_FOUR))
            },
            { scanning = true },
            icon = Icons.Default.QrCodeScanner,
            modifier = Modifier.testTag("giftCardScan"),
        )
        OutlinedTextField(
            card.expiry,
            onExpiry,
            label = { Text(stringResource(R.string.gift_card_expiry)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag("giftCardExpiry"),
        )
    } else {
        Notice(stringResource(if (card.entry == GiftCardEntry.KEYED) R.string.gift_card_keyed_note else R.string.gift_card_swiped_note))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choices(
    options: List<T>,
    selected: T?,
    tag: String,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                modifier = Modifier.testTag("${tag}_$index"),
            ) { Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Full-width secondary actions: read a balance directly, or select a value change to review below. */
@Composable
private fun ColumnScope.Operations(
    state: GiftCardsUiState,
    onSelect: (GiftCardOperation) -> Unit,
    onBalance: () -> Unit,
) {
    state.operations.forEach { operation ->
        val chosen = operation == state.operation
        val tag = Modifier.testTag("giftCardOperation_${operation.name}").semantics { selected = chosen }
        if (operation == GiftCardOperation.BALANCE) {
            val balance = state.copy(operation = GiftCardOperation.BALANCE)
            GatedButton(
                stringResource(operationLabel(operation)),
                onBalance,
                blocked = balance.reason,
                enabled = balance.ready,
                loading = state.reading,
                icon = operationIcon(operation),
                requiresSetup = true,
                modifier = tag,
            )
            if (state.operation == GiftCardOperation.BALANCE) state.balance?.let { Balance(it) }
        } else {
            SecondaryButton(
                stringResource(operationLabel(operation)),
                { onSelect(operation) },
                tag,
                icon = if (chosen) Icons.Default.Check else operationIcon(operation),
            )
        }
    }
    if (state.changesValue && !state.managerConfigured) Notice(stringResource(R.string.gift_card_manager_note), kind = NoticeKind.WARNING)
}

/** Icons for choosing an action and confirming the selected value change. */
private fun operationIcon(operation: GiftCardOperation): ImageVector =
    when (operation) {
        GiftCardOperation.BALANCE -> Icons.Default.AccountBalanceWallet
        GiftCardOperation.ACTIVATE -> Icons.Default.CreditCard
        GiftCardOperation.LOAD -> Icons.Default.AddCircle
        GiftCardOperation.CASH_OUT -> Icons.Default.Payments
        GiftCardOperation.REFUND, GiftCardOperation.UNDO_LOAD -> Icons.AutoMirrored.Filled.Undo
        GiftCardOperation.DEACTIVATE -> Icons.Default.Block
        GiftCardOperation.REDEEM -> Icons.Default.CardGiftcard
    }

@Composable
private fun ColumnScope.Balance(result: GiftCardBalanceResult) {
    when (result) {
        is GiftCardBalanceResult.Read -> {
            val balance = rememberMoneyFormatter(result.currency)
            Card {
                LabeledValue(stringResource(R.string.gift_card_balance), balance.format(result.balanceMinor), emphasize = true)
                result.lastFour?.let { LabeledValue(stringResource(R.string.gift_card_card), "**** $it") }
            }
        }

        is GiftCardBalanceResult.NotRead -> {
            ActionMessage(
                listOfNotNull(
                    stringResource(if (result.cancelled) R.string.gift_card_balance_cancelled else R.string.gift_card_balance_failed),
                    result.failure?.text(),
                    result.message,
                ).joinToString(" "),
                isError = true,
            )
        }
    }
}

/** What the amount entry changes: the typed amount, how a value is paid for, and the external funding confirmation. */
private class AmountEvents(
    val onAmount: ((AmountValue) -> AmountValue) -> Unit,
    val onFunding: (GiftCardFunding) -> Unit,
    val onExternal: (Boolean) -> Unit,
)

/** The amount typed on the keypad and, for a funded value, how it is paid for. */
@Composable
private fun ColumnScope.AmountEntry(
    state: GiftCardsUiState,
    money: MoneyFormatter,
    events: AmountEvents,
) {
    Text(
        money.format(state.amount.minor),
        style = LocalDimens.current.amountStyle,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().testTag("giftCardAmount"),
    )
    if (!state.wholeBalance) {
        NumericKeypad(
            onDigit = { digit -> events.onAmount { it.append(digit) } },
            onDoubleZero = { events.onAmount { it.appendDoubleZero() } },
            onBackspace = { events.onAmount { it.backspace() } },
        )
    }
    if (state.operation == GiftCardOperation.CASH_OUT) Notice(stringResource(R.string.gift_card_cash_out_note))
    if (state.operation == GiftCardOperation.ACTIVATE) Notice(stringResource(R.string.gift_card_activate_note))
    if (state.funded) {
        SectionHeader(stringResource(R.string.gift_card_funding), inset = false)
        Choices(GiftCardFunding.entries, state.funding, "giftCardFunding", events.onFunding) { stringResource(fundingLabel(it)) }
        if (state.funding == GiftCardFunding.EXTERNAL) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(state.externalConfirmed, events.onExternal, Modifier.testTag("giftCardExternalConfirmed"))
                Text(stringResource(R.string.gift_card_external_confirm, money.format(state.amount.minor)))
            }
        }
    }
}

/** Activities still needing staff, then loads that may be undone; each opens its screen. */
@Composable
private fun ColumnScope.Activities(
    state: GiftCardsUiState,
    money: MoneyFormatter,
    onOpen: (String) -> Unit,
) {
    listOf(R.string.gift_card_needs_attention to state.attention, R.string.gift_card_recent_loads to state.undoable)
        .filter { it.second.isNotEmpty() }
        .forEach { (title, activities) ->
            SectionHeader(stringResource(title), inset = false)
            activities.forEach { activity ->
                TextButton(
                    onClick = { onOpen(activity.groupId) },
                    Modifier.fillMaxWidth().testTag("giftCardActivity_${activity.groupId}"),
                ) {
                    Text(activityLine(activity, money), Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
}

/**
 * One stored-value activity: its requests while they are sent, then its outcome, stages, the checks, confirmations and
 * repairs it still needs, and its receipt. Opened after a start, from Gift cards or from History ([fromHistory]).
 */
@Composable
fun GiftCardActivityScreen(
    groupId: String,
    navigator: Navigator,
    modifier: Modifier = Modifier,
    fromHistory: Boolean = false,
    vm: GiftCardActivityViewModel = giftCardActivityViewModel(groupId, fromHistory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val gate = currentSetupGate()
    var pending by rememberSaveable { mutableStateOf<GiftCardAction?>(null) }
    var approved by rememberSaveable { mutableStateOf<GiftCardAction?>(null) }
    val processing = state.processing(groupId)

    fun done() {
        vm.finish()
        if (fromHistory) navigator.back() else navigator.home()
    }
    BackHandler(enabled = !processing) { done() }
    approved?.let { action ->
        return ManagerApproval(
            actions = setOf(ManagerAction.GIFT_CARD_CHANGE),
            onApprove = {
                approved = null
                vm.follow(action)
            },
            onCancel = { approved = null },
        )
    }
    val activity = state.activity
    val pinned =
        state.actions
            .firstOrNull {
                it == GiftCardAction.CONTINUE || it == GiftCardAction.CONFIRM_HANDOVER
            }.takeUnless { fromHistory }

    fun follow(action: GiftCardAction) {
        if (action == GiftCardAction.CONFIRM_HANDOVER) pending = action else gate.run { pending = action }
    }
    pending?.let { action ->
        activity?.let { FollowUpDialog(action, it, onConfirm = { approved = action }, onDismiss = { pending = null }) }
    }
    MiniScaffold(
        title = stringResource(R.string.gift_card_activity_title),
        onBack = if (processing) null else ::done,
        modifier = modifier,
        bottomBar = { GiftCardResultBar(state, !fromHistory && !processing, pinned, ::done, ::follow) },
    ) { padding ->
        if (processing || activity == null) {
            ActivityProgress(state, vm::cancel, Modifier.padding(padding))
        } else {
            ScreenColumn(padding, horizontalAlignment = Alignment.CenterHorizontally) {
                ActivityResult(state, activity, vm.transaction, vm::updateCard, pinned, ::follow)
            }
        }
    }
}

/** Pins the activity's next handover or continuation beside Home, with the same prerequisites as its body action. */
@Composable
private fun GiftCardResultBar(
    state: GiftCardActivityUiState,
    visible: Boolean,
    pinned: GiftCardAction?,
    onHome: () -> Unit,
    onAction: (GiftCardAction) -> Unit,
) {
    if (!visible || state.activity == null) return
    ResultBar(
        onHome = onHome,
        next =
            pinned?.let {
                ResultAction(
                    stringResource(actionLabel(it)),
                    "giftCardAction_${it.name}",
                    enabled = followUpReady(state, it),
                    onClick = { onAction(it) },
                )
            },
    )
}

/** Asks staff to confirm [action] on [activity] before Manager approval; nothing while it is not stored. */
@Composable
private fun FollowUpDialog(
    action: GiftCardAction,
    activity: GiftCardActivity,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val money = rememberMoneyFormatter(activity.currency)
    ConfirmDialog(
        title = stringResource(actionLabel(action)),
        message = actionConfirmation(action, activity, money),
        confirmLabel = stringResource(actionLabel(action)),
        onConfirm = {
            onDismiss()
            onConfirm()
        },
        onDismiss = onDismiss,
    )
}

/** The activity's request on the terminal, with cancelling it once the terminal can be asked to stop. */
@Composable
private fun ActivityProgress(
    state: GiftCardActivityUiState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = state.progress as? TransactionState.Processing
    val money = rememberMoneyFormatter(progress?.amount?.currency ?: state.activity?.currency ?: PaymentSettings.FALLBACK_CURRENCY)
    ProcessingContent(progress?.amount?.let { money.format(it.amountMinor) }, stringResource(R.string.gift_card_processing), modifier) {
        if (progress?.canCancel == true) {
            TertiaryButton(
                stringResource(R.string.action_cancel),
                onCancel,
                enabled = !progress.cancelling,
                modifier = Modifier.testTag("giftCardCancel"),
            )
        }
    }
}

/** The stored activity: outcome, stages, status check, presenting the card again, follow-ups and receipt. */
@Composable
private fun ColumnScope.ActivityResult(
    state: GiftCardActivityUiState,
    activity: GiftCardActivity,
    transaction: TransactionActions,
    onCard: ((CardChoice) -> CardChoice) -> Unit,
    pinned: GiftCardAction?,
    onAction: (GiftCardAction) -> Unit,
) {
    val money = rememberMoneyFormatter(activity.currency)
    Outcome(activity, money)
    Stages(activity, money)
    if (state.refused) OutcomeNote(stringResource(R.string.gift_card_not_started))
    if (GiftCardAction.CHECK_AGAIN in
        state.actions
    ) {
        StatusCheck(state.transaction, transaction::recheck, transaction::dismissCheckResult)
    }
    if (GiftCardAction.CONTINUE in state.actions) {
        GiftCardPicker(
            state.settings.filter { it.provider == activity.provider },
            state.card,
            onProvider = {},
            onEntry = { entry -> onCard { it.copy(entry = entry, scanned = null) } },
            onScan = { number -> onCard { it.copy(scanned = number.filter(Char::isDigit)) } },
            onExpiry = { expiry -> onCard { it.copy(expiry = expiry.filter(Char::isDigit).take(EXPIRY_LENGTH)) } },
        )
    }
    FollowUps(state, pinned, onAction)
    if (activity.stages.any { it.status == GiftCardStatus.SUCCEEDED }) ReceiptActions(state.transaction, transaction, MerchantCopy.NONE)
}

@Composable
private fun ColumnScope.FollowUps(
    state: GiftCardActivityUiState,
    pinned: GiftCardAction?,
    onAction: (GiftCardAction) -> Unit,
) {
    val actions = state.actions - GiftCardAction.CHECK_AGAIN
    if (actions.isEmpty()) return
    if (!state.managerConfigured) Notice(stringResource(R.string.gift_card_manager_note), kind = NoticeKind.WARNING)
    actions.filter { it != pinned }.sortedBy { it.ordinal }.forEach { action ->
        val ready = followUpReady(state, action)
        val text = stringResource(actionLabel(action))
        val tag = Modifier.testTag("giftCardAction_${action.name}")
        if (action == GiftCardAction.CONFIRM_HANDOVER || action == GiftCardAction.CONTINUE) {
            PrimaryButton(text, { onAction(action) }, tag, enabled = ready)
        } else {
            SecondaryButton(text, { onAction(action) }, tag, enabled = ready)
        }
    }
}

/** Whether the selected follow-up can be confirmed with the currently presented card and Manager setup. */
private fun followUpReady(
    state: GiftCardActivityUiState,
    action: GiftCardAction,
): Boolean =
    state.managerConfigured &&
        when (action) {
            GiftCardAction.CONTINUE -> state.cardReady
            GiftCardAction.CONFIRM_HANDOVER -> state.activity?.cashHandover?.canRecord == true
            GiftCardAction.CHECK_AGAIN, GiftCardAction.REFUND_FUNDING, GiftCardAction.RETURN_FUNDS, GiftCardAction.UNDO_LOAD -> true
        }

/** The activity's outcome: what it set out to do, where it stands and its main amount. */
@Composable
private fun Outcome(
    activity: GiftCardActivity,
    money: MoneyFormatter,
) {
    val main = activity.change ?: activity.funding ?: activity.stages.first()
    val (kind, title) =
        when {
            activity.unresolved -> StatusKind.WARNING to R.string.gift_card_outcome_unresolved
            activity.needsRepair -> StatusKind.ERROR to R.string.gift_card_outcome_repair
            main.status == GiftCardStatus.SUCCEEDED -> StatusKind.SUCCESS to R.string.gift_card_outcome_done
            else -> StatusKind.ERROR to R.string.gift_card_outcome_not_done
        }
    val handover = activity.cashHandover
    OutcomeHeader(kind, stringResource(title), money.format(main.amountMinor), titleTag = "giftCardStatus") {
        Subheading(stringResource(giftCardStageLabel(activity.kind)))
        when {
            activity.funding?.status == GiftCardStatus.UNKNOWN -> {
                OutcomeNote(stringResource(R.string.gift_card_funding_unknown))
            }

            activity.needsRepair -> {
                OutcomeNote(stringResource(R.string.gift_card_repair_note))
            }

            handover?.needsAttention == true -> {
                if (handover.canRecord) {
                    OutcomeNote(stringResource(R.string.gift_card_handover_due, money.format(activity.totals.valueRemovedMinor)))
                } else {
                    OutcomeNote(stringResource(R.string.cashback_missing_amount))
                }
            }

            activity.stages.any { it.stage == GiftCardStage.FUNDING_REFUND && it.status == GiftCardStatus.SUCCEEDED } -> {
                OutcomeNote(stringResource(R.string.refund_async_note))
            }
        }
    }
}

/** Every stage in order with where it stands and what it moved; the card and its last reported balance. */
@Composable
private fun ColumnScope.Stages(
    activity: GiftCardActivity,
    money: MoneyFormatter,
) {
    Card {
        activity.provider?.let { LabeledValue(stringResource(R.string.gift_card_provider), providerName(it)) }
        activity.cardLastFour?.let { LabeledValue(stringResource(R.string.gift_card_card), "**** $it") }
        activity.balanceMinor?.let { LabeledValue(stringResource(R.string.gift_card_balance), money.format(it)) }
        activity.stages.forEach { stage ->
            LabeledValue(
                "${stringResource(giftCardStageLabel(stage.stage))} · ${stringResource(statusLabel(stage.status))}",
                money.format(if (stage.status == GiftCardStatus.SUCCEEDED) stage.movedMinor else stage.amountMinor),
            )
            if (stage.status != GiftCardStatus.SUCCEEDED) stage.outcomeNote?.let { OutcomeNote(it) }
        }
        LabeledValue(stringResource(R.string.receipt_reference), (activity.change ?: activity.stages.first()).merchantReference)
    }
}

/** The stage name a history row, receipt or detail shows for [stage]. */
@StringRes
fun giftCardStageLabel(stage: GiftCardStage): Int =
    when (stage) {
        GiftCardStage.FUNDING_CARD -> R.string.gift_card_stage_funding_card
        GiftCardStage.FUNDING_EXTERNAL -> R.string.gift_card_stage_funding_external
        GiftCardStage.ACTIVATE -> R.string.gift_card_stage_activate
        GiftCardStage.LOAD -> R.string.gift_card_stage_load
        GiftCardStage.UNDO_LOAD -> R.string.gift_card_stage_undo
        GiftCardStage.DEACTIVATE -> R.string.gift_card_stage_deactivate
        GiftCardStage.CASH_OUT -> R.string.gift_card_stage_cash_out
        GiftCardStage.CASH_HANDOVER -> R.string.gift_card_stage_handover
        GiftCardStage.REFUND -> R.string.gift_card_stage_refund
        GiftCardStage.FUNDING_REFUND -> R.string.gift_card_stage_funding_refund
        GiftCardStage.FUNDING_RETURNED -> R.string.gift_card_stage_funding_returned
    }

/** Where a stage stands, in words. */
@StringRes
internal fun statusLabel(status: GiftCardStatus): Int =
    when (status) {
        GiftCardStatus.PENDING -> R.string.status_pending
        GiftCardStatus.SUCCEEDED -> R.string.gift_card_status_done
        GiftCardStatus.DECLINED -> R.string.status_declined
        GiftCardStatus.CANCELLED -> R.string.status_cancelled
        GiftCardStatus.FAILED -> R.string.status_failed
        GiftCardStatus.UNKNOWN -> R.string.status_unknown
        GiftCardStatus.AWAITING -> R.string.gift_card_status_awaiting
    }

/** A provider's name: brand names stay as they are; local gift cards are described. */
@Composable
@ReadOnlyComposable
internal fun providerName(provider: GiftCardProvider): String =
    when (provider) {
        GiftCardProvider.GIVEX -> "Givex"
        GiftCardProvider.SVS -> "SVS"
        GiftCardProvider.FISERV -> "Fiserv ValueLink"
        GiftCardProvider.INTERSOLVE -> "Intersolve"
        GiftCardProvider.LOCAL -> stringResource(R.string.gift_card_provider_local)
    }

@StringRes
internal fun entryLabel(entry: GiftCardEntry): Int =
    when (entry) {
        GiftCardEntry.SCANNED -> R.string.gift_card_entry_scanned
        GiftCardEntry.SWIPED -> R.string.gift_card_entry_swiped
        GiftCardEntry.KEYED -> R.string.gift_card_entry_keyed
    }

@StringRes
private fun operationLabel(operation: GiftCardOperation): Int =
    when (operation) {
        GiftCardOperation.BALANCE -> R.string.gift_card_check_balance
        GiftCardOperation.ACTIVATE -> R.string.gift_card_activate
        GiftCardOperation.LOAD -> R.string.gift_card_load
        GiftCardOperation.CASH_OUT -> R.string.gift_card_cash_out
        GiftCardOperation.REFUND -> R.string.gift_card_refund
        GiftCardOperation.DEACTIVATE -> R.string.gift_card_deactivate
        GiftCardOperation.UNDO_LOAD -> R.string.gift_card_undo
        GiftCardOperation.REDEEM -> R.string.split_pay_gift_card
    }

@StringRes
private fun fundingLabel(funding: GiftCardFunding): Int =
    when (funding) {
        GiftCardFunding.CARD -> R.string.gift_card_funding_card
        GiftCardFunding.EXTERNAL -> R.string.gift_card_funding_external
    }

@StringRes
private fun actionLabel(action: GiftCardAction): Int =
    when (action) {
        GiftCardAction.CHECK_AGAIN -> R.string.gift_card_outcome_unresolved
        GiftCardAction.CONTINUE -> R.string.gift_card_continue
        GiftCardAction.REFUND_FUNDING -> R.string.gift_card_refund_funding
        GiftCardAction.RETURN_FUNDS -> R.string.gift_card_return_funds
        GiftCardAction.CONFIRM_HANDOVER -> R.string.gift_card_confirm_handover
        GiftCardAction.UNDO_LOAD -> R.string.gift_card_undo
    }

/** What confirming [action] does, for its dialog. */
@Composable
@ReadOnlyComposable
private fun actionConfirmation(
    action: GiftCardAction,
    activity: GiftCardActivity,
    money: MoneyFormatter,
): String {
    val funded = money.format(activity.funding?.movedMinor ?: 0)
    return when (action) {
        GiftCardAction.CONTINUE -> {
            stringResource(R.string.gift_card_continue_confirm, funded)
        }

        GiftCardAction.REFUND_FUNDING -> {
            stringResource(R.string.gift_card_refund_funding_confirm, funded)
        }

        GiftCardAction.RETURN_FUNDS -> {
            stringResource(R.string.gift_card_return_funds_confirm, funded)
        }

        GiftCardAction.CONFIRM_HANDOVER -> {
            stringResource(
                R.string.gift_card_handover_confirm,
                money.format(activity.totals.valueRemovedMinor),
            )
        }

        GiftCardAction.UNDO_LOAD -> {
            stringResource(R.string.gift_card_undo_confirm, money.format(activity.value?.movedMinor ?: 0))
        }

        GiftCardAction.CHECK_AGAIN -> {
            ""
        }
    }
}

/** The primary action's label for the chosen action and amount. */
@Composable
@ReadOnlyComposable
private fun startLabel(
    state: GiftCardsUiState,
    money: MoneyFormatter,
): String {
    val amount = money.format(state.amount.minor)
    return when (state.operation) {
        GiftCardOperation.ACTIVATE -> stringResource(R.string.gift_card_start_activate, amount)
        GiftCardOperation.LOAD -> stringResource(R.string.gift_card_start_load, amount)
        GiftCardOperation.CASH_OUT -> stringResource(R.string.gift_card_start_cash_out, amount)
        GiftCardOperation.REFUND -> stringResource(R.string.gift_card_start_refund, amount)
        GiftCardOperation.DEACTIVATE -> stringResource(R.string.gift_card_deactivate)
        GiftCardOperation.BALANCE, GiftCardOperation.UNDO_LOAD, GiftCardOperation.REDEEM -> stringResource(R.string.gift_card_check_balance)
    }
}

/** What confirming the chosen value change does. */
@Composable
@ReadOnlyComposable
private fun confirmation(
    state: GiftCardsUiState,
    money: MoneyFormatter,
): String {
    val amount = money.format(state.amount.minor)
    val provider =
        state.setting
            ?.provider
            ?.let { providerName(it) }
            .orEmpty()
    return when {
        state.operation == GiftCardOperation.DEACTIVATE -> stringResource(R.string.gift_card_deactivate_confirm, provider)
        state.operation == GiftCardOperation.CASH_OUT -> stringResource(R.string.gift_card_cash_out_confirm, amount, provider)
        state.operation == GiftCardOperation.REFUND -> stringResource(R.string.gift_card_refund_confirm, amount, provider)
        state.funded && state.funding == GiftCardFunding.CARD -> stringResource(R.string.gift_card_funded_card_confirm, amount, provider)
        state.funded -> stringResource(R.string.gift_card_funded_external_confirm, amount, provider)
        else -> stringResource(R.string.gift_card_activate_empty_confirm, provider)
    }
}

/** One activity in a list: what it is, its card and amount. */
@Composable
@ReadOnlyComposable
private fun activityLine(
    activity: GiftCardActivity,
    money: MoneyFormatter,
): String {
    val main = activity.change ?: activity.funding ?: activity.stages.first()
    return listOfNotNull(
        stringResource(giftCardStageLabel(activity.kind)),
        activity.cardLastFour?.let { "**** $it" },
        money.format(main.amountMinor),
    ).joinToString(" · ")
}

@Composable
private fun giftCardsViewModel(): GiftCardsViewModel {
    val container = LocalAppContainer.current
    return viewModel {
        GiftCardsViewModel(
            container.giftCards,
            container.giftCardRecords,
            container.settingsState.map { it.payment to container.currency(it).code },
            container.managerApproval.forAction(ManagerAction.GIFT_CARD_CHANGE).available,
            container.terminalStatus.state.map { it.giftCardsBlocked },
        )
    }
}

@Composable
private fun giftCardActivityViewModel(
    groupId: String,
    fromHistory: Boolean,
): GiftCardActivityViewModel {
    val container = LocalAppContainer.current
    return viewModel(key = groupId) {
        GiftCardActivityViewModel(
            groupId,
            container.giftCards,
            container.giftCardRecords,
            container.receipts,
            container.operations,
            container.settingsState.map { it.payment },
            container.managerApproval.forAction(ManagerAction.GIFT_CARD_CHANGE).available,
            justStarted = !fromHistory,
        )
    }
}

private const val LAST_FOUR = 4
private const val EXPIRY_LENGTH = 4
