package app.minimpos.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.minimpos.app.ui.theme.Dimens
import app.minimpos.app.ui.theme.IconSize
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.app.ui.theme.LocalStatusColors
import app.minimpos.app.ui.theme.Spacing

/**
 * The top of result and detail screens: the outcome's badge and [title], and the [amount], followed by [content] such
 * as why a payment failed. [titleTag] tags the title for tests. On height-limited screens ([Dimens.compact]) a smaller
 * badge sits beside the title and amount instead of above them, which leaves the receipt actions in view on the AMS1.
 */
@Composable
fun OutcomeHeader(
    kind: StatusKind,
    title: String,
    amount: String,
    modifier: Modifier = Modifier,
    titleTag: String? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val dimens = LocalDimens.current
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dimens.spacing),
    ) {
        val titleModifier = (if (titleTag != null) Modifier.testTag(titleTag) else Modifier).semantics { heading() }
        val amountModifier = if (titleTag != null) Modifier.testTag("${titleTag}Amount") else Modifier
        if (dimens.compact) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Medium, Alignment.CenterHorizontally),
            ) {
                StatusBadge(kind, size = IconSize.Large)
                Column(Modifier.weight(1f, fill = false)) {
                    Text(title, style = dimens.outcomeStyle, modifier = titleModifier)
                    Text(amount, style = dimens.amountStyle, modifier = amountModifier)
                }
            }
        } else {
            StatusBadge(kind)
            Text(title, style = dimens.outcomeStyle, textAlign = TextAlign.Center, modifier = titleModifier)
            Text(amount, style = dimens.amountStyle, textAlign = TextAlign.Center, modifier = amountModifier)
        }
        content()
    }
}

/** Explanatory text under an [OutcomeHeader], such as the terminal's message, centred and muted. */
@Composable
fun OutcomeNote(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * What a screen shows while the terminal handles a transaction: the [amount] (when known), a spinner, [message] and
 * any actions in [content] (such as cancelling) under it, centred in the space [modifier] gives it and scrolling when
 * large text makes it taller. Screen readers announce [message] whenever it changes, such as to "Canceling…".
 */
@Composable
fun ProcessingContent(
    amount: String?,
    message: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val dimens = LocalDimens.current
    CenteredScrollColumn(
        modifier.fillMaxSize().padding(dimens.screenPadding * 2),
        verticalArrangement = Arrangement.spacedBy(dimens.spacing * 2, Alignment.CenterVertically),
    ) {
        amount?.let { Text(it, style = dimens.amountStyle, textAlign = TextAlign.Center) }
        CircularProgressIndicator(Modifier.size(dimens.progressSize).testTag("progress"), strokeWidth = 5.dp)
        Text(
            message,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        content()
    }
}

/**
 * A sale or refund in a list: [title] and [subtitle] on the left, the [amount] and its [status] (coloured by [kind])
 * on the right. [contentPadding] is the list's usual row padding unless the row sits inside a padded container.
 */
@Composable
fun TransactionRow(
    title: String,
    subtitle: String,
    amount: String,
    status: String,
    kind: StatusKind,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues =
        PaddingValues(horizontal = LocalDimens.current.rowHorizontalPadding, vertical = LocalDimens.current.rowPadding),
) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(Spacing.Small))
        Column(horizontalAlignment = Alignment.End) {
            Text(amount, style = MaterialTheme.typography.titleMedium)
            // The outcome's glyph beside its word, so the status does not rely on colour alone.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.XSmall)) {
                Icon(kind.icon(), contentDescription = null, tint = kind.color(), modifier = Modifier.size(IconSize.Small))
                Text(status, style = MaterialTheme.typography.labelSmall, color = kind.color())
            }
        }
    }
}

/** The colour of text and badges for this kind of outcome. */
@Composable
@ReadOnlyComposable
fun StatusKind.color(): Color =
    LocalStatusColors.current.let { colors ->
        when (this) {
            StatusKind.SUCCESS -> colors.success
            StatusKind.WARNING -> colors.warning
            StatusKind.ERROR -> colors.error
        }
    }

/** The glyph of this kind of outcome, on badges and beside statuses in lists. */
fun StatusKind.icon(): ImageVector =
    when (this) {
        StatusKind.SUCCESS -> Icons.Default.Check
        StatusKind.WARNING -> Icons.Default.PriorityHigh
        StatusKind.ERROR -> Icons.Default.Close
    }

/** How an outcome is shown: colour and icon. */
enum class StatusKind {
    /** Green with a tick: approved or accepted. */
    SUCCESS,

    /** Orange with an exclamation mark: unknown or needs attention. */
    WARNING,

    /** Red with a cross: declined, cancelled or failed. */
    ERROR,
}

/** The round outcome badge at the top of result screens. */
@Composable
fun StatusBadge(
    kind: StatusKind,
    modifier: Modifier = Modifier,
    size: Dp = LocalDimens.current.badgeSize,
) {
    Box(
        modifier = modifier.size(size).background(kind.color(), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(kind.icon(), contentDescription = null, tint = LocalStatusColors.current.onStatus, modifier = Modifier.size(size * 0.55f))
    }
}
