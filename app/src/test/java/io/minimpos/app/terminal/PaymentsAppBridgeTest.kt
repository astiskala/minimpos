package io.minimpos.app.terminal

import com.google.common.truth.Truth.assertThat
import io.minimpos.terminal.transport.TerminalUnreachableException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The activity's side of the Payments app's links: one exchange at a time, answered, failed, abandoned or late. */
class PaymentsAppBridgeTest {
    private val bridge = PaymentsAppBridge()
    private val answer = "${PaymentsAppBridge.RETURN_URL}/nexo?response=abc"

    /** Starts an exchange, waits until the activity is asked to open it, and runs [activity] with that link. */
    private fun exchange(activity: (PaymentsAppBridge.Launch) -> Unit): String =
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
        assertThat(reply).isEqualTo(answer)
        assertThat(bridge.awaitingAnswer).isNull()
        assertThat(bridge.lateReplies()).isEmpty()
    }

    @Test
    fun `a Payments app that cannot be started, or that sends no answer, ends the exchange`() {
        val missing = assertThrows(TerminalUnreachableException::class.java) { exchange { bridge.failed(it.id, "Not installed") } }
        assertThat(missing.message).isEqualTo("Not installed")
        val abandoned =
            assertThrows(IOException::class.java) {
                exchange { launch ->
                    // Abandoning before the link was opened, or another exchange, does nothing.
                    bridge.abandon(launch.id)
                    bridge.opened(launch.id)
                    bridge.abandon(launch.id + 1)
                    bridge.abandon(launch.id)
                }
            }
        assertThat(abandoned).isNotInstanceOf(TerminalUnreachableException::class.java)
        val late =
            assertThrows(IOException::class.java) {
                runBlocking { bridge.exchange("https://www.adyen.com/test/nexo", "p", 10.milliseconds) }
            }
        assertThat(late.message).contains("did not answer in time")
    }

    @Test
    fun `an answer with no exchange waiting is kept for status checks`() {
        assertThat(bridge.deliver(answer)).isTrue()
        assertThat(bridge.lateReplies()).containsExactly(answer)
    }
}
