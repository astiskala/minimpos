package app.minimpos.app.feature.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.minimpos.app.R
import app.minimpos.app.data.settings.PaymentSettings
import app.minimpos.app.data.settings.ReceiptTipping
import app.minimpos.app.terminal.TerminalState
import app.minimpos.app.ui.components.SectionHeader
import app.minimpos.terminal.client.TerminalClient

/** Checkout offers for terminal tips, receipt-tip holds and Adyen Giving; remote terminal configuration stays separate. */
@Composable
internal fun ColumnScope.TippingSection(
    payment: PaymentSettings,
    terminal: TerminalState,
    update: ((PaymentSettings) -> PaymentSettings) -> Unit,
    target: String? = null,
) {
    val capabilities = terminal.capabilities
    val terminalBlocked = stringResource(R.string.settings_unavailable_tap_to_pay).takeUnless { capabilities.supportsTerminalFeatures }
    val printBlocked = stringResource(R.string.settings_unavailable_tap_to_pay).takeUnless { capabilities.supportsPrinting }
    SectionHeader(stringResource(R.string.settings_tipping))
    val options =
        listOf(
            ReceiptTipping.DISABLED to stringResource(R.string.settings_tip_disabled),
            ReceiptTipping.DEFAULT_OFF to stringResource(R.string.settings_tip_default_off),
            ReceiptTipping.DEFAULT_ON to stringResource(R.string.settings_tip_default_on),
        )
    SettingChoice(
        title = stringResource(R.string.checkout_tip_on_terminal),
        options = options,
        selected = payment.terminalTipping,
        onSelect = { value -> update { it.copy(terminalTipping = value) } },
        subtitle = stringResource(R.string.settings_terminal_tip_hint),
        tag = "terminalTipping",
        target = target,
        blocked = terminalBlocked,
    )
    if (payment.terminalTipping != ReceiptTipping.DISABLED) {
        TipOverrideSettings(payment, terminal, update)
    }
    SettingChoice(
        title = stringResource(R.string.checkout_tip_on_receipt),
        options = options,
        selected = payment.receiptTipping,
        onSelect = { value -> update { it.copy(receiptTipping = value) } },
        subtitle = stringResource(R.string.settings_tip_default_hint),
        tag = "receiptTipping",
        target = target,
        blocked = printBlocked,
    )
    SettingChoice(
        title = stringResource(R.string.checkout_ask_giving),
        options = options,
        selected = payment.askGiving,
        onSelect = { value -> update { it.copy(askGiving = value) } },
        subtitle = stringResource(R.string.settings_ask_giving_hint),
        tag = "askGivingSetting",
        target = target,
        blocked = terminalBlocked,
    )
}

@Composable
private fun ColumnScope.TipOverrideSettings(
    payment: PaymentSettings,
    terminal: TerminalState,
    update: ((PaymentSettings) -> PaymentSettings) -> Unit,
) {
    val blocked = terminalSoftwareBlocked(terminal, TerminalClient.TIP_OPTIONS_MINIMUM_SOFTWARE)
    SettingSwitch(
        stringResource(R.string.settings_dynamic_tip_override),
        payment.dynamicTipOverride,
        { value -> update { it.copy(dynamicTipOverride = value) } },
        subtitle = stringResource(R.string.settings_dynamic_tip_override_hint),
        tag = "dynamicTipOverride",
        blocked = blocked,
    )
    SettingSwitch(
        stringResource(R.string.settings_tip_on_pre_tax),
        payment.tipOnPreTax,
        { value -> update { it.copy(tipOnPreTax = value) } },
        subtitle = stringResource(R.string.settings_tip_on_pre_tax_hint),
        tag = "tipOnPreTax",
        blocked = blocked,
    )
}
