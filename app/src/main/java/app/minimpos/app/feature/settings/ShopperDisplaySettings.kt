package app.minimpos.app.feature.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.minimpos.app.R
import app.minimpos.app.data.settings.PaymentSettings
import app.minimpos.app.terminal.DestinationCapabilities

/**
 * Optional shopper interactions on a separate terminal; unsupported destinations retain preferences without using them.
 * Asking for an email waits for emailed receipts to be ready ([emailReceiptsReady]), saying so under its switch.
 */
@Composable
internal fun ColumnScope.ShopperDisplaySection(
    payment: PaymentSettings,
    emailReceiptsReady: Boolean,
    capabilities: DestinationCapabilities,
    update: ((PaymentSettings) -> PaymentSettings) -> Unit,
) {
    val blocked =
        stringResource(
            if (capabilities.supportsTerminalFeatures) {
                R.string.settings_unavailable_destination
            } else {
                R.string.settings_unavailable_tap_to_pay
            },
        ).takeUnless { capabilities.supportsShopperDisplay }
    SettingIntro(stringResource(R.string.settings_shopper_display_hint))
    SettingSwitch(
        stringResource(R.string.settings_show_virtual_receipt),
        payment.showVirtualReceipt,
        { value -> update { it.copy(showVirtualReceipt = value) } },
        subtitle = stringResource(R.string.settings_show_virtual_receipt_hint),
        tag = "showVirtualReceipt",
        blocked = blocked,
    )
    SettingSwitch(
        stringResource(R.string.settings_confirm_basket_terminal),
        payment.confirmBasketOnTerminal,
        { value -> update { it.copy(confirmBasketOnTerminal = value) } },
        subtitle = stringResource(R.string.settings_confirm_basket_terminal_hint),
        tag = "confirmBasketOnTerminal",
        blocked = blocked,
    )
    SettingSwitch(
        stringResource(R.string.settings_collect_email_terminal),
        payment.collectEmailOnTerminal,
        { value -> update { it.copy(collectEmailOnTerminal = value) } },
        subtitle = stringResource(R.string.settings_collect_email_terminal_hint),
        tag = "collectEmailOnTerminal",
        blocked = blocked ?: stringResource(R.string.settings_email_receipts_first).takeUnless { emailReceiptsReady },
    )
    SettingSwitch(
        stringResource(R.string.settings_show_terminal_qr),
        payment.showTerminalQrReceipt,
        { value -> update { it.copy(showTerminalQrReceipt = value) } },
        subtitle = stringResource(R.string.settings_show_terminal_qr_hint),
        tag = "showTerminalQrReceipt",
        blocked = blocked,
    )
    SettingSwitch(
        stringResource(R.string.settings_ask_shopper_rating),
        payment.askShopperRating,
        { value -> update { it.copy(askShopperRating = value) } },
        subtitle = stringResource(R.string.settings_ask_shopper_rating_hint),
        tag = "askShopperRating",
        blocked = blocked,
    )
}
