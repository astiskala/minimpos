package io.github.astiskala.minimpos.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.astiskala.minimpos.app.R
import io.github.astiskala.minimpos.app.ui.components.LocalAppContainer
import io.github.astiskala.minimpos.app.ui.theme.LocalDimens
import io.github.astiskala.minimpos.app.ui.theme.LocalStatusColors

private fun Modifier.tagged(tag: String?) = if (tag != null) testTag(tag) else this

/**
 * A text setting that keeps its own editing state (so asynchronous DataStore round trips never move the cursor) and
 * commits every valid change straight away, so nothing is lost when leaving the screen.
 */
@Composable
fun SettingTextField(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supporting: String? = null,
    isError: (String) -> Boolean = { false },
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    /** Off for codes (IDs, keys, addresses) that the keyboard must not "correct". */
    autoCorrect: Boolean = true,
    imeAction: ImeAction = ImeAction.Default,
    tag: String? = null,
) {
    val container = LocalAppContainer.current
    var text by remember { mutableStateOf(value) }
    val error = isError(text)
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (LocalDimens.current.compact) 2.dp else 4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                container.userActivity()
                text = it
                if (!isError(it)) onCommit(it)
            },
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            isError = error,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = autoCorrect, imeAction = imeAction),
            modifier = Modifier.fillMaxWidth().tagged(tag),
        )
        supporting?.let {
            FieldNote(
                Icons.Outlined.Info,
                it,
                if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** A line under a field, starting at its edge: an [icon] and then [text], both in [tint]. */
@Composable
private fun FieldNote(
    icon: ImageVector,
    text: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

/** A whole-number setting; [onCommit] only gets values in [range], and others are shown as an error. */
@Composable
fun SettingNumberField(
    label: String,
    value: Int,
    range: IntRange,
    onCommit: (Int) -> Unit,
    supporting: String? = null,
    tag: String? = null,
) = SettingTextField(
    label = label,
    value = value.toString(),
    onCommit = { it.toIntOrNull()?.let(onCommit) },
    supporting = supporting,
    isError = { it.toIntOrNull()?.let { number -> number in range } != true },
    keyboardType = KeyboardType.Number,
    tag = tag,
)

/** An on/off setting as a row that toggles when tapped anywhere. */
@Composable
fun SettingSwitch(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    tag: String? = null,
) {
    Row(
        modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(
            horizontal = 16.dp,
            vertical =
                LocalDimens.current.rowPadding - 2.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.tagged(tag))
    }
}

/** A single-choice setting: shows the current value, opens a radio list dialog. */
@Composable
fun <T : Any> SettingChoice(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    tag: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .clickable {
                open = true
            }.padding(horizontal = 16.dp, vertical = LocalDimens.current.rowPadding)
            .tagged(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                options.firstOrNull { it.first == selected }?.second.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                // Long lists (e.g. the EU's 25 currencies) scroll inside the dialog.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    options.forEach { (value, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = value == selected, onClick = {
                                    onSelect(value)
                                    open = false
                                })
                                .padding(vertical = 10.dp)
                                .tagged(tag?.let { "${it}_$value" }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = value == selected, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** A password field that shows what was typed only on request. */
@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    val container = LocalAppContainer.current
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = {
            container.userActivity()
            onValueChange(it)
        },
        label = { Text(label) },
        // On one line, so a long hint cannot make the field grow when it gets the focus.
        placeholder = placeholder?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }, modifier = Modifier.testTag("secretVisibility")) {
                Icon(
                    if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = stringResource(if (visible) R.string.settings_secret_hide else R.string.settings_secret_show),
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * A secret typed straight into the screen (no dialog whose buttons the keyboard could hide): never displayed once
 * saved. The keyboard's Done key calls [onSubmit], as does the caller's own button.
 */
@Composable
fun SecretField(
    label: String,
    isSet: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
) {
    val colors = LocalStatusColors.current
    val compact = LocalDimens.current.compact
    Column(modifier.fillMaxWidth()) {
        PasswordField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            onDone = onSubmit,
            placeholder = stringResource(if (isSet) R.string.settings_secret_replace else R.string.settings_secret_enter),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = if (compact) 2.dp else 4.dp).tagged(tag),
        )
        // Whether it is saved, on its own line at the screen's text edge (not indented like a field's supporting text).
        FieldNote(
            if (isSet) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
            stringResource(if (isSet) R.string.settings_secret_saved else R.string.settings_secret_not_saved),
            if (isSet) colors.success else colors.error,
            Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = if (compact) 8.dp else 12.dp),
        )
    }
}

/** A secret (password): never displayed, only set, replaced or cleared through a dialog. */
@Composable
fun SettingSecret(
    title: String,
    isSet: Boolean,
    onSet: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .clickable {
                open = true
            }.padding(horizontal = 16.dp, vertical = LocalDimens.current.rowPadding)
            .tagged(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(if (isSet) R.string.settings_secret_set else R.string.settings_secret_not_set),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSet) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
    }
    if (open) {
        var value by remember { mutableStateOf("") }

        fun save() {
            if (value.isEmpty()) return
            onSet(value)
            open = false
        }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                // The keyboard's Done key saves too: on small screens it can hide the dialog's buttons.
                PasswordField(
                    value = value,
                    onValueChange = { value = it },
                    label = stringResource(R.string.settings_secret_new),
                    onDone = ::save,
                    modifier = Modifier.testTag("secretInput"),
                )
            },
            confirmButton = {
                TextButton(onClick = ::save, enabled = value.isNotEmpty(), modifier = Modifier.testTag("secretSave")) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                Row {
                    if (isSet) {
                        TextButton(onClick = {
                            onClear()
                            open = false
                        }) { Text(stringResource(R.string.action_clear), color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        )
    }
}

/** Rarely needed settings, collapsed until opened. */
@Composable
fun AdvancedSettings(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) = CollapsibleSettings(stringResource(R.string.settings_advanced), tag = "advanced", modifier = modifier) { content() }

/**
 * Settings under an upper-case [title] that opens and closes them; they start open when [initiallyOpen]. The title is
 * tagged [tag] for tests.
 */
@Composable
fun CollapsibleSettings(
    title: String,
    tag: String,
    modifier: Modifier = Modifier,
    initiallyOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    open = !open
                }.padding(horizontal = 16.dp, vertical = LocalDimens.current.rowPadding)
                .testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (open) content()
    }
}

/** A row that opens a settings section, with its current value as [subtitle]. */
@Composable
fun SettingNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
) {
    val dimens = LocalDimens.current
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    onClick = onClick,
                ).padding(horizontal = 16.dp, vertical = dimens.rowPadding + 2.dp)
                .tagged(tag),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(Modifier.padding(start = 56.dp))
    }
}

/** Small explanatory text under a setting. */
@Composable
fun SettingNote(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
