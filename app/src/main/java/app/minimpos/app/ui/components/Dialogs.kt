package app.minimpos.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.minimpos.app.R
import app.minimpos.app.terminal.SharedKeyOffer
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.core.shopper.ShopperReferences

/** Scrollable choices under [title]; the caller closes the dialog before running the chosen action. */
@Composable
fun ActionOptionsDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LocalDimens.current.spacing),
                content = content,
            )
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

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
        text = { Text(message, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors =
                    if (destructive) {
                        ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
                modifier = Modifier.testTag("confirm"),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel ?: stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Reports how an action went: [message] under a success or error badge ([isError]) and [title], scrolling on small
 * screens. Optional [details] appear as spaced bullets with hanging indents. The message is tagged [tag] and the OK
 * button `[tag]Ok`; both OK and dismissing call [onDismiss].
 */
@Composable
fun ResultDialog(
    title: String,
    message: String,
    isError: Boolean,
    tag: String,
    onDismiss: () -> Unit,
    details: List<String> = emptyList(),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { StatusBadge(if (isError) StatusKind.ERROR else StatusKind.SUCCESS, size = 40.dp) },
        title = { Text(title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()).semantics(mergeDescendants = true) {}.testTag(tag),
                verticalArrangement = Arrangement.spacedBy(LocalDimens.current.spacing),
            ) {
                Text(message)
                details.forEach { detail ->
                    Row(horizontalArrangement = Arrangement.spacedBy(LocalDimens.current.spacing)) {
                        Text("•")
                        Text(detail, Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("${tag}Ok")) { Text(stringResource(R.string.action_ok)) }
        },
    )
}

/**
 * Reports a neutral answer, such as nothing found, without a success or error badge: [title], [message] (tagged [tag],
 * scrolling on small screens) and OK (tagged `[tag]Ok`); both OK and dismissing call [onDismiss].
 */
@Composable
fun InfoDialog(
    title: String,
    message: String,
    tag: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message, Modifier.verticalScroll(rememberScrollState()).testTag(tag)) },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("${tag}Ok")) { Text(stringResource(R.string.action_ok)) }
        },
    )
}

/** Confirms [offer]'s exact terminal and environment without exposing secrets; body scrolls on small screens. */
@Composable
fun SharedKeyDialog(
    offer: SharedKeyOffer,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val label = stringResource(if (offer.resume) R.string.settings_resume_shared_key else R.string.settings_create_shared_key)
    val message =
        stringResource(
            if (offer.resume) R.string.settings_shared_key_resume_confirm else R.string.settings_shared_key_confirm,
            offer.poiId,
            offer.environment.name,
        ) + if (offer.replacesLocalKey) "\n\n" + stringResource(R.string.settings_shared_key_replace) else ""
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = { Text(message, Modifier.verticalScroll(rememberScrollState()).testTag("sharedKeyConfirmation")) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirmSharedKey")) { Text(label) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
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
 * only possible while [validate] accepts it. The typed text survives rotation, so it is never used for secrets.
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
    var value by rememberSaveable { mutableStateOf(initial) }
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
