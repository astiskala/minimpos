package io.github.astiskala.minimpos.app.terminal

import io.github.astiskala.minimpos.app.data.db.SetupProblem
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.data.security.SecretStore
import io.github.astiskala.minimpos.app.data.settings.TerminalSettings
import io.github.astiskala.minimpos.core.money.PaymentContext
import io.github.astiskala.minimpos.terminal.checkout.CheckoutCredentials
import io.github.astiskala.minimpos.terminal.checkout.CheckoutModifications
import io.github.astiskala.minimpos.terminal.checkout.CheckoutPaymentLinks
import io.github.astiskala.minimpos.terminal.checkout.PaymentLinkApi
import io.github.astiskala.minimpos.terminal.checkout.PaymentModifications
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment

/**
 * How far the Checkout API is set up, see [TerminalSetup.apiSetup]: the one decision behind both what the screens show
 * ([problem]) and where captures go ([AdyenApi.target], whose setup [TerminalSetup.unlock] also says when a saved API key
 * can no longer be read).
 */
sealed interface ApiSetup {
    /** What must still be entered before the API can be called; null unless [Incomplete]. */
    val problem: SetupProblem? get() = (this as? Incomplete)?.problem

    /** Payments go to the simulator, so the API is simulated too. */
    data object Simulated : ApiSetup

    /**
     * Not all of the API is entered (or known), so captures, adjustments and payment links fail visibly, and payments
     * wait for it too (see [TerminalSetup.problem]).
     *
     * @property problem What to enter.
     */
    data class Incomplete(
        override val problem: SetupProblem,
    ) : ApiSetup

    /** Everything is entered; the API can be called. */
    data object Complete : ApiSetup
}

/**
 * Where captures, adjustments and payment links go now, see [AdyenApi.target].
 *
 * @param setup How far the API is set up, which decides, when nothing can be sent, why ([ApiSetup.problem]);
 *   [ApiSetup.Incomplete] with [SetupProblem.UNREADABLE_API_KEY] when the saved key cannot be decrypted.
 * @param modifications Sends the captures and adjustments; null when the API cannot be called
 *   ([ApiSetup.Incomplete]).
 * @param links Creates, checks and expires payment links; null unless the API is [ApiSetup.Complete] (payment links
 *   are never simulated).
 * @property context Actual account and environment; isolated fake adapters may omit it.
 */
data class ApiTarget(
    private val setup: ApiSetup,
    private val modifications: PaymentModifications? = null,
    private val links: PaymentLinkApi? = null,
    /** Actual account/environment; fixed fake targets may omit it. */
    val context: PaymentContext? = null,
) {
    /** Capture/adjustment access for [expected]; missing stored context is blocked unless this is a context-free fake. */
    fun modifications(expected: PaymentContext?): ApiAccess<PaymentModifications> = access(modifications, expected)

    /** Link access for [expected]; simulated targets have no link adapter, even when modifications are available. */
    fun links(expected: PaymentContext?): ApiAccess<PaymentLinkApi> = access(links, expected)

    private fun <T : Any> access(
        client: T?,
        expected: PaymentContext?,
    ): ApiAccess<T> =
        when {
            client == null -> ApiAccess.Unavailable(setup.problem ?: SetupProblem.API_REQUIRED)
            context != null && expected?.matchesApi(context) != true -> ApiAccess.ContextMismatch
            else -> ApiAccess.Ready(client)
        }
}

/** Eligibility at the current target, before any stored operation is sent; adapters are exposed only when ready. */
sealed interface ApiAccess<out T> {
    /**
     * The adapter may send the operation for its stored context.
     * @param T The eligible adapter type.
     * @property client The eligible adapter.
     */
    data class Ready<T>(
        val client: T,
    ) : ApiAccess<T>

    /** Nothing may be sent; callers decide whether the operation's sale event should record this failure. */
    sealed interface Blocked : ApiAccess<Nothing> {
        /** Missing setup or a different payment context; worded only by screens. */
        val problem: SetupProblem
    }

    /**
     * No adapter is available. A pending creation may record this as known failure, but an unknown creation may not.
     * @property problem Missing or unreadable setup.
     */
    data class Unavailable(
        override val problem: SetupProblem,
    ) : Blocked

    /** Current credentials do not match the stored operation; its record must remain unchanged. */
    data object ContextMismatch : Blocked {
        override val problem: SetupProblem = SetupProblem.PAYMENT_CONTEXT
    }
}

/** What [AdyenApi.verify] found. */
sealed interface ApiCheck {
    /** Adyen accepted the API key and merchant account (or the API is simulated). */
    data object Works : ApiCheck

    /**
     * The API cannot be called yet.
     *
     * @property problem What to enter first.
     */
    data class NotSetUp(
        val problem: SetupProblem,
    ) : ApiCheck

    /**
     * Adyen refused, or could not be reached.
     *
     * @property message Why, as Adyen or the client worded it.
     */
    data class Failed(
        val message: String,
    ) : ApiCheck
}

/**
 * Adyen's Checkout API as the app uses it: for capturing payments taken with manual capture and adjusting what they
 * hold, and for payment links. It is required wherever payments go and set up in Settings › Terminal (merchant account
 * and live URL prefix in [io.github.astiskala.minimpos.app.data.settings.TerminalSettings], the API key in
 * [io.github.astiskala.minimpos.app.data.security.SecretStore]). How far it is set up is [TerminalSetup.apiSetup]. While payments go to
 * the simulator the API is simulated too. The environment (TEST or LIVE) is where payments go
 * ([TerminalSetup.environment]): the terminal certificate's, the cloud API key's or the installed Payments app's.
 *
 * @param setups Where payments go now, how far the API is set up, and the API key.
 * @param simulated Answers while payments go to the simulator: in the app the [SimulatedTerminal]'s, which knows the
 *   simulator's payments.
 * @param connect Makes the client for real credentials; tests replace it.
 * @param connectLinks Makes the payment link client for real credentials; tests replace it.
 */
class AdyenApi(
    private val setups: TerminalSetupSource,
    private val simulated: PaymentModifications,
    private val connect: (CheckoutCredentials) -> PaymentModifications = { CheckoutModifications(it) },
    private val connectLinks: (CheckoutCredentials) -> PaymentLinkApi = { CheckoutPaymentLinks(it) },
) {
    private val clients = Reused<CheckoutCredentials, PaymentModifications>()
    private val linkClients = Reused<CheckoutCredentials, PaymentLinkApi>()

    /** Where captures, adjustments and payment links go now with the stored settings, as [TerminalSetup.apiSetup] decides. */
    suspend fun target(): ApiTarget {
        val unlocked = setups.unlocked()
        val setup = unlocked.setup
        val target =
            when (val api = setup.apiSetup) {
                ApiSetup.Simulated -> ApiTarget(api, simulated)
                ApiSetup.Complete -> connected(setup.settings.terminal, checkNotNull(setup.environment), checkNotNull(unlocked.apiKey))
                is ApiSetup.Incomplete -> ApiTarget(api)
            }
        return target.copy(context = setup.paymentContext())
    }

    /** The client for [terminal]'s credentials with [apiKey] in [environment]; reused while they stay the same. */
    private fun connected(
        terminal: TerminalSettings,
        environment: TerminalEnvironment,
        apiKey: String,
    ): ApiTarget {
        val credentials = CheckoutCredentials(apiKey, terminal.merchantAccount.trim(), environment, terminal.liveUrlPrefix.trim())
        return ApiTarget(ApiSetup.Complete, clients.get(credentials, connect), linkClients.get(credentials, connectLinks))
    }

    /** Checks that the API can be used with the stored settings, without changing anything. */
    suspend fun verify(): ApiCheck {
        val target = target()
        return when (val access = target.modifications(target.context)) {
            is ApiAccess.Ready -> access.client.verify()?.let(ApiCheck::Failed) ?: ApiCheck.Works
            is ApiAccess.Blocked -> ApiCheck.NotSetUp(access.problem)
        }
    }
}
