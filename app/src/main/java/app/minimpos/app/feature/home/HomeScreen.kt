package app.minimpos.app.feature.home

import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
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
import app.minimpos.app.feature.settings.CapabilityCatalog
import app.minimpos.app.feature.settings.SettingsSections
import app.minimpos.app.feature.text
import app.minimpos.app.history.attention
import app.minimpos.app.terminal.TerminalConnection
import app.minimpos.app.ui.components.CompactSecondaryButton
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.MiniScaffold
import app.minimpos.app.ui.components.StatusKind
import app.minimpos.app.ui.components.color
import app.minimpos.app.ui.components.currentSetupGate
import app.minimpos.app.ui.components.icon
import app.minimpos.app.ui.components.openInBrowser
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import app.minimpos.app.ui.theme.IconSize
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.app.ui.theme.LocalStatusColors
import app.minimpos.app.ui.theme.Opacity
import app.minimpos.app.ui.theme.Spacing
import app.minimpos.app.update.UpdateCheck

/**
 * The home screen: one large green tile, New sale, then only the tiles this setup can use ([HomeTiles]), two per row.
 * Those that start a payment are tinted green; Refund, History and Reconciliation follow in grey. The tiles share the
 * height up to their largest size and stay at the top, so everything fits a 4" terminal screen without scrolling and
 * a taller one keeps the rest white. Only when the tiles would get less room than their content needs (a landscape
 * phone, large text) does Home scroll instead.
 *
 * While payments still need setting up, the large tile finishes that instead and New sale joins the smaller tiles;
 * every tile that reaches Adyen or a terminal is faded and explains instead of opening. History marks how many
 * records need attention and then opens on them.
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
    val remote by container.capabilityDiscovery.state.collectAsStateWithLifecycle()
    val wallets by container.walletDiscovery.state.collectAsStateWithLifecycle()
    val update by container.update.state.collectAsStateWithLifecycle()
    val attention by remember(container) { container.history.attention() }.collectAsStateWithLifecycle(0)
    val gate = currentSetupGate()
    // A failed check is retried whenever Home is shown, so its warning clears once the terminal answers again.
    LaunchedEffect(Unit) { container.terminalStatus.recheckIfFailed() }
    // Every tile but History and the admin areas reaches Adyen or a terminal, so it asks to finish setup first.
    val open = { route: Route -> if (route.requiresSetup) gate.run { navigator.push(route) } else navigator.push(route) }
    val catalog = CapabilityCatalog(settings, terminal, remote, wallets, container.currency(settings).code)
    val tiles = HomeTiles.of(settings, catalog, gate.pending)

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
            val height = (maxHeight - dimens.screenPadding * 2).coerceAtLeast(0.dp)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(dimens.screenPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.widthIn(max = dimens.contentMaxWidth).fillOrGrow(height),
                    verticalArrangement = Arrangement.spacedBy(dimens.spacing),
                ) {
                    // Wherever payments go but the simulator: this terminal, one on the network or in the cloud, or Tap to Pay.
                    val failed = (terminal.connection as? TerminalConnection.Failed)?.takeIf { !gate.pending }
                    if (failed != null && terminal.loaded && terminal.mode != TerminalMode.SIMULATOR) {
                        ConnectionFailed(failed) { navigator.push(Route.SettingsSection(SettingsSections.TERMINAL)) }
                    }
                    (update as? UpdateCheck.Available)?.let { offered ->
                        UpdateCard(offered, container.update::dismiss)
                    }
                    MainTile(gate.pending, gate::openSetup) { open(Route.Sale) }
                    Tiles(tiles, gate.pending, attention, onOpen = open)
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
 * Why payments are not working although they are set up: the terminal did not answer. Tagged `terminalSetup`;
 * [onOpenSettings] opens Terminal settings.
 */
@Composable
private fun ConnectionFailed(
    connection: TerminalConnection.Failed,
    onOpenSettings: () -> Unit,
) {
    val colors = LocalStatusColors.current
    val dimens = LocalDimens.current
    Surface(
        onClick = onOpenSettings,
        color = colors.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("terminalSetup"),
    ) {
        Row(Modifier.padding(dimens.cardPadding), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = colors.error)
            Spacer(Modifier.width(dimens.spacing))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_connection_failed), style = MaterialTheme.typography.titleSmall)
                Text(
                    connection.failure.text(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
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

/** The large tile: New sale, or finishing the setup while payments still need it ([setupPending]). */
@Composable
private fun ColumnScope.MainTile(
    setupPending: Boolean,
    onSetup: () -> Unit,
    onSale: () -> Unit,
) {
    if (setupPending) {
        LargeTile(
            Icons.Default.Key,
            stringResource(R.string.home_setup),
            stringResource(R.string.home_setup_text),
            "terminalSetup",
            onSetup,
        )
    } else {
        LargeTile(
            Icons.Default.PointOfSale,
            stringResource(R.string.home_new_sale),
            stringResource(R.string.home_new_sale_hint),
            "newSale",
            onSale,
        )
    }
}

/**
 * Home's one large green tile, the main thing to do now (a sale, or finishing the setup): the icon beside the title
 * and hint, the same on every screen. The title stays on one line, shrinking for a long translation or large text; the
 * hint takes at most two.
 */
@Composable
private fun ColumnScope.LargeTile(
    icon: ImageVector,
    title: String,
    hint: String,
    tag: String,
    onClick: () -> Unit,
) {
    val dimens = LocalDimens.current
    val titleStyle = MaterialTheme.typography.titleLarge
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = MaterialTheme.shapes.large,
        modifier =
            Modifier
                .fillMaxWidth()
                .weight(
                    LARGE_TILE_WEIGHT,
                    fill = false,
                ).heightIn(max = dimens.homeHeroMaxHeight)
                .fillMaxHeight()
                .testTag(tag),
    ) {
        Row(
            Modifier.padding(horizontal = dimens.screenPadding + Spacing.Small, vertical = dimens.spacing / 2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(IconSize.Tile))
            Spacer(Modifier.width(dimens.screenPadding))
            // A column sharing its height by weight hands out whole pixels, and can leave this tile one short of its
            // content. The texts then lean into the padding rather than lose the hint's last line.
            Column(Modifier.wrapContentHeight(unbounded = true)) {
                Text(
                    title,
                    style = titleStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(minFontSize = MIN_LARGE_TITLE_SIZE, maxFontSize = titleStyle.fontSize),
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
 * The smaller tiles, two per row with an odd last one spanning its row, zone by zone: those that start a payment,
 * tinted, then those for afterwards. While [setupPending], the tiles that reach Adyen or a terminal are faded.
 * [attention] is how many records History has to show for attention.
 */
@Composable
private fun ColumnScope.Tiles(
    tiles: HomeTiles,
    setupPending: Boolean,
    attention: Int,
    onOpen: (Route) -> Unit,
) {
    val dimens = LocalDimens.current
    val shown = tiles.payment + tiles.afterwards
    val labels = rememberSharedFontSize(shown.map { stringResource(it.label) }, MaterialTheme.typography.titleMedium, MIN_TILE_LABEL_SIZE)
    listOf(tiles.payment to true, tiles.afterwards to false).forEach { (zone, starts) ->
        zone.chunked(2).forEach { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(TILE_WEIGHT, fill = false)
                    .heightIn(max = dimens.homeTileMaxHeight)
                    .fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(dimens.spacing),
            ) {
                row.forEach { tile ->
                    val issues = if (tile == HomeTile.HISTORY) attention else 0
                    val route = if (issues > 0) Route.History(issues = true) else tile.route
                    Tile(
                        tile,
                        labels,
                        TileState(starts, faded = setupPending && route.requiresSetup, alone = row.size == 1, attention = issues),
                    ) { onOpen(route) }
                }
            }
        }
    }
}

/**
 * How a smaller tile is drawn.
 *
 * @property startsPayment Whether it is tinted green, as the tiles that start a payment are.
 * @property faded Whether it only explains for now, as payments still need setting up.
 * @property alone Whether it spans its row.
 * @property attention How many records need attention, shown on History; 0 shows nothing.
 */
private data class TileState(
    val startsPayment: Boolean,
    val faded: Boolean,
    val alone: Boolean,
    val attention: Int,
)

/**
 * A smaller tile: its icon at the top and the label at the bottom, the same on every screen. All of them share
 * [labels], so they show their labels at one size: the largest, up to the style's, at which the longest still fits a
 * half-width tile on a line (Pre-authorization, say, or Reconciliation), so no tile's label looks smaller than another's.
 */
@Composable
private fun RowScope.Tile(
    tile: HomeTile,
    labels: TextAutoSize,
    state: TileState,
    onClick: () -> Unit,
) {
    val dimens = LocalDimens.current
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        color = if (state.startsPayment) colors.primaryContainer else colors.surfaceVariant,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.weight(1f).fillMaxHeight().testTag(tile.tag),
    ) {
        // The vertical padding is the spacing, so the smallest terminal's tile is still no taller than it can spare.
        Column(
            Modifier.alpha(if (state.faded) Opacity.DISABLED else 1f).padding(horizontal = dimens.cardPadding, vertical = dimens.spacing),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(tile.icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(IconSize.Standard))
                if (state.attention > 0) {
                    Spacer(Modifier.weight(1f))
                    AttentionCount(state.attention)
                }
            }
            Row {
                Text(
                    stringResource(tile.label),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = labels,
                    modifier = Modifier.weight(1f),
                )
                // Alone in its row, a tile gives its label the width a half-width tile's has (the gap between two
                // tiles and both their paddings less than half), so the shared size is measured for one width.
                if (state.alone) {
                    Spacer(Modifier.width(dimens.spacing + dimens.cardPadding * 2))
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * The warning glyph and how many records need attention. Screen readers get the count in words ("3 need attention")
 * as text, which the tile reads with its label. Tagged `historyAttention`.
 */
@Composable
private fun AttentionCount(count: Int) {
    val description = pluralStringResource(R.plurals.home_history_attention, count, count)
    Row(
        Modifier.testTag("historyAttention").clearAndSetSemantics { text = AnnotatedString(description) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            StatusKind.WARNING.icon(),
            contentDescription = null,
            tint = StatusKind.WARNING.color(),
            modifier = Modifier.size(IconSize.Small),
        )
        Text(count.toString(), style = MaterialTheme.typography.titleSmall, color = StatusKind.WARNING.color(), maxLines = 1)
    }
}

private val HomeTile.icon: ImageVector
    get() =
        when (this) {
            HomeTile.NEW_SALE -> Icons.Default.PointOfSale
            HomeTile.PRE_AUTH -> Icons.Default.LockClock
            HomeTile.TOKENS -> Icons.Default.CreditCard
            HomeTile.GIFT_CARDS -> Icons.Default.CardGiftcard
            HomeTile.CASHOUT -> Icons.Default.Payments
            HomeTile.REFUND -> Icons.Default.QrCodeScanner
            HomeTile.HISTORY -> Icons.AutoMirrored.Filled.ReceiptLong
            HomeTile.RECONCILIATION -> Icons.Default.Balance
        }

@get:StringRes
private val HomeTile.label: Int
    get() =
        when (this) {
            HomeTile.NEW_SALE -> R.string.home_new_sale
            HomeTile.PRE_AUTH -> R.string.home_pre_auth
            HomeTile.TOKENS -> R.string.home_saved_card
            HomeTile.GIFT_CARDS -> R.string.home_gift_cards
            HomeTile.CASHOUT -> R.string.cashout_title
            HomeTile.REFUND -> R.string.home_refund
            HomeTile.HISTORY -> R.string.home_history
            HomeTile.RECONCILIATION -> R.string.home_reconciliation
        }

private val HomeTile.tag: String
    get() =
        when (this) {
            HomeTile.NEW_SALE -> "newSale"
            HomeTile.PRE_AUTH -> "preAuth"
            HomeTile.TOKENS -> "savedCard"
            HomeTile.GIFT_CARDS -> "giftCards"
            HomeTile.CASHOUT -> "cashout"
            HomeTile.REFUND -> "refund"
            HomeTile.HISTORY -> "history"
            HomeTile.RECONCILIATION -> "reconciliation"
        }

private val HomeTile.route: Route
    get() =
        when (this) {
            HomeTile.NEW_SALE -> Route.Sale
            HomeTile.PRE_AUTH -> Route.PreAuth
            HomeTile.TOKENS -> Route.SavedCard
            HomeTile.GIFT_CARDS -> Route.GiftCards
            HomeTile.CASHOUT -> Route.Cashout
            HomeTile.REFUND -> Route.RefundStart
            HomeTile.HISTORY -> Route.History()
            HomeTile.RECONCILIATION -> Route.Reconciliation
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
 * [measurer] measures the others, which the layout scope cannot. Tiles lay their labels out in the same width, so
 * each arrives at the same size, however short its own label is. Longer labels, such as Pre-authorization, shrink
 * every label rather than lose their end. The size is kept for the last width and density, which every tile of the
 * group shares; layout runs on the main thread only.
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

/** The smallest the large tile's title shrinks to, for a long translation or large text, before it is ellipsized. */
private val MIN_LARGE_TITLE_SIZE = 16.sp

/**
 * The large tile's share of the height beside a row of smaller tiles ([TILE_WEIGHT]). Tiles take their share only up
 * to their largest size: they shrink together on a small screen and stop growing on a tall one, where the space they
 * leave stays under them.
 */
private const val LARGE_TILE_WEIGHT = 1f

/**
 * A row of smaller tiles' share of the height beside the large tile: their smallest heights' ratio (an icon over a
 * label to a title over a two-line hint), so both reach them together and the most tiles still fit a 4" screen under
 * the simulator banner.
 */
private const val TILE_WEIGHT = 0.84f
