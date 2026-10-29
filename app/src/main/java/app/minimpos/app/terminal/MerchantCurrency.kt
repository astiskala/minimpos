package app.minimpos.app.terminal

import app.minimpos.app.data.settings.TerminalSettings
import app.minimpos.core.money.AdyenCurrencies
import app.minimpos.terminal.transport.MerchantLookup
import app.minimpos.terminal.transport.StoreDetailsApi
import app.minimpos.terminal.transport.TerminalEnvironment

/**
 * An optional primary settlement currency, never evidence of accepted payment currencies.
 * @property code Supported currency code from the exact merchant account's Management response.
 * @property terminal Connection settings the proposal belongs to, for atomic pricing-edit validation.
 * @param fence Checks that the connection and credentials still match before offering or choosing it.
 */
class MerchantCurrencyProposal internal constructor(
    val code: String,
    val terminal: TerminalSettings,
    private val fence: TerminalSetupSource.SetupFence,
) {
    /** Whether this proposal still belongs to the saved connection and credentials. */
    suspend fun current(): Boolean = fence.stillCurrent()
}

/**
 * Optional read-only merchant currency lookup; failures never block setup or change prices.
 * @param setups Resolves the account, environment and secrets once for a lookup.
 * @param connect Supplies Management access in that exact environment.
 */
class MerchantCurrency(
    private val setups: TerminalSetupSource,
    private val connect: (String, TerminalEnvironment) -> StoreDetailsApi,
) {
    /** Reads a supported primary settlement currency for complete real setup, dropping stale or unavailable answers. */
    suspend fun find(): MerchantCurrencyProposal? {
        val origin = setups.unlocked()
        val setup = origin.setup
        if (setup.problem != null || setup.apiSetup != ApiSetup.Complete) return null
        val key = origin.apiKey ?: return null
        val environment = setup.environment ?: return null
        val fence = setups.SetupFence(origin, SetupFreshness.SECRETS)
        val terminal = setup.settings.terminal
        val result = connect(key, environment).merchant(terminal.merchantAccount.trim()) as? MerchantLookup.Found ?: return null
        val code = result.primarySettlementCurrency.takeIf { AdyenCurrencies[it] != null } ?: return null
        return MerchantCurrencyProposal(code, terminal, fence).takeIf { it.current() }
    }
}
