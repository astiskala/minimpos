package app.minimpos.app.feature.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.minimpos.app.R
import app.minimpos.app.data.db.EmailFault
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.settings.EmailCapture
import app.minimpos.app.data.settings.EmailSettings
import app.minimpos.app.data.settings.PaymentSettings
import app.minimpos.app.data.settings.ShopperReferenceSource
import app.minimpos.app.feature.GatedButton
import app.minimpos.app.feature.OutcomeMessage
import app.minimpos.app.feature.text
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.SectionHeader
import app.minimpos.app.ui.components.TextInputDialog
import app.minimpos.core.shopper.ShopperReferences

/** Email collection and automatic receipts alongside their SMTP delivery setup and test action. */
@Composable
internal fun ColumnScope.EmailSection(
    state: SettingsUiState,
    actions: SettingsActions,
    events: SettingsEvents,
) {
    val email = state.settings.email
    val terminal by LocalAppContainer.current.terminalStatus.state
        .collectAsStateWithLifecycle()
    var askRecipient by remember { mutableStateOf(false) }

    fun update(transform: (EmailSettings) -> EmailSettings) = events.onUpdate { it.copy(email = transform(it.email)) }
    SectionHeader(stringResource(R.string.settings_email_receipts))
    EmailCaptureSettings(state.settings.payment) { transform ->
        events.onUpdate { it.copy(payment = transform(it.payment)) }
    }
    SmtpServerSettings(
        email = email,
        passwordSaved = Secret.SMTP_PASSWORD in state.secrets,
        secretError = actions.secretError?.text(),
        canOpenLinks = terminal.loaded && !terminal.onTerminal,
        onPassword = { events.onSecretChange(Secret.SMTP_PASSWORD, it) },
        update = ::update,
    )
    EmailMessageSettings(email, ::update)
    SettingActions {
        GatedButton(
            stringResource(R.string.settings_send_test),
            { askRecipient = true },
            blocked = Failure.Email(EmailFault.NOT_CONFIGURED).takeUnless { email.isConfigured },
            loading = actions.email.running,
            icon = Icons.Default.Email,
            modifier = Modifier.testTag("sendTestEmail"),
        )
        OutcomeMessage(actions.email)
    }
    if (askRecipient) {
        TextInputDialog(
            title = stringResource(R.string.settings_send_test),
            label = stringResource(R.string.checkout_email),
            confirmLabel = stringResource(R.string.action_send),
            keyboardType = KeyboardType.Email,
            validate = ShopperReferences::isValidEmail,
            onConfirm = {
                askRecipient = false
                events.onTestEmailSend(it)
            },
            onDismiss = { askRecipient = false },
        )
    }
}

@Composable
private fun ColumnScope.EmailMessageSettings(
    email: EmailSettings,
    update: ((EmailSettings) -> EmailSettings) -> Unit,
) {
    SectionHeader(stringResource(R.string.settings_message))
    SettingTextField(
        stringResource(R.string.settings_from_address),
        email.fromAddress,
        { value -> update { it.copy(fromAddress = value.trim()) } },
        isError = { it.isNotBlank() && !ShopperReferences.isValidEmail(it) },
        keyboardType = KeyboardType.Email,
        tag = "fromAddress",
    )
    SettingTextField(stringResource(R.string.settings_from_name), email.fromName, { value -> update { it.copy(fromName = value) } })
    SettingTextField(
        stringResource(R.string.settings_bcc),
        email.bcc,
        { value -> update { it.copy(bcc = value.trim()) } },
        isError = { it.isNotBlank() && !ShopperReferences.isValidEmail(it) },
        keyboardType = KeyboardType.Email,
    )
    SettingTextField(stringResource(R.string.settings_subject), email.subject, { value ->
        update { it.copy(subject = value) }
    }, supporting = stringResource(R.string.settings_subject_hint))
}

/** Shopper-reference requirements still restrict email collection to before payment when necessary. */
@Composable
private fun ColumnScope.EmailCaptureSettings(
    payment: PaymentSettings,
    update: ((PaymentSettings) -> PaymentSettings) -> Unit,
) {
    val emailIsReference = payment.shopperReferenceSource == ShopperReferenceSource.EMAIL
    SettingNote(stringResource(R.string.settings_shopper_email_hint))
    SettingChoice(
        title = stringResource(R.string.settings_email_capture),
        options =
            listOfNotNull(
                (EmailCapture.OFF to stringResource(R.string.settings_capture_off)).takeUnless { emailIsReference },
                EmailCapture.BEFORE_PAYMENT to stringResource(R.string.settings_capture_before),
                (EmailCapture.AFTER_PAYMENT to stringResource(R.string.settings_capture_after)).takeUnless { emailIsReference },
            ),
        selected = payment.effectiveEmailCapture,
        onSelect = { capture -> update { it.copy(emailCapture = capture) } },
        subtitle = stringResource(R.string.settings_email_capture_reference).takeIf { emailIsReference },
        tag = "emailCapture",
    )
    if (payment.captureEmailBefore) {
        SettingSwitch(stringResource(R.string.settings_auto_send), payment.autoSendEmail, { value ->
            update { it.copy(autoSendEmail = value) }
        })
    }
}
