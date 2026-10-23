package app.minimpos.terminal.simulator

import app.minimpos.terminal.checkout.CardChargeRequest
import app.minimpos.terminal.checkout.CardChargeResult
import app.minimpos.terminal.checkout.SavedCardApi
import app.minimpos.terminal.checkout.StoredCard
import app.minimpos.terminal.checkout.StoredCardListing
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Saved cards as the [TerminalSimulator]'s Checkout API answers them, without a network or an API key. The cards are
 * the ones the app knows were saved by simulated payments ([cards]), so a restart keeps them. A charge follows the
 * simulator's [outcome] like a terminal payment: approved, declined (also for a cancel or busy outcome, which a card on
 * file cannot have), or, for [SimulatedOutcome.TIMEOUT], approved with the answer lost. Repeating a charge with the same
 * idempotency key answers what the first one did, as Adyen does. Approved charges join the simulator's ledger, so
 * their captures are refused like those of other immediate payments. Thread-safe.
 */
class SimulatedSavedCards internal constructor(
    private val random: Random,
    private val ledger: SimulatedLedger,
    private val cards: suspend (shopperReference: String) -> List<StoredCard>,
    private val answered: ConcurrentHashMap<String, CardChargeResult>,
    private val outcome: () -> SimulatedOutcome,
) : SavedCardApi {
    override suspend fun list(shopperReference: String): StoredCardListing = StoredCardListing.Listed(cards(shopperReference))

    override suspend fun charge(
        request: CardChargeRequest,
        idempotencyKey: String,
    ): CardChargeResult {
        answered[idempotencyKey]?.let { return it }
        val card = cards(request.shopperReference).firstOrNull { it.id == request.storedPaymentMethodId }
        val chosen = outcome()
        val approve =
            card != null &&
                when (chosen) {
                    SimulatedOutcome.APPROVE, SimulatedOutcome.TIMEOUT -> true
                    SimulatedOutcome.RANDOM -> random.nextInt(RANDOM_SPAN) != 0
                    SimulatedOutcome.DECLINE, SimulatedOutcome.CANCEL, SimulatedOutcome.BUSY -> false
                }
        val psp = SimulatedModifications.pspReference(random)
        val result =
            when {
                card == null -> {
                    CardChargeResult.Refused(psp, ExternalText("Stored payment method not found (simulated)"))
                }

                approve -> {
                    CardChargeResult.Authorised(
                        psp,
                        card.brand,
                        card.lastFour,
                        random.nextInt(AUTH_CODES).toString().padStart(6, '0'),
                    )
                }

                else -> {
                    CardChargeResult.Refused(psp, ExternalText("Not enough balance (simulated)"))
                }
            }
        answered[idempotencyKey] = result
        if (result is CardChargeResult.Authorised) ledger.approved(psp, manualCapture = false)
        // A lost answer: the card was charged, and only asking again with the same key says so.
        return if (chosen == SimulatedOutcome.TIMEOUT && approve) CardChargeResult.Failed(Fault.TimedOut) else result
    }

    private companion object {
        const val RANDOM_SPAN = 5
        const val AUTH_CODES = 1_000_000
    }
}
