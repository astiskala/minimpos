package app.minimpos.terminal.simulator

import app.minimpos.terminal.checkout.CardChargeResult
import app.minimpos.terminal.transport.CompletedTransactions
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * What simulated Adyen remembers about the history the app keeps, shared by the [TerminalSimulator] and its Checkout API
 * ([SimulatedModifications], [SimulatedSavedCards]) so they answer consistently, and the one owner of what removing that
 * history releases: everything after a confirmed purge ([forgetHistory]), only the deleted records' identities otherwise
 * ([forget]). Reconciliation period totals, gift-card balances and transactions in progress are not history and stay
 * with the terminal. Thread-safe.
 */
internal class SimulatedAccount {
    /** Completed transactions that status checks repeat, by ServiceID and POI transaction ID. */
    val completed = CompletedTransactions()

    /** What the Checkout API knows about the simulator's payments, by PSP reference. */
    val ledger = SimulatedLedger()

    /** ServiceIDs of transactions the terminal was asked to abort. */
    val aborted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The currency and approved amount of each payment, by POI transaction ID, for its later reversal. */
    val paymentAmounts = ConcurrentHashMap<String, Pair<String, BigDecimal>>()

    /** Saved cards deleted at Adyen, by shopper reference and token ID. */
    val deletedTokens: MutableSet<Pair<String, String>> = ConcurrentHashMap.newKeySet()

    private val charges = ConcurrentHashMap<String, CardChargeResult>()
    private val chargeKeys = ConcurrentHashMap<String, MutableSet<String>>()

    /** The answer to the saved-card charge sent with [idempotencyKey]; null when none was. */
    fun charge(idempotencyKey: String): CardChargeResult? = charges[idempotencyKey]

    /** Remembers [result] as the answer to the charge with merchant [reference] sent with [idempotencyKey]. */
    fun charged(
        reference: String,
        idempotencyKey: String,
        result: CardChargeResult,
    ) {
        charges[idempotencyKey] = result
        chargeKeys.computeIfAbsent(reference) { ConcurrentHashMap.newKeySet() }.add(idempotencyKey)
    }

    /** Forgets every history identity after a confirmed complete purge. */
    fun forgetHistory() {
        completed.clear()
        aborted.clear()
        paymentAmounts.clear()
        charges.clear()
        chargeKeys.clear()
        deletedTokens.clear()
        ledger.clear()
    }

    /** Releases only the identities of [removed] records, preserving unfinished retries. */
    fun forget(removed: SimulatorRemoval) {
        completed.forget(removed.serviceIds, removed.transactionIds)
        removed.serviceIds.forEach(aborted::remove)
        removed.transactionIds.forEach(paymentAmounts::remove)
        removed.references.forEach { reference -> chargeKeys.remove(reference)?.forEach(charges::remove) }
        ledger.forget(removed.pspReferences)
    }
}
