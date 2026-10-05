package io.github.astiskala.minimpos.app.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.astiskala.minimpos.app.R
import io.github.astiskala.minimpos.app.data.settings.EmailSettings
import io.github.astiskala.minimpos.app.data.settings.SmtpSecurity
import io.github.astiskala.minimpos.app.email.SmtpProvider
import io.github.astiskala.minimpos.app.ui.components.ActionMessage
import io.github.astiskala.minimpos.app.ui.components.SectionHeader
import io.github.astiskala.minimpos.app.ui.components.TertiaryButton
import io.github.astiskala.minimpos.app.ui.components.openUrl

/**
 * The SMTP server, login and password ([onPassword] with null forgets it); the providers' help pages are linked only
 * when [canOpenLinks] (not on an Adyen terminal, which has no browser).
 */
@Composable
internal fun ColumnScope.SmtpServerSettings(
    email: EmailSettings,
    passwordSaved: Boolean,
    secretError: String?,
    canOpenLinks: Boolean,
    onPassword: (String?) -> Unit,
    update: ((EmailSettings) -> EmailSettings) -> Unit,
) {
    SectionHeader(stringResource(R.string.settings_smtp))
    SmtpServerFields(email, canOpenLinks, update)
    SettingTextField(stringResource(R.string.settings_smtp_username), email.username, { value ->
        update { it.copy(username = value.trim()) }
    })
    SettingSecret(stringResource(R.string.settings_smtp_password), passwordSaved, onPassword, { onPassword(null) })
    secretError?.let { ActionMessage(it, isError = true, modifier = Modifier.padding(horizontal = 16.dp)) }
}

/**
 * Buttons that fill in a known provider's server or clear it for one typed by hand ("Other"), then what that provider
 * needs (and its help page when [canOpenLinks]), and the server, port and security. The text fields keep what was
 * typed, so they are made again once a server filled in or cleared by a button has been stored.
 */
@Composable
private fun ColumnScope.SmtpServerFields(
    email: EmailSettings,
    canOpenLinks: Boolean,
    update: ((EmailSettings) -> EmailSettings) -> Unit,
) {
    var pending by remember { mutableStateOf<EmailSettings?>(null) }
    var filled by remember { mutableIntStateOf(0) }
    var otherChosen by remember { mutableStateOf(false) }
    LaunchedEffect(pending, email.host, email.port) {
        val target = pending ?: return@LaunchedEffect
        if (target.host == email.host && target.port == email.port) {
            filled++
            pending = null
        }
    }

    fun fill(transform: (EmailSettings) -> EmailSettings) {
        pending = transform(email)
        update(transform)
    }
    val provider = SmtpProvider.of(email.host)
    SmtpProviderButtons(
        selected = provider,
        otherSelected = provider == null && (otherChosen || email.host.isNotBlank()),
        onFill = { chosen ->
            otherChosen = false
            fill(chosen::fill)
        },
        onOther = {
            otherChosen = true
            fill(SmtpProvider::other)
        },
    )
    provider?.let { SmtpProviderHelp(it, canOpenLinks) }
    key(filled) {
        SettingTextField(stringResource(R.string.settings_smtp_host), email.host, { value ->
            update { it.copy(host = value.trim()) }
        }, tag = "smtpHost")
        SettingNumberField(
            stringResource(R.string.settings_port),
            email.port,
            EmailSettings.PORTS,
            { port -> update { it.copy(port = port) } },
            tag = "smtpPort",
        )
    }
    SettingChoice(
        title = stringResource(R.string.settings_smtp_security),
        options =
            listOf(
                SmtpSecurity.STARTTLS to stringResource(R.string.settings_smtp_starttls),
                SmtpSecurity.SSL to stringResource(R.string.settings_smtp_ssl),
                SmtpSecurity.NONE to stringResource(R.string.settings_smtp_none),
            ),
        selected = email.security,
        onSelect = { security -> update { it.copy(security = security) } },
        tag = "smtpSecurity",
    )
}

/**
 * One button per [SmtpProvider], which fills in its server ([onFill]), and "Other" for a server typed by hand
 * ([onOther]); the one whose server is set, [selected], or "Other" when [otherSelected], is checked.
 */
@Composable
private fun SmtpProviderButtons(
    selected: SmtpProvider?,
    otherSelected: Boolean,
    onFill: (SmtpProvider) -> Unit,
    onOther: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            stringResource(R.string.settings_smtp_providers),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmtpProvider.entries.forEach { provider ->
                ProviderChip(provider.label, provider == selected, { onFill(provider) }, "smtpProvider_${provider.name}")
            }
            ProviderChip(stringResource(R.string.settings_smtp_other), otherSelected, onOther, "smtpProvider_OTHER")
        }
    }
}

/** A chip labelled [label], with a check mark while [checked]; tagged [tag]. */
@Composable
private fun ProviderChip(
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
    tag: String,
) = FilterChip(
    selected = checked,
    onClick = onClick,
    label = { Text(label) },
    leadingIcon = if (checked) ({ Icon(Icons.Default.Check, contentDescription = null) }) else null,
    modifier = Modifier.testTag(tag),
)

/**
 * What [provider] needs (usually an app password rather than the account's own) and, when [canOpenLinks], a button that
 * opens its help page in a browser. Adyen terminals have no browser, so there the advice stands alone (the getting
 * started guide links the help pages). A phone or tablet without a browser just does not open the page.
 */
@Composable
private fun SmtpProviderHelp(
    provider: SmtpProvider,
    canOpenLinks: Boolean,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            stringResource(providerAdvice(provider)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("smtpAdvice"),
        )
        if (canOpenLinks) {
            TertiaryButton(
                stringResource(R.string.settings_smtp_help, provider.label),
                { context.openUrl(provider.helpUrl) },
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                modifier = Modifier.testTag("smtpHelp"),
            )
        }
    }
}

/** What [provider] needs before receipts can be sent through it. */
@StringRes
private fun providerAdvice(provider: SmtpProvider): Int =
    when (provider) {
        SmtpProvider.GMAIL -> R.string.settings_smtp_advice_gmail
        SmtpProvider.MICROSOFT_365 -> R.string.settings_smtp_advice_microsoft_365
        SmtpProvider.ICLOUD -> R.string.settings_smtp_advice_icloud
        SmtpProvider.YAHOO -> R.string.settings_smtp_advice_yahoo
        SmtpProvider.FASTMAIL -> R.string.settings_smtp_advice_fastmail
        SmtpProvider.ZOHO -> R.string.settings_smtp_advice_zoho
        SmtpProvider.PROTON -> R.string.settings_smtp_advice_proton
    }
