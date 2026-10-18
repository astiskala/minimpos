package app.minimpos.app.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreditCard
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.minimpos.app.R
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.feature.settings.SettingsSections
import app.minimpos.app.feature.text
import app.minimpos.app.terminal.TerminalConnection
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.MiniScaffold
import app.minimpos.app.ui.components.currentSetupGate
import app.minimpos.app.ui.components.openInBrowser
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.app.ui.theme.LocalStatusColors
import app.minimpos.app.update.UpdateCheck

/**
 * The home screen fills the available height, so everything fits on a 4" terminal screen without scrolling. Only when
 * the tiles would get less room than their content needs (a landscape phone, large text) does it scroll instead. While
 * payments still need setting up, a card says so and every tile that reaches Adyen or a terminal explains that instead
 * of opening.
 */
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
    val gate = currentSetupGate()
    // A failed check is retried whenever Home is shown, so its warning clears once the terminal answers again.
    LaunchedEffect(Unit) { container.terminalStatus.recheckIfFailed() }
    // Every tile but History and the admin areas reaches Adyen or a terminal, so it asks to finish setup first.
    val open = { route: Route -> if (route.requiresSetup) gate.run { navigator.push(route) } else navigator.push(route) }

    MiniScaffold(
        title = settings.receipt.businessName.ifBlank { stringResource(R.string.app_name) },
        onBack = null,
        modifier = modifier,
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            // The tiles share the height, up to that of the largest terminals (the S1F4 Pro), and stop growing beyond.
            val height = (maxHeight - dimens.screenPadding * 2).coerceIn(0.dp, dimens.homeMaxHeight)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(dimens.screenPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.widthIn(max = dimens.contentMaxWidth).fillOrGrow(height),
                    verticalArrangement = Arrangement.spacedBy(dimens.spacing),
                ) {
                    // Wherever payments go but the simulator: this terminal, one on the network or in the cloud, or Tap to Pay.
                    if (terminal.loaded && terminal.mode != TerminalMode.SIMULATOR) {
                        ConnectionProblem(gate.pending, terminal.connection, gate::openSetup) {
                            navigator.push(Route.SettingsSection(SettingsSections.TERMINAL))
                        }
                    }
                    (update as? UpdateCheck.Available)?.let { offered ->
                        UpdateCard(offered, container.update::dismiss)
                    }
                    PaymentTiles(onOpen = open)
                    AreaTiles(locked = pinSet, onOpen = open)
                }
            }
        }
    }
}

/**
 * Makes the column exactly [height] tall, its weighted tiles sharing what the cards leave, unless its content needs
 * more: its minimum intrinsic height gives every weighted tile at least its own minimum (text, icon and touch size).
 * The column then grows past [height] and the screen scrolls, rather than squeezing tiles until their labels are cut.
 */
private fun Modifier.fillOrGrow(height: Dp): Modifier =
    layout { measurable, constraints ->
        val target = maxOf(height.roundToPx(), measurable.minIntrinsicHeight(constraints.maxWidth))
        val placeable = measurable.measure(constraints.copy(minHeight = target, maxHeight = target))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
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
 * A [SetupCard] while payments cannot work, else nothing: while they still need setting up ([needsSetup]) the gate's
 * generic "not set up yet" ([onSetUp] finishes it); otherwise, when the terminal did not answer, why
 * ([onOpenSettings] opens Terminal settings).
 */
@Composable
private fun ConnectionProblem(
    needsSetup: Boolean,
    connection: TerminalConnection,
    onSetUp: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    when {
        needsSetup -> {
            SetupCard(
                Icons.Default.Key,
                stringResource(R.string.setup_required_title),
                stringResource(R.string.home_setup_text),
                error = false,
                onClick = onSetUp,
            )
        }

        connection is TerminalConnection.Failed -> {
            SetupCard(
                Icons.Default.ErrorOutline,
                stringResource(R.string.home_connection_failed),
                connection.failure.text(),
                error = true,
                onClick = onOpenSettings,
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
 * the height is limited the title gets a smaller style, shrinking to one line, and the hint a single line, so two tiles
 * fit a 4" screen.
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
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = MaterialTheme.shapes.large,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = dimens.screenPadding + 8.dp, vertical = dimens.spacing / 2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(if (dimens.compact) 32.dp else 40.dp))
            Spacer(Modifier.width(dimens.screenPadding))
            Column {
                val style = if (dimens.compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall
                Text(
                    title,
                    style = style,
                    maxLines = if (dimens.compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                    autoSize =
                        TextAutoSize.StepBased(minFontSize = MIN_PAYMENT_TITLE_SIZE, maxFontSize = style.fontSize).takeIf {
                            dimens.compact
                        },
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
    Row(Modifier.fillMaxWidth().weight(0.7f), horizontalArrangement = Arrangement.spacedBy(LocalDimens.current.spacing)) {
        HomeTile(
            Icons.Default.CreditCard,
            stringResource(R.string.home_saved_card),
            onClick = { onOpen(Route.SavedCard) },
            modifier = Modifier.testTag("savedCard"),
        )
        HomeTile(
            Icons.Default.QrCodeScanner,
            stringResource(R.string.home_refund),
            onClick = { onOpen(Route.RefundScan) },
            modifier = Modifier.testTag("refund"),
        )
    }
    Row(Modifier.fillMaxWidth().weight(0.7f), horizontalArrangement = Arrangement.spacedBy(LocalDimens.current.spacing)) {
        HomeTile(
            Icons.AutoMirrored.Filled.ReceiptLong,
            stringResource(R.string.home_history),
            onClick = { onOpen(Route.History) },
            modifier = Modifier.testTag("history"),
        )
        HomeTile(
            Icons.Default.Balance,
            stringResource(R.string.home_reconciliation),
            onClick = { onOpen(Route.Reconciliation) },
            modifier = Modifier.testTag("reconciliation"),
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

/** A half-width tile: its icon above the label, or beside it where the tile is too short for both stacked. */
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
        IconAndLabel(stackGap = 4.dp, sideGap = dimens.spacing, modifier = Modifier.padding(dimens.cardPadding)) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(if (dimens.compact) 24.dp else 32.dp),
            )
            val style = MaterialTheme.typography.titleMedium
            // Longer labels, such as Charge saved card, shrink to fit a half-width tile rather than lose their end.
            Text(
                label,
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = MIN_TILE_LABEL_SIZE, maxFontSize = style.fontSize),
            )
        }
    }
}

/**
 * Lays out its two children, an icon then a label: the icon at the top and the label at the bottom, at least
 * [stackGap] apart, when the height holds both; otherwise side by side, [sideGap] apart and centred vertically, as on
 * the AMS1 while the setup card takes room. Its minimum intrinsic height is the side-by-side one, which is what Home
 * guarantees each tile before it scrolls.
 */
@Composable
private fun IconAndLabel(
    stackGap: Dp,
    sideGap: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val policy = remember(stackGap, sideGap) { IconAndLabelPolicy(stackGap, sideGap) }
    Layout(content, modifier, policy)
}

/** The measure policy of [IconAndLabel]. */
private class IconAndLabelPolicy(
    private val stackGap: Dp,
    private val sideGap: Dp,
) : MeasurePolicy {
    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val (icon, label) = measurables
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val iconPlaceable = icon.measure(loose)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val stackedHeight = iconPlaceable.height + stackGap.roundToPx() + label.minIntrinsicHeight(width)
        if (!constraints.hasBoundedHeight || stackedHeight <= constraints.maxHeight) {
            val labelPlaceable = label.measure(loose)
            val height = if (constraints.hasBoundedHeight) constraints.maxHeight else stackedHeight
            return layout(width, height) {
                iconPlaceable.place(0, 0)
                labelPlaceable.place(0, height - labelPlaceable.height)
            }
        }
        val labelX = iconPlaceable.width + sideGap.roundToPx()
        val labelPlaceable = label.measure(loose.copy(maxWidth = (width - labelX).coerceAtLeast(0)))
        val height = constraints.maxHeight
        return layout(width, height) {
            iconPlaceable.place(0, (height - iconPlaceable.height) / 2)
            labelPlaceable.place(labelX, (height - labelPlaceable.height) / 2)
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int,
    ): Int {
        val (icon, label) = measurables
        val labelWidth = (width - icon.maxIntrinsicWidth(Constraints.Infinity) - sideGap.roundToPx()).coerceAtLeast(0)
        return maxOf(icon.minIntrinsicHeight(width), label.minIntrinsicHeight(labelWidth))
    }

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int,
    ): Int {
        val (icon, label) = measurables
        return icon.maxIntrinsicHeight(width) + stackGap.roundToPx() + label.maxIntrinsicHeight(width)
    }
}

/** The smallest a Home tile label shrinks to before it is ellipsized. */
private val MIN_TILE_LABEL_SIZE = 12.sp

/** The smallest a payment tile's title shrinks to on a short screen before it is ellipsized. */
private val MIN_PAYMENT_TITLE_SIZE = 16.sp
