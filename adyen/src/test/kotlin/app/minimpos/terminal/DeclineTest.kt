package app.minimpos.terminal

import app.minimpos.terminal.client.Decline
import app.minimpos.terminal.client.RetryAdvice
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [Decline]'s one reading of nexo `ErrorCondition` and Adyen's refusal reason, including its retry advice table. */
class DeclineTest {
    @Test
    fun `no decline when the transaction succeeded`() {
        assertThat(Decline.of(succeeded = true, errorCondition = "Refusal", refusalReason = "Not enough balance")).isNull()
    }

    @Test
    fun `cancelled covers both nexo cancellation conditions`() {
        assertThat(Decline.of(false, "Cancel", null)!!.cancelled).isTrue()
        assertThat(Decline.of(false, "Aborted", null)!!.cancelled).isTrue()
        assertThat(Decline.of(false, "Refusal", null)!!.cancelled).isFalse()
    }

    @Test
    fun `the busy terminal names the transaction it is working on`() {
        val decline = Decline.of(false, "Busy", null, mapOf("serviceId" to "A1B2C3D4E5", "message" to "irrelevant"))
        assertThat(decline!!.busyServiceId).isEqualTo("A1B2C3D4E5")
    }

    @Test
    fun `only a busy decline carries that ServiceID`() {
        assertThat(Decline.of(false, "Refusal", null, mapOf("serviceId" to "A1B2C3D4E5"))!!.busyServiceId).isNull()
    }

    @Test
    fun `no advice without an ErrorCondition`() {
        assertThat(Decline.of(false, null, "Not enough balance")!!.advice).isNull()
    }

    @Test
    fun `every ErrorCondition maps to Adyen's advised next step`() {
        val table =
            listOf(
                "DeviceOut" to RetryAdvice.WAIT_AND_RETRY,
                "UnavailableDevice" to RetryAdvice.WAIT_AND_RETRY,
                "Busy" to RetryAdvice.TERMINAL_BUSY,
                "NotAllowed" to RetryAdvice.DIFFERENT_PAYMENT_METHOD,
                "PaymentRestriction" to RetryAdvice.DIFFERENT_PAYMENT_METHOD,
                "MessageFormat" to RetryAdvice.CHECK_SETUP,
                "UnavailableService" to RetryAdvice.CHECK_SETUP,
                "Cancel" to RetryAdvice.RETRY,
                "InvalidCard" to RetryAdvice.RETRY,
                "Refusal" to RetryAdvice.RETRY,
                "SomethingFutureTerminalsSend" to RetryAdvice.RETRY,
            )
        table.forEach { (condition, expected) ->
            assertThat(Decline.of(false, condition, null)!!.advice).isEqualTo(expected)
        }
    }

    @Test
    fun `an approved-during-cancel answer must not be retried`() {
        assertThat(Decline.of(false, "Cancel", "Approved")!!.advice).isEqualTo(RetryAdvice.DO_NOT_RETRY)
        assertThat(Decline.of(false, "Cancel", "approved")!!.advice).isEqualTo(RetryAdvice.DO_NOT_RETRY)
    }

    @Test
    fun `final refusal reasons advise a different payment method, case-insensitively`() {
        assertThat(Decline.of(false, "Refusal", "Do Not Honor")!!.advice).isEqualTo(RetryAdvice.DIFFERENT_PAYMENT_METHOD)
        assertThat(Decline.of(false, "Refusal", "do not honor")!!.advice).isEqualTo(RetryAdvice.DIFFERENT_PAYMENT_METHOD)
        assertThat(Decline.of(false, "InvalidCard", "Card data authentication failed")!!.advice)
            .isEqualTo(RetryAdvice.DIFFERENT_PAYMENT_METHOD)
        assertThat(Decline.of(false, "Refusal", "Not enough balance")!!.advice).isEqualTo(RetryAdvice.RETRY)
    }
}
