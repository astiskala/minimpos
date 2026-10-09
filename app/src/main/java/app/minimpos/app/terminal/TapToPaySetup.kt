package app.minimpos.app.terminal

import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.security.SecretStore
import app.minimpos.app.data.security.SecretStoreException
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.SettingsRepository
import app.minimpos.app.data.settings.StorageJson
import app.minimpos.app.data.settings.TerminalSettings
import app.minimpos.terminal.paymentsapp.AdyenPaymentsAppManagement
import app.minimpos.terminal.paymentsapp.AppLinkExchange
import app.minimpos.terminal.paymentsapp.BoardingTarget
import app.minimpos.terminal.paymentsapp.ManagementResult
import app.minimpos.terminal.paymentsapp.Onboarding
import app.minimpos.terminal.paymentsapp.PaymentsAppManagement
import app.minimpos.terminal.paymentsapp.PaymentsAppOnboarding
import app.minimpos.terminal.transport.TerminalEnvironment
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
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
     * Active local settings were not changed; remote registration may already have completed and remains recoverable.
     *
     * @property message What the Payments app, Adyen or storage answered; null when no reason was supplied.
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
 * @param verifyApi Validates the main credential and Checkout account before registration can change.
 */
class TapToPaySetup(
    private val setups: TerminalSetupSource,
    private val settings: SettingsRepository,
    private val exchange: AppLinkExchange,
    private val management: (apiKey: String, environment: TerminalEnvironment) -> PaymentsAppManagement = { key, environment ->
        AdyenPaymentsAppManagement(key, environment)
    },
    private val verifyApi: suspend () -> ApiCheck,
) {
    /**
     * Boards the Payments app (or confirms that it is boarded); [reboard] boards it again, e.g. for another store. It
     * opens the Payments app up to twice and calls the Management API in between. The shared key is entered separately
     * through the setup helper or on this device; boarding never retrieves or replaces it.
     */
    suspend fun board(reboard: Boolean = false): TapToPayOutcome =
        when (val access = access()) {
            is Access.Denied -> {
                TapToPayOutcome.NotSetUp(access.problem)
            }

            is Access.Granted -> {
                when (val checked = verifyApi()) {
                    ApiCheck.Works -> boardSaved(access, reboard)
                    is ApiCheck.NotSetUp -> TapToPayOutcome.NotSetUp(checked.problem)
                    is ApiCheck.Failed -> TapToPayOutcome.Failed(checked.message)
                }
            }
        }

    private suspend fun boardSaved(
        access: Access.Granted,
        reboard: Boolean,
    ): TapToPayOutcome {
        val original = setups.current().settings.terminal
        val result = register(original.target(), access.environment, access.apiKey, access.management, reboard)
        return if (result is TapToPayOutcome.Boarded) {
            val boarded = original.copy(paymentsAppInstallationId = result.installationId)
            settings.update { if (it.terminal == original) it.copy(terminal = boarded) else it }
            if (settings.current().terminal == boarded) result else TapToPayOutcome.NotSetUp(SetupProblem.SETUP_CHANGED)
        } else {
            result
        }
    }

    internal suspend fun checkSaved(setup: TerminalSetup): TapToPayOutcome {
        if (setups.current().settings.terminal != setup.settings.terminal) return TapToPayOutcome.NotSetUp(SetupProblem.SETUP_CHANGED)
        val terminal = setup.settings.terminal
        return when (val access = setups.boarding()) {
            is BoardingSetup.Blocked -> {
                TapToPayOutcome.NotSetUp(access.problem)
            }

            is BoardingSetup.Ready -> {
                if (terminal.paymentsAppInstallationId.isBlank()) {
                    TapToPayOutcome.NotSetUp(SetupProblem.PAYMENTS_APP_NOT_BOARDED)
                } else {
                    registered(management(access.apiKey, access.environment), terminal.target(), terminal.paymentsAppInstallationId)
                }
            }
        }
    }

    internal suspend fun candidate(
        current: AppSettings,
        values: Map<Secret, String>,
        explicit: Boolean,
    ): TapToPayOutcome =
        when (val access = setups.registrations.access(current, values)) {
            is BoardingSetup.Blocked -> TapToPayOutcome.NotSetUp(access.problem)
            is BoardingSetup.Ready -> candidateRegistration(current.terminal.target(), access, explicit)
        }

    private suspend fun candidateRegistration(
        target: BoardingTarget,
        access: BoardingSetup.Ready,
        explicit: Boolean,
    ): TapToPayOutcome {
        val (previous, unreadable) = recovery()
        if (unreadable) return TapToPayOutcome.NotSetUp(SetupProblem.MANAGEMENT_UNAVAILABLE)
        val same = previous?.matches(access, target) == true
        val active = setups.current().settings.terminal
        val activeMatches = active.targetOrNull() == target
        val installation =
            if (same) {
                previous.installation
            } else {
                active.paymentsAppInstallationId.takeIf { activeMatches && it.isNotBlank() }
            }
        val client = management(access.apiKey, access.environment)
        return when {
            installation != null -> {
                registered(client, target, installation)
            }

            !explicit -> {
                TapToPayOutcome.NotSetUp(SetupProblem.PAYMENTS_APP_NOT_BOARDED)
            }

            else -> {
                register(
                    target,
                    access.environment,
                    access.apiKey,
                    client,
                    reboard = !same && active.paymentsAppInstallationId.isNotBlank() && !activeMatches,
                )
            }
        }
    }

    private suspend fun recovery(): Pair<RegistrationRecovery?, Boolean> {
        val text = setups.registrations.read()
        if (text == null) return null to setups.registrations.exists()
        return try {
            StorageJson.decodeFromString(serializer<RegistrationRecovery>(), text) to false
        } catch (ignored: SerializationException) {
            null to true
        }
    }

    private suspend fun register(
        target: BoardingTarget,
        environment: TerminalEnvironment,
        key: String,
        client: PaymentsAppManagement,
        reboard: Boolean,
    ): TapToPayOutcome =
        try {
            remember(key, environment, target, null)
            val onboarding = PaymentsAppOnboarding(environment, exchange, client, PaymentsAppBridge.RETURN_URL)
            when (val result = onboarding.board(target, reboard)) {
                is Onboarding.Failed -> {
                    TapToPayOutcome.Failed(result.message)
                }

                is Onboarding.Boarded -> {
                    remember(key, environment, target, result.installationId)
                    registered(client, target, result.installationId)
                }
            }
        } catch (e: IOException) {
            TapToPayOutcome.Failed(e.message)
        } catch (e: SecretStoreException) {
            TapToPayOutcome.Failed(e.message)
        }

    private suspend fun remember(
        key: String,
        environment: TerminalEnvironment,
        target: BoardingTarget,
        installation: String?,
    ) {
        val recovery = RegistrationRecovery(key, environment, target.merchantAccount, target.storeId, installation)
        setups.registrations.write(StorageJson.encodeToString(serializer<RegistrationRecovery>(), recovery))
    }

    private suspend fun registered(
        client: PaymentsAppManagement,
        target: BoardingTarget,
        id: String,
    ): TapToPayOutcome =
        when (val result = client.registration(target, id)) {
            is ManagementResult.Done -> TapToPayOutcome.Boarded(id)
            is ManagementResult.Failed -> TapToPayOutcome.Failed(result.message)
        }

    private fun TerminalSettings.target(): BoardingTarget = BoardingTarget(merchantAccount.trim(), storeId.trim().ifEmpty { null })

    private fun TerminalSettings.targetOrNull(): BoardingTarget? = if (merchantAccount.isBlank()) null else target()

    @Serializable
    private class RegistrationRecovery(
        val key: String,
        val environment: TerminalEnvironment,
        val merchant: String,
        val store: String?,
        val installation: String?,
    ) {
        fun matches(
            access: BoardingSetup.Ready,
            target: BoardingTarget,
        ): Boolean {
            val sameCredential = key == access.apiKey && environment == access.environment
            val sameTarget = merchant == target.merchantAccount && store == target.storeId
            return sameCredential && sameTarget
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
        val id = terminal.paymentsAppInstallationId.trim()
        val result = if (id.isEmpty()) ManagementResult.Done() else access.management.revoke(terminal.merchantAccount.trim(), id)
        return when (result) {
            is ManagementResult.Failed -> {
                TapToPayOutcome.Failed(result.message)
            }

            is ManagementResult.Done -> {
                settings.update { it.copy(terminal = it.terminal.copy(paymentsAppInstallationId = "")) }
                setups.registrations.write(null)
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
                val client = management(boarding.apiKey, boarding.environment)
                Access.Granted(boarding.environment, client, boarding.apiKey)
            }
        }

    private sealed interface Access {
        class Granted(
            val environment: TerminalEnvironment,
            val management: PaymentsAppManagement,
            val apiKey: String,
        ) : Access

        class Denied(
            val problem: SetupProblem,
        ) : Access
    }
}
