package io.github.astiskala.minimpos.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import io.github.astiskala.minimpos.app.R
import io.github.astiskala.minimpos.core.shopper.ShopperReferences

/**
 * Asks the operator to confirm [message] before an action such as deleting. [destructive] shows the confirm button in
 * the error colour. Dismissing or the [dismissLabel] button ("Cancel" when null, which reads badly when the action
 * itself is a cancellation) calls [onDismiss]; the caller hides the dialog in both callbacks.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismissLabel: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirm")) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel ?: stringResource(R.string.action_cancel)) } },
    )
}

/** Confirms permanent history loss, adding loss-of-recovery details when [unfinished] operations exist. */
@Composable
fun HistorySwitchDialog(
    unfinished: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) = ConfirmDialog(
    title = stringResource(R.string.history_switch_title),
    message =
        stringResource(R.string.history_switch_message) +
            if (unfinished) "\n\n" + stringResource(R.string.history_switch_unfinished) else "",
    confirmLabel = stringResource(R.string.history_switch_confirm),
    onConfirm = onConfirm,
    onDismiss = onDismiss,
    destructive = true,
)

/**
 * A one-field input dialog (e.g. an email address to send a receipt to). [onConfirm] gets the trimmed text, and is
 * only possible while [validate] accepts it.
 */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initial: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    validate: (String) -> Boolean = { it.isNotBlank() },
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                // The keyboard can hide the dialog's buttons on small screens, so its Done key confirms too.
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (validate(value)) onConfirm(value.trim()) }),
                modifier = Modifier.fillMaxWidth().testTag("dialogInput"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }, enabled = validate(value), modifier = Modifier.testTag("dialogConfirm")) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Asks for the address to email a receipt to, starting with [initial] (such as the email captured at checkout).
 * [onSend] gets a valid, trimmed address.
 */
@Composable
@NonRestartableComposable
fun EmailReceiptDialog(
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
    initial: String = "",
) = TextInputDialog(
    title = stringResource(R.string.result_email),
    label = stringResource(R.string.checkout_email),
    confirmLabel = stringResource(R.string.action_send),
    onConfirm = onSend,
    onDismiss = onDismiss,
    initial = initial,
    keyboardType = KeyboardType.Email,
    validate = ShopperReferences::isValidEmail,
)
