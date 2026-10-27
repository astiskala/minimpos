package app.minimpos.app.payment

import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.security.SessionLock
import app.minimpos.app.protectedApproval
import app.minimpos.app.terminal.ApiSetup
import app.minimpos.app.terminal.ApiTarget
import app.minimpos.app.terminal.TerminalState
import app.minimpos.core.cart.AppliedTax
import app.minimpos.core.cart.Cart
import app.minimpos.core.cart.CartProduct
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.payment.PaymentContext
import app.minimpos.core.tax.TaxMode
import app.minimpos.terminal.checkout.CardChargeRequest
import app.minimpos.terminal.checkout.CardChargeResult
import app.minimpos.terminal.checkout.SavedCardApi
import app.minimpos.terminal.checkout.StoredCard
import app.minimpos.terminal.checkout.StoredCardDeletion
import app.minimpos.terminal.checkout.StoredCardListing
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.Fault
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration

/** Token management shares charge serialization, but reconciles uncertainty with reads instead of replaying DELETE. */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TokenDeletionTest {
    private val env = TestEnvironment()
    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
    private val card = StoredCard("TOKEN1", "visa", "1111", recurringModels = setOf("Subscription"))
    private var cards = listOf(card)
    private var answer: StoredCardDeletion = StoredCardDeletion.Deleted
    private var listFault: Fault? = null
    private var remove = true
    private var configured = true
    private var now = 1_000L
    private val lock = SessionLock { now }.apply { unlock() }
    private val approval = protectedApproval(lock, 60_000L) { configured }
    private val deletes = mutableListOf<Pair<String, String>>()
    private val listings = mutableListOf<String>()
    private var beforeDelete: suspend () -> Unit = {}
    private var beforeTarget: suspend () -> Unit = {}
    private var beforeCharge: suspend () -> Unit = {}
    private var beforeList: suspend () -> Unit = {}
    private val api =
        object : SavedCardApi {
            override suspend fun list(shopperReference: String): StoredCardListing {
                listings += shopperReference
                beforeList()
                return listFault?.let(StoredCardListing::Failed) ?: StoredCardListing.Listed(cards)
            }

            override suspend fun delete(
                shopperReference: String,
                id: String,
            ): StoredCardDeletion {
                deletes += shopperReference to id
                beforeDelete()
                if (remove) cards = cards.filterNot { it.id == id }
                return answer
            }

            override suspend fun charge(
                request: CardChargeRequest,
                idempotencyKey: String,
            ): CardChargeResult {
                beforeCharge()
                return CardChargeResult.Authorised("PSP")
            }
        }
    private val targets =
        MutableStateFlow(
            ApiTarget(ApiSetup.Complete, context = PaymentContext("TERMINAL", "T1", "POS", "Merchant", "TEST"), cards = api),
        )
    private val charges =
        SavedCardCharges(
            scope,
            env.container.sales,
            {
                beforeTarget()
                targets.value
            },
            approval = approval,
            targets = targets,
        )
    private val checkouts = MutableStateFlow(Checkout(form = CheckoutForm(customerReference = "CUST-001")))
    private val terminal = MutableStateFlow(TerminalState())

    @After
    fun tearDown() {
        scope.cancel()
        env.close()
    }

    private fun listed(): SavedCardLookup =
        charges.lookup(scope, checkouts, terminal).also {
            it.find()
            await { it.state.first { reading -> reading.cards != null } }
        }

    private fun status() = charges.deletion.value.status

    @Test
    fun `deletes an unchargeable token once and invalidates explicit and automatic selections`() {
        val explicit = listed()
        val automatic = charges.lookup(scope, checkouts, terminal, Duration.ZERO)
        assertThat(explicit.state.value.chargeable(card)).isFalse()
        assertThat(automatic.state.value.cards).contains(card)
        val offer = checkNotNull(explicit.removal(card))
        assertThat(explicit.delete(offer)).isTrue()
        assertThat(explicit.delete(offer)).isFalse()
        assertThat(status()).isEqualTo(TokenDeletionStatus.DELETED)
        assertThat(deletes).containsExactly("CUST-001" to "TOKEN1")
        assertThat(explicit.state.value.cards).isNull()
        assertThat(automatic.state.value.cards).isEmpty()
        assertThat(explicit.removal(card)).isNull()
        assertThat(charges.recheckDeletion()).isFalse()
    }

    @Test
    fun `refusal keeps history intact and requires another explicit listing before selection`() {
        val lookup = listed()
        remove = false
        answer = StoredCardDeletion.Failed(Fault.Permission(ApiKey.ADYEN))
        assertThat(lookup.delete(checkNotNull(lookup.removal(card)))).isTrue()
        assertThat(status()).isEqualTo(TokenDeletionStatus.FAILED)
        assertThat(charges.deletion.value.failure).isEqualTo(Failure.Remote(Fault.Permission(ApiKey.ADYEN)))
        assertThat(lookup.state.value.cards).isNull()
        lookup.find()
        assertThat(lookup.state.value.cards).containsExactly(card)
        assertThat(listings).hasSize(2)
    }

    @Test
    fun `missing response and missing token reconcile by listing without repeating DELETE`() {
        val lookup = listed()
        answer = StoredCardDeletion.Failed(Fault.TimedOut)
        assertThat(lookup.delete(checkNotNull(lookup.removal(card)))).isTrue()
        assertThat(status()).isEqualTo(TokenDeletionStatus.DELETED)
        assertThat(listings).containsExactly("CUST-001", "CUST-001")
        assertThat(deletes).hasSize(1)
        assertThat(charges.recheckDeletion()).isFalse()
    }

    @Test
    fun `404 requires a complete fresh listing before reporting removal`() {
        val lookup = listed()
        answer = StoredCardDeletion.Failed(Fault.AdyenRejected(404))
        lookup.delete(checkNotNull(lookup.removal(card)))
        assertThat(status()).isEqualTo(TokenDeletionStatus.DELETED)
        assertThat(listings).hasSize(2)
    }

    @Test
    fun `continued presence is uncertain and failed rechecks never become a refusal or retry`() {
        val lookup = listed()
        answer = StoredCardDeletion.Failed(Fault.TimedOut)
        remove = false
        lookup.delete(checkNotNull(lookup.removal(card)))
        assertThat(status()).isEqualTo(TokenDeletionStatus.UNKNOWN)
        assertThat(lookup.state.value.cards).isNull()
        assertThat(charges.start(start())).isNull()
        listFault = Fault.TimedOut
        assertThat(charges.recheckDeletion()).isTrue()
        assertThat(status()).isEqualTo(TokenDeletionStatus.UNKNOWN)
        cards = emptyList()
        listFault = null
        assertThat(charges.recheckDeletion()).isTrue()
        assertThat(status()).isEqualTo(TokenDeletionStatus.DELETED)
        assertThat(deletes).hasSize(1)
        assertThat(listings).hasSize(4)
    }

    @Test
    fun `a changed shopper invalidates the confirmed offer even if both shoppers have that token ID`() {
        val lookup = listed()
        val offer = checkNotNull(lookup.removal(card))
        checkouts.value = checkouts.value.copy(form = CheckoutForm(customerReference = "OTHER"))
        lookup.find()
        assertThat(lookup.state.value.reference).isEqualTo("OTHER")
        assertThat(lookup.delete(offer)).isFalse()
        assertThat(deletes).isEmpty()
    }

    @Test
    fun `changed account environment or credential invalidates offers and queued sends`() {
        val original = targets.value
        listOf(
            original.copy(context = original.context!!.copy(merchantAccount = "Other")),
            original.copy(context = original.context.copy(environment = "LIVE")),
            ApiTarget(ApiSetup.Incomplete(SetupProblem.API_KEY)),
        ).forEach { replacement ->
            targets.value = original
            val lookup = listed()
            val offer = checkNotNull(lookup.removal(card))
            targets.value = replacement
            assertThat(lookup.delete(offer)).isFalse()
            targets.value = original
            val queued = listed()
            beforeTarget = { targets.value = replacement }
            queued.delete(checkNotNull(queued.removal(card)))
            assertThat(status()).isEqualTo(TokenDeletionStatus.FAILED)
            assertThat(charges.deletion.value.failure).isEqualTo(Failure.NotSetUp(SetupProblem.SETUP_CHANGED))
            beforeTarget = {}
        }
        assertThat(deletes).isEmpty()
    }

    @Test
    fun `uncertain deletion refuses a different environment and resumes in its original context`() {
        val original = targets.value
        val lookup = listed()
        answer = StoredCardDeletion.Failed(Fault.TimedOut)
        listFault = Fault.TimedOut
        lookup.delete(checkNotNull(lookup.removal(card)))
        val count = listings.size
        targets.value = original.copy(context = checkNotNull(original.context).copy(environment = "LIVE"))
        assertThat(charges.recheckDeletion()).isTrue()
        assertThat(status()).isEqualTo(TokenDeletionStatus.UNKNOWN)
        assertThat(listings).hasSize(count)
        targets.value = original
        listFault = null
        assertThat(charges.recheckDeletion()).isTrue()
        assertThat(status()).isEqualTo(TokenDeletionStatus.DELETED)
        assertThat(deletes).hasSize(1)
    }

    @Test
    fun `a replacement credential may read deletion status only in the original merchant context`() {
        val lookup = listed()
        answer = StoredCardDeletion.Failed(Fault.TimedOut)
        listFault = Fault.TimedOut
        lookup.delete(checkNotNull(lookup.removal(card)))
        assertThat(status()).isEqualTo(TokenDeletionStatus.UNKNOWN)
        targets.value = ApiTarget(ApiSetup.Complete, context = targets.value.context, cards = object : SavedCardApi by api {})
        listFault = null
        assertThat(charges.recheckDeletion()).isTrue()
        assertThat(status()).isEqualTo(TokenDeletionStatus.DELETED)
        assertThat(deletes).hasSize(1)
    }

    @Test
    fun `a configured unexpired Manager approval is required independently of admin unlock`() {
        val lookup = listed()
        configured = false
        env.container.sessionLock.unlock()
        lookup.delete(checkNotNull(lookup.removal(card)))
        assertThat(status()).isEqualTo(TokenDeletionStatus.FAILED)
        configured = true
        lock.lock()
        lookup.find()
        lookup.delete(checkNotNull(lookup.removal(card)))
        assertThat(charges.deletion.value.failure).isEqualTo(Failure.NotSetUp(SetupProblem.MANAGER_APPROVAL))
        lock.unlock()
        lookup.find()
        beforeTarget = { now += 60_000L }
        lookup.delete(checkNotNull(lookup.removal(card)))
        assertThat(status()).isEqualTo(TokenDeletionStatus.FAILED)
        assertThat(deletes).isEmpty()
    }

    @Test
    fun `deletion keeps its grant across navigation and blocks racing charges and double activations`() {
        val lookup = listed()
        val offer = checkNotNull(lookup.removal(card))
        val send = CompletableDeferred<Unit>()
        beforeTarget = { lock.lock() }
        beforeDelete = { send.await() }
        assertThat(lookup.delete(offer)).isTrue()
        assertThat(charges.managing).isTrue()
        assertThat(lookup.delete(offer)).isFalse()
        assertThat(charges.start(start())).isNull()
        send.complete(Unit)
        assertThat(status()).isEqualTo(TokenDeletionStatus.DELETED)
        assertThat(deletes).hasSize(1)
    }

    @Test
    fun `a running charge prevents deletion and its history remains refundable after later deletion`() {
        val lookup = listed()
        val offer = checkNotNull(lookup.removal(card))
        val send = CompletableDeferred<Unit>()
        beforeCharge = { send.await() }
        val id = checkNotNull(charges.start(start()))
        assertThat(lookup.delete(offer)).isFalse()
        send.complete(Unit)
        await { charges.state.first { it is TransactionState.Finished } }
        val historical = checkNotNull(await { env.container.sales.get(id) })
        assertThat(lookup.delete(offer)).isTrue()
        assertThat(status()).isEqualTo(TokenDeletionStatus.DELETED)
        assertThat(await { env.container.sales.get(id) }).isEqualTo(historical)
    }

    @Test
    fun `recreated management never replays a deletion and an old listing cannot revive a selection`() {
        val old = listed()
        val release = CompletableDeferred<Unit>()
        beforeList = { release.await() }
        old.find()
        // A separate current listing starts a deletion while this older read is delayed.
        beforeList = {}
        val current = listed()
        current.delete(checkNotNull(current.removal(card)))
        release.complete(Unit)
        assertThat(old.state.value.cards).isNull()
        val recreated = SavedCardCharges(scope, env.container.sales, { targets.value }, approval = approval, targets = targets)
        assertThat(recreated.deletion.value.status).isEqualTo(TokenDeletionStatus.IDLE)
        val fresh = recreated.lookup(scope, checkouts, terminal)
        assertThat(fresh.state.value.cards).isNull()
        fresh.find()
        assertThat(fresh.state.value.cards).isEmpty()
        assertThat(deletes).hasSize(1)
    }

    @Test
    fun `checkout selections become stale synchronously and a confirmed full purge releases old management evidence`() {
        checkouts.value = checkouts.value.copy(payment = checkouts.value.payment.copy(recurringProcessingModel = "Subscription"))
        val explicit = listed()
        val automatic = charges.lookup(scope, checkouts, terminal, Duration.ZERO)
        assertThat(automatic.state.value.chargeable(card)).isTrue()
        val selected = start()
        val send = CompletableDeferred<Unit>()
        beforeDelete = { send.await() }
        explicit.delete(checkNotNull(explicit.removal(card)))
        assertThat(automatic.start(selected)).isNull()
        send.complete(Unit)
        assertThat(automatic.start(selected)).isNull()
        charges.forgetAll()
        assertThat(charges.deletion.value.status).isEqualTo(TokenDeletionStatus.IDLE)
        assertThat(explicit.removal(card)).isNull()
    }

    @Test
    fun `changing away from and back to the same shopper cannot revive an old confirmation`() {
        val lookup = listed()
        val offer = checkNotNull(lookup.removal(card))
        checkouts.value = checkouts.value.copy(form = CheckoutForm(customerReference = "OTHER"))
        checkouts.value = checkouts.value.copy(form = CheckoutForm(customerReference = "CUST-001"))
        lookup.find()
        assertThat(lookup.delete(offer)).isFalse()
        assertThat(deletes).isEmpty()
    }

    private fun start(): SavedCardStart {
        val cart = Cart().addProduct(CartProduct(1, "Flat white", null, 450, AppliedTax("GST", 10_000))) { "line" }
        return SavedCardStart(
            PaymentStart(
                cart.totals(TaxMode.INCLUSIVE),
                CurrencySpec.of("AUD"),
                "ref",
                "CUST-001",
                null,
                null,
                shopperReference = "CUST-001",
            ),
            card,
            "Subscription",
        )
    }
}
