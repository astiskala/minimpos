package app.minimpos.app.feature.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.minimpos.app.R
import app.minimpos.app.data.settings.PaymentSettings
import app.minimpos.app.data.settings.ReceiptTipping
import app.minimpos.app.ui.components.SectionHeader

/** Checkout offers for terminal tips, receipt-tip holds and Adyen Giving; remote terminal configuration stays separate. */
@Composable
internal fun ColumnScope.TippingSection(
    payment: PaymentSettings,
    update: ((PaymentSettings) -> PaymentSettings) -> Unit,
) {
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
    )
    if (payment.terminalTipping != ReceiptTipping.DISABLED) {
        SettingSwitch(
            stringResource(R.string.settings_dynamic_tip_override),
            payment.dynamicTipOverride,
            { value -> update { it.copy(dynamicTipOverride = value) } },
            subtitle = stringResource(R.string.settings_dynamic_tip_override_hint),
            tag = "dynamicTipOverride",
        )
        SettingSwitch(
            stringResource(R.string.settings_tip_on_pre_tax),
            payment.tipOnPreTax,
            { value -> update { it.copy(tipOnPreTax = value) } },
            subtitle = stringResource(R.string.settings_tip_on_pre_tax_hint),
            tag = "tipOnPreTax",
        )
    }
    SettingChoice(
        title = stringResource(R.string.checkout_tip_on_receipt),
        options = options,
        selected = payment.receiptTipping,
        onSelect = { value -> update { it.copy(receiptTipping = value) } },
        subtitle = stringResource(R.string.settings_tip_default_hint),
        tag = "receiptTipping",
    )
    SettingChoice(
        title = stringResource(R.string.checkout_ask_giving),
        options = options,
        selected = payment.askGiving,
        onSelect = { value -> update { it.copy(askGiving = value) } },
        subtitle = stringResource(R.string.settings_ask_giving_hint),
        tag = "askGivingSetting",
    )
}
