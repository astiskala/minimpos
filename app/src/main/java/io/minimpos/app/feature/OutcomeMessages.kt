package io.minimpos.app.feature

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.minimpos.app.R
import io.minimpos.app.terminal.SetupProblem
import io.minimpos.app.ui.components.ActionMessage
import io.minimpos.app.ui.components.currentLocale
import io.minimpos.core.money.CurrencySpec
import io.minimpos.core.money.MoneyFormatter

/** How the screens word this outcome; the one place view-model outcomes become text. */
@Composable
@ReadOnlyComposable
fun ActionOutcome.text(): String =
    when (this) {
        ActionOutcome.Printed -> stringResource(R.string.result_printed)

        is ActionOutcome.Emailed -> stringResource(R.string.result_emailed, to)

        ActionOutcome.AbortSent -> stringResource(R.string.result_abort_sent)

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

/** The string resource that words this setup problem, also for the messages the container stores with transactions. */
@get:StringRes
val SetupProblem.textRes: Int
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
