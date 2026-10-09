package app.minimpos.app.data

import app.minimpos.app.data.db.AdjustmentStatus
import app.minimpos.app.data.db.CaptureStatus
import app.minimpos.app.data.db.DeviceFault
import app.minimpos.app.data.db.EmailFault
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.SaleWithLines
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.db.StoredReason
import app.minimpos.app.data.db.StoredReasonConverter
import app.minimpos.app.data.repo.SaleEvent
import app.minimpos.app.data.repo.after
import app.minimpos.app.refund.PaymentAction
import app.minimpos.app.refund.PaymentStanding
import app.minimpos.app.refund.actions
import app.minimpos.app.refund.standing
import app.minimpos.terminal.checkout.ModificationResult
import app.minimpos.terminal.checkout.PaymentLink
import app.minimpos.terminal.checkout.PaymentLinkStatus
import app.minimpos.terminal.client.TransactionDetails
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.MalformedPart
import com.google.common.truth.Truth.assertThat
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
        val notSetUp =
            pending.after(
                SaleEvent.Settled(SaleStatus.FAILED, null, null, StoredReason.NotDone(Failure.NotSetUp(SetupProblem.PASSPHRASE))),
            )
        assertThat(notSetUp.reason).isEqualTo(StoredReason.NotDone(Failure.NotSetUp(SetupProblem.PASSPHRASE)))
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

        val failed =
            sending.after(
                SaleEvent.CaptureAnswered(ModificationResult.Failed(Fault.AdyenRejected(422, null, ExternalText("Invalid amount")))),
            )
        assertThat(failed.standing).isEqualTo(PaymentStanding.CAPTURE_FAILED)
        assertThat(failed.modificationReason)
            .isEqualTo(StoredReason.NotDone(Failure.Remote(Fault.AdyenRejected(422, null, ExternalText("Invalid amount")))))
        assertThat(failed.actions()).containsExactly(PaymentAction.CANCEL, PaymentAction.RETRY_CAPTURE)
        // An answer that captured clears the reason the last one failed.
        assertThat(failed.after(SaleEvent.CaptureAnswered(ModificationResult.Received(null))).modificationReason).isNull()
        // A setup problem recorded before the capture is cleared by sending it.
        val sentAfterSetup = tipSale.afterAll(SaleEvent.ModificationNotSetUp(SetupProblem.LIVE_PREFIX), SaleEvent.CaptureSending(2_300))
        assertThat(sentAfterSetup.modificationReason).isNull()

        val unknown = sending.after(SaleEvent.CaptureAnswered(ModificationResult.Failed(Fault.TimedOut)))
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

        val refused = preAuth.after(SaleEvent.AdjustmentAnswered(2_500, ModificationResult.Refused(ExternalText("Not enough balance"))))
        assertThat(refused.heldMinor).isEqualTo(2_000)
        assertThat(refused.modificationMessage).isEqualTo("Not enough balance")
        assertThat(refused.after(SaleEvent.AdjustmentAnswered(2_400, ModificationResult.Received(null))).modificationMessage).isNull()

        val notSent = refused.after(SaleEvent.ModificationNotSetUp(SetupProblem.API_KEY))
        assertThat(notSent.modificationReason).isEqualTo(StoredReason.NotDone(Failure.NotSetUp(SetupProblem.API_KEY)))
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

        assertThat(linkSale.after(SaleEvent.NotSent(Fault.AdyenRejected(422))).status).isEqualTo(SaleStatus.FAILED)
        val notSetUp = linkSale.after(SaleEvent.NotSetUp(SetupProblem.API_REQUIRED))
        assertThat(notSetUp.status).isEqualTo(SaleStatus.FAILED)
        assertThat(notSetUp.reason).isEqualTo(StoredReason.NotDone(Failure.NotSetUp(SetupProblem.API_REQUIRED)))
        val unknown = linkSale.after(SaleEvent.OutcomeUnknown(Failure.Remote(Fault.TimedOut)))
        assertThat(unknown.status).isEqualTo(SaleStatus.UNKNOWN)
        assertThat(unknown.reason).isEqualTo(StoredReason.Unconfirmed(Failure.Remote(Fault.TimedOut)))
        assertThat(unknown.message).isNull()
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
        val failures =
            SetupProblem.entries.map(Failure::NotSetUp) + DeviceFault.entries.map(Failure::Device) +
                EmailFault.entries.map { Failure.Email(it, ExternalText("535 5.7.8 Rejected")) } + Failure.Email(EmailFault.REJECTED) +
                ALL_FAULTS.map(Failure::Remote)
        val reasons =
            listOf(StoredReason.Unconfirmed(), StoredReason.Interrupted) + failures.map(StoredReason::NotDone) +
                failures.map(StoredReason::Unconfirmed)
        reasons.forEach { assertThat(StoredReasonConverter.decode(StoredReasonConverter.encode(it))).isEqualTo(it) }
        assertThat(StoredReasonConverter.encode(null)).isNull()
        assertThat(StoredReasonConverter.decode(null)).isNull()
        assertThat(StoredReasonConverter.decode("NOT_SET_UP:SOMETHING_NEW")).isNull()
        assertThat(StoredReasonConverter.decode("SOMETHING_NEW")).isNull()
        assertThat(StoredReasonConverter.decode("NOT_DONE:{\"kind\":\"fault\",\"value\":\"SOMETHING_NEW\"}")).isNull()
        assertThat(StoredReasonConverter.decode("NOT_DONE:{\"kind\":\"fault\",\"value\":\"UNTRUSTED\"}")).isNull()
        assertThat(StoredReasonConverter.decode("UNCONFIRMED:not json")).isNull()
    }

    @Test
    fun `reasons stored by 1_0_0 keep their encoding and meaning`() {
        assertThat(StoredReasonConverter.encode(StoredReason.NotDone(Failure.NotSetUp(SetupProblem.POI_ID)))).isEqualTo("NOT_SET_UP:POI_ID")
        assertThat(
            StoredReasonConverter.decode("NOT_SET_UP:API_KEY"),
        ).isEqualTo(StoredReason.NotDone(Failure.NotSetUp(SetupProblem.API_KEY)))
        assertThat(StoredReasonConverter.encode(StoredReason.Unconfirmed())).isEqualTo("OUTCOME_UNKNOWN")
        assertThat(StoredReasonConverter.decode("OUTCOME_UNKNOWN")).isEqualTo(StoredReason.Unconfirmed())
        assertThat(StoredReasonConverter.decode("INTERRUPTED")).isEqualTo(StoredReason.Interrupted)
    }

    @Test
    fun `a fault keeps Adyen's words and codes in the stored reason`() {
        val reason = StoredReason.NotDone(Failure.Remote(Fault.AdyenRejected(422, "137", ExternalText("Invalid amount"))))
        val stored = checkNotNull(StoredReasonConverter.encode(reason))
        assertThat(stored).startsWith("NOT_DONE:{")
        assertThat(stored).contains("Invalid amount")
        assertThat(StoredReasonConverter.decode(stored)).isEqualTo(reason)
    }

    private companion object {
        private val SAID = ExternalText("said")

        /** One of each fault, with every optional field set and left out. */
        val ALL_FAULTS: List<Fault> =
            listOf(
                Fault.Unreachable("10.0.0.1:8443", terminal = true),
                Fault.UnknownHost("checkout-test.adyen.com", terminal = false),
                Fault.Untrusted("10.0.0.1"),
                Fault.KeyRejected(SAID),
                Fault.KeyRejected(null),
                Fault.TerminalRejected(SAID),
                Fault.TerminalOffline("P400Plus-1", SAID),
                Fault.NotStarted,
                Fault.AppRefused(null),
                Fault.Unsupported,
                Fault.NoLateReply,
                Fault.NoRecord,
                Fault.RequestNotEncrypted,
                Fault.Credential(ApiKey.PAYMENTS_APP),
                Fault.Permission(ApiKey.ADYEN, "Management API—Stores read"),
                Fault.Permission(ApiKey.ADYEN),
                Fault.NotFound("P400Plus-1"),
                Fault.NotFound(null),
                Fault.AdyenRejected(422, "137", SAID),
                Fault.AdyenRejected(400),
                Fault.ListTooLarge,
                Fault.TimedOut,
                Fault.ConnectionLost,
                Fault.ReplyUnverified,
                Fault.UnreadableReply(SAID),
                Fault.Malformed(MalformedPart.KEY_VERSION),
                Fault.NoAnswerFromTerminal("P400Plus-1", null),
                Fault.Abandoned,
                Fault.TerminalHttp(500),
                Fault.AdyenUnavailable(503, "905", SAID),
                Fault.StillInProgress,
            )
    }
}
