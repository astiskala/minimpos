package app.minimpos.app.terminal

import app.minimpos.app.data.db.DeviceFault
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.security.JournalRead
import app.minimpos.app.data.security.RecoveryJournal
import app.minimpos.app.data.security.SecretStoreException
import app.minimpos.app.data.settings.BoardingIdentity
import app.minimpos.app.data.settings.SettingsRepository
import app.minimpos.app.data.settings.TerminalSettings
import app.minimpos.terminal.paymentsapp.AppLinkExchange
import app.minimpos.terminal.paymentsapp.BoardingCredentialCheck
import app.minimpos.terminal.paymentsapp.BoardingTarget
import app.minimpos.terminal.paymentsapp.ManagementResult
import app.minimpos.terminal.paymentsapp.Onboarding
import app.minimpos.terminal.paymentsapp.PaymentsAppCredentials
import app.minimpos.terminal.paymentsapp.PaymentsAppManagement
import app.minimpos.terminal.paymentsapp.PaymentsAppOnboarding
import app.minimpos.terminal.paymentsapp.ProvisionedCredential
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalEnvironment
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid

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
     * Active local settings were not changed, except that boarding (from Settings or a setup import) forgets a stored
     * installation ID once the Payments app may have dropped its boarding; remote registration may already have
     * completed and remains recoverable.
     *
     * @property failure Why: the Payments app, Adyen or this device's secure storage.
     */
    data class Failed(
        val failure: Failure,
    ) : TapToPayOutcome
}

/**
 * Sets up Tap to Pay with the Adyen Payments app on this phone: boards it for the merchant account (or store) in the
 * settings, using a dedicated credential provisioned automatically by the primary API key, and stores the installation
 * ID it reports as the POIID; or revokes it. The selected environment chooses the Payments app.
 *
 * @param setups Whether the Adyen API key can create this phone's own credential ([TerminalSetupSource.owner]) and
 *   whether it can be boarded with that credential ([TerminalSetupSource.boarding]), and the merchant account and store.
 * @param settings Where the installation ID is stored.
 * @param exchange Opens the Payments app.
 * @param management The Management API for an API key and environment; tests replace it.
 * @property diagnostics Advisory credential preflight and evidence of actual new boarding; never gates payments.
 * @param verifyApi Validates the main credential and Checkout account before registration can change.
 * @param credentials Creates this phone's own credential with the Adyen API key, in an environment.
 */
class TapToPaySetup(
    private val setups: TerminalSetupSource,
    private val settings: SettingsRepository,
    private val exchange: AppLinkExchange,
    private val management: (apiKey: String, environment: TerminalEnvironment) -> PaymentsAppManagement,
    val diagnostics: TapToPayDiagnostics,
    private val verifyApi: suspend () -> ApiCheck,
    private val credentials: (apiKey: String, environment: TerminalEnvironment) -> PaymentsAppCredentials,
) {
    private val journal = setups.journal(RecoveryJournal.BOARDING, serializer<RegistrationRecovery>())
    private val provisioning = Mutex()

    // Adyen's list of boarded instances can lag behind a boarding its token already confirmed. Storing the new
    // installation ID needs a fresh connection check, which would otherwise close the setup gate until the list
    // catches up. Kept in memory only: after a restart, the list decides again.
    private val confirmed = AtomicReference<RegistrationRecovery?>()

    /**
     * Boards the Payments app (or confirms that it is boarded); [reboard] boards it again, e.g. for another store. It
     * opens the Payments app up to twice and calls the Management API in between; an existing boarding not listed for
     * this merchant account or store requires an explicit reboarding action, never an automatic fallback.
     * Once the Payments app may have dropped its boarding, a failure also forgets the
     * stored installation ID, so the setup asks for Tap to Pay again. The shared key is entered separately through the
     * setup helper or on this device; boarding never retrieves or replaces it.
     */
    suspend fun board(reboard: Boolean = false): TapToPayOutcome {
        val unlocked = setups.unlocked(forValidation = true)
        return when (val owner = setups.owner()) {
            is BoardingOwner.Blocked -> {
                TapToPayOutcome.NotSetUp(owner.problem)
            }

            is BoardingOwner.Ready -> {
                when (val checked = verifyApi()) {
                    ApiCheck.Works -> boardVerified(unlocked, owner, reboard)
                    is ApiCheck.NotSetUp -> TapToPayOutcome.NotSetUp(checked.problem)
                    is ApiCheck.Failed -> TapToPayOutcome.Failed(checked.failure)
                }
            }
        }
    }

    private suspend fun boardVerified(
        unlocked: UnlockedSetup,
        owner: BoardingOwner.Ready,
        reboard: Boolean,
    ): TapToPayOutcome {
        val fence = setups.SetupFence(unlocked, SetupFreshness.SECRETS)
        val blocked = fence.changed()?.let { TapToPayOutcome.NotSetUp(it) } ?: prepare(owner)
        if (blocked != null) return blocked
        fence.changed()?.let { return TapToPayOutcome.NotSetUp(it) }
        return when (val access = access()) {
            is Access.Denied -> TapToPayOutcome.NotSetUp(access.problem)
            is Access.Granted -> fence.changed()?.let { TapToPayOutcome.NotSetUp(it) } ?: boardSaved(unlocked.setup, access, reboard)
        }
    }

    /**
     * Makes sure this phone's own credential exists for [owner], creating it (or recovering an interrupted creation)
     * with the Adyen API key; null once it is stored. Its creation intent is stored first, so an answer that never
     * arrives is recovered rather than repeated; one that cannot be decrypted any more is replaced. Once a credential
     * is created, those of other Adyen API keys are forgotten.
     */
    private suspend fun prepare(owner: BoardingOwner.Ready): TapToPayOutcome? =
        provisioning.withLock {
            try {
                val stored = (setups.boardingCredentials.read() as? JournalRead.Present)?.value.orEmpty()
                val previous = stored.firstOrNull { it.matches(owner) }
                if (previous?.apiKey != null) return@withLock null
                val intent =
                    previous
                        ?: BoardingCredential(owner.fingerprint, owner.merchantAccount, owner.environment, "Mini mPOS ${Uuid.random()}")
                            .also { setups.boardingCredentials.write(stored + it) }
                val api = credentials(owner.apiKey, owner.environment)
                when (val result = api.provision(intent.merchant, intent.reference, recover = previous != null)) {
                    is ProvisionedCredential.Failed -> {
                        if (previous == null && !result.creationMayExist) setups.boardingCredentials.write(stored)
                        TapToPayOutcome.Failed(Failure.Remote(result.fault))
                    }

                    is ProvisionedCredential.Ready -> {
                        val ready = BoardingCredential(intent.owner, intent.merchant, intent.environment, intent.reference, result.apiKey)
                        val kept = stored.filter { it.owner == intent.owner && it.reference != intent.reference }
                        setups.boardingCredentials.write(kept + ready)
                        null
                    }
                }
            } catch (ignored: SecretStoreException) {
                TapToPayOutcome.Failed(Failure.Device(DeviceFault.SECURE_STORAGE))
            }
        }

    private suspend fun boardSaved(
        origin: TerminalSetup,
        access: Access.Granted,
        reboard: Boolean,
    ): TapToPayOutcome {
        val fence = setups.SetupFence(origin, access.boarding)
        val original = origin.settings.terminal
        val attempt = Attempt(origin, original.target(), access)
        val result = register(attempt, reboard)
        if (result !is TapToPayOutcome.Boarded) return result
        val boarded = original.copy(paymentsAppInstallationId = result.installationId)
        fence.update { it.copy(terminal = boarded, boardingIdentity = attempt.identity ?: it.boardingIdentity) }
        return if (settings.current().terminal == boarded) {
            diagnostics.registrationSaved(access.boarding)
            result
        } else {
            TapToPayOutcome.NotSetUp(SetupProblem.SETUP_CHANGED)
        }
    }

    internal suspend fun checkSaved(setup: TerminalSetup): TapToPayOutcome {
        setups.SetupFence(setup).changed()?.let { return TapToPayOutcome.NotSetUp(it) }
        val terminal = setup.settings.terminal
        return when (val access = setups.boarding()) {
            is BoardingSetup.Blocked -> {
                TapToPayOutcome.NotSetUp(access.problem)
            }

            is BoardingSetup.Ready -> {
                if (terminal.paymentsAppInstallationId.isBlank()) {
                    TapToPayOutcome.NotSetUp(SetupProblem.PAYMENTS_APP_NOT_BOARDED)
                } else {
                    registered(access, terminal.target(), terminal.paymentsAppInstallationId)
                }
            }
        }
    }

    /**
     * Whether the Payments app is boarded for the [candidate] setup; [explicit] setup also creates this phone's own
     * credential and boards it. Without one, that explicit setup is what is missing
     * ([SetupProblem.PAYMENTS_APP_NOT_BOARDED]).
     */
    internal suspend fun candidate(
        candidate: UnlockedSetup,
        explicit: Boolean,
    ): TapToPayOutcome {
        val unprepared =
            if (explicit) {
                when (val owner = setups.owner(candidate)) {
                    is BoardingOwner.Blocked -> TapToPayOutcome.NotSetUp(owner.problem)
                    is BoardingOwner.Ready -> prepare(owner)
                }
            } else {
                null
            }
        if (unprepared != null) return unprepared
        return when (val access = setups.boarding(candidate)) {
            is BoardingSetup.Blocked -> {
                val unprovisioned = access.problem in setOf(SetupProblem.PAYMENTS_APP_API_KEY, SetupProblem.UNREADABLE_PAYMENTS_APP_KEY)
                TapToPayOutcome.NotSetUp(if (unprovisioned) SetupProblem.PAYMENTS_APP_NOT_BOARDED else access.problem)
            }

            is BoardingSetup.Ready -> {
                candidateRegistration(
                    candidate.setup.settings.terminal
                        .target(),
                    access,
                    explicit,
                )
            }
        }
    }

    private suspend fun candidateRegistration(
        target: BoardingTarget,
        access: BoardingSetup.Ready,
        explicit: Boolean,
    ): TapToPayOutcome {
        val previous =
            when (val stored = journal.read()) {
                JournalRead.Absent -> null
                is JournalRead.Present -> stored.value
                JournalRead.Unreadable -> return TapToPayOutcome.Failed(Failure.Device(DeviceFault.SECURE_STORAGE))
            }
        val same = previous?.matches(access, target) == true
        val origin = setups.current()
        val active = origin.settings.terminal
        val sameEnvironment = active.environment == access.environment
        val activeMatches = sameEnvironment && active.targetOrNull() == target
        val installation =
            if (same) {
                previous.installation
            } else {
                active.paymentsAppInstallationId.takeIf { activeMatches && it.isNotBlank() }
            }
        return when {
            installation != null -> {
                registered(access, target, installation)
            }

            !explicit -> {
                TapToPayOutcome.NotSetUp(SetupProblem.PAYMENTS_APP_NOT_BOARDED)
            }

            else -> {
                register(
                    Attempt(origin, target, granted(access)),
                    reboard = !same && sameEnvironment && active.paymentsAppInstallationId.isNotBlank() && !activeMatches,
                )
            }
        }
    }

    /**
     * Boards this phone for [attempt] ([reboard] afresh) and checks it with Adyen's list of boarded instances, unless
     * the boarding token already confirmed it. A Payments app already boarded but not listed at the target needs an
     * explicit reboarding action; a diagnostic failure never starts reboarding.
     */
    private suspend fun register(
        attempt: Attempt,
        reboard: Boolean,
    ): TapToPayOutcome =
        try {
            confirmed.set(null)
            remember(attempt, null)
            val access = attempt.access
            attempt.credential = diagnostics.inspect(attempt.origin, access.boarding)
            val onboarding = PaymentsAppOnboarding(access.environment, exchange, access.management, PaymentsAppBridge.RETURN_URL)
            when (val result = onboarding.board(attempt.target, reboard)) {
                is Onboarding.AlreadyBoarded -> {
                    listing(attempt, result.installationId).outcome(result.installationId)
                }

                else -> {
                    settle(attempt, result)
                }
            }
        } catch (ignored: SecretStoreException) {
            TapToPayOutcome.Failed(Failure.Device(DeviceFault.SECURE_STORAGE))
        }

    /**
     * One [register] call: it boards this phone at the merchant account or store [target], with this phone's own
     * credential, environment and Management API ([access]) that each installation reported is remembered with for recovery.
     * [origin] is the setup stored when it began, whose installation ID is forgotten if the Payments app may have
     * dropped its boarding.
     */
    private class Attempt(
        val origin: TerminalSetup,
        val target: BoardingTarget,
        val access: Access.Granted,
    ) {
        var credential: BoardingCredentialCheck? = null
        var identity: BoardingIdentity? = null
    }

    /**
     * What boarding [result] comes to: listed at the target, unless the boarding token already confirmed it. Once the
     * Payments app may have dropped its boarding without being registered here, the stored installation ID is forgotten.
     */
    private suspend fun settle(
        attempt: Attempt,
        result: Onboarding,
    ): TapToPayOutcome {
        if (result is Onboarding.NewlyBoarded) {
            attempt.identity =
                diagnostics.boardingIdentity(
                    attempt.access.boarding,
                    attempt.target,
                    result.installationId,
                    attempt.credential,
                )
        }
        return when (result) {
            is Onboarding.Failed -> {
                if (result.unboarded) forget(attempt)
                TapToPayOutcome.Failed(Failure.Remote(result.fault))
            }

            is Onboarding.NewlyBoarded if result.confirmed -> {
                confirmed.set(remember(attempt, result.installationId))
                TapToPayOutcome.Boarded(result.installationId)
            }

            is Onboarding.Boarded -> {
                val outcome = listing(attempt, result.installationId).outcome(result.installationId)
                if (result is Onboarding.NewlyBoarded && outcome !is TapToPayOutcome.Boarded) forget(attempt)
                outcome
            }
        }
    }

    /**
     * Forgets the installation ID stored when [attempt] began only for that same environment and unchanged setup,
     * so setup asks for Tap to Pay again without dropping another installed app's registration.
     */
    private suspend fun forget(attempt: Attempt) {
        val origin = attempt.origin
        val terminal = origin.settings.terminal
        if (terminal.environment != attempt.access.environment || terminal.paymentsAppInstallationId.isBlank()) return
        setups.SetupFence(origin).update { it.copy(terminal = terminal.copy(paymentsAppInstallationId = "")) }
    }

    /** Remembers installation [id] for [attempt], then asks Adyen whether it is listed at the target. */
    private suspend fun listing(
        attempt: Attempt,
        id: String,
    ): ManagementResult {
        remember(attempt, id)
        return attempt.access.management.registration(attempt.target, id)
    }

    private suspend fun remember(
        attempt: Attempt,
        installation: String?,
    ): RegistrationRecovery {
        val target = attempt.target
        val access = attempt.access
        val recovery =
            RegistrationRecovery(access.apiKey, access.environment, target.merchantAccount, target.storeId, installation, attempt.identity)
        journal.write(recovery)
        return recovery
    }

    /**
     * Evidence that [installation] was newly boarded with the [candidate]'s own credential and target; null when none
     * was recorded with that boarding, as for a status-only check.
     */
    internal suspend fun candidateIdentity(
        candidate: UnlockedSetup,
        installation: String,
    ): BoardingIdentity? {
        val access = setups.boarding(candidate) as? BoardingSetup.Ready ?: return null
        val previous =
            when (val stored = journal.read()) {
                is JournalRead.Present -> stored.value
                JournalRead.Absent, JournalRead.Unreadable -> return null
            }
        return previous
            .takeIf {
                it.matches(
                    access,
                    candidate.setup.settings.terminal
                        .target(),
                ) && it.installation == installation
            }?.identity
    }

    /**
     * Whether installation [id] is listed at [target] for [access]. A boarding the token [confirmed] counts as listed
     * until the list first shows it.
     */
    private suspend fun registered(
        access: BoardingSetup.Ready,
        target: BoardingTarget,
        id: String,
    ): TapToPayOutcome {
        val listed = management(access.apiKey, access.environment).registration(target, id)
        val vouched = confirmed.get()?.takeIf { it.matches(access, target) && it.installation == id } ?: return listed.outcome(id)
        if (listed is ManagementResult.Done) confirmed.compareAndSet(vouched, null)
        return if (listed == ManagementResult.NotBoarded) TapToPayOutcome.Boarded(id) else listed.outcome(id)
    }

    /** What this answer to the Management API's registration check of installation [id] means for Tap to Pay. */
    private fun ManagementResult.outcome(id: String): TapToPayOutcome =
        when (this) {
            is ManagementResult.Done -> TapToPayOutcome.Boarded(id)
            ManagementResult.NotBoarded -> TapToPayOutcome.NotSetUp(SetupProblem.PAYMENTS_APP_NOT_BOARDED)
            is ManagementResult.Failed -> TapToPayOutcome.Failed(Failure.Remote(fault))
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
        val identity: BoardingIdentity? = null,
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
        val origin = setups.current()
        val access =
            when (val checked = access()) {
                is Access.Denied -> return TapToPayOutcome.NotSetUp(checked.problem)
                is Access.Granted -> checked
            }
        setups.SetupFence(origin, access.boarding).changed()?.let { return TapToPayOutcome.NotSetUp(it) }
        val terminal = origin.settings.terminal
        val id = terminal.paymentsAppInstallationId.trim()
        val result = if (id.isEmpty()) ManagementResult.Done() else access.management.revoke(terminal.merchantAccount.trim(), id)
        return when (result) {
            is ManagementResult.Failed -> {
                TapToPayOutcome.Failed(Failure.Remote(result.fault))
            }

            ManagementResult.NotBoarded -> {
                TapToPayOutcome.Failed(Failure.Remote(Fault.UnreadableReply()))
            }

            is ManagementResult.Done -> {
                confirmed.set(null)
                // The instance is revoked whatever changed meanwhile; only a newer boarding is kept, with its recovery.
                var revoked = false
                settings.update {
                    revoked = it.terminal.environment == access.environment && it.terminal.paymentsAppInstallationId.trim() == id
                    if (revoked) it.copy(terminal = it.terminal.copy(paymentsAppInstallationId = ""), boardingIdentity = null) else it
                }
                if (revoked) journal.write(null)
                TapToPayOutcome.Unregistered
            }
        }
    }

    /** The Management API for the selected Payments app, or what is missing for it ([TerminalSetup.boarding]). */
    private suspend fun access(): Access =
        when (val boarding = setups.boarding()) {
            is BoardingSetup.Blocked -> {
                Access.Denied(boarding.problem)
            }

            is BoardingSetup.Ready -> {
                granted(boarding)
            }
        }

    private fun granted(boarding: BoardingSetup.Ready): Access.Granted =
        Access.Granted(boarding, management(boarding.apiKey, boarding.environment))

    private sealed interface Access {
        class Granted(
            val boarding: BoardingSetup.Ready,
            val management: PaymentsAppManagement,
        ) : Access {
            val environment: TerminalEnvironment get() = boarding.environment
            val apiKey: String get() = boarding.apiKey
        }

        class Denied(
            val problem: SetupProblem,
        ) : Access
    }
}
