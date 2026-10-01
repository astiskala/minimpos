package io.minimpos.app.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.minimpos.app.R
import io.minimpos.app.data.settings.TerminalMode
import io.minimpos.app.feature.settings.SettingsSections
import io.minimpos.app.terminal.TerminalConnection
import io.minimpos.app.ui.components.LocalAppContainer
import io.minimpos.app.ui.components.MiniScaffold
import io.minimpos.app.ui.navigation.Navigator
import io.minimpos.app.ui.navigation.Route
import io.minimpos.app.ui.theme.LocalDimens
import io.minimpos.app.ui.theme.LocalStatusColors

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
                if (terminal.loaded && terminal.mode == TerminalMode.TERMINAL) {
                    ConnectionProblem(terminal.connection, terminal.setupProblem != null) {
                        navigator.push(Route.SettingsSection(SettingsSections.TERMINAL))
                    }
                }
                NewSaleTile { navigator.push(Route.Sale) }
                AreaTiles(locked = pinSet, onOpen = navigator::push)
            }
        }
    }
}

/** A [SetupCard] while payments cannot work: something must still be entered or the terminal did not answer; else nothing. */
@Composable
private fun ConnectionProblem(
    connection: TerminalConnection,
    setupNeeded: Boolean,
    onClick: () -> Unit,
) {
    when {
        setupNeeded || connection is TerminalConnection.NotSetUp -> {
            SetupCard(
                Icons.Default.Key,
                stringResource(R.string.home_setup_title),
                stringResource(R.string.home_setup_text),
                error = false,
                onClick = onClick,
            )
        }

        connection is TerminalConnection.Failed -> {
            SetupCard(
                Icons.Default.ErrorOutline,
                stringResource(R.string.home_connection_failed),
                connection.message,
                error = true,
                onClick = onClick,
            )
        }
    }
}

/** The large green tile that starts a sale. */
@Composable
private fun ColumnScope.NewSaleTile(onClick: () -> Unit) {
    val dimens = LocalDimens.current
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.primary,
        contentColor = Color.White,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().weight(1.2f).testTag("newSale"),
    ) {
        Row(Modifier.padding(horizontal = dimens.screenPadding + 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.PointOfSale,
                contentDescription = null,
                modifier = Modifier.size(if (dimens.compact) 32.dp else 40.dp),
            )
            Spacer(Modifier.width(dimens.screenPadding))
            Column {
                Text(stringResource(R.string.home_new_sale), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.home_new_sale_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Two rows of tiles for the other areas; the admin ones show a lock when [locked] (a PIN is set). */
@Composable
private fun ColumnScope.AreaTiles(
    locked: Boolean,
    onOpen: (Route) -> Unit,
) {
    TileRow {
        HomeTile(
            Icons.Default.QrCodeScanner,
            stringResource(R.string.home_refund),
            locked = false,
            onClick = { onOpen(Route.RefundScan) },
            modifier = Modifier.testTag("refund"),
        )
        HomeTile(
            Icons.AutoMirrored.Filled.ReceiptLong,
            stringResource(R.string.home_history),
            locked = false,
            onClick = { onOpen(Route.History) },
            modifier = Modifier.testTag("history"),
        )
    }
    TileRow {
        HomeTile(
            Icons.Default.Inventory2,
            stringResource(R.string.home_products),
            locked = locked,
            onClick = { onOpen(Route.Products) },
            modifier = Modifier.testTag("products"),
        )
        HomeTile(
            Icons.Default.Settings,
            stringResource(R.string.home_settings),
            locked = locked,
            onClick = { onOpen(Route.Settings) },
            modifier = Modifier.testTag("settings"),
        )
    }
}

@Composable
private fun ColumnScope.TileRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(LocalDimens.current.spacing), content = content)
}

/** Points to Terminal settings while payments cannot work yet: the shared key is missing, or the terminal did not answer. */
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
    locked: Boolean,
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(if (dimens.compact) 24.dp else 32.dp),
                )
                if (locked) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = stringResource(R.string.locked),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp).weight(1f))
            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
