package app.minimpos.app.data

import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.repo.SaleEvent
import app.minimpos.app.data.repo.after
import app.minimpos.core.payment.PaymentContext
import app.minimpos.terminal.checkout.ModificationResult
import app.minimpos.terminal.client.TransactionDetails
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.random.Random

/**
 * Plain JUnit properties of [SaleEvent] over many generated histories of an approved pre-authorisation: captures and
 * adjustments never change how the payment ended, a capture Adyen took is never overwritten, and recording any
 * happening a second time (a retry, or a replay after the app stopped) changes nothing more.
 */
class SaleEventPropertiesTest {
    private val approved =
        TransactionDetails(
            success = true,
            errorCondition = null,
            message = null,
            refusalReason = null,
            poiTransactionId = "T.PSP1",
            poiTimestamp = "2026-09-29T11:04:00.000Z",
            pspReference = "PSP1",
            merchantReference = "MP-1",
            amount = null,
            currency = "AUD",
            paymentBrand = "visa",
            maskedPan = null,
            entryMode = null,
            approvalCode = "123456",
            customerReceipt = emptyList(),
            cashierReceipt = emptyList(),
            signatureRequired = false,
            additionalData = mapOf(TransactionDetails.ADJUST_AUTHORISATION_DATA to "BQABAQfirst"),
            tokenization = null,
        )

    private val preAuthorisation =
        SaleEntity(
            id = "s1",
            createdAt = 1,
            currency = "AUD",
            taxMode = "INCLUSIVE",
            netMinor = 1_818,
            taxMinor = 182,
            totalMinor = 2_000,
            status = SaleStatus.PENDING,
            merchantReference = "MP-1",
            kind = SaleKind.PRE_AUTHORISATION,
        ).after(SaleEvent.Settled(SaleStatus.APPROVED, null, approved))

    private fun Random.amount(): Long = listOf(500L, 2_000L, 2_500L, nextLong(1, 5_000)).random(this)

    private fun Random.answer(): ModificationResult =
        when (nextInt(5)) {
            0 -> ModificationResult.Received("MOD-${nextInt(3)}")
            1 -> ModificationResult.Authorised("MOD-${nextInt(3)}", "BQABAQ${nextInt(3)}")
            2 -> ModificationResult.Refused(ExternalText("Refused"))
            3 -> ModificationResult.Failed(Fault.TimedOut)
            else -> ModificationResult.Failed(Fault.Credential(ApiKey.ADYEN))
        }

    /** One thing that can happen to a held payment after it was approved. */
    private fun Random.event(): SaleEvent =
        when (nextInt(9)) {
            0 -> SaleEvent.CaptureSending(amount(), tipMinor = null, startedAt = nextLong(1, 1_000))
            1 -> SaleEvent.CaptureAnswered(answer(), processedAt = nextLong(1, 1_000))
            2 -> SaleEvent.AdjustmentSending("adjust-${nextInt(3)}", amount())
            3 -> SaleEvent.AdjustmentAnswered(amount(), answer())
            4 -> SaleEvent.ModificationNotSetUp(SetupProblem.API_KEY)
            5 -> SaleEvent.Interrupted
            6 -> SaleEvent.Emailed("shopper@example.com")
            7 -> SaleEvent.ContextRecorded(PaymentContext("TERMINAL", "S1F2", "POS", "Merchant", "TEST"))
            else -> SaleEvent.Emailed("other@example.com")
        }

    @Test
    fun `captures and adjustments never change how a payment ended, and a taken capture is never overwritten`() {
        val random = Random(12)
        repeat(3_000) { case ->
            val events = (0..<random.nextInt(1, 25)).map { random.event() }
            var sale = preAuthorisation
            var taken: SaleEntity? = null
            events.forEachIndexed { step, event ->
                val next = sale.after(event)
                val where = "case $case step $step: $event after ${events.take(step)}"
                assertWithMessage(where).that(next.status).isEqualTo(SaleStatus.APPROVED)
                assertWithMessage(where).that(next.totalMinor).isEqualTo(preAuthorisation.totalMinor)
                assertWithMessage(where).that(next.pspReference).isEqualTo(preAuthorisation.pspReference)
                taken?.let { before ->
                    assertWithMessage(where).that(next.captureStatus).isEqualTo(before.captureStatus)
                    assertWithMessage(where).that(next.capturedMinor).isEqualTo(before.capturedMinor)
                }
                if (taken == null && next.captureStatus?.captured == true) taken = next
                sale = next
            }
        }
    }

    @Test
    fun `recording the same happening twice changes nothing more than recording it once`() {
        val random = Random(13)
        repeat(3_000) { case ->
            val history = (0..<random.nextInt(0, 10)).map { random.event() }
            val sale = history.fold(preAuthorisation) { sale, event -> sale.after(event) }
            val event = random.event()
            val once = sale.after(event)
            assertWithMessage("case $case: $event after $history").that(once.after(event)).isEqualTo(once)
        }
    }

    @Test
    fun `the generated histories reach captured, adjusted and unconfirmed sales`() {
        val random = Random(12)
        val reached =
            (0..<3_000).map {
                (0..<random.nextInt(1, 25)).map { random.event() }.fold(preAuthorisation) { sale, event -> sale.after(event) }
            }
        assertThat(reached.any { it.captureStatus?.captured == true }).isTrue()
        assertThat(reached.any { it.adjustmentPending }).isTrue()
        assertThat(reached.any { it.captureStatus?.captured == false }).isTrue()
    }
}
