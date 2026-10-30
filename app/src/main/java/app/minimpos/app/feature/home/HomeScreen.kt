package app.minimpos.app.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.minimpos.app.R
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.feature.settings.SettingsSections
import app.minimpos.app.feature.text
import app.minimpos.app.terminal.TerminalConnection
import app.minimpos.app.ui.components.CompactSecondaryButton
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.MiniScaffold
import app.minimpos.app.ui.components.currentSetupGate
import app.minimpos.app.ui.components.openInBrowser
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import app.minimpos.app.ui.theme.IconSize
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.app.ui.theme.LocalStatusColors
import app.minimpos.app.ui.theme.Spacing
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
        actions = {
            IconButton(onClick = { open(Route.Settings) }, modifier = Modifier.testTag("settings")) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.home_settings))
            }
        },
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
                    AreaTiles(onOpen = open)
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
    val sale = stringResource(R.string.home_new_sale)
    val preAuth = stringResource(R.string.home_pre_auth)
    val titles = rememberSharedFontSize(listOf(sale, preAuth), MaterialTheme.typography.titleLarge, MIN_PAYMENT_TITLE_SIZE)
    PaymentTile(
        Icons.Default.PointOfSale,
        sale,
        titles,
        stringResource(R.string.home_new_sale_hint),
        onClick = { onOpen(Route.Sale) },
        modifier = Modifier.fillMaxWidth().weight(1f).testTag("newSale"),
    )
    PaymentTile(
        Icons.Default.LockClock,
        preAuth,
        titles,
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
            CompactSecondaryButton(
                stringResource(R.string.update_download),
                { context.openInBrowser(update.apkUrl) },
                Modifier.testTag("updateDownload"),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("updateDismiss")) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
            }
        }
    }
}

/**
 * A large green tile that starts a payment (a sale, or a pre-authorisation): the icon beside the title and hint, the
 * same on every screen. The title is one line, sized by [titles] so both tiles' titles shrink together, only as far as
 * the longest needs.
 * Where the height is limited the hint is a single line too, so two tiles fit a 4" screen.
 */
@Composable
private fun PaymentTile(
    icon: ImageVector,
    title: String,
    titles: TextAutoSize,
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
            Modifier.padding(horizontal = dimens.screenPadding + Spacing.Small, vertical = dimens.spacing / 2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(IconSize.Tile))
            Spacer(Modifier.width(dimens.screenPadding))
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = titles,
                )
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Tiles for the everyday areas, in row-major order among the implemented workflows.
 */
@Composable
private fun ColumnScope.AreaTiles(onOpen: (Route) -> Unit) {
    val savedCard = stringResource(R.string.home_saved_card)
    val giftCards = stringResource(R.string.home_gift_cards)
    val refund = stringResource(R.string.home_refund)
    val cashout = stringResource(R.string.cashout_title)
    val history = stringResource(R.string.home_history)
    val reconciliation = stringResource(R.string.home_reconciliation)
    val labels =
        rememberSharedFontSize(
            listOf(savedCard, giftCards, refund, cashout, history, reconciliation),
            MaterialTheme.typography.titleMedium,
            MIN_TILE_LABEL_SIZE,
        )
    Row(Modifier.fillMaxWidth().weight(0.7f), horizontalArrangement = Arrangement.spacedBy(LocalDimens.current.spacing)) {
        HomeTile(
            Icons.Default.CreditCard,
            savedCard,
            labels,
            onClick = { onOpen(Route.SavedCard) },
            modifier = Modifier.testTag("savedCard"),
        )
        HomeTile(
            Icons.Default.CardGiftcard,
            giftCards,
            labels,
            onClick = { onOpen(Route.GiftCards) },
            modifier = Modifier.testTag("giftCards"),
        )
    }
    Row(Modifier.fillMaxWidth().weight(0.7f), horizontalArrangement = Arrangement.spacedBy(LocalDimens.current.spacing)) {
        HomeTile(
            Icons.Default.QrCodeScanner,
            refund,
            labels,
            onClick = { onOpen(Route.RefundStart) },
            modifier = Modifier.testTag("refund"),
        )
        HomeTile(
            Icons.Default.Payments,
            cashout,
            labels,
            onClick = { onOpen(Route.Cashout) },
            modifier = Modifier.testTag("cashout"),
        )
    }
    HistoryTiles(history, reconciliation, labels, onOpen)
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

/**
 * A half-width tile: its icon at the top and the label at the bottom, the same on every screen. The tiles of a group
 * share [labels], so they show their labels at one size: the largest, up to the style's, at which the longest still
 * fits on a line (Reconciliation, say, or Charge saved card), so no tile's label looks smaller than another's.
 */
@Composable
private fun RowScope.HomeTile(
    icon: ImageVector,
    label: String,
    labels: TextAutoSize,
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
        // The vertical padding is the spacing, so the smallest terminal's tile is still no taller than it can spare.
        Column(
            Modifier.padding(horizontal = dimens.cardPadding, vertical = dimens.spacing),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(IconSize.Standard),
            )
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                autoSize = labels,
            )
        }
    }
}

/** One [SharedFontSize] for a group of tiles, so the group's size is measured once rather than by every tile. */
@Composable
private fun rememberSharedFontSize(
    texts: List<String>,
    style: TextStyle,
    minSize: TextUnit,
): TextAutoSize {
    val measurer = rememberTextMeasurer()
    return remember(texts, style, minSize, measurer) { SharedFontSize(texts, style, minSize, measurer) }
}

/**
 * Sizes a single-line text like [TextAutoSize.StepBased], in 1 sp steps from [style]'s size down to [minSize], but for
 * the whole group of [texts] at once: the largest size at which all of them fit the width the text is given.
 * [measurer] measures the others, which the layout scope cannot. Tiles of one size lay their labels out in the same
 * width, so each arrives at the same size, however short its own label is. Longer labels, such as Charge saved card,
 * shrink every label rather than lose their end. The size is kept for the last width and density, which every tile of
 * the group shares; layout runs on the main thread only.
 */
private class SharedFontSize(
    private val texts: List<String>,
    private val style: TextStyle,
    private val minSize: TextUnit,
    private val measurer: TextMeasurer,
) : TextAutoSize {
    private var last: Pair<Triple<Int, Float, Float>, TextUnit>? = null

    override fun equals(other: Any?): Boolean =
        other is SharedFontSize && texts == other.texts && style == other.style && minSize == other.minSize &&
            measurer == other.measurer

    override fun hashCode(): Int = listOf(texts, style, minSize, measurer).hashCode()

    override fun TextAutoSizeLayoutScope.getFontSize(
        constraints: Constraints,
        text: AnnotatedString,
    ): TextUnit {
        val key = Triple(constraints.maxWidth, density, fontScale)
        last?.takeIf { it.first == key }?.let { return it.second }
        return largestFitting(constraints.maxWidth).also { last = key to it }
    }

    private fun TextAutoSizeLayoutScope.largestFitting(maxWidth: Int): TextUnit {
        var size = style.fontSize.value
        while (size > minSize.value) {
            val sized = style.copy(fontSize = size.sp)
            val fits = texts.all { measurer.measure(it, sized, softWrap = false, maxLines = 1, density = this).size.width <= maxWidth }
            if (fits) return size.sp
            size -= 1f
        }
        return minSize
    }
}

/** The smallest a Home tile label shrinks to before it is ellipsized. */
private val MIN_TILE_LABEL_SIZE = 12.sp

/** The smallest a payment tile's title shrinks to, for a long translation or large text, before it is ellipsized. */
private val MIN_PAYMENT_TITLE_SIZE = 16.sp

/** The final Home row keeps History before Reconciliation at every text size. */
@Composable
private fun ColumnScope.HistoryTiles(
    history: String,
    reconciliation: String,
    labels: TextAutoSize,
    onOpen: (Route) -> Unit,
) {
    Row(Modifier.fillMaxWidth().weight(0.7f), horizontalArrangement = Arrangement.spacedBy(LocalDimens.current.spacing)) {
        HomeTile(
            Icons.AutoMirrored.Filled.ReceiptLong,
            history,
            labels,
            onClick = { onOpen(Route.History) },
            modifier = Modifier.testTag("history"),
        )
        HomeTile(
            Icons.Default.Balance,
            reconciliation,
            labels,
            onClick = { onOpen(Route.Reconciliation) },
            modifier = Modifier.testTag("reconciliation"),
        )
    }
}
