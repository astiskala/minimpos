package app.minimpos.app.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.minimpos.app.R
import app.minimpos.app.ui.components.SectionHeader
import app.minimpos.core.access.ManagerAction

/** Admin-only action permissions, retained when either PIN is removed. */
@Composable
internal fun ManagerPermissions(
    state: SettingsUiState,
    events: SettingsEvents,
) {
    SectionHeader(stringResource(R.string.manager_permissions))
    ManagerAction.entries.forEach { action ->
        SettingSwitch(
            title = stringResource(action.title()),
            checked = state.settings.security.requiresManager(action),
            enabled = state.pinSet,
            subtitle = stringResource(R.string.manager_permission_hint),
            tag = "managerPermission${action.name}",
            onChange = { required ->
                events.onUpdate {
                    it.copy(security = it.security.copy(managerRequirements = it.security.managerRequirements + (action.name to required)))
                }
            },
        )
    }
}

private fun ManagerAction.title(): Int =
    when (this) {
        ManagerAction.SALE -> R.string.manager_action_sale
        ManagerAction.PRE_AUTHORISATION -> R.string.manager_action_pre_authorisation
        ManagerAction.CASH_PAYMENT -> R.string.manager_action_cash_payment
        ManagerAction.LINK_CREATE -> R.string.manager_action_link_create
        ManagerAction.REFERENCED_REFUND -> R.string.manager_action_referenced_refund
        ManagerAction.UNREFERENCED_REFUND -> R.string.manager_action_unreferenced_refund
        ManagerAction.CASH_REFUND -> R.string.manager_action_cash_refund
        ManagerAction.CAPTURE -> R.string.manager_action_capture
        ManagerAction.ADJUST_AUTHORISATION -> R.string.manager_action_adjust_authorisation
        ManagerAction.CANCEL_AUTHORISATION -> R.string.manager_action_cancel_authorisation
        ManagerAction.CASHOUT -> R.string.manager_action_cashout
        ManagerAction.GIFT_CARD_CHANGE -> R.string.manager_action_gift_card_change
        ManagerAction.SAVED_CARD_CHARGE -> R.string.manager_action_saved_card_charge
        ManagerAction.TOKEN_DELETE -> R.string.manager_action_token_delete
        ManagerAction.MANUAL_ENTRY -> R.string.manager_action_manual_entry
        ManagerAction.LINK_CHANGE -> R.string.manager_action_link_change
        ManagerAction.CLOSE_PERIOD -> R.string.manager_action_close_period
        ManagerAction.HISTORY -> R.string.manager_action_history
        ManagerAction.RECEIPT -> R.string.manager_action_receipt
        ManagerAction.TOTALS -> R.string.manager_action_totals
        ManagerAction.SAVED_CARD_LOOKUP -> R.string.manager_action_saved_card_lookup
        ManagerAction.GIFT_CARD_BALANCE -> R.string.manager_action_gift_card_balance
    }
