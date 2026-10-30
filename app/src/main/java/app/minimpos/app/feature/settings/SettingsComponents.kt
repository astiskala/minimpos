package app.minimpos.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.minimpos.app.R
import app.minimpos.app.ui.components.BlockedReason
import app.minimpos.app.ui.components.CompactTertiaryButton
import app.minimpos.app.ui.components.Description
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.SwitchRow
import app.minimpos.app.ui.theme.IconSize
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.app.ui.theme.LocalStatusColors
import app.minimpos.app.ui.theme.Spacing

private fun Modifier.tagged(tag: String?) = if (tag != null) testTag(tag) else this

/**
 * A text setting that keeps its own editing state (so asynchronous DataStore round trips never move the cursor) and
 * commits every valid change straight away, so nothing is lost when leaving the screen. External saved changes
 * refresh unfocused fields; focused drafts keep their cursor through asynchronous storage updates.
 * The external title matches toggle titles; [description] explains the field before entry, while [supporting] is
 * additional information below it.
 */
@Composable
fun SettingTextField(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    description: String? = null,
    supporting: String? = null,
    isError: (String) -> Boolean = { false },
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    /** Off for codes (IDs, keys, addresses) that the keyboard must not "correct". */
    autoCorrect: Boolean = true,
    imeAction: ImeAction = ImeAction.Default,
    tag: String? = null,
    descriptionTag: String? = null,
) {
    val container = LocalAppContainer.current
    var text by rememberSaveable { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    // The last value this field committed or adopted, so only changes made elsewhere replace a draft, never an invalid one.
    var synced by remember { mutableStateOf(value) }
    LaunchedEffect(value, focused) {
        if (!focused && value != synced) {
            text = value
            synced = value
        }
    }
    val error = isError(text)
    Column(
        modifier.fillMaxWidth().padding(
            horizontal = LocalDimens.current.rowHorizontalPadding,
            vertical = LocalDimens.current.rowPadding - 2.dp,
        ),
    ) {
        SettingFieldHeading(label, description = description, descriptionTag = descriptionTag)
        OutlinedTextField(
            value = text,
            onValueChange = {
                container.userActivity()
                text = it
                if (!isError(it)) {
                    synced = it
                    onCommit(it)
                }
            },
            placeholder = placeholder?.let { { Text(it) } },
            isError = error,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = autoCorrect, imeAction = imeAction),
            modifier =
                Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.semantics { contentDescription = label }.tagged(
                    tag,
                ),
        )
        supporting?.let {
            FieldNote(
                Icons.Outlined.Info,
                it,
                if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                Modifier.padding(top = Spacing.XSmall),
            )
        }
    }
}

/** Field title and primary explanation, preceding entry and styled like a toggle row. */
@Composable
internal fun SettingFieldHeading(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    descriptionTag: String? = null,
) {
    Column(modifier) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = Spacing.XSmall),
        )
        description?.let { Description(it, Modifier.tagged(descriptionTag).padding(bottom = Spacing.XSmall)) }
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
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(IconSize.Small))
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
    description: String? = null,
    supporting: String? = null,
    tag: String? = null,
) = SettingTextField(
    label = label,
    value = value.toString(),
    onCommit = { it.toIntOrNull()?.let(onCommit) },
    description = description,
    supporting = supporting,
    isError = { it.toIntOrNull()?.let { number -> number in range } != true },
    keyboardType = KeyboardType.Number,
    tag = tag,
)

/**
 * An on/off setting as a row that toggles when tapped anywhere: a short [title], then a [subtitle] saying in a sentence
 * or two what it does (the guides hold the details). While [blocked] says what to set up first, the switch shows off and
 * cannot change, with that reason under it; the saved choice is kept for when it applies again.
 */
@Composable
fun SettingSwitch(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    tag: String? = null,
    enabled: Boolean = true,
    blocked: String? = null,
) {
    Column(modifier.padding(horizontal = LocalDimens.current.rowHorizontalPadding, vertical = LocalDimens.current.rowPadding - 2.dp)) {
        SwitchRow(
            title = title,
            checked = checked && blocked == null,
            onChange = onChange,
            subtitle = subtitle,
            enabled = enabled && blocked == null,
            modifier = Modifier.tagged(tag),
        )
        blocked?.let { BlockedReason(it) }
    }
}

/**
 * A single-choice setting: shows the current value, opens a radio list dialog. With [navigationIcon], uses the
 * settings menu's two-line layout and shows [subtitle] inside the dialog instead. A [blocked] reason prevents changes
 * without overwriting the saved selection. [initiallyOpen] opens the dialog when first shown.
 */
@Composable
fun <T : Any> SettingChoice(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    tag: String? = null,
    navigationIcon: ImageVector? = null,
    blocked: String? = null,
    initiallyOpen: Boolean = false,
) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    Column(modifier) {
        if (navigationIcon != null) {
            SettingNavRow(
                navigationIcon,
                title,
                options.firstOrNull { it.first == selected }?.second.orEmpty(),
                { open = true },
                Modifier,
                tag,
                enabled = blocked == null,
            )
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = blocked == null) {
                        open = true
                    }.padding(horizontal = LocalDimens.current.rowHorizontalPadding, vertical = LocalDimens.current.rowPadding)
                    .tagged(tag),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        options.firstOrNull { it.first == selected }?.second.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (blocked == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    subtitle?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Shows the row opens a choice, like the arrow of a dropdown field.
                if (blocked == null) {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        blocked?.let { BlockedReason(it, Modifier.padding(horizontal = LocalDimens.current.rowHorizontalPadding)) }
        if (open && blocked == null) {
            ChoiceDialog(
                title,
                options,
                selected,
                onSelect = {
                    onSelect(it)
                    open = false
                },
                onDismiss = { open = false },
                note = subtitle.takeIf { navigationIcon != null },
                tag = tag,
            )
        }
    }
}

/** A scrollable choice list, including the optional explanation for a settings menu row. */
@Composable
private fun <T : Any> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    note: String?,
    tag: String?,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                note?.let { Description(it, Modifier.padding(bottom = Spacing.Medium)) }
                options.forEach { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .selectable(selected = value == selected, onClick = { onSelect(value) }, role = Role.RadioButton)
                            .padding(vertical = 10.dp)
                            .tagged(tag?.let { "${it}_$value" }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Spacer(Modifier.width(Spacing.Medium))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { CompactTertiaryButton(stringResource(R.string.action_cancel), onDismiss) },
    )
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
    saved: String = "",
    description: String? = null,
    tag: String? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val container = LocalAppContainer.current
    var visible by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    // A saved secret shows its masked [saved] text (empty when none is saved) until focused to type a replacement.
    val showsSaved = saved.isNotEmpty() && !focused && value.isEmpty()
    Column(modifier.fillMaxWidth()) {
        SettingFieldHeading(label, description = description)
        content()
        OutlinedTextField(
            value = if (showsSaved) saved else value,
            onValueChange = {
                container.userActivity()
                if (!showsSaved) onValueChange(it)
            },
            // On one line, so a long hint cannot make the field grow when it gets the focus.
            placeholder = placeholder?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            singleLine = true,
            visualTransformation = if (visible || showsSaved) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                if (showsSaved) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.settings_secret_saved),
                        tint = LocalStatusColors.current.success,
                    )
                    return@OutlinedTextField
                }
                IconButton(onClick = { visible = !visible }, modifier = Modifier.testTag("secretVisibility")) {
                    Icon(
                        if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = stringResource(if (visible) R.string.settings_secret_hide else R.string.settings_secret_show),
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier =
                Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.semantics { contentDescription = label }.tagged(
                    tag,
                ),
        )
    }
}

/**
 * A secret typed straight into the screen (no dialog whose buttons the keyboard could hide): never displayed once
 * saved, except as masked dots and its plain-text [hint] (an API key's last characters) when it has one. The keyboard's
 * Done key calls [onSubmit], as does the caller's own button. [onDraft] reports only whether text is unsaved, then
 * false when the field leaves composition; never reports the secret itself. [content] is shown between the
 * description and the input, such as an expander for more detail.
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
    onDraft: ((Boolean) -> Unit)? = null,
    hint: String? = null,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val reportDraft by rememberUpdatedState(onDraft)
    LaunchedEffect(value) { reportDraft?.invoke(value.isNotEmpty()) }
    DisposableEffect(Unit) { onDispose { reportDraft?.invoke(false) } }
    Column(
        modifier.fillMaxWidth().padding(
            horizontal = LocalDimens.current.rowHorizontalPadding,
            vertical = LocalDimens.current.rowPadding - 2.dp,
        ),
    ) {
        PasswordField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            description = description,
            onDone = onSubmit,
            placeholder = stringResource(if (isSet) R.string.settings_secret_replace else R.string.settings_secret_enter),
            saved = if (isSet) MASK + MASK + hint.orEmpty() else "",
            tag = tag,
            content = content,
        )
    }
}

/** The dots standing in for a saved secret's hidden characters. */
private const val MASK = "••••"

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
    var open by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .clickable {
                open = true
            }.padding(horizontal = LocalDimens.current.rowHorizontalPadding, vertical = LocalDimens.current.rowPadding)
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
                    tag = "secretInput",
                )
            },
            confirmButton = {
                CompactTertiaryButton(
                    stringResource(R.string.action_save),
                    ::save,
                    Modifier.testTag("secretSave"),
                    enabled = value.isNotEmpty(),
                )
            },
            dismissButton = {
                Row {
                    if (isSet) {
                        CompactTertiaryButton(stringResource(R.string.action_clear), {
                            onClear()
                            open = false
                        }, destructive = true)
                    }
                    CompactTertiaryButton(stringResource(R.string.action_cancel), { open = false })
                }
            },
        )
    }
}

/** A two-line settings menu row, with a summary or current value as [subtitle]; grows for large text. */
@Composable
fun SettingNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
    enabled: Boolean = true,
) {
    val dimens = LocalDimens.current
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clickable(
                    enabled = enabled,
                    onClick = onClick,
                ).padding(horizontal = LocalDimens.current.rowHorizontalPadding, vertical = dimens.rowPadding + 2.dp)
                .tagged(tag),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(IconSize.Standard))
            Spacer(Modifier.width(Spacing.Large))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(Modifier.padding(start = 56.dp))
    }
}

/**
 * The explanation that opens a screen without a heading: a [SettingNote] with the space above it that a
 * [app.minimpos.app.ui.components.SectionHeader] would leave, so it does not sit against the app bar.
 */
@Composable
fun SettingIntro(
    text: String,
    modifier: Modifier = Modifier,
) {
    val compact = LocalDimens.current.compact
    SettingNote(text, modifier.testTag("settingIntro").padding(top = if (compact) Spacing.Small else Spacing.Large))
}

/**
 * Explains a settings section or step: the app's [Description], indented like the rows. Use an inset
 * [app.minimpos.app.ui.components.Notice] for a requirement or problem instead.
 */
@Composable
fun SettingNote(
    text: String,
    modifier: Modifier = Modifier,
) = Description(text, modifier.padding(vertical = Spacing.XSmall), inset = true)
