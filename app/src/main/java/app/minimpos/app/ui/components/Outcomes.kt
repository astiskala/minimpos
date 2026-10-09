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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.app.ui.theme.LocalStatusColors

/**
 * The top of result and detail screens: the outcome's badge and [title], and the [amount], followed by [content] such
 * as why a payment failed. [titleTag] tags the title for tests.
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
        StatusBadge(kind)
        Text(
            title,
            style = dimens.outcomeStyle,
            textAlign = TextAlign.Center,
            modifier = if (titleTag != null) Modifier.testTag(titleTag) else Modifier,
        )
        Text(amount, style = dimens.amountStyle, textAlign = TextAlign.Center)
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
 * any actions in [content] (such as cancelling) under it, centred in the space [modifier] gives it.
 */
@Composable
fun ProcessingContent(
    amount: String?,
    message: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val dimens = LocalDimens.current
    Column(
        modifier.fillMaxSize().padding(dimens.screenPadding * 2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dimens.spacing * 2, Alignment.CenterVertically),
    ) {
        amount?.let { Text(it, style = dimens.amountStyle, textAlign = TextAlign.Center) }
        CircularProgressIndicator(Modifier.size(dimens.progressSize).testTag("progress"), strokeWidth = 5.dp)
        Text(message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
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
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = LocalDimens.current.rowPadding),
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
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(amount, style = MaterialTheme.typography.titleMedium)
            Text(status, style = MaterialTheme.typography.labelSmall, color = kind.color())
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
    val icon =
        when (kind) {
            StatusKind.SUCCESS -> Icons.Default.Check
            StatusKind.WARNING -> Icons.Default.PriorityHigh
            StatusKind.ERROR -> Icons.Default.Close
        }
    Box(
        modifier = modifier.size(size).background(kind.color(), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
    }
}
