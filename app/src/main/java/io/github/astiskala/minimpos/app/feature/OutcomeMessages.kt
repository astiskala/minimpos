package io.github.astiskala.minimpos.app.feature

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.astiskala.minimpos.app.R
import io.github.astiskala.minimpos.app.data.db.RefundEntity
import io.github.astiskala.minimpos.app.data.db.SaleEntity
import io.github.astiskala.minimpos.app.data.db.SetupProblem
import io.github.astiskala.minimpos.app.data.db.StoredReason
import io.github.astiskala.minimpos.app.ui.components.ActionMessage
import io.github.astiskala.minimpos.app.ui.components.currentLocale
import io.github.astiskala.minimpos.core.money.CurrencySpec
import io.github.astiskala.minimpos.core.money.MoneyFormatter

/** How the screens word this outcome; the one place view-model outcomes become text. */
@Composable
@ReadOnlyComposable
fun ActionOutcome.text(): String =
    when (this) {
        ActionOutcome.Printed -> stringResource(R.string.result_printed)

        is ActionOutcome.Emailed -> stringResource(R.string.result_emailed, to)

        ActionOutcome.AbortSent -> stringResource(R.string.result_abort_sent)

        ActionOutcome.LinkNotPaid -> stringResource(R.string.link_not_paid)

        is ActionOutcome.CaptureFailed -> captureText()

        is ActionOutcome.Connected -> connectedText()

        is ActionOutcome.ConnectionFailed -> "${stringResource(
            R.string.settings_connection_failed,
        )}: ${reason ?: stringResource(R.string.setup_no_response)}"

        is ActionOutcome.NotSetUp -> problem.text()

        ActionOutcome.NoAnswer -> stringResource(R.string.setup_no_response)

        ActionOutcome.ApiWorks -> stringResource(R.string.settings_api_ok)

        is ActionOutcome.TapToPayReady -> stringResource(R.string.result_tap_to_pay_ready, installationId)

        ActionOutcome.TapToPayRemoved -> stringResource(R.string.result_tap_to_pay_removed)

        is ActionOutcome.TestEmailSent -> stringResource(R.string.settings_test_email_sent, to)

        is ActionOutcome.SecretNotStored -> stringResource(R.string.settings_secret_not_stored, reason.orEmpty())

        is ActionOutcome.Failed -> message
    }

/** How the screens word this setup problem: what to enter, install or fix. */
@Composable
@ReadOnlyComposable
fun SetupProblem.text(): String = stringResource(textRes)

/**
 * Why this sale's payment (or payment link) did not succeed, as the screens say it: the app's [SaleEntity.reason] in
 * the current language, then what Adyen or the terminal said ([SaleEntity.message]); null when neither is stored.
 */
@Composable
@ReadOnlyComposable
fun SaleEntity.outcomeNote(): String? =
    note(
        reason?.text(if (paymentLink) R.string.link_unknown_outcome else R.string.payment_unknown_outcome, R.string.payment_interrupted),
        message,
    )

/** Why this sale's latest capture or adjustment did not go through, worded as [outcomeNote]; null when it did. */
@Composable
@ReadOnlyComposable
fun SaleEntity.modificationNote(): String? =
    note(modificationReason?.text(R.string.capture_interrupted, R.string.capture_interrupted), modificationMessage)

/** Why this refund was not accepted, worded as [SaleEntity.outcomeNote]; null when nothing is stored. */
@Composable
@ReadOnlyComposable
fun RefundEntity.outcomeNote(): String? = note(reason?.text(R.string.refund_unknown_outcome, R.string.payment_interrupted), message)

/** The app's [worded] reason, with what Adyen or the terminal said ([said]) in brackets after it. */
private fun note(
    worded: String?,
    said: String?,
): String? =
    when {
        worded == null -> said
        said == null -> worded
        else -> "$worded ($said)"
    }

/**
 * The title and text of Home's setup card while this is missing: on a terminal ([onTerminal]) whose shared key is
 * missing, the request to enter it; otherwise "finish setting up" with what is missing.
 */
@Composable
@ReadOnlyComposable
fun SetupProblem.setupCard(onTerminal: Boolean): Pair<String, String> =
    if (onTerminal && this in SHARED_KEY_PROBLEMS) {
        stringResource(R.string.home_setup_title) to stringResource(R.string.home_setup_text)
    } else {
        stringResource(R.string.home_setup_title_remote) to text()
    }

/** What is missing of the shared key, which a terminal running the app needs first. */
private val SHARED_KEY_PROBLEMS =
    setOf(SetupProblem.KEY_IDENTIFIER, SetupProblem.PASSPHRASE, SetupProblem.KEY_VERSION, SetupProblem.UNREADABLE_PASSPHRASE)

/** This stored reason in the current language: an unknown outcome as [unknown], an interruption as [interrupted]. */
@Composable
@ReadOnlyComposable
private fun StoredReason.text(
    @StringRes unknown: Int,
    @StringRes interrupted: Int,
): String =
    when (this) {
        is StoredReason.NotSetUp -> problem.text()
        StoredReason.OutcomeUnknown -> stringResource(unknown)
        StoredReason.Interrupted -> stringResource(interrupted)
    }

/** The string resource that words this setup problem. */
@get:StringRes
private val SetupProblem.textRes: Int
    get() =
        when (this) {
            SetupProblem.POI_ID -> R.string.setup_poiid
            SetupProblem.HOST -> R.string.setup_host
            SetupProblem.KEY_IDENTIFIER -> R.string.setup_key_identifier
            SetupProblem.PASSPHRASE -> R.string.setup_passphrase
            SetupProblem.KEY_VERSION -> R.string.setup_key_version
            SetupProblem.MERCHANT_ACCOUNT -> R.string.setup_merchant_account
            SetupProblem.API_KEY -> R.string.setup_api_key
            SetupProblem.ENVIRONMENT -> R.string.setup_environment
            SetupProblem.TERMINAL_ENVIRONMENT -> R.string.setup_terminal_environment
            SetupProblem.LIVE_PREFIX -> R.string.setup_live_prefix
            SetupProblem.UNREADABLE_PASSPHRASE -> R.string.setup_unreadable_passphrase
            SetupProblem.UNREADABLE_API_KEY -> R.string.setup_unreadable_api_key
            SetupProblem.API_REQUIRED -> R.string.setup_api_required
            SetupProblem.CLOUD_API_KEY -> R.string.setup_cloud_api_key
            SetupProblem.PAYMENTS_APP_MISSING -> R.string.setup_payments_app_missing
            SetupProblem.PAYMENTS_APP_AMBIGUOUS -> R.string.setup_payments_app_ambiguous
            SetupProblem.PAYMENTS_APP_NOT_BOARDED -> R.string.setup_payments_app_not_boarded
            SetupProblem.PAYMENTS_APP_ON_TERMINAL -> R.string.setup_payments_app_on_terminal
            SetupProblem.PAYMENTS_APP_API_KEY -> R.string.setup_payments_app_api_key
            SetupProblem.UNREADABLE_PAYMENTS_APP_KEY -> R.string.setup_unreadable_payments_app_key
            SetupProblem.MANAGER_APPROVAL -> R.string.manager_pin_enter
            SetupProblem.PAYMENT_CONTEXT -> R.string.payment_context_mismatch
        }

/** Why a tip, capture or adjustment did not go through, named after what was sent; a refusal says the amount. */
@Composable
@ReadOnlyComposable
private fun ActionOutcome.CaptureFailed.captureText(): String =
    when (this) {
        is ActionOutcome.NotCaptured -> {
            stringResource(if (step == CaptureStep.ADJUSTMENT) R.string.adjust_failed else R.string.capture_failed, reason)
        }

        is ActionOutcome.CaptureRefused -> {
            val amount = MoneyFormatter(CurrencySpec.of(currency), currentLocale()).format(amountMinor)
            stringResource(if (step == CaptureStep.TIP) R.string.tip_refused else R.string.capture_refused, amount, reason)
        }

        ActionOutcome.CaptureNotAllowed -> {
            stringResource(R.string.capture_not_allowed)
        }
    }

/** A successful connection test, with the terminal's status and whether it has a printer. */
@Composable
@ReadOnlyComposable
private fun ActionOutcome.Connected.connectedText(): String {
    val printer = stringResource(if (hasPrinter) R.string.home_printer_yes else R.string.home_printer_no)
    return "${stringResource(R.string.settings_connection_ok)} (${globalStatus ?: "OK"}, $printer)"
}

/** The outcome of [state] under its button, in the error colour for a failure; nothing while there is none. */
@Composable
fun OutcomeMessage(
    state: ActionState,
    modifier: Modifier = Modifier,
) {
    state.outcome?.let { ActionMessage(it.text(), state.isError, modifier) }
}
