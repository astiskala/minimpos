package app.minimpos.app.feature.transfer

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransferPlaybackTest {
    @Test
    fun `code playback has no background timer and resumes with a fresh delay`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val owner =
                    object : LifecycleOwner {
                        override val lifecycle = LifecycleRegistry.createUnsafe(this)
                    }
                owner.lifecycle.currentState = Lifecycle.State.CREATED
                var advances = 0
                val job = backgroundScope.launch(dispatcher) { playTransferCodes(owner.lifecycle) { advances++ } }
                advanceTimeBy(5400)
                runCurrent()
                assertThat(advances).isEqualTo(0)
                owner.lifecycle.currentState = Lifecycle.State.STARTED
                advanceTimeBy(1800)
                runCurrent()
                assertThat(advances).isEqualTo(1)
                owner.lifecycle.currentState = Lifecycle.State.CREATED
                advanceTimeBy(10_000)
                runCurrent()
                assertThat(advances).isEqualTo(1)
                owner.lifecycle.currentState = Lifecycle.State.STARTED
                advanceTimeBy(1799)
                runCurrent()
                assertThat(advances).isEqualTo(1)
                advanceTimeBy(1)
                runCurrent()
                assertThat(advances).isEqualTo(2)
                owner.lifecycle.currentState = Lifecycle.State.DESTROYED
                runCurrent()
                assertThat(job.isCompleted).isTrue()
            } finally {
                Dispatchers.resetMain()
            }
        }
}
