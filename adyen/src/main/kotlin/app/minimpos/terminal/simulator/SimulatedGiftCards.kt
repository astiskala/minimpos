package app.minimpos.terminal.simulator

import com.adyen.model.nexo.StoredValueAccountID
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Simulated gift-card accounts behind the [TerminalSimulator], so balance, activation, load, undo, deactivation,
 * redemption, cash-out and refunds agree with each other. A scanned card is keyed by its number; a swiped or keyed card
 * stands for one demo card per provider. Every card starts inactive with no balance. Provider-side state, so it
 * survives history purges like a real card would; kept in memory only. Thread-safe.
 *
 * @param clock Stamps loads for the 24-hour undo window.
 */
internal class SimulatedGiftCards(
    private val clock: Clock,
) {
    private class Load(
        val psp: String,
        val at: Instant,
        val amount: BigDecimal,
        var undone: Boolean = false,
    )

    private class Card(
        var active: Boolean = false,
        var currency: String? = null,
        var balance: BigDecimal = BigDecimal.ZERO,
        var latestLoad: Load? = null,
    )

    /** What one simulated operation did. */
    sealed interface Answer {
        /**
         * Done.
         *
         * @property approved Amount activated, loaded, removed or credited.
         * @property balance The card's balance afterwards.
         * @property currency The card's currency.
         * @property partial Whether a redemption covered only part of what was requested.
         */
        data class Approved(
            val approved: BigDecimal,
            val balance: BigDecimal,
            val currency: String,
            val partial: Boolean = false,
        ) : Answer

        /**
         * Refused by the provider.
         *
         * @property refusal Refusal reason, as a real provider would word it.
         */
        data class Refused(
            val refusal: String,
        ) : Answer
    }

    private val cards = mutableMapOf<String, Card>()
    private val redemptions = mutableMapOf<String, Pair<String, BigDecimal>>()

    /** The account [account] names. */
    fun key(account: StoredValueAccountID?): String =
        "${account?.storedValueProvider.orEmpty()}:${account?.storedValueID?.ifEmpty { null } ?: DEMO_NUMBER}"

    /** The last four digits the simulator reports for [account]. */
    fun lastFour(account: StoredValueAccountID?): String = (account?.storedValueID?.ifEmpty { null } ?: DEMO_NUMBER).takeLast(LAST_FOUR)

    /** Activates [key] with an initial [amount]; refused when it is already active. */
    @Synchronized
    fun activate(
        key: String,
        amount: BigDecimal,
        currency: String,
    ): Answer {
        val card = cards.getOrPut(key) { Card() }
        if (card.active) return Answer.Refused(ALREADY_ACTIVE)
        card.active = true
        card.currency = currency
        card.balance = amount
        return Answer.Approved(amount, amount, currency)
    }

    /** Loads [amount] onto active [key] as the load [psp]. */
    @Synchronized
    fun load(
        key: String,
        amount: BigDecimal,
        currency: String,
        psp: String,
    ): Answer {
        val card = active(key) ?: return Answer.Refused(NOT_ACTIVE)
        if (card.currency != currency) return Answer.Refused(CURRENCY)
        card.balance += amount
        card.latestLoad = Load(psp, clock.instant(), amount)
        return Answer.Approved(amount, card.balance, currency)
    }

    /** Undoes the load whose transaction ID is [originalTransactionId]: only a card's most recent load within 24 hours. */
    @Synchronized
    fun undo(originalTransactionId: String): Answer {
        val psp = originalTransactionId.substringAfter('.')
        val card = cards.values.firstOrNull { it.latestLoad?.psp == psp } ?: return Answer.Refused(NOT_UNDOABLE)
        val load = checkNotNull(card.latestLoad)
        val fresh = Duration.between(load.at, clock.instant()) <= UNDO_WINDOW
        val undoable = !load.undone && fresh && card.active
        if (!undoable) return Answer.Refused(NOT_UNDOABLE)
        if (card.balance < load.amount) return Answer.Refused(NOT_ENOUGH)
        load.undone = true
        card.balance -= load.amount
        return Answer.Approved(load.amount, card.balance, checkNotNull(card.currency))
    }

    /** Deactivates active [key]; only Givex supports it. */
    @Synchronized
    fun deactivate(
        key: String,
        provider: String?,
    ): Answer {
        if (provider != GIVEX) return Answer.Refused(UNSUPPORTED)
        val card = active(key) ?: return Answer.Refused(NOT_ACTIVE)
        val removed = card.balance
        card.active = false
        card.balance = BigDecimal.ZERO
        return Answer.Approved(removed, BigDecimal.ZERO, checkNotNull(card.currency))
    }

    /** The balance of active [key]. */
    @Synchronized
    fun balance(key: String): Answer {
        val card = active(key) ?: return Answer.Refused(NOT_ACTIVE)
        return Answer.Approved(BigDecimal.ZERO, card.balance, checkNotNull(card.currency))
    }

    /**
     * Pays [requested] from [key] as the payment [transactionId]; a short balance is approved in part when
     * [partialAllowed].
     */
    @Synchronized
    fun redeem(
        key: String,
        requested: BigDecimal,
        currency: String,
        partialAllowed: Boolean,
        transactionId: String,
    ): Answer {
        val card = active(key) ?: return Answer.Refused(NOT_ACTIVE)
        if (card.currency != currency) return Answer.Refused(CURRENCY)
        val approved =
            when {
                card.balance >= requested -> requested
                partialAllowed && card.balance > BigDecimal.ZERO -> card.balance
                else -> return Answer.Refused(NOT_ENOUGH)
            }
        card.balance -= approved
        redemptions[transactionId] = key to approved
        return Answer.Approved(approved, card.balance, currency, partial = approved < requested)
    }

    /** Pays [requested] out of [key]: the whole balance, or less only for Givex; a card emptied becomes inactive. */
    @Synchronized
    fun cashOut(
        key: String,
        requested: BigDecimal,
        currency: String,
        provider: String?,
    ): Answer {
        val card = active(key) ?: return Answer.Refused(NOT_ACTIVE)
        if (card.currency != currency) return Answer.Refused(CURRENCY)
        if (requested > card.balance) return Answer.Refused(NOT_ENOUGH)
        if (requested < card.balance && provider != GIVEX) return Answer.Refused(WHOLE_BALANCE)
        card.balance -= requested
        if (card.balance.signum() == 0) card.active = false
        return Answer.Approved(requested, card.balance, currency)
    }

    /** Credits [amount] onto active [key] (an unreferenced refund). */
    @Synchronized
    fun refund(
        key: String,
        amount: BigDecimal,
        currency: String,
    ): Answer {
        val card = active(key) ?: return Answer.Refused(NOT_ACTIVE)
        if (card.currency != currency) return Answer.Refused(CURRENCY)
        card.balance += amount
        return Answer.Approved(amount, card.balance, currency)
    }

    /**
     * Credits back a referenced refund of the redemption [transactionId], [amount] or all of it when null; false when
     * that payment was not a simulated gift-card redemption.
     */
    @Synchronized
    fun reverseRedemption(
        transactionId: String,
        amount: BigDecimal?,
    ): Boolean {
        val (key, redeemed) = redemptions[transactionId] ?: return false
        val card = cards[key] ?: return false
        card.balance += (amount ?: redeemed).min(redeemed)
        return true
    }

    private fun active(key: String): Card? = cards[key]?.takeIf { it.active }

    /** Provider refusals and fixed demo values. */
    companion object {
        /** The card number a swiped or keyed simulated card stands for. */
        const val DEMO_NUMBER = "6006491000000042"
        private const val LAST_FOUR = 4
        private const val GIVEX = "givex"
        private val UNDO_WINDOW: Duration = Duration.ofHours(24)
        private const val ALREADY_ACTIVE = "Card already active (simulated)"
        private const val NOT_ACTIVE = "Card not active (simulated)"
        private const val CURRENCY = "Currency not supported by card (simulated)"
        private const val NOT_UNDOABLE = "Only the most recent load within 24 hours can be undone (simulated)"
        private const val NOT_ENOUGH = "Not enough balance"
        private const val UNSUPPORTED = "Not supported by provider (simulated)"
        private const val WHOLE_BALANCE = "Cash out must cover the whole balance (simulated)"
    }
}
