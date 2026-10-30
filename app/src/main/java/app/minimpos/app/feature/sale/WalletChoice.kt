package app.minimpos.app.feature.sale

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.minimpos.app.ui.components.SecondaryButton
import app.minimpos.app.ui.theme.Spacing
import app.minimpos.core.payment.ScanWallet
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest

/**
 * Public, credential-free artwork, identical across payment environments. Only known enum brands enter the path;
 * the CDN's TEST name does not select or probe a payment environment.
 */
internal val ScanWallet.logoUrl: String
    get() = "https://checkoutshopper-test.cdn.adyen.com/checkoutshopper/images/logos/$brand.svg"

/** A selectable wallet with cached Adyen SVG artwork; its name and action work even while the logo is unavailable. */
@Composable
internal fun WalletChoice(
    wallet: ScanWallet,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val request =
        remember(context, wallet) {
            ImageRequest
                .Builder(context)
                .data(wallet.logoUrl)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build()
        }
    val fallback = rememberVectorPainter(Icons.Default.AccountBalanceWallet)
    SecondaryButton(onClick = onClick, modifier = modifier) {
        AsyncImage(
            model = request,
            contentDescription = null,
            placeholder = fallback,
            error = fallback,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(40.dp, 26.dp).testTag("wallet_logo_${wallet.brand}"),
        )
        Spacer(Modifier.width(Spacing.Medium))
        Text(
            wallet.displayName,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
