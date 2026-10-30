package app.minimpos.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.minimpos.app.ui.theme.IconSize
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.app.ui.theme.LocalStatusColors
import app.minimpos.app.ui.theme.Spacing

/**
 * The upper-case title of a group of rows or fields, the first heading level inside a screen and a heading for screen
 * readers. [inset] indents it like list rows; turn it off inside a [ScreenColumn], which already pads its content.
 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    inset: Boolean = true,
) {
    val dimens = LocalDimens.current
    val compact = dimens.compact
    val horizontal = if (inset) dimens.rowHorizontalPadding else 0.dp
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            modifier
                .padding(
                    start = horizontal,
                    end = horizontal,
                    top = if (compact) Spacing.Medium else Spacing.Section,
                    bottom = if (compact) Spacing.XSmall else Spacing.Small,
                ).semantics { heading() },
    )
}

/** The title of a bottom sheet, in the app bar's title style; a heading for screen readers. */
@Composable
fun SheetTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = LocalDimens.current.titleStyle,
        modifier = modifier.padding(horizontal = LocalDimens.current.rowHorizontalPadding).semantics { heading() },
    )
}

/**
 * The sentence-case title of a block inside a section, such as a step, a card or a group of choices: the second
 * heading level, and a heading for screen readers. [inset] indents it like list rows.
 */
@Composable
fun Subheading(
    text: String,
    modifier: Modifier = Modifier,
    inset: Boolean = false,
) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier =
            modifier
                .padding(horizontal = if (inset) LocalDimens.current.rowHorizontalPadding else 0.dp)
                .semantics { heading() },
    )
}

/**
 * Explains what a screen, section or action does, in a sentence or two: muted body text, start-aligned unless
 * [centered] under a centred amount, and one size smaller on compact screens. Use [Notice] for a requirement or risk
 * the operator must act on, and [OutcomeNote] only under an [OutcomeHeader]. [inset] indents it like list rows.
 */
@Composable
fun Description(
    text: String,
    modifier: Modifier = Modifier,
    inset: Boolean = false,
    centered: Boolean = false,
) {
    Text(
        text,
        style = explanationStyle(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        modifier = modifier.fillMaxWidth().padding(horizontal = if (inset) LocalDimens.current.rowHorizontalPadding else 0.dp),
    )
}

/** How much a [Notice] asks of the operator: its icon and tint. */
enum class NoticeKind {
    /** A requirement or consequence to keep in mind, on a grey panel. */
    INFO,

    /** A risk or a conflict to resolve, on an orange panel. */
    WARNING,
}

/**
 * A requirement, consequence or risk the operator must act on, on a tinted panel with an icon; screen readers read it
 * as one item. Use [BlockedReason] instead for why the action above it cannot run. [inset] indents it like list rows.
 */
@Composable
fun Notice(
    text: String,
    modifier: Modifier = Modifier,
    kind: NoticeKind = NoticeKind.INFO,
    inset: Boolean = false,
) {
    val dimens = LocalDimens.current
    val status = LocalStatusColors.current
    val warning = kind == NoticeKind.WARNING
    Surface(
        color = if (warning) status.warningContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier =
            modifier
                .padding(horizontal = if (inset) dimens.rowHorizontalPadding else 0.dp)
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {},
    ) {
        Row(Modifier.padding(if (dimens.compact) Spacing.Small else dimens.cardPadding)) {
            Icon(
                if (warning) Icons.Outlined.WarningAmber else Icons.Outlined.Info,
                contentDescription = null,
                tint = if (warning) status.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(if (dimens.compact) IconSize.Small else IconSize.Inline),
            )
            Spacer(Modifier.width(dimens.spacing))
            Text(text, style = explanationStyle(), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        }
    }
}

/** Explanations' text: body text, a size smaller on compact screens such as the AMS1. */
@Composable
@ReadOnlyComposable
private fun explanationStyle(): TextStyle =
    if (LocalDimens.current.compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
