package app.minimpos.app.terminal

import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.settings.BoardingIdentity
import app.minimpos.app.data.settings.SettingsRepository
import app.minimpos.terminal.paymentsapp.AppLinkAnswer
import app.minimpos.terminal.paymentsapp.AppLinkExchange
import app.minimpos.terminal.paymentsapp.BoardingCredentialCheck
import app.minimpos.terminal.paymentsapp.BoardingTarget
import app.minimpos.terminal.paymentsapp.PaymentsAppLinks
import app.minimpos.terminal.paymentsapp.PaymentsAppManagement
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

/** Advisory client-key presence; failures never imply absence or block a payment. */
enum class ClientKeyCheck {
    /** No current check, or its credential/environment changed. */
    NOT_CHECKED,

    /** A documented successful response contains a nonblank clientKey. */
    PRESENT,

    /** A documented successful response contains an empty clientKey string. */
    MISSING,

    /** GET /me was forbidden; payment permissions are not changed. */
    PERMISSION_UNAVAILABLE,

    /** Authentication failed in the one selected environment; no cross-environment probe. */
    AUTHENTICATION_FAILED,

    /** Transport, API or schema failure; presence is unknown. */
    FAILED,
}

/** Supported /boarded callback, independent of credential saving, key presence and payment outcomes. */
enum class PaymentsAppBoardingStatus {
    /** No current supported status check. */
    NOT_CHECKED,

    /** The Payments app answered boarded=true. */
    BOARDED,

    /** The Payments app answered boarded=false. */
    NOT_BOARDED,

    /** No usable supported callback. */
    UNVERIFIED,
}

/**
 * Non-secret identity verified by GET /me.
 * @property id Credential identifier, never an API/client key.
 * @property username Credential name from Adyen.
 * @property active Whether Adyen reports it enabled.
 */
data class TapToPayCredential(
    val id: String,
    val username: String,
    val active: Boolean,
)

/**
 * Non-secret diagnostic reading; null identities/times mean unavailable, never an assumed successful check.
 * @property environment Selected Payments app environment checked, or null before checking.
 * @property clientKey Credential introspection result.
 * @property credential Verified /me result, containing presence only, never the client key value.
 * @property checkedAt UTC epoch milliseconds of the credential response, or null before checking.
 * @property boarding Independent supported boarding-status result.
 * @property boardingAt UTC epoch milliseconds of that callback, or null before checking.
 * @property installationId Installation actually reported by /boarded, or null without a readable callback.
 * @property associated Whether persisted new-boarding evidence matches this verified credential and current installation.
 * @property boardingIdentity Original boarding evidence for this installation/environment, independent of the current credential.
 * @property failure Why the credential check could not run; null for a verified credential.
 * @property apiHasRequiredRole Whether the boarding role was observed, or null before a readable role check.
 */
data class TapToPayCheck(
    val environment: TerminalEnvironment? = null,
    val clientKey: ClientKeyCheck = ClientKeyCheck.NOT_CHECKED,
    val credential: TapToPayCredential? = null,
    val checkedAt: Long? = null,
    val boarding: PaymentsAppBoardingStatus = PaymentsAppBoardingStatus.NOT_CHECKED,
    val boardingAt: Long? = null,
    val installationId: String? = null,
    val associated: Boolean = false,
    val boardingIdentity: BoardingIdentity? = null,
    val failure: Failure? = null,
    val apiHasRequiredRole: Boolean? = null,
)

/**
 * Optional read-only checks, never invoked by a payment. Cached conclusions are fenced to the separate boarding
 * credential and installed environment; changes immediately expose Not checked. Explicit refresh always reads again.
 * @param setups Owns credential access and freshness checks.
 * @param settings Stores only evidence of an actual new boarding.
 * @param exchange Opens supported Payments app status links.
 * @param management Uses the same credential as boarding-token generation.
 * @param scope Keeps state invalidation alive independently of Settings navigation.
 * @param now UTC epoch milliseconds, replaceable by tests.
 */
class TapToPayDiagnostics(
    private val setups: TerminalSetupSource,
    private val settings: SettingsRepository,
    private val exchange: AppLinkExchange,
    private val management: (String, TerminalEnvironment) -> PaymentsAppManagement,
    scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Cached(
        val fence: TerminalSetupSource.SetupFence?,
        val reading: TapToPayCheck,
    )

    private val cached = MutableStateFlow(Cached(null, TapToPayCheck()))
    private val refreshes = Mutex()

    /** Live, non-secret reading; observing it never sends a network request or opens the Payments app. */
    val state =
        combine(cached, setups.boardingChanges) { saved, _ ->
            if (saved.fence?.stillCurrent() == true) saved.reading else TapToPayCheck()
        }.stateIn(scope, SharingStarted.Eagerly, TapToPayCheck())

    /** Rechecks the current credential and supported /boarded status without generating tokens or reboarding. */
    suspend fun refresh(): TapToPayCheck =
        refreshes.withLock {
            val origin = setups.current()
            when (val access = setups.boarding()) {
                is BoardingSetup.Blocked -> {
                    publish(setups.SetupFence(origin), TapToPayCheck(failure = Failure.NotSetUp(access.problem)))
                }

                is BoardingSetup.Ready -> {
                    inspect(origin, access)
                    val before = cached.value
                    val answer =
                        exchange.exchange(
                            PaymentsAppLinks(access.environment).boardedCheck("${PaymentsAppBridge.RETURN_URL}/boarded"),
                            PaymentsAppLinks.packageName(access.environment),
                            30.seconds,
                        )
                    val fields = (answer as? AppLinkAnswer.Returned)?.let { PaymentsAppLinks.answer(it.url) }.orEmpty()
                    val boarding =
                        when (fields["boarded"]) {
                            "true" -> PaymentsAppBoardingStatus.BOARDED
                            "false" -> PaymentsAppBoardingStatus.NOT_BOARDED
                            else -> PaymentsAppBoardingStatus.UNVERIFIED
                        }
                    val next =
                        before.reading.copy(
                            boarding = boarding,
                            boardingAt = now(),
                            installationId = fields["installationId"]?.takeIf { it.isNotBlank() },
                        )
                    publish(before.fence, withBoardingIdentity(next))
                }
            }
        }

    /** Preflight for the exact [access] that will request a boarding token; no app handoff or blocking prerequisite. */
    internal suspend fun inspect(
        origin: TerminalSetup,
        access: BoardingSetup.Ready,
    ): BoardingCredentialCheck {
        val fence = setups.SetupFence(origin, access)
        val result = management(access.apiKey, access.environment).credential()
        val reading =
            TapToPayCheck(
                environment = access.environment,
                clientKey =
                    when (result) {
                        is BoardingCredentialCheck.Verified -> {
                            if (result.clientKeyPresent) ClientKeyCheck.PRESENT else ClientKeyCheck.MISSING
                        }

                        is BoardingCredentialCheck.Failed -> {
                            when (result.fault) {
                                is Fault.Permission -> ClientKeyCheck.PERMISSION_UNAVAILABLE
                                is Fault.Credential -> ClientKeyCheck.AUTHENTICATION_FAILED
                                is Fault.NotSent, is Fault.MaybeSent -> ClientKeyCheck.FAILED
                            }
                        }
                    },
                credential = (result as? BoardingCredentialCheck.Verified)?.let { TapToPayCredential(it.id, it.username, it.active) },
                checkedAt = now(),
                failure = (result as? BoardingCredentialCheck.Failed)?.let { Failure.Remote(it.fault) },
                apiHasRequiredRole = (result as? BoardingCredentialCheck.Verified)?.hasRequiredRole,
            )
        publish(fence, withBoardingIdentity(reading))
        return result
    }

    /** Evidence only for an actual new boarding, persisted by its setup transaction, never by the advisory check. */
    internal fun boardingIdentity(
        access: BoardingSetup.Ready,
        target: BoardingTarget,
        installation: String,
        credential: BoardingCredentialCheck?,
    ): BoardingIdentity {
        val verified = credential as? BoardingCredentialCheck.Verified
        return BoardingIdentity(
            access.environment,
            installation,
            target.merchantAccount,
            target.storeId,
            verified?.id,
            verified?.username,
            now(),
        )
    }

    /** Carries a preflight across this operation's installation-ID write, only for the same original boarding key. */
    internal suspend fun registrationSaved(access: BoardingSetup.Ready) {
        val previous = cached.value.reading
        val fence = setups.SetupFence(setups.current(), access)
        publish(fence, withBoardingIdentity(previous))
    }

    private suspend fun withBoardingIdentity(reading: TapToPayCheck): TapToPayCheck {
        val current = settings.current()
        val terminal = current.terminal
        val evidence =
            current.boardingIdentity
                ?.takeIf { it.environment == reading.environment }
                ?.takeIf { it.installationId == (reading.installationId ?: terminal.paymentsAppInstallationId) }
                ?.takeIf { it.merchant == terminal.merchantAccount.trim() && it.store.orEmpty() == terminal.storeId.trim() }
        return reading.copy(
            boardingIdentity = evidence,
            associated = evidence?.credentialId != null && evidence.credentialId == reading.credential?.id,
        )
    }

    private suspend fun publish(
        fence: TerminalSetupSource.SetupFence?,
        reading: TapToPayCheck,
    ): TapToPayCheck {
        val next = if (fence?.stillCurrent() == true) Cached(fence, reading) else Cached(null, TapToPayCheck())
        cached.value = next
        return next.reading
    }
}
