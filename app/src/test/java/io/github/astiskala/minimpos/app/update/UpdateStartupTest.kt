package io.github.astiskala.minimpos.app.update

import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.FakeDevice
import io.github.astiskala.minimpos.app.FakeUpdateCheck
import io.github.astiskala.minimpos.app.TestEnvironment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The container starts the update check itself, on devices that are not Adyen terminals. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UpdateStartupTest {
    private fun started(
        device: FakeDevice,
        answer: UpdateCheck,
    ): Pair<TestEnvironment, FakeUpdateCheck> {
        val updates = FakeUpdateCheck(answer)
        val env = TestEnvironment(device = device, dispatcher = UnconfinedTestDispatcher(), updates = updates)
        env.container.start()
        return env to updates
    }

    @Test
    fun `the container checks once on a device that is not an Adyen terminal`() {
        val (env, updates) = started(FakeDevice(), UpdateCheck.Available("0.6.5", "https://example.com/minimpos-0.6.5.apk"))
        try {
            assertThat(updates.checks).isEqualTo(1)
            assertThat(env.container.update.state.value)
                .isEqualTo(UpdateCheck.Available("0.6.5", "https://example.com/minimpos-0.6.5.apk"))
        } finally {
            env.close()
        }
    }

    @Test
    fun `an Adyen terminal never checks, and offers nothing`() {
        val (env, updates) = started(FakeDevice(detectedPoiId = "S1F2-000158213605014"), UpdateCheck.Current)
        try {
            assertThat(updates.checks).isEqualTo(0)
            assertThat(env.container.update.state.value).isEqualTo(UpdateCheck.Unchecked)
        } finally {
            env.close()
        }
    }
}
