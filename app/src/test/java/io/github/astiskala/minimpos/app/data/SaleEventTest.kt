package io.github.astiskala.minimpos.app.data

import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.data.db.AdjustmentStatus
import io.github.astiskala.minimpos.app.data.db.CaptureStatus
import io.github.astiskala.minimpos.app.data.db.SaleEntity
import io.github.astiskala.minimpos.app.data.db.SaleKind
import io.github.astiskala.minimpos.app.data.db.SaleStatus
import io.github.astiskala.minimpos.app.data.db.SaleWithLines
import io.github.astiskala.minimpos.app.data.db.SetupProblem
import io.github.astiskala.minimpos.app.data.db.StoredReason
import io.github.astiskala.minimpos.app.data.db.StoredReasonConverter
import io.github.astiskala.minimpos.app.data.repo.SaleEvent
import io.github.astiskala.minimpos.app.data.repo.after
import io.github.astiskala.minimpos.app.refund.PaymentAction
import io.github.astiskala.minimpos.app.refund.PaymentStanding
import io.github.astiskala.minimpos.app.refund.actions
import io.github.astiskala.minimpos.app.refund.standing
import io.github.astiskala.minimpos.terminal.checkout.ModificationResult
import io.github.astiskala.minimpos.terminal.checkout.PaymentLink
import io.github.astiskala.minimpos.terminal.checkout.PaymentLinkStatus
import io.github.astiskala.minimpos.terminal.client.TransactionDetails
import org.junit.Test
import java.time.Instant

/** Plain JUnit: what each happening does to a stored sale, observed through where it then stands. */
class SaleEventTest {
    private val pending =
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
        )
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
    private val tipSale = pending.copy(tipOnReceipt = true).after(SaleEvent.Settled(SaleStatus.APPROVED, null, approved))
    private val preAuth = pending.copy(kind = SaleKind.PRE_AUTHORISATION).after(SaleEvent.Settled(SaleStatus.APPROVED, null, approved))

    private fun SaleEntity.actions() = SaleWithLines(this, emptyList()).actions

    private fun SaleEntity.afterAll(vararg events: SaleEvent): SaleEntity = events.fold(this) { sale, event -> sale.after(event) }

    @Test
    fun `a payment is sent and settled with what the terminal answered`() {
        val sending = pending.after(SaleEvent.Sending("S1F2-000158"))
        assertThat(sending.poiId).isEqualTo("S1F2-000158")
        assertThat(sending.status).isEqualTo(SaleStatus.PENDING)
        assertThat(tipSale.pspReference).isEqualTo("PSP1")
        assertThat(tipSale.adjustAuthorisationData).isEqualTo("BQABAQfirst")
        assertThat(tipSale.standing).isEqualTo(PaymentStanding.AWAITING_TIP)
        // Without an answer the stored terminal details are kept.
        val unknown = tipSale.after(SaleEvent.Settled(SaleStatus.UNKNOWN, "No answer", null))
        assertThat(unknown.pspReference).isEqualTo("PSP1")
        assertThat(unknown.message).isEqualTo("No answer")
        assertThat(unknown.standing).isEqualTo(PaymentStanding.NOT_APPROVED)
        // What the app says and what the terminal says replace each other.
        val notSetUp = pending.after(SaleEvent.Settled(SaleStatus.FAILED, null, null, StoredReason.NotSetUp(SetupProblem.PASSPHRASE)))
        assertThat(notSetUp.reason).isEqualTo(StoredReason.NotSetUp(SetupProblem.PASSPHRASE))
        assertThat(notSetUp.after(SaleEvent.Settled(SaleStatus.DECLINED, "Refused", null)).reason).isNull()
    }

    @Test
    fun `a tip is captured through its sending and Adyen's answer`() {
        val sending = tipSale.after(SaleEvent.CaptureSending(2_300, tipMinor = 300))
        assertThat(sending.standing).isEqualTo(PaymentStanding.CAPTURE_SENDING)
        assertThat(sending.tipMinor).isEqualTo(300)
        assertThat(sending.actions()).isEmpty()

        val requested = sending.after(SaleEvent.CaptureAnswered(ModificationResult.Received("CAP")))
        assertThat(requested.standing).isEqualTo(PaymentStanding.CAPTURE_REQUESTED)
        assertThat(requested.amountMinor).isEqualTo(2_300)
        assertThat(requested.actions()).containsExactly(PaymentAction.REFUND)

        val failed = sending.after(SaleEvent.CaptureAnswered(ModificationResult.NotProcessed("Invalid amount")))
        assertThat(failed.standing).isEqualTo(PaymentStanding.CAPTURE_FAILED)
        assertThat(failed.modificationMessage).isEqualTo("Invalid amount")
        assertThat(failed.actions()).containsExactly(PaymentAction.CANCEL, PaymentAction.RETRY_CAPTURE)
        // An answer that captured clears the reason the last one failed.
        assertThat(failed.after(SaleEvent.CaptureAnswered(ModificationResult.Received(null))).modificationMessage).isNull()
        // A setup problem recorded before the capture is cleared by sending it.
        val sentAfterSetup = tipSale.afterAll(SaleEvent.ModificationNotSetUp(SetupProblem.LIVE_PREFIX), SaleEvent.CaptureSending(2_300))
        assertThat(sentAfterSetup.modificationReason).isNull()

        val unknown = sending.after(SaleEvent.CaptureAnswered(ModificationResult.Unknown("timeout")))
        assertThat(unknown.standing).isEqualTo(PaymentStanding.CAPTURE_UNKNOWN)
        assertThat(unknown.actions()).containsExactly(PaymentAction.RETRY_CAPTURE)
        // Sending it again keeps the amount and tip.
        assertThat(unknown.after(SaleEvent.CaptureSending(2_300)).tipMinor).isEqualTo(300)
    }

    @Test
    fun `adjustments change what is held only when they went through`() {
        val authorised = preAuth.after(SaleEvent.AdjustmentAnswered(2_500, ModificationResult.Authorised("ADJ", "BQABAQnext")))
        assertThat(authorised.heldMinor).isEqualTo(2_500)
        assertThat(authorised.adjustment).isEqualTo(AdjustmentStatus.AUTHORISED)
        assertThat(authorised.adjustAuthorisationData).isEqualTo("BQABAQnext")
        assertThat(authorised.standing).isEqualTo(PaymentStanding.HELD)

        val received = preAuth.after(SaleEvent.AdjustmentAnswered(2_500, ModificationResult.Received("ADJ")))
        assertThat(received.adjustment).isEqualTo(AdjustmentStatus.REQUESTED)
        assertThat(received.adjustAuthorisationData).isEqualTo("BQABAQfirst")

        val refused = preAuth.after(SaleEvent.AdjustmentAnswered(2_500, ModificationResult.Refused("Not enough balance")))
        assertThat(refused.heldMinor).isEqualTo(2_000)
        assertThat(refused.modificationMessage).isEqualTo("Not enough balance")
        assertThat(refused.after(SaleEvent.AdjustmentAnswered(2_400, ModificationResult.Received(null))).modificationMessage).isNull()

        val notSent = refused.after(SaleEvent.ModificationNotSetUp(SetupProblem.API_KEY))
        assertThat(notSent.modificationReason).isEqualTo(StoredReason.NotSetUp(SetupProblem.API_KEY))
        assertThat(notSent.modificationMessage).isNull()
        assertThat(notSent.standing).isEqualTo(PaymentStanding.HELD)
    }

    @Test
    fun `a payment link awaits its payment until Adyen says it was paid, expired or cancelled`() {
        val link = PaymentLink("PL1", "https://test.adyen.link/PL1", PaymentLinkStatus.ACTIVE, Instant.ofEpochMilli(99))
        val linkSale = pending.copy(paymentLink = true, paymentLinkExpiresAt = 50)
        val awaiting = linkSale.after(SaleEvent.LinkAnswered(link))
        assertThat(awaiting.status).isEqualTo(SaleStatus.AWAITING_PAYMENT)
        assertThat(awaiting.paymentLinkUrl).isEqualTo("https://test.adyen.link/PL1")
        assertThat(awaiting.paymentLinkExpiresAt).isEqualTo(99)
        assertThat(SaleEvent.LinkAnswered(link.copy(status = PaymentLinkStatus.PAYMENT_PENDING)).stillOpen).isTrue()
        // An expiry Adyen did not send keeps the one chosen when the sale was opened.
        assertThat(linkSale.after(SaleEvent.LinkAnswered(link.copy(expiresAt = null))).paymentLinkExpiresAt).isEqualTo(50)

        val paid = awaiting.after(SaleEvent.LinkAnswered(link.copy(status = PaymentLinkStatus.COMPLETED)))
        assertThat(paid.standing).isEqualTo(PaymentStanding.CHARGED)
        val ended = link.copy(status = PaymentLinkStatus.EXPIRED)
        assertThat(awaiting.after(SaleEvent.LinkAnswered(ended)).status).isEqualTo(SaleStatus.EXPIRED)
        assertThat(awaiting.after(SaleEvent.LinkAnswered(ended, cancelling = true)).status).isEqualTo(SaleStatus.CANCELLED)

        assertThat(linkSale.after(SaleEvent.NotSent("Invalid")).status).isEqualTo(SaleStatus.FAILED)
        val notSetUp = linkSale.after(SaleEvent.NotSetUp(SetupProblem.API_REQUIRED))
        assertThat(notSetUp.status).isEqualTo(SaleStatus.FAILED)
        assertThat(notSetUp.reason).isEqualTo(StoredReason.NotSetUp(SetupProblem.API_REQUIRED))
        val unknown = linkSale.after(SaleEvent.OutcomeUnknown("No answer"))
        assertThat(unknown.status).isEqualTo(SaleStatus.UNKNOWN)
        assertThat(unknown.reason).isEqualTo(StoredReason.OutcomeUnknown)
        assertThat(unknown.message).isEqualTo("No answer")
        // A link created after an unknown outcome clears why.
        val created = unknown.after(SaleEvent.LinkAnswered(link))
        assertThat(created.message).isNull()
        assertThat(created.reason).isNull()
    }

    @Test
    fun `an interruption makes a pending payment or capture unknown and leaves the rest`() {
        val interrupted = SaleEvent.Interrupted
        val payment = pending.after(interrupted)
        assertThat(payment.status).isEqualTo(SaleStatus.UNKNOWN)
        assertThat(payment.reason).isEqualTo(StoredReason.Interrupted)
        assertThat(payment.captureStatus).isNull()

        val capture = tipSale.afterAll(SaleEvent.CaptureSending(2_100, tipMinor = 100), interrupted)
        assertThat(capture.status).isEqualTo(SaleStatus.APPROVED)
        assertThat(capture.captureStatus).isEqualTo(CaptureStatus.UNKNOWN)
        assertThat(capture.modificationReason).isEqualTo(StoredReason.Interrupted)
        assertThat(capture.reason).isNull()
        assertThat(capture.actions()).containsExactly(PaymentAction.RETRY_CAPTURE)

        assertThat(tipSale.after(interrupted)).isEqualTo(tipSale)
    }

    @Test
    fun `the receipt's email is recorded`() {
        assertThat(tipSale.after(SaleEvent.Emailed("a@b.co")).emailedTo).isEqualTo("a@b.co")
    }

    @Test
    fun `each capture status has one standing, and the ones that count as captured are charged`() {
        CaptureStatus.entries.forEach { status ->
            val standing = PaymentStanding.entries.single { it.capture == status }
            assertThat(standing.captured).isEqualTo(status.captured)
            assertThat(standing.charged).isEqualTo(status.captured)
        }
    }

    @Test
    fun `stored reasons survive the database, and ones this version does not know read as none`() {
        val reasons = listOf(StoredReason.OutcomeUnknown, StoredReason.Interrupted) + SetupProblem.entries.map(StoredReason::NotSetUp)
        reasons.forEach { assertThat(StoredReasonConverter.decode(StoredReasonConverter.encode(it))).isEqualTo(it) }
        assertThat(StoredReasonConverter.encode(StoredReason.NotSetUp(SetupProblem.POI_ID))).isEqualTo("NOT_SET_UP:POI_ID")
        assertThat(StoredReasonConverter.encode(null)).isNull()
        assertThat(StoredReasonConverter.decode(null)).isNull()
        assertThat(StoredReasonConverter.decode("NOT_SET_UP:SOMETHING_NEW")).isNull()
        assertThat(StoredReasonConverter.decode("SOMETHING_NEW")).isNull()
    }
}
