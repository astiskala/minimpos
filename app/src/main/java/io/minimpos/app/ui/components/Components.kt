package io.minimpos.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.minimpos.app.R
import io.minimpos.app.ui.theme.LocalDimens
import io.minimpos.app.ui.theme.LocalStatusColors

/**
 * The app's screen frame. The content area ends above the keyboard, so focused fields scroll into view; [bottomBar]
 * (usually [BottomActions]) keeps the main action reachable without scrolling and steps aside while typing.
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
    content: @Composable (PaddingValues) -> Unit,
) {
    val dimens = LocalDimens.current
    val typing = WindowInsets.isImeVisible
    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                CenterAlignedTopAppBar(
                    title = {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = dimens.titleStyle)
                    },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                            }
                        }
                    },
                    actions = actions,
                    expandedHeight = dimens.topBarHeight,
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
                ModeBanner()
                HorizontalDivider()
            }
        },
        snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } },
        bottomBar = { if (!typing) bottomBar() },
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime),
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
    Surface(shadowElevation = 8.dp, modifier = modifier) {
        Box(Modifier.fillMaxWidth().navigationBarsPadding(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = dimens.screenPadding, vertical = dimens.spacing),
                verticalArrangement = Arrangement.spacedBy(dimens.spacing),
                content = content,
            )
        }
    }
}

/** The upper-case title of a group of rows in a list. */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    val compact = LocalDimens.current.compact
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = if (compact) 12.dp else 20.dp, bottom = if (compact) 4.dp else 8.dp),
    )
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
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = if (compact) 3.dp else 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = style, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
        Text(
            value,
            style = style,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = if (compact) 180.dp else 240.dp),
        )
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
        modifier = modifier.fillMaxSize().padding(if (compact) 16.dp else 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(if (compact) 40.dp else 56.dp),
        )
        Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        content?.let {
            Spacer(Modifier.height(24.dp))
            it()
        }
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

/** Shows a result message briefly under an action button. */
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
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
        textAlign = TextAlign.Center,
    )
}
