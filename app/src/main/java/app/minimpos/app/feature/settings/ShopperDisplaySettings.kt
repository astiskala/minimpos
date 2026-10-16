package app.minimpos.app.feature.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.minimpos.app.R
import app.minimpos.app.data.settings.PaymentSettings

/** Optional shopper interactions on a separate terminal; unsupported destinations retain preferences without using them. */
@Composable
internal fun ColumnScope.ShopperDisplaySection(
    payment: PaymentSettings,
    update: ((PaymentSettings) -> PaymentSettings) -> Unit,
) {
    SettingNote(stringResource(R.string.settings_shopper_display_hint))
    SettingSwitch(
        stringResource(R.string.settings_show_virtual_receipt),
        payment.showVirtualReceipt,
        { value -> update { it.copy(showVirtualReceipt = value) } },
        tag = "showVirtualReceipt",
    )
    SettingSwitch(
        stringResource(R.string.settings_confirm_basket_terminal),
        payment.confirmBasketOnTerminal,
        { value -> update { it.copy(confirmBasketOnTerminal = value) } },
        tag = "confirmBasketOnTerminal",
    )
    SettingSwitch(
        stringResource(R.string.settings_collect_email_terminal),
        payment.collectEmailOnTerminal,
        { value -> update { it.copy(collectEmailOnTerminal = value) } },
        tag = "collectEmailOnTerminal",
    )
    SettingSwitch(
        stringResource(R.string.settings_show_terminal_qr),
        payment.showTerminalQrReceipt,
        { value -> update { it.copy(showTerminalQrReceipt = value) } },
        tag = "showTerminalQrReceipt",
    )
    SettingSwitch(
        stringResource(R.string.settings_ask_shopper_rating),
        payment.askShopperRating,
        { value -> update { it.copy(askShopperRating = value) } },
        tag = "askShopperRating",
    )
}
