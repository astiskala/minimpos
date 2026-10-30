package app.minimpos.app.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.minimpos.app.ui.theme.BorderWidth
import app.minimpos.app.ui.theme.IconSize

/**
 * A filter choice, such as a history filter or a product category: [label] outlined like the search field beside it,
 * or, while [selected], tinted green with a tick so the choice does not rely on colour alone. [dropdown] adds a menu
 * arrow after the label, for a chip that opens a menu; [onClick] chooses it.
 */
@Composable
fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dropdown: Boolean = false,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier,
        leadingIcon = if (selected) ({ Icon(Icons.Default.Check, contentDescription = null, Modifier.size(IconSize.Small)) }) else null,
        trailingIcon = if (dropdown) ({ Icon(Icons.Default.ArrowDropDown, contentDescription = null) }) else null,
        colors =
            FilterChipDefaults.filterChipColors(
                labelColor = MaterialTheme.colorScheme.onSurface,
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
                selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        border =
            FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = selected,
                borderColor = MaterialTheme.colorScheme.outline,
                selectedBorderColor = MaterialTheme.colorScheme.primary,
                borderWidth = BorderWidth,
                selectedBorderWidth = BorderWidth,
            ),
    )
}
