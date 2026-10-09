package app.minimpos.app.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.minimpos.app.R
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.feature.settings.SettingsSections
import app.minimpos.app.feature.setupCard
import app.minimpos.app.feature.text
import app.minimpos.app.terminal.TerminalConnection
import app.minimpos.app.terminal.TerminalState
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.MiniScaffold
import app.minimpos.app.ui.components.openInBrowser
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.app.ui.theme.LocalStatusColors
import app.minimpos.app.update.UpdateCheck

/** The home screen fills the available height rather than scrolling, so everything fits on a 4" terminal screen. */
@Composable
fun HomeScreen(
    navigator: Navigator,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val dimens = LocalDimens.current
    val settings by container.settingsState.collectAsStateWithLifecycle()
    val pinSet by container.pinManager.pinConfigured.collectAsStateWithLifecycle(initialValue = false)
    val terminal by container.terminalStatus.state.collectAsStateWithLifecycle()
    val update by container.update.state.collectAsStateWithLifecycle()
    // A failed check is retried whenever Home is shown, so its warning clears once the terminal answers again.
    LaunchedEffect(Unit) { container.terminalStatus.recheckIfFailed() }

    MiniScaffold(
        title = settings.receipt.businessName.ifBlank { stringResource(R.string.app_name) },
        onBack = null,
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(dimens.screenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The tiles share the height, up to that of the largest terminals (the S1F4 Pro), and stop growing beyond.
            Column(
                Modifier.widthIn(max = 560.dp).heightIn(max = 640.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(dimens.spacing),
            ) {
                // Wherever payments go but the simulator: this terminal, one on the network or in the cloud, or Tap to Pay.
                if (terminal.loaded && terminal.mode != TerminalMode.SIMULATOR) {
                    ConnectionProblem(terminal) { navigator.push(Route.SettingsSection(SettingsSections.TERMINAL)) }
                }
                (update as? UpdateCheck.Available)?.let { offered ->
                    UpdateCard(offered, container.update::dismiss)
                }
                PaymentTiles(onOpen = navigator::push)
                AreaTiles(locked = pinSet, onOpen = navigator::push)
            }
        }
    }
}

/**
 * The two large green tiles that start a payment, one above the other: New sale and New pre-authorisation. Both are
 * always there, since a pre-authorisation can also be of a custom amount, without pre-authorisation products.
 */
@Composable
private fun ColumnScope.PaymentTiles(onOpen: (Route) -> Unit) {
    PaymentTile(
        Icons.Default.PointOfSale,
        stringResource(R.string.home_new_sale),
        stringResource(R.string.home_new_sale_hint),
        onClick = { onOpen(Route.Sale) },
        modifier = Modifier.fillMaxWidth().weight(1f).testTag("newSale"),
    )
    PaymentTile(
        Icons.Default.LockClock,
        stringResource(R.string.home_pre_auth),
        stringResource(R.string.home_pre_auth_hint),
        onClick = { onOpen(Route.PreAuth) },
        modifier = Modifier.fillMaxWidth().weight(1f).testTag("preAuth"),
    )
}

/**
 * A [SetupCard] while payments cannot work: something must still be entered (the Checkout API included) or the terminal
 * did not answer; else nothing. On a terminal whose shared key is missing it asks for that; otherwise it says what is
 * missing.
 */
@Composable
private fun ConnectionProblem(
    terminal: TerminalState,
    onClick: () -> Unit,
) {
    val connection = terminal.connection
    val problem = terminal.setupProblem ?: (connection as? TerminalConnection.NotSetUp)?.problem
    when {
        problem != null -> {
            val (title, text) = problem.setupCard(terminal.onTerminal)
            SetupCard(
                Icons.Default.Key,
                title,
                text,
                error = false,
                onClick = onClick,
            )
        }

        connection is TerminalConnection.Failed -> {
            SetupCard(
                Icons.Default.ErrorOutline,
                stringResource(R.string.home_connection_failed),
                connection.failure.text(),
                error = true,
                onClick = onClick,
            )
        }
    }
}

/**
 * Offers a newer version (the update's version name) as a dismissible card, never shown on an Adyen terminal (the
 * container does not check there): Update opens the release's APK in a browser app (not an app that claims GitHub
 * links), which downloads it, and Android then asks for its installation. Closing hides the offer for the rest of this
 * session.
 */
@Composable
private fun UpdateCard(
    update: UpdateCheck.Available,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val dimens = LocalDimens.current
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("update"),
    ) {
        Row(Modifier.padding(dimens.cardPadding), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SystemUpdate, contentDescription = null)
            Spacer(Modifier.width(dimens.spacing))
            Text(
                stringResource(R.string.update_available, update.versionName),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(dimens.spacing))
            Button(
                onClick = { context.openInBrowser(update.apkUrl) },
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.testTag("updateDownload"),
            ) { Text(stringResource(R.string.update_download)) }
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("updateDismiss")) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
            }
        }
    }
}

/**
 * A large green tile that starts a payment (a sale, or a pre-authorisation): the icon beside the title and hint. Where
 * the height is limited the title gets a smaller style and the hint a single line, so two tiles fit a 4" screen.
 */
@Composable
private fun PaymentTile(
    icon: ImageVector,
    title: String,
    hint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.primary,
        contentColor = Color.White,
        shape = MaterialTheme.shapes.large,
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = dimens.screenPadding + 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(if (dimens.compact) 32.dp else 40.dp))
            Spacer(Modifier.width(dimens.screenPadding))
            Column {
                Text(
                    title,
                    style = if (dimens.compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (dimens.compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Tiles for the everyday areas, then the admin ones as a slim row of plain buttons, which show a lock when [locked]
 * (a PIN is set).
 */
@Composable
private fun ColumnScope.AreaTiles(
    locked: Boolean,
    onOpen: (Route) -> Unit,
) {
    Row(Modifier.fillMaxWidth().weight(0.8f), horizontalArrangement = Arrangement.spacedBy(LocalDimens.current.spacing)) {
        HomeTile(
            Icons.Default.QrCodeScanner,
            stringResource(R.string.home_refund),
            onClick = { onOpen(Route.RefundScan) },
            modifier = Modifier.testTag("refund"),
        )
        HomeTile(
            Icons.AutoMirrored.Filled.ReceiptLong,
            stringResource(R.string.home_history),
            onClick = { onOpen(Route.History) },
            modifier = Modifier.testTag("history"),
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LocalDimens.current.spacing)) {
        AdminButton(
            Icons.Default.Inventory2,
            stringResource(R.string.home_products),
            locked = locked,
            onClick = { onOpen(Route.Products) },
            modifier = Modifier.testTag("products"),
        )
        AdminButton(
            Icons.Default.Settings,
            stringResource(R.string.home_settings),
            locked = locked,
            onClick = { onOpen(Route.Settings) },
            modifier = Modifier.testTag("settings"),
        )
    }
}

/** A low-key outlined button in grey for an admin area, with a small lock after the [label] when [locked]. */
@Composable
private fun RowScope.AdminButton(
    icon: ImageVector,
    label: String,
    locked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = modifier.weight(1f).heightIn(min = LocalDimens.current.secondaryButtonHeight),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (locked) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Default.Lock, contentDescription = stringResource(R.string.locked), modifier = Modifier.size(14.dp))
        }
    }
}

/** Points to Terminal settings while payments cannot work yet: something is missing, or the terminal did not answer. */
@Composable
private fun SetupCard(
    icon: ImageVector,
    title: String,
    text: String,
    error: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalStatusColors.current
    val dimens = LocalDimens.current
    Surface(
        onClick = onClick,
        color = if (error) colors.errorContainer else colors.warningContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("terminalSetup"),
    ) {
        Row(Modifier.padding(dimens.cardPadding), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (error) colors.error else colors.warning)
            Spacer(Modifier.width(dimens.spacing))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun RowScope.HomeTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.weight(1f).fillMaxHeight(),
    ) {
        Column(Modifier.padding(dimens.cardPadding)) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(if (dimens.compact) 24.dp else 32.dp),
            )
            Spacer(Modifier.height(4.dp).weight(1f))
            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
