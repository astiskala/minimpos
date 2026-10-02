package io.minimpos.app.feature.home

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
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.minimpos.app.R
import io.minimpos.app.data.db.SaleKind
import io.minimpos.app.data.settings.TerminalMode
import io.minimpos.app.feature.settings.SettingsSections
import io.minimpos.app.feature.setupCard
import io.minimpos.app.terminal.TerminalConnection
import io.minimpos.app.terminal.TerminalState
import io.minimpos.app.ui.components.LocalAppContainer
import io.minimpos.app.ui.components.MiniScaffold
import io.minimpos.app.ui.navigation.Navigator
import io.minimpos.app.ui.navigation.Route
import io.minimpos.app.ui.theme.LocalDimens
import io.minimpos.app.ui.theme.LocalStatusColors
import kotlinx.coroutines.flow.map

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
    val preAuthOffered by remember {
        container.catalog.products.map { products -> products.any { it.kind == SaleKind.PRE_AUTHORISATION } }
    }.collectAsStateWithLifecycle(initialValue = false)
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
                PaymentTiles(preAuthOffered, onOpen = navigator::push)
                AreaTiles(locked = pinSet, onOpen = navigator::push)
            }
        }
    }
}

/**
 * The large green tile that starts a sale or, when pre-authorisation products exist ([preAuthOffered]), two side by
 * side: New sale and Pre-authorise.
 */
@Composable
private fun ColumnScope.PaymentTiles(
    preAuthOffered: Boolean,
    onOpen: (Route) -> Unit,
) {
    if (!preAuthOffered) {
        PaymentTile(
            Icons.Default.PointOfSale,
            stringResource(R.string.home_new_sale),
            stringResource(R.string.home_new_sale_hint),
            stacked = false,
            onClick = { onOpen(Route.Sale) },
            modifier = Modifier.fillMaxWidth().weight(1.2f).testTag("newSale"),
        )
        return
    }
    Row(Modifier.fillMaxWidth().weight(1.2f), horizontalArrangement = Arrangement.spacedBy(LocalDimens.current.spacing)) {
        PaymentTile(
            Icons.Default.PointOfSale,
            stringResource(R.string.home_new_sale),
            stringResource(R.string.home_new_sale_hint),
            stacked = true,
            onClick = { onOpen(Route.Sale) },
            modifier = Modifier.weight(1f).fillMaxHeight().testTag("newSale"),
        )
        PaymentTile(
            Icons.Default.LockClock,
            stringResource(R.string.home_pre_auth),
            stringResource(R.string.home_pre_auth_hint),
            stacked = true,
            onClick = { onOpen(Route.PreAuth) },
            modifier = Modifier.weight(1f).fillMaxHeight().testTag("preAuth"),
        )
    }
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
                connection.message ?: stringResource(R.string.setup_no_response),
                error = true,
                onClick = onClick,
            )
        }
    }
}

/**
 * A large green tile that starts a payment: a sale, or a pre-authorisation. Alone it is a row (icon beside the text);
 * [stacked] beside another one, the icon sits above the text, which then also gets a smaller style to fit half the
 * width.
 */
@Composable
private fun PaymentTile(
    icon: ImageVector,
    title: String,
    hint: String,
    stacked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    val iconSize = if (dimens.compact) 32.dp else 40.dp
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.primary,
        contentColor = Color.White,
        shape = MaterialTheme.shapes.large,
        modifier = modifier,
    ) {
        if (stacked) {
            Column(Modifier.padding(dimens.cardPadding), verticalArrangement = Arrangement.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize))
                Spacer(Modifier.height(dimens.spacing).weight(1f, fill = false))
                PaymentTileText(
                    title,
                    hint,
                    if (dimens.compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                )
            }
        } else {
            Row(Modifier.padding(horizontal = dimens.screenPadding + 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize))
                Spacer(Modifier.width(dimens.screenPadding))
                PaymentTileText(title, hint, MaterialTheme.typography.headlineSmall)
            }
        }
    }
}

@Composable
private fun PaymentTileText(
    title: String,
    hint: String,
    titleStyle: TextStyle,
) {
    Column {
        Text(title, style = titleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(hint, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(LocalDimens.current.spacing)) {
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
