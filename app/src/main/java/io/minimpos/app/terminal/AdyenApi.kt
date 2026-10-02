package io.minimpos.app.terminal

import io.minimpos.app.data.security.Secret
import io.minimpos.app.data.security.SecretStore
import io.minimpos.app.data.settings.CaptureMode
import io.minimpos.app.data.settings.TerminalSettings
import io.minimpos.terminal.checkout.CheckoutCredentials
import io.minimpos.terminal.checkout.CheckoutModifications
import io.minimpos.terminal.checkout.PaymentModifications
import io.minimpos.terminal.transport.TerminalEnvironment

/**
 * How far the Checkout API is set up, see [TerminalSetup.apiSetup]: the one decision behind both what the screens show
 * ([mode], [problem]) and where captures go ([AdyenApi.target], which also finds a saved API key that can no longer be
 * read).
 */
sealed interface ApiSetup {
    /** How captures are made: in the Customer Area only for [CustomerArea], else through the API. */
    val mode: CaptureMode get() = if (this == CustomerArea) CaptureMode.CUSTOMER_AREA else CaptureMode.API

    /** What must still be entered before the API can be called; null unless [Incomplete]. */
    val problem: SetupProblem? get() = (this as? Incomplete)?.problem

    /** Payments go to the simulator, so the API is simulated too. */
    data object Simulated : ApiSetup

    /** Nothing of the API is entered: staff capture in the Customer Area. */
    data object CustomerArea : ApiSetup

    /**
     * Something of the API is entered, but not all of it, so captures fail visibly instead of silently going to the
     * Customer Area.
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
 * Where captures and adjustments go now, see [AdyenApi.target].
 *
 * @property setup How far the API is set up, which decides how captures are made ([ApiSetup.mode]) and, when nothing can
 *   be sent, why ([ApiSetup.problem]); [ApiSetup.Incomplete] with [SetupProblem.UNREADABLE_API_KEY] when the saved key
 *   cannot be decrypted.
 * @property modifications Sends the captures and adjustments; null when the API cannot be called ([ApiSetup.CustomerArea]
 *   or [ApiSetup.Incomplete]).
 */
data class ApiTarget(
    val setup: ApiSetup,
    val modifications: PaymentModifications? = null,
)

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
 * hold. It is optional and set up in Settings › Terminal (merchant account and live URL prefix in
 * [io.minimpos.app.data.settings.TerminalSettings], the API key in [SecretStore]); without it, staff capture in the
 * Customer Area. How far it is set up is [TerminalSetup.apiSetup]. While payments go to the simulator the API is
 * simulated too. The environment (TEST or LIVE) is where payments go ([TerminalSetup.environment]): the terminal
 * certificate's, the cloud API key's or the installed Payments app's.
 *
 * @param setups Where payments go now, and how far the API is set up.
 * @param secrets Holds the API key.
 * @param simulated Answers while payments go to the simulator: in the app the [SimulatedTerminal]'s, which knows the
 *   simulator's payments.
 * @param connect Makes the client for real credentials; tests replace it.
 */
class AdyenApi(
    private val setups: TerminalSetupSource,
    private val secrets: SecretStore,
    private val simulated: PaymentModifications,
    private val connect: (CheckoutCredentials) -> PaymentModifications = { CheckoutModifications(it) },
) {
    private val clients = Reused<CheckoutCredentials, PaymentModifications>()

    /** Where captures and adjustments go now with the stored settings, as [TerminalSetup.apiSetup] decides. */
    suspend fun target(): ApiTarget {
        val setup = setups.current()
        return when (val api = setup.apiSetup) {
            ApiSetup.Simulated -> ApiTarget(api, simulated)
            ApiSetup.Complete -> connected(setup.settings.terminal, checkNotNull(setup.environment))
            ApiSetup.CustomerArea, is ApiSetup.Incomplete -> ApiTarget(api)
        }
    }

    /** The client for [terminal]'s credentials in [environment], which are complete; reused while they stay the same. */
    private suspend fun connected(
        terminal: TerminalSettings,
        environment: TerminalEnvironment,
    ): ApiTarget {
        val key = secrets.get(Secret.CHECKOUT_API_KEY) ?: return ApiTarget(ApiSetup.Incomplete(SetupProblem.UNREADABLE_API_KEY))
        val credentials = CheckoutCredentials(key, terminal.merchantAccount.trim(), environment, terminal.liveUrlPrefix.trim())
        return ApiTarget(ApiSetup.Complete, clients.get(credentials, connect))
    }

    /** Checks that the API can be used with the stored settings, without changing anything. */
    suspend fun verify(): ApiCheck {
        val target = target()
        val modifications = target.modifications ?: return ApiCheck.NotSetUp(target.setup.problem ?: SetupProblem.API_REQUIRED)
        return modifications.verify()?.let(ApiCheck::Failed) ?: ApiCheck.Works
    }
}
