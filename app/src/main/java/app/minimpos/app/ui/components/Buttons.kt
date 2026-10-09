package app.minimpos.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.minimpos.app.R
import app.minimpos.app.ui.theme.LocalDimens

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
        ButtonContent(text, icon, if (loading) MaterialTheme.colorScheme.onPrimary else null, spinnerSize = 22.dp)
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
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.fillMaxWidth().heightIn(min = LocalDimens.current.secondaryButtonHeight),
        shape = MaterialTheme.shapes.medium,
    ) {
        ButtonContent(text, icon, if (loading) MaterialTheme.colorScheme.primary else null, spinnerSize = 18.dp)
    }
}

/**
 * A lesser full-width action as text, such as showing the receipt, with the height of a [SecondaryButton] so every
 * action is as easy to hit, and an optional leading [icon]. [destructive] shows it in the error colour, as for removing
 * something saved.
 */
@Composable
fun TertiaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    icon: ImageVector? = null,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = LocalDimens.current.secondaryButtonHeight),
        shape = MaterialTheme.shapes.medium,
        colors =
            if (destructive) {
                ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.textButtonColors()
            },
    ) {
        ButtonContent(text, icon, spinner = null, spinnerSize = 18.dp)
    }
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
    spinnerSize: Dp,
) {
    if (spinner != null) {
        CircularProgressIndicator(modifier = Modifier.size(spinnerSize), strokeWidth = 2.dp, color = spinner)
        Spacer(Modifier.width(12.dp))
    } else if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(text, style = MaterialTheme.typography.labelLarge)
}

/**
 * − [quantity] + controls, as on the cart and a refund's items. [onChange] gets the new quantity; decreasing stops at
 * [min] and increasing at [max]. [increaseTag] tags the + button for tests.
 */
@Composable
fun QuantityStepper(
    quantity: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    min: Int = 0,
    max: Int = Int.MAX_VALUE,
    increaseTag: String? = null,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(quantity - 1) }, enabled = quantity > min) {
            Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.action_decrease))
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
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.action_increase))
        }
    }
}
