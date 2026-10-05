package io.github.astiskala.minimpos.app.terminal

import io.github.astiskala.minimpos.app.data.db.SetupProblem
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.data.security.SecretStore
import io.github.astiskala.minimpos.app.data.settings.SettingsRepository
import io.github.astiskala.minimpos.terminal.paymentsapp.AdyenPaymentsAppManagement
import io.github.astiskala.minimpos.terminal.paymentsapp.AppLinkExchange
import io.github.astiskala.minimpos.terminal.paymentsapp.BoardingTarget
import io.github.astiskala.minimpos.terminal.paymentsapp.ManagementResult
import io.github.astiskala.minimpos.terminal.paymentsapp.Onboarding
import io.github.astiskala.minimpos.terminal.paymentsapp.PaymentsAppManagement
import io.github.astiskala.minimpos.terminal.paymentsapp.PaymentsAppOnboarding
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment
import java.io.IOException

/** The outcome of [TapToPaySetup.board] and [TapToPaySetup.unregister]. Failures are reported here, never thrown. */
sealed interface TapToPayOutcome {
    /**
     * The Payments app is boarded on this phone. Its installation ID is stored as the POIID only if the originating
     * terminal settings are still selected.
     *
     * @property installationId The instance's installation ID.
     */
    data class Boarded(
        val installationId: String,
    ) : TapToPayOutcome

    /** The instance was revoked at Adyen and forgotten here. */
    data object Unregistered : TapToPayOutcome

    /**
     * Nothing changed, because something must be entered or installed first.
     *
     * @property problem What.
     */
    data class NotSetUp(
        val problem: SetupProblem,
    ) : TapToPayOutcome

    /**
     * Nothing changed.
     *
     * @property message What the Payments app or Adyen answered; null when the Payments app gave no reason.
     */
    data class Failed(
        val message: String?,
    ) : TapToPayOutcome
}

/**
 * Sets up Tap to Pay with the Adyen Payments app on this phone: boards it for the merchant account (or store) in the
 * settings, with the Payments app API key ([Secret.PAYMENTS_APP_API_KEY]), and stores the installation ID it reports as
 * the POIID; or revokes it. The environment is the installed Payments app's.
 *
 * @param setups Whether it can be boarded, with the Payments app API key ([TerminalSetupSource.boarding]), and the
 *   merchant account and store.
 * @param settings Where the installation ID is stored.
 * @param exchange Opens the Payments app.
 * @param management The Management API for an API key and environment; tests replace it.
 */
class TapToPaySetup(
    private val setups: TerminalSetupSource,
    private val settings: SettingsRepository,
    private val exchange: AppLinkExchange,
    private val management: (apiKey: String, environment: TerminalEnvironment) -> PaymentsAppManagement = { key, environment ->
        AdyenPaymentsAppManagement(key, environment)
    },
) {
    /**
     * Boards the Payments app (or confirms that it is boarded); [reboard] boards it again, e.g. for another store. It
     * opens the Payments app up to twice and calls the Management API in between. The shared key is entered separately
     * through the setup helper or on this device; boarding never retrieves or replaces it.
     */
    suspend fun board(reboard: Boolean = false): TapToPayOutcome {
        val access =
            when (val checked = access()) {
                is Access.Denied -> return TapToPayOutcome.NotSetUp(checked.problem)
                is Access.Granted -> checked
            }
        val terminal = setups.current().settings.terminal
        val onboarding = PaymentsAppOnboarding(access.environment, exchange, access.management, PaymentsAppBridge.RETURN_URL)
        val target = BoardingTarget(terminal.merchantAccount.trim(), terminal.storeId.trim().ifEmpty { null })
        val result =
            try {
                onboarding.board(target, reboard)
            } catch (e: IOException) {
                return TapToPayOutcome.Failed(e.message)
            }
        return when (result) {
            is Onboarding.Boarded -> {
                val boarded = terminal.copy(paymentsAppInstallationId = result.installationId)
                settings.update { if (it.terminal == terminal) it.copy(terminal = boarded) else it }
                TapToPayOutcome.Boarded(result.installationId)
            }

            is Onboarding.Failed -> {
                TapToPayOutcome.Failed(result.message)
            }
        }
    }

    /**
     * Revokes the boarded instance at Adyen, so it can no longer take payments, and forgets its installation ID. Adyen
     * also asks to unregister it in the Payments app's own settings.
     */
    suspend fun unregister(): TapToPayOutcome {
        val access =
            when (val checked = access()) {
                is Access.Denied -> return TapToPayOutcome.NotSetUp(checked.problem)
                is Access.Granted -> checked
            }
        val terminal = setups.current().settings.terminal
        val installationId = terminal.paymentsAppInstallationId.trim()
        val revoked =
            if (installationId.isEmpty()) {
                ManagementResult.Done()
            } else {
                access.management.revoke(
                    terminal.merchantAccount.trim(),
                    installationId,
                )
            }
        return when (revoked) {
            is ManagementResult.Failed -> {
                TapToPayOutcome.Failed(revoked.message)
            }

            is ManagementResult.Done -> {
                settings.update { it.copy(terminal = it.terminal.copy(paymentsAppInstallationId = "")) }
                TapToPayOutcome.Unregistered
            }
        }
    }

    /** The Management API for the installed Payments app, or what is missing for it ([TerminalSetup.boarding]). */
    private suspend fun access(): Access =
        when (val boarding = setups.boarding()) {
            is BoardingSetup.Blocked -> {
                Access.Denied(boarding.problem)
            }

            is BoardingSetup.Ready -> {
                Access.Granted(boarding.environment, management(boarding.apiKey, boarding.environment))
            }
        }

    private sealed interface Access {
        class Granted(
            val environment: TerminalEnvironment,
            val management: PaymentsAppManagement,
        ) : Access

        class Denied(
            val problem: SetupProblem,
        ) : Access
    }
}
