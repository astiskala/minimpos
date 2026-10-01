package io.minimpos.app.terminal

import io.minimpos.app.data.security.Secret
import io.minimpos.app.data.security.SecretStore
import io.minimpos.app.data.settings.SettingsRepository
import io.minimpos.app.data.settings.TerminalMode
import io.minimpos.app.data.settings.TerminalSettings
import io.minimpos.terminal.checkout.CheckoutCredentials
import io.minimpos.terminal.checkout.CheckoutModifications
import io.minimpos.terminal.checkout.PaymentModifications
import io.minimpos.terminal.simulator.SimulatedModifications
import io.minimpos.terminal.transport.TerminalEnvironment
import kotlinx.coroutines.flow.first

/** How payments taken with manual capture (pre-authorisations, tips on the receipt) are captured and adjusted. */
enum class CaptureMode {
    /** By the app, through Adyen's Checkout API (simulated while payments go to the simulator). */
    API,

    /** By staff in the Customer Area: no Checkout API is set up, so the app only records what to capture. */
    CUSTOMER_AREA,
}

/** Where captures and adjustments go now, see [AdyenApi.target]. */
sealed interface ApiTarget {
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
     * @property message What to enter, as [AdyenApi.setupProblem] words it.
     */
    data class NotSetUp(
        val message: String,
    ) : ApiTarget
}

/**
 * Adyen's Checkout API as the app uses it: for capturing payments taken with manual capture and adjusting what they
 * hold. It is optional and set up in Settings › Terminal (merchant account and live URL prefix in [TerminalSettings],
 * the API key in [SecretStore]); without it, staff capture in the Customer Area. While payments go to the simulator the
 * API is simulated too. The environment (TEST or LIVE) is the terminal's, from its certificate.
 *
 * @param settings The stored settings, read for each call.
 * @param secrets Holds the API key.
 * @param gateway Tells where payments go.
 * @param simulated Answers while payments go to the simulator.
 * @param connect Makes the client for real credentials; tests replace it.
 */
class AdyenApi(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val gateway: TerminalGateway,
    private val simulated: PaymentModifications = SimulatedModifications(),
    private val connect: (CheckoutCredentials) -> PaymentModifications = { CheckoutModifications(it) },
) {
    @Volatile private var cached: Pair<CheckoutCredentials, PaymentModifications>? = null

    /**
     * How captures are made with [terminal], given whether an API key is saved ([keySaved]): through the API in
     * simulator mode or once anything of it is entered (so a half-finished setup fails visibly instead of silently
     * leaving captures to the Customer Area), else in the Customer Area.
     */
    fun mode(
        terminal: TerminalSettings,
        keySaved: Boolean,
    ): CaptureMode =
        when {
            gateway.effectiveMode(terminal) == TerminalMode.SIMULATOR -> CaptureMode.API
            keySaved || terminal.merchantAccount.isNotBlank() -> CaptureMode.API
            else -> CaptureMode.CUSTOMER_AREA
        }

    /**
     * What must still be entered before the API can be called with [terminal] and [keySaved]; null when nothing is
     * missing, in simulator mode, and when no API is set up at all ([CaptureMode.CUSTOMER_AREA]).
     */
    fun setupProblem(
        terminal: TerminalSettings,
        keySaved: Boolean,
    ): String? =
        when {
            gateway.effectiveMode(terminal) == TerminalMode.SIMULATOR || mode(terminal, keySaved) == CaptureMode.CUSTOMER_AREA -> {
                null
            }

            terminal.merchantAccount.isBlank() -> {
                "Enter the merchant account in Terminal settings"
            }

            !keySaved -> {
                "Enter the Checkout API key in Terminal settings"
            }

            terminal.environment == null -> {
                "Test the connection to the terminal first, so the app knows whether it is TEST or LIVE"
            }

            terminal.environment == TerminalEnvironment.LIVE && terminal.liveUrlPrefix.isBlank() -> {
                "Enter the live URL prefix in Terminal settings"
            }

            else -> {
                null
            }
        }

    /** Where captures and adjustments go now with the stored settings. */
    suspend fun target(): ApiTarget {
        val terminal = settings.current().terminal
        val keySaved = Secret.CHECKOUT_API_KEY in secrets.configured.first()
        val problem = setupProblem(terminal, keySaved)
        return when {
            gateway.effectiveMode(terminal) == TerminalMode.SIMULATOR -> ApiTarget.Ready(simulated)
            mode(terminal, keySaved) == CaptureMode.CUSTOMER_AREA -> ApiTarget.CustomerArea
            problem != null -> ApiTarget.NotSetUp(problem)
            else -> connected(terminal)
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
