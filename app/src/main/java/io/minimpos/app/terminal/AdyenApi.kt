package io.minimpos.app.terminal

import io.minimpos.app.data.security.Secret
import io.minimpos.app.data.security.SecretStore
import io.minimpos.app.data.settings.CaptureMode
import io.minimpos.app.data.settings.TerminalSettings
import io.minimpos.terminal.checkout.CheckoutCredentials
import io.minimpos.terminal.checkout.CheckoutModifications
import io.minimpos.terminal.checkout.PaymentModifications

/**
 * How far the Checkout API is set up with given settings, see [TerminalSetup.apiSetup]: the one decision behind both
 * what the screens show ([mode], [problem]) and where captures go ([AdyenApi.target]).
 */
sealed interface ApiSetup {
    /** How captures are made: in the Customer Area only for [CustomerArea], else through the API. */
    val mode: CaptureMode get() = if (this == CustomerArea) CaptureMode.CUSTOMER_AREA else CaptureMode.API

    /** What must still be entered before the API can be called; null unless [Incomplete]. */
    val problem: String? get() = (this as? Incomplete)?.message

    /** Payments go to the simulator, so the API is simulated too. */
    data object Simulated : ApiSetup

    /** Nothing of the API is entered: staff capture in the Customer Area. */
    data object CustomerArea : ApiSetup

    /**
     * Something of the API is entered, but not all of it, so captures fail visibly instead of silently going to the
     * Customer Area.
     *
     * @property message What to enter.
     */
    data class Incomplete(
        val message: String,
    ) : ApiSetup

    /** Everything is entered; the API can be called. */
    data object Complete : ApiSetup
}

/** Where captures and adjustments go now, see [AdyenApi.target]. */
sealed interface ApiTarget {
    /** How captures are made with this target: in the Customer Area only for [CustomerArea], else through the API. */
    val captureMode: CaptureMode get() = if (this == CustomerArea) CaptureMode.CUSTOMER_AREA else CaptureMode.API

    /**
     * The Checkout API (or its simulation) can be called.
     *
     * @property modifications Sends the captures and adjustments.
     */
    data class Ready(
        val modifications: PaymentModifications,
    ) : ApiTarget

    /** No Checkout API is set up ([CaptureMode.CUSTOMER_AREA]). */
    data object CustomerArea : ApiTarget

    /**
     * The Checkout API is partly set up, so nothing can be sent.
     *
     * @property message What to enter, as [ApiSetup.Incomplete] words it.
     */
    data class NotSetUp(
        val message: String,
    ) : ApiTarget
}

/**
 * Adyen's Checkout API as the app uses it: for capturing payments taken with manual capture and adjusting what they
 * hold. It is optional and set up in Settings › Terminal (merchant account and live URL prefix in
 * [io.minimpos.app.data.settings.TerminalSettings], the API key in [SecretStore]); without it, staff capture in the
 * Customer Area. How far it is set up is [TerminalSetup.apiSetup]. While payments go to the simulator the API is
 * simulated too. The environment (TEST or LIVE) is the terminal's, from its certificate.
 *
 * @param setups Where payments go now, and how far the API is set up.
 * @param secrets Holds the API key.
 * @param simulated Answers while payments go to the simulator: in the app the gateway's, which knows the simulator's
 *   payments ([TerminalGateway.simulatedModifications]).
 * @param connect Makes the client for real credentials; tests replace it.
 */
class AdyenApi(
    private val setups: TerminalSetupSource,
    private val secrets: SecretStore,
    private val simulated: PaymentModifications,
    private val connect: (CheckoutCredentials) -> PaymentModifications = { CheckoutModifications(it) },
) {
    @Volatile private var cached: Pair<CheckoutCredentials, PaymentModifications>? = null

    /** Where captures and adjustments go now with the stored settings, as [TerminalSetup.apiSetup] decides. */
    suspend fun target(): ApiTarget {
        val setup = setups.current()
        return when (val api = setup.apiSetup) {
            ApiSetup.Simulated -> ApiTarget.Ready(simulated)
            ApiSetup.CustomerArea -> ApiTarget.CustomerArea
            is ApiSetup.Incomplete -> ApiTarget.NotSetUp(api.message)
            ApiSetup.Complete -> connected(setup.settings.terminal)
        }
    }

    /** The client for [terminal]'s credentials, which are complete; reused while they stay the same. */
    private suspend fun connected(terminal: TerminalSettings): ApiTarget {
        val key =
            secrets.get(Secret.CHECKOUT_API_KEY)
                ?: return ApiTarget.NotSetUp(
                    "The saved Checkout API key could not be read on this device; enter it again in Terminal settings",
                )
        val credentials =
            CheckoutCredentials(key, terminal.merchantAccount.trim(), checkNotNull(terminal.environment), terminal.liveUrlPrefix.trim())
        val modifications = cached?.takeIf { it.first == credentials }?.second ?: connect(credentials).also { cached = credentials to it }
        return ApiTarget.Ready(modifications)
    }

    /**
     * Checks that the API can be used with the stored settings, without changing anything; null when it can, else why
     * not (including what is still missing).
     */
    suspend fun verify(): String? =
        when (val target = target()) {
            is ApiTarget.Ready -> target.modifications.verify()
            ApiTarget.CustomerArea -> "Enter the merchant account and the Checkout API key first"
            is ApiTarget.NotSetUp -> target.message
        }
}
