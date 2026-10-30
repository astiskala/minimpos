package app.minimpos.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.minimpos.app.R
import app.minimpos.app.ui.theme.Elevation
import app.minimpos.app.ui.theme.IconSize
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.app.ui.theme.LocalStatusColors
import app.minimpos.app.ui.theme.MinTouchTarget
import app.minimpos.app.ui.theme.Spacing

/**
 * Shared chrome above one adaptive scene's [content]. Navigation 3 retains ownership of the content and its transitions.
 * [title] names the section; [onBack] leaves it, while the content retains its existing system Back handling.
 */
@Composable
fun NavigationFrame(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        ScreenTopBar(title, onBack) {}
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenTopBar(
    title: String,
    onBack: (() -> Unit)?,
    content: @Composable RowScope.() -> Unit,
) {
    val dimens = LocalDimens.current
    Column {
        CenterAlignedTopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = dimens.titleStyle) },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            },
            actions = content,
            expandedHeight = dimens.topBarHeight,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
        ModeBanner()
        HorizontalDivider()
    }
}

/**
 * The app's screen frame. The content area ends above the keyboard, so focused fields scroll into view; [bottomBar]
 * (usually [BottomActions]) keeps the main action reachable without scrolling and steps aside while typing.
 * [header] lets panes retain headings and actions beneath a shared [NavigationFrame] without repeating system insets.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MiniScaffold(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
    bottomBar: @Composable () -> Unit = {},
    header: ScreenHeader = ScreenHeader.FULL,
    content: @Composable (PaddingValues) -> Unit,
) {
    val dimens = LocalDimens.current
    val typing = WindowInsets.isImeVisible
    Scaffold(
        modifier = modifier,
        topBar = {
            if (header == ScreenHeader.FULL) {
                ScreenTopBar(title, onBack, actions)
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = dimens.screenPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (header == ScreenHeader.DETAIL_PANE) {
                        Text(
                            title,
                            style = dimens.titleStyle,
                            modifier = Modifier.weight(1f).padding(vertical = Spacing.Small).semantics { heading() },
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    actions()
                }
            }
        },
        snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } },
        bottomBar = { if (!typing) bottomBar() },
        contentWindowInsets =
            (
                if (header == ScreenHeader.FULL) {
                    ScaffoldDefaults.contentWindowInsets
                } else {
                    ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                }
            ).union(WindowInsets.ime),
        content = content,
    )
}

/** Actions pinned under a screen's content (see [MiniScaffold]). */
@Composable
fun BottomActions(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dimens = LocalDimens.current
    Surface(shadowElevation = Elevation.ActionBar, modifier = modifier) {
        Box(Modifier.fillMaxWidth().navigationBarsPadding(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .widthIn(
                        max = dimens.contentMaxWidth,
                    ).fillMaxWidth()
                    .padding(horizontal = dimens.screenPadding, vertical = dimens.spacing),
                verticalArrangement = Arrangement.spacedBy(dimens.spacing),
                content = content,
            )
        }
    }
}

/**
 * A column centred in the space [modifier] gives it, which scrolls once its content is taller (large text, a landscape
 * phone) instead of cutting it off.
 */
@Composable
fun CenteredScrollColumn(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Center,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/**
 * A screen's scrolling body inside [MiniScaffold]'s [padding]: centred and no wider than the content width on large
 * windows, its children spaced apart and aligned by [horizontalAlignment].
 */
@Composable
fun ScreenColumn(
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dimens = LocalDimens.current
    Column(
        modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(dimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.widthIn(max = dimens.contentMaxWidth),
            horizontalAlignment = horizontalAlignment,
            verticalArrangement = Arrangement.spacedBy(dimens.spacing),
            content = content,
        )
    }
}

/** A label on the left and its value on the right, as on a receipt; nothing is shown when [value] is null or blank. */
@Composable
fun LabeledValue(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    emphasize: Boolean = false,
) {
    if (value.isNullOrBlank()) return
    val compact = LocalDimens.current.compact
    // Smaller on narrow screens, so labels such as "Reference" are not split mid-word by long values.
    val style = if (compact) MaterialTheme.typography.bodyMedium else LocalTextStyle.current
    // The label keeps 104 dp of the row (the value may always have half), so a long value wraps at its own words and the
    // label wraps only between words ("Offline processing"), never inside one.
    BoxWithConstraints(modifier.fillMaxWidth().padding(vertical = if (compact) 3.dp else 4.dp)) {
        val valueMax = (maxWidth - 104.dp).coerceAtLeast(maxWidth * 0.5f)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = style, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(if (compact) Spacing.Small else Spacing.Medium))
            Text(
                value,
                style = style,
                fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(max = valueMax),
            )
        }
    }
}

/** Fills the screen when there is nothing to list yet, with an optional [content] action below the message. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    content: (@Composable () -> Unit)? = null,
) {
    val compact = LocalDimens.current.compact
    Column(
        modifier = modifier.fillMaxSize().padding(if (compact) Spacing.Large else Spacing.XXLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(if (compact) IconSize.Large else 56.dp),
        )
        Spacer(Modifier.height(if (compact) Spacing.Small else Spacing.Large))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.Small))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        content?.let {
            Spacer(Modifier.height(Spacing.XLarge))
            it()
        }
    }
}

/**
 * One accessible toggle target for the row; [enabled] false prevents changes.
 * Subtitles use at most [subtitleMaxLines] lines, with ellipsis when limited.
 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    subtitleMaxLines: Int = Int.MAX_VALUE,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(
                min = MinTouchTarget,
            ).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(Spacing.Medium))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A full-width grey panel grouping related content, such as totals or payment details. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(LocalDimens.current.cardPadding), content = content)
    }
}

/**
 * Why the disabled action above cannot run yet, saying what to do, with a warning icon; tagged `blockedReason`. Every
 * action with a known precondition shows it this way rather than failing only after it is pressed. Screen readers
 * announce it when it appears or changes.
 */
@Composable
fun BlockedReason(
    message: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = Spacing.XSmall)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .testTag("blockedReason"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = LocalStatusColors.current.warning, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.Small))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Shows a result message briefly under an action button; screen readers announce it as it appears. */
@Composable
fun ActionMessage(
    message: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalStatusColors.current
    Text(
        message,
        color = if (isError) colors.error else colors.success,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.fillMaxWidth().padding(top = Spacing.XSmall).semantics { liveRegion = LiveRegionMode.Polite },
        textAlign = TextAlign.Center,
    )
}
