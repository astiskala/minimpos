package app.minimpos.app.terminal

import app.minimpos.terminal.paymentsapp.AppLinkAnswer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The activity's side of the Payments app's links: one exchange at a time, answered, failed, abandoned or late. */
class PaymentsAppBridgeTest {
    private val bridge = PaymentsAppBridge()
    private val answer = "${PaymentsAppBridge.RETURN_URL}/nexo?response=abc"

    /** Starts an exchange, waits until the activity is asked to open it, and runs [activity] with that link. */
    private fun exchange(activity: (PaymentsAppBridge.Launch) -> Unit): AppLinkAnswer =
        runBlocking {
            val reply =
                async { bridge.exchange("https://www.adyen.com/test/nexo?request=x", "com.adyen.ipp.mobile.companion.test", 5.seconds) }
            activity(bridge.launches.filterNotNull().first())
            reply.await()
        }

    @Test
    fun `a link is opened once and its answer goes back to the exchange`() {
        val reply =
            exchange { launch ->
                assertThat(launch.packageName).isEqualTo("com.adyen.ipp.mobile.companion.test")
                assertThat(bridge.awaitingAnswer).isNull()
                bridge.opened(launch.id)
                assertThat(bridge.launches.value).isNull()
                assertThat(bridge.awaitingAnswer).isEqualTo(launch.id)
                // Other links that start the app are not the Payments app's answer.
                assertThat(bridge.deliver("https://example.com/")).isFalse()
                assertThat(bridge.deliver(answer)).isTrue()
            }
        assertThat(reply).isEqualTo(AppLinkAnswer.Returned(answer))
        assertThat(bridge.awaitingAnswer).isNull()
        assertThat(bridge.lateReplies()).containsExactly(answer)
        bridge.forgetLateReplies(setOf(answer))
        assertThat(bridge.lateReplies()).isEmpty()
    }

    @Test
    fun `a Payments app that cannot be started, or that sends no answer, ends the exchange`() {
        assertThat(exchange { bridge.failed(it.id) }).isEqualTo(AppLinkAnswer.NotStarted)
        val abandoned =
            exchange { launch ->
                // Abandoning before the link was opened, or another exchange, does nothing.
                bridge.abandon(launch.id)
                bridge.opened(launch.id)
                bridge.abandon(launch.id + 1)
                bridge.abandon(launch.id)
            }
        assertThat(abandoned).isEqualTo(AppLinkAnswer.Abandoned)
        val late = runBlocking { bridge.exchange("https://www.adyen.com/test/nexo", "p", 10.milliseconds) }
        assertThat(late).isEqualTo(AppLinkAnswer.TimedOut)
    }

    @Test
    fun `acknowledging one durable reply preserves every other late answer`() {
        val other = "$answer-other"
        bridge.deliver(answer)
        bridge.deliver(other)
        bridge.forgetLateReplies(setOf(answer))
        assertThat(bridge.lateReplies()).containsExactly(other)
        bridge.forgetLateReplies(setOf(answer))
        assertThat(bridge.lateReplies()).containsExactly(other)
    }

    @Test
    fun `an answer with no exchange waiting is kept for status checks`() {
        assertThat(bridge.deliver(answer)).isTrue()
        assertThat(bridge.lateReplies()).containsExactly(answer)
    }

    @Test
    fun `a late financial callback racing a status exchange survives until durable acknowledgement`() {
        val status = "$answer-status"
        exchange { launch ->
            bridge.opened(launch.id)
            bridge.deliver(answer)
            bridge.deliver(status)
        }
        assertThat(bridge.lateReplies()).containsExactly(answer, status).inOrder()
        bridge.forgetLateReplies(setOf(answer))
        assertThat(bridge.lateReplies()).containsExactly(status)
    }

    @Test
    fun `a boarding callback cannot consume a pending financial exchange`() {
        val returned =
            exchange { launch ->
                bridge.opened(launch.id)
                bridge.deliver("${PaymentsAppBridge.RETURN_URL}/boarded?boarded=true")
                bridge.deliver(answer)
            }
        assertThat(returned).isEqualTo(AppLinkAnswer.Returned(answer))
    }
}
