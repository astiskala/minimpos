package app.minimpos.app.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.minimpos.app.R
import app.minimpos.app.ui.theme.IconSize
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.app.ui.theme.MinTouchTarget
import app.minimpos.app.ui.theme.Spacing

/**
 * A screen's main action: full width, at least the theme's button height, with an optional leading [icon]. While
 * [loading] it shows a spinner and cannot be pressed again.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    color: Color = MaterialTheme.colorScheme.primary,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.fillMaxWidth().heightIn(min = LocalDimens.current.buttonHeight),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = color),
    ) {
        ButtonContent(text, icon, if (loading) MaterialTheme.colorScheme.onPrimary else null)
    }
}

/** An outlined, full-width action with an optional leading [icon]; like [PrimaryButton] it shows a spinner while [loading]. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    SecondaryButton(onClick, modifier, enabled = enabled && !loading) {
        ButtonContent(text, icon, if (loading) MaterialTheme.colorScheme.primary else null)
    }
}

/**
 * An outlined full-width action with custom [content], using the same size and shape as the text overload.
 * The slot supports branded wallet artwork without duplicating the button frame. [enabled] controls activation.
 */
@Composable
fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = LocalDimens.current.secondaryButtonHeight),
        shape = MaterialTheme.shapes.medium,
        content = content,
    )
}

/**
 * A lesser full-width action as text, such as showing the receipt, with the height of a [SecondaryButton] so every
 * action is as easy to hit, and an optional leading [icon]. [destructive] shows it in the error colour, as for removing
 * something saved; like [PrimaryButton] it shows a spinner while [loading].
 */
@Composable
fun TertiaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    icon: ImageVector? = null,
    loading: Boolean = false,
) {
    val content = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    TextButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.fillMaxWidth().heightIn(min = LocalDimens.current.secondaryButtonHeight),
        shape = MaterialTheme.shapes.medium,
        colors =
            if (destructive) {
                ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.textButtonColors()
            },
    ) {
        ButtonContent(text, icon, spinner = content.takeIf { loading })
    }
}

/**
 * A secondary action that keeps its natural width for banners and inline controls. The drawn height is at least
 * [MinTouchTarget]; shape and label typography match the full-width buttons. [enabled] controls activation.
 */
@Composable
fun CompactSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    CompactSecondaryButton(onClick, modifier, enabled) {
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/**
 * A compact outlined frame for custom [content], such as a cart count. Callers may allocate extra width or height
 * through [modifier]; [contentPadding] can preserve a dense chip's established layout. [enabled] controls activation.
 */
@Composable
fun CompactSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = Spacing.Medium, vertical = Spacing.Small),
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = MinTouchTarget),
        shape = MaterialTheme.shapes.medium,
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * A lesser inline action with the same touch size and label role as [CompactSecondaryButton], without a border.
 * [destructive] uses the error color for removals. [enabled] controls activation.
 */
@Composable
fun CompactTertiaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    CompactTertiaryButton(onClick, modifier, enabled, destructive) {
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/**
 * An inline text action with custom [content], retaining the standard shape and touch height. [contentPadding] may
 * align an expander's icon with a field edge; labels use the theme's button role. [destructive] marks removals in red.
 */
@Composable
fun CompactTertiaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = Spacing.Medium, vertical = Spacing.Small),
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = MinTouchTarget),
        shape = MaterialTheme.shapes.medium,
        colors =
            if (destructive) {
                ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.textButtonColors()
            },
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * What every button shows: a [spinner] in that colour (while it works) or else the [icon], then the [text], so all
 * kinds of button line up the same way.
 */
@Composable
private fun RowScope.ButtonContent(
    text: String,
    icon: ImageVector?,
    spinner: Color?,
) {
    if (spinner != null) {
        CircularProgressIndicator(modifier = Modifier.size(IconSize.Inline), strokeWidth = 2.dp, color = spinner)
        Spacer(Modifier.width(Spacing.Small))
    } else if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(IconSize.Inline))
        Spacer(Modifier.width(Spacing.Small))
    }
    Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
}

/**
 * − [quantity] + controls, as on the cart and a refund's items; screen readers name [itemName] on both buttons, so a
 * list of steppers stays clear. [onChange] gets the new quantity; decreasing stops at [min] and increasing at [max].
 * [increaseTag] tags the + button for tests.
 */
@Composable
fun QuantityStepper(
    quantity: Int,
    itemName: String,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    min: Int = 0,
    max: Int = Int.MAX_VALUE,
    increaseTag: String? = null,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(quantity - 1) }, enabled = quantity > min) {
            Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.action_decrease_item, itemName))
        }
        // A fixed width, so the buttons stay put when the count reaches two digits.
        Text(
            quantity.toString(),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 28.dp),
        )
        IconButton(
            onClick = { onChange(quantity + 1) },
            enabled = quantity < max,
            modifier = if (increaseTag != null) Modifier.testTag(increaseTag) else Modifier,
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.action_increase_item, itemName))
        }
    }
}
