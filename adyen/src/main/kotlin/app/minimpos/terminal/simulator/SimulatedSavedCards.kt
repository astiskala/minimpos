package app.minimpos.terminal.simulator

import app.minimpos.terminal.checkout.CardChargeRequest
import app.minimpos.terminal.checkout.CardChargeResult
import app.minimpos.terminal.checkout.SavedCardApi
import app.minimpos.terminal.checkout.StoredCard
import app.minimpos.terminal.checkout.StoredCardDeletion
import app.minimpos.terminal.checkout.StoredCardListing
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.HTTP_NOT_FOUND
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
    private val account: SimulatedAccount,
    private val cards: suspend (shopperReference: String) -> List<StoredCard>,
    private val outcome: () -> SimulatedOutcome,
) : SavedCardApi {
    override suspend fun list(shopperReference: String): StoredCardListing = StoredCardListing.Listed(available(shopperReference))

    private suspend fun available(reference: String): List<StoredCard> =
        cards(reference).filter { reference to it.id !in account.deletedTokens }

    override suspend fun delete(
        shopperReference: String,
        id: String,
    ): StoredCardDeletion {
        if (available(shopperReference).none { it.id == id }) return StoredCardDeletion.Failed(Fault.AdyenRejected(HTTP_NOT_FOUND))
        val chosen = outcome()
        if (chosen in setOf(SimulatedOutcome.DECLINE, SimulatedOutcome.CANCEL, SimulatedOutcome.BUSY)) {
            return StoredCardDeletion.Failed(Fault.Permission(ApiKey.ADYEN))
        }
        account.deletedTokens += shopperReference to id
        return if (chosen == SimulatedOutcome.TIMEOUT) StoredCardDeletion.Failed(Fault.TimedOut) else StoredCardDeletion.Deleted
    }

    override suspend fun charge(
        request: CardChargeRequest,
        idempotencyKey: String,
    ): CardChargeResult {
        account.charge(idempotencyKey)?.let { return it }
        val card = available(request.shopperReference).firstOrNull { it.id == request.storedPaymentMethodId }
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
        account.charged(request.reference, idempotencyKey, result)
        if (result is CardChargeResult.Authorised) account.ledger.approved(psp, manualCapture = false)
        // A lost answer: the card was charged, and only asking again with the same key says so.
        return if (chosen == SimulatedOutcome.TIMEOUT && approve) CardChargeResult.Failed(Fault.TimedOut) else result
    }

    private companion object {
        const val RANDOM_SPAN = 5
        const val AUTH_CODES = 1_000_000
    }
}
