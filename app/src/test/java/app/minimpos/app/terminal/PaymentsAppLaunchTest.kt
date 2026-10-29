package app.minimpos.app.terminal

import android.content.ActivityNotFoundException
import android.os.TransactionTooLargeException
import app.minimpos.terminal.paymentsapp.AppLinkAnswer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
class PaymentsAppLaunchTest {
    private val bridge = PaymentsAppBridge()

    private fun launch(open: (PaymentsAppBridge.Launch) -> Unit): AppLinkAnswer =
        runBlocking {
            val answer = async { bridge.exchange("https://www.adyen.com/test/nexo?request=x", "test.package", 5.seconds) }
            bridge.launch(bridge.launches.filterNotNull().first(), open)
            answer.await()
        }

    @Test
    fun `oversized Binder failure stays uncertain and retains any late financial answer`() {
        val result = launch { throw IllegalStateException(TransactionTooLargeException()) }
        assertThat(result).isEqualTo(AppLinkAnswer.LaunchFailed)
        assertThat((result as AppLinkAnswer.Unanswered).fault.mayHaveTakenEffect).isTrue()
        assertThat(bridge.launches.value).isNull()
        assertThat(bridge.awaitingAnswer).isNull()
        val callback = "${PaymentsAppBridge.RETURN_URL}/nexo?response=late"
        bridge.deliver(callback)
        assertThat(bridge.lateReplies()).containsExactly(callback)
    }

    @Test
    fun `missing app is known not sent`() {
        assertThat(launch { throw ActivityNotFoundException() }).isEqualTo(AppLinkAnswer.NotStarted)
    }

    @Test
    fun `unrelated runtime failures propagate`() {
        val failure = IllegalStateException("programming error")
        assertThat(assertThrows(IllegalStateException::class.java) { launch { throw failure } }).isSameInstanceAs(failure)
    }
}
