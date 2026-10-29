package app.minimpos.app.payment

import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.terminal.ApiSetup
import app.minimpos.app.terminal.ApiTarget
import app.minimpos.app.terminal.TerminalState
import app.minimpos.core.payment.PaymentContext
import app.minimpos.terminal.checkout.CardChargeRequest
import app.minimpos.terminal.checkout.CardChargeResult
import app.minimpos.terminal.checkout.SavedCardApi
import app.minimpos.terminal.checkout.StoredCard
import app.minimpos.terminal.checkout.StoredCardListing
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

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SavedCardLookupTest {
    private val env = TestEnvironment()
    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
    private val card = StoredCard("TOKEN1", "visa", "1111", recurringModels = setOf("UnscheduledCardOnFile"))
    private val subscription = StoredCard("TOKEN2", "mc", "2222", recurringModels = setOf("Subscription"))
    private var beforeAnswer: suspend () -> Unit = {}
    private val listed = mutableListOf<String>()
    private val api =
        object : SavedCardApi {
            override suspend fun list(shopperReference: String): StoredCardListing {
                listed += shopperReference
                beforeAnswer()
                return StoredCardListing.Listed(listOf(card, subscription))
            }

            override suspend fun charge(
                request: CardChargeRequest,
                idempotencyKey: String,
            ): CardChargeResult = CardChargeResult.Authorised("PSP1", "visa", "1111", "123456")
        }
    private val targets =
        MutableStateFlow(ApiTarget(ApiSetup.Complete, context = PaymentContext("TERMINAL", "T1", "POS", "Merchant", "TEST"), cards = api))
    private val charges = SavedCardCharges(env.container.appScope, env.container.sales, { targets.value }, targets = targets)
    private val checkouts = MutableStateFlow(Checkout(form = CheckoutForm(customerReference = "CUST-001")))
    private val terminal = MutableStateFlow(TerminalState())

    @After
    fun tearDown() {
        scope.cancel()
        env.close()
    }

    @Test
    fun `explicit and automatic lookups offer only cards saved for UnscheduledCardOnFile`() {
        val explicit = charges.lookup(scope, checkouts, terminal)
        explicit.find()
        val first = await { explicit.state.first { it.cards != null } }
        assertThat(first.cards).containsExactly(card, subscription).inOrder()
        assertThat(first.eligible).containsExactly(card)
        assertThat(first.selectionProblem(card)).isNull()
        assertThat(first.selectionProblem(subscription)).isEqualTo(Failure.NotSetUp(SetupProblem.SETUP_CHANGED))

        val automatic = charges.lookup(scope, checkouts, terminal, Duration.ZERO)
        assertThat(await { automatic.state.first { it.cards != null } }.eligible).containsExactly(card)
    }

    @Test
    fun `changed target clears a listing and a delayed old answer cannot revive it`() {
        val entered = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Unit>()
        beforeAnswer = {
            entered.complete(Unit)
            answer.await()
        }
        val lookup = charges.lookup(scope, checkouts, terminal)
        lookup.find()
        await { entered.await() }
        targets.value = targets.value.copy(context = targets.value.context!!.copy(merchantAccount = "OtherMerchant"))
        await { lookup.state.first { !it.finding } }
        answer.complete(Unit)
        assertThat(lookup.state.value.cards).isNull()
        assertThat(lookup.state.value.eligible).isEmpty()
        assertThat(lookup.state.value.selectionProblem(card)).isEqualTo(Failure.NotSetUp(SetupProblem.SETUP_CHANGED))
        assertThat(listed).hasSize(1)
    }

    @Test
    fun `listing itself refuses an answer from a replaced credential even before observation catches up`() {
        val previous = targets.value
        beforeAnswer = { targets.value = ApiTarget(ApiSetup.Incomplete(SetupProblem.API_KEY)) }
        assertThat(await { charges.cards("CUST-001", previous) })
            .isEqualTo(CardLookup.NotDone(Failure.NotSetUp(SetupProblem.SETUP_CHANGED)))
    }
}
