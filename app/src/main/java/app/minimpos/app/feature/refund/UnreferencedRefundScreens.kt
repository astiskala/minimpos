package app.minimpos.app.feature.refund

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.minimpos.app.R
import app.minimpos.app.data.db.UnreferencedRefundReason
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.PaymentSettings
import app.minimpos.app.feature.lock.ManagerApproval
import app.minimpos.app.payment.StoredPaymentActions
import app.minimpos.app.payment.UnreferencedRefundBlock
import app.minimpos.app.payment.UnreferencedRefunds
import app.minimpos.app.terminal.WalletAvailability
import app.minimpos.app.ui.components.BlockedReason
import app.minimpos.app.ui.components.BottomActions
import app.minimpos.app.ui.components.Card
import app.minimpos.app.ui.components.ConfirmDialog
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.MiniScaffold
import app.minimpos.app.ui.components.NumericKeypad
import app.minimpos.app.ui.components.OutcomeNote
import app.minimpos.app.ui.components.PrimaryButton
import app.minimpos.app.ui.components.ScreenColumn
import app.minimpos.app.ui.components.SecondaryButton
import app.minimpos.app.ui.components.currentSetupGate
import app.minimpos.app.ui.components.rememberMoneyFormatter
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.core.access.ManagerAction
import app.minimpos.core.money.AmountEntry
import app.minimpos.core.money.CurrencySpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * How Refund finds what to refund: scanning the receipt's refund QR code, choosing the stored payment in History (whose
 * detail offers its referenced refund), or refunding without a receipt. Referenced refunds keep their own eligibility.
 */
@Composable
fun RefundStartScreen(
    navigator: Navigator,
    modifier: Modifier = Modifier,
) {
    val gate = currentSetupGate()
    MiniScaffold(stringResource(R.string.refund_title), navigator::back, modifier = modifier) { padding ->
        ScreenColumn(padding) {
            RefundWay(Icons.Default.QrCodeScanner, R.string.refund_entry_scan, R.string.refund_entry_scan_hint, "refundScan") {
                navigator.push(Route.RefundScan)
            }
            RefundWay(Icons.Default.History, R.string.refund_entry_history, R.string.refund_entry_history_hint, "refundHistory") {
                navigator.push(Route.History)
            }
            RefundWay(
                Icons.Default.CreditCard,
                R.string.refund_entry_unreferenced,
                R.string.refund_entry_unreferenced_hint,
                "refundUnreferenced",
            ) { gate.run { navigator.push(Route.UnreferencedRefund) } }
        }
    }
}

/** One way to find a refund: its [hint] above its [label]ed button, tagged [tag]. */
@Composable
private fun RefundWay(
    icon: ImageVector,
    @StringRes label: Int,
    @StringRes hint: Int,
    tag: String,
    onClick: () -> Unit,
) {
    Card {
        Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(LocalDimens.current.spacing))
        SecondaryButton(stringResource(label), onClick, Modifier.testTag(tag), icon = icon)
    }
}

/**
 * What the unreferenced refund screen shows.
 *
 * @property currency The payment currency the refund is made in.
 * @property amount The amount typed in.
 * @property reason Why staff make it; null until chosen.
 * @property block Why none can be made now; null when ready.
 * @property managerConfigured Whether this action is public or a Manager PIN is configured to approve it.
 * @property limitMinor The local limit in minor units of [currency].
 */
internal data class UnreferencedRefundState(
    val currency: String = PaymentSettings.FALLBACK_CURRENCY,
    val amount: AmountEntry = AmountEntry(),
    val reason: UnreferencedRefundReason? = null,
    val block: UnreferencedRefundBlock? = UnreferencedRefundBlock.NOT_VERIFIED,
    val managerConfigured: Boolean = false,
    val limitMinor: Long = 0,
) {
    /** Whether the typed amount is above the local limit. */
    val overLimit: Boolean get() = amount.minor > limitMinor

    /** Whether the refund can be confirmed: ready, with a Manager PIN, a reason and an amount within the limit. */
    val ready: Boolean get() = block == null && managerConfigured && reason != null && amount.minor in 1..limitMinor
}

/**
 * Entering an unreferenced card refund. Readiness follows the stored configuration and verified store facts; starting
 * checks them again through [StoredPaymentActions.refundWithoutReceipt], and the configured Manager PIN once more
 * before sending.
 */
internal class UnreferencedRefundViewModel(
    settings: StateFlow<AppSettings>,
    discovery: StateFlow<WalletAvailability>,
    configured: Flow<Boolean>,
    currency: (AppSettings) -> CurrencySpec,
    private val actions: StoredPaymentActions,
) : ViewModel() {
    private val amount = MutableStateFlow(AmountEntry())
    private val reason = MutableStateFlow<UnreferencedRefundReason?>(null)
    private val facts = discovery

    /** The screen state. */
    val state: StateFlow<UnreferencedRefundState> =
        combine(settings, discovery, configured, amount, reason) { app, verified, pin, entered, why ->
            val code = currency(app).code
            val refunds = app.payment.unreferencedRefunds
            UnreferencedRefundState(code, entered, why, UnreferencedRefunds.block(refunds, verified, code), pin, refunds.limitMinor)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, UnreferencedRefundState())

    /** Updates the typed amount with a keypad step such as [AmountEntry.append]. */
    fun updateAmount(transform: (AmountEntry) -> AmountEntry) {
        amount.value = transform(amount.value)
    }

    /** Chooses why the refund is made. */
    fun choose(value: UnreferencedRefundReason) {
        reason.value = value
    }

    /** Starts the reviewed refund; its ID, or null when it is not ready or another refund is running. */
    fun start(): String? {
        val current = state.value
        val why = current.reason ?: return null
        if (!current.ready) return null
        return actions.refundWithoutReceipt(current.amount.minor, current.currency, why, facts.value)
    }
}

/**
 * An unreferenced card refund: staff enter the amount and the reason, review a warning that it is linked to no original
 * payment, confirm, and approve with the Manager PIN; the shopper then presents the card on the terminal.
 */
@Composable
internal fun UnreferencedRefundScreen(
    navigator: Navigator,
    modifier: Modifier = Modifier,
    vm: UnreferencedRefundViewModel = unreferencedRefundViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val money = rememberMoneyFormatter(state.currency)
    val gate = currentSetupGate()
    var confirm by rememberSaveable { mutableStateOf(false) }
    var approve by rememberSaveable { mutableStateOf(false) }
    if (approve) {
        return ManagerApproval(actions = setOf(ManagerAction.UNREFERENCED_REFUND), onApprove = {
            approve = false
            if (vm.start() != null) navigator.replace(Route.RefundProcessing)
        }, onCancel = { approve = false })
    }
    if (confirm) {
        ConfirmDialog(
            stringResource(R.string.unreferenced_title),
            stringResource(R.string.unreferenced_confirm, money.format(state.amount.minor)),
            stringResource(R.string.refund_confirm),
            {
                confirm = false
                approve = true
            },
            { confirm = false },
        )
    }
    MiniScaffold(stringResource(R.string.unreferenced_title), navigator::back, modifier = modifier, bottomBar = {
        BottomActions {
            PrimaryButton(
                stringResource(R.string.refund_button, money.format(state.amount.minor)),
                { gate.run { confirm = true } },
                enabled = state.ready,
                modifier = Modifier.testTag("startUnreferencedRefund"),
            )
            unreferencedBlockText(state)?.let { BlockedReason(it) }
        }
    }) { padding ->
        ScreenColumn(padding) {
            OutcomeNote(stringResource(R.string.unreferenced_warning))
            Text(
                money.format(state.amount.minor),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.testTag("unreferencedAmount"),
            )
            if (state.limitMinor > 0) Text(stringResource(R.string.unreferenced_limit, money.format(state.limitMinor)))
            Text(stringResource(R.string.unreferenced_reason), style = MaterialTheme.typography.titleSmall)
            ReasonChoice(state.reason, vm::choose)
            NumericKeypad(
                { digit -> vm.updateAmount { it.append(digit) } },
                { vm.updateAmount { it.appendDoubleZero() } },
                { vm.updateAmount { it.backspace() } },
            )
        }
    }
}

/** Why the refund cannot be confirmed now, worded with its fix; null when nothing blocks it or only input is missing. */
@Composable
private fun unreferencedBlockText(state: UnreferencedRefundState): String? =
    when {
        state.block != null -> stringResource(blockText(state.block))
        !state.managerConfigured -> stringResource(R.string.unreferenced_manager)
        state.overLimit -> stringResource(R.string.unreferenced_over_limit)
        else -> null
    }

@StringRes
private fun blockText(block: UnreferencedRefundBlock): Int =
    when (block) {
        UnreferencedRefundBlock.DESTINATION -> R.string.unreferenced_blocked_destination
        UnreferencedRefundBlock.NOT_ENABLED -> R.string.unreferenced_blocked_not_enabled
        UnreferencedRefundBlock.NO_LIMIT -> R.string.unreferenced_blocked_no_limit
        UnreferencedRefundBlock.NOT_VERIFIED -> R.string.unreferenced_blocked_not_verified
        UnreferencedRefundBlock.COUNTRY -> R.string.unreferenced_blocked_country
        UnreferencedRefundBlock.CHANGED -> R.string.unreferenced_blocked_changed
    }

/** The operational reasons, one selectable row each. */
@Composable
private fun ReasonChoice(
    selected: UnreferencedRefundReason?,
    onSelect: (UnreferencedRefundReason) -> Unit,
) {
    Column(Modifier.selectableGroup()) {
        UnreferencedRefundReason.entries.forEach { reason ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .selectable(selected = reason == selected, onClick = { onSelect(reason) }, role = Role.RadioButton)
                    .testTag("reason_${reason.name}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = reason == selected, onClick = null)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(unreferencedReasonLabel(reason)))
            }
        }
    }
}

/** The label of an unreferenced refund's [reason]. */
@StringRes
internal fun unreferencedReasonLabel(reason: UnreferencedRefundReason): Int =
    when (reason) {
        UnreferencedRefundReason.RETURN_WITHOUT_RECEIPT -> R.string.unreferenced_reason_return
        UnreferencedRefundReason.PAID_ELSEWHERE -> R.string.unreferenced_reason_elsewhere
        UnreferencedRefundReason.PRICE_ADJUSTMENT -> R.string.unreferenced_reason_adjustment
        UnreferencedRefundReason.OTHER -> R.string.unreferenced_reason_other
    }

@Composable
private fun unreferencedRefundViewModel(): UnreferencedRefundViewModel {
    val container = LocalAppContainer.current
    return viewModel {
        UnreferencedRefundViewModel(
            container.settingsState,
            container.walletDiscovery.state,
            container.managerAvailable(ManagerAction.UNREFERENCED_REFUND),
            container::currency,
            container.storedPaymentActions,
        )
    }
}
