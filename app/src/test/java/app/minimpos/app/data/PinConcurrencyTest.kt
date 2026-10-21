package app.minimpos.app.data

import app.minimpos.app.TestEnvironment
import app.minimpos.app.data.security.PinCheck
import app.minimpos.app.data.security.PinManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
class PinConcurrencyTest {
    @Test
    fun `PIN derivation uses its CPU dispatcher and concurrent attempts cannot bypass lockout`() =
        runTest {
            val env = TestEnvironment()
            try {
                val cpu = CountingDispatcher()
                val pins = PinManager(env.container.secrets, clock = { 0L }, iterations = 1_000, cpu = cpu)
                pins.setPin("1234")
                val attempts = coroutineScope { List(10) { async { pins.verify("9999") } }.awaitAll() }
                assertThat(attempts.filterIsInstance<PinCheck.Rejected>().map { it.attemptsLeft }).containsExactly(4, 3, 2, 1)
                assertThat(attempts.filterIsInstance<PinCheck.LockedOut>()).hasSize(6)
                // Setting hashes once; only the first five failed attempts are allowed to derive a candidate.
                assertThat(cpu.dispatched.get()).isEqualTo(6)
                assertThat(pins.verify("1234")).isInstanceOf(PinCheck.LockedOut::class.java)
                assertThat(cpu.dispatched.get()).isEqualTo(6)
            } finally {
                env.close()
            }
        }

    private class CountingDispatcher(
        private val delegate: CoroutineDispatcher = Dispatchers.Default,
    ) : CoroutineDispatcher() {
        val dispatched = AtomicInteger()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            dispatched.incrementAndGet()
            delegate.dispatch(context, block)
        }
    }
}
