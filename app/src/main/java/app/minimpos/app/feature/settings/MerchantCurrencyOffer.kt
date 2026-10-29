package app.minimpos.app.feature.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.minimpos.app.R
import app.minimpos.app.ui.components.SecondaryButton
import app.minimpos.app.ui.components.Subheading

/** Optional currency choice after setup; choosing it still requires the ordinary pricing-change review. */
@Composable
internal fun ColumnScope.MerchantCurrencyOffer(
    code: String?,
    current: String,
    onChoose: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (code == null || code == current) return
    Subheading(stringResource(R.string.settings_merchant_currency), inset = true)
    SettingNote(stringResource(R.string.settings_merchant_currency_hint, code))
    SettingActions {
        SecondaryButton(
            stringResource(R.string.settings_merchant_currency_use, code),
            onChoose,
            modifier = Modifier.testTag("useMerchantCurrency"),
        )
        TextButton(onClick = onDismiss, modifier = Modifier.testTag("keepCurrency")) {
            Text(stringResource(R.string.settings_merchant_currency_keep))
        }
    }
}
