package app.minimpos.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable

/**
 * A text field's trailing icon while [isError], or null otherwise: with the red outline and the supporting text that
 * explains the fix, the error does not rely on colour alone. The field tints it in the error colour. Fields whose
 * trailing slot holds an action, such as a scanner, keep the action instead.
 */
fun fieldErrorIcon(isError: Boolean): (@Composable () -> Unit)? =
    if (isError) {
        { Icon(Icons.Default.Error, contentDescription = null) }
    } else {
        null
    }
