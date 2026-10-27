package app.minimpos.app.feature.sale

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.minimpos.app.R
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.feature.ResultAction
import app.minimpos.app.feature.ResultBar
import app.minimpos.app.feature.lock.ManagerApproval
import app.minimpos.app.ui.components.BottomActions
import app.minimpos.app.ui.components.ConfirmDialog
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.MiniScaffold
import app.minimpos.app.ui.components.NumericKeypad
import app.minimpos.app.ui.components.OutcomeHeader
import app.minimpos.app.ui.components.OutcomeNote
import app.minimpos.app.ui.components.PrimaryButton
import app.minimpos.app.ui.components.ScreenColumn
import app.minimpos.app.ui.components.StatusKind
import app.minimpos.app.ui.components.SwitchRow
import app.minimpos.app.ui.components.currentSetupGate
import app.minimpos.app.ui.components.rememberMoneyFormatter
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import app.minimpos.core.access.ManagerAction

/** Standalone eftpos cash-out, kept separate from merchandise and purchase-linked cashback readiness. */
@Composable
internal fun CashoutScreen(
    navigator: Navigator,
    modifier: Modifier = Modifier,
    vm: CashoutViewModel = cashoutViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val money = rememberMoneyFormatter(state.currency)
    val gate = currentSetupGate()
    var confirm by remember { mutableStateOf(false) }
    var approve by remember { mutableStateOf(false) }
    if (approve) {
        return ManagerApproval(actions = setOf(ManagerAction.CASHOUT), onApprove = {
            approve = false
            if (vm.start()) navigator.replace(Route.Payment(SaleKind.SALE))
        }, onCancel = { approve = false })
    }
    if (confirm) {
        ConfirmDialog(
            stringResource(R.string.cashout_title),
            stringResource(R.string.cashout_confirm, money.format(state.amount.minor)),
            stringResource(R.string.shopper_confirm),
            {
                confirm = false
                approve = true
            },
            { confirm = false },
        )
    }
    MiniScaffold(stringResource(R.string.cashout_title), navigator::back, modifier = modifier, bottomBar = {
        BottomActions {
            PrimaryButton(
                stringResource(R.string.cashout_start, money.format(state.amount.minor)),
                { gate.run { confirm = true } },
                enabled = state.ready,
                modifier = Modifier.testTag("startCashout"),
            )
        }
    }) { padding ->
        ScreenColumn(padding) {
            Text(stringResource(R.string.cashout_configuration))
            Text(money.format(state.amount.minor))
            SwitchRow(
                stringResource(R.string.cashback_card_confirm, "eftpos"),
                state.cardConfirmed,
                vm::confirmCard,
                Modifier.testTag("cashoutCard"),
            )
            if (!state.available) Text(stringResource(R.string.cashback_unavailable))
            if (!state.managerConfigured) Text(stringResource(R.string.gift_card_manager_note))
            NumericKeypad(vm::digit, vm::doubleZero, vm::backspace)
        }
    }
}

/** Records cash handover after a separate confirmation and a live Manager permission recheck. */
@Composable
internal fun CashbackHandoverScreen(
    saleId: String,
    navigator: Navigator,
    modifier: Modifier = Modifier,
    vm: CashHandoverViewModel = cashHandoverViewModel(saleId),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val amount = state.amountMinor?.let(rememberMoneyFormatter(state.currency)::format).orEmpty()
    var approve by remember { mutableStateOf(false) }
    if (approve) {
        return ManagerApproval(actions = setOf(ManagerAction.CASHOUT), onApprove = {
            approve = false
            vm.confirm()
        }, onCancel = { approve = false })
    }
    MiniScaffold(stringResource(R.string.cashback_handover), navigator::back, modifier = modifier, bottomBar = {
        if (state.confirmed) {
            ResultBar(
                onHome = {
                    vm.finish()
                    navigator.home()
                },
                next = ResultAction(stringResource(R.string.action_done), "handoverDone", onClick = navigator::back),
            )
        } else {
            BottomActions {
                PrimaryButton(
                    stringResource(R.string.cashback_handover_amount, amount),
                    { approve = true },
                    enabled = state.ready,
                    modifier = Modifier.testTag("confirmCashbackHandover"),
                )
            }
        }
    }) { padding ->
        ScreenColumn(padding) {
            OutcomeHeader(
                if (state.confirmed) StatusKind.SUCCESS else StatusKind.WARNING,
                stringResource(if (state.confirmed) R.string.cashback_handed_over else R.string.cashback_handover),
                amount,
                titleTag = "cashHandoverStatus",
            ) {
                if (!state.confirmed) OutcomeNote(stringResource(R.string.cashback_handover_confirm))
                if (!state.managerConfigured) OutcomeNote(stringResource(R.string.gift_card_manager_note))
            }
        }
    }
}

@Composable
private fun cashoutViewModel(): CashoutViewModel {
    val container = LocalAppContainer.current
    return viewModel {
        CashoutViewModel(
            container.settingsState,
            container.walletDiscovery.state,
            container.managerApproval.forAction(ManagerAction.CASHOUT).available,
            container::currency,
            container.payments,
        )
    }
}

@Composable
private fun cashHandoverViewModel(saleId: String): CashHandoverViewModel {
    val container = LocalAppContainer.current
    return viewModel(key = "cash-$saleId") {
        CashHandoverViewModel(
            saleId,
            container.storedPayments,
            container.cashTenders,
            container.operations,
            container.managerApproval.forAction(ManagerAction.CASHOUT).available,
        )
    }
}
