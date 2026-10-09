package app.minimpos.app.update

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The state Home reads: the check's answer, and an offer dismissed for the session. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateTest {
    private val offered = UpdateCheck.Available("0.6.5", "https://example.com/minimpos-0.6.5.apk")

    @Test
    fun `the check's answer becomes the state`() =
        runTest(UnconfinedTestDispatcher()) {
            val update = AppUpdate({ offered }, this)
            assertThat(update.state.value).isEqualTo(UpdateCheck.Unchecked)
            update.start()
            assertThat(update.state.value).isEqualTo(offered)

            val failed = AppUpdate({ UpdateCheck.Unavailable }, this)
            failed.start()
            assertThat(failed.state.value).isEqualTo(UpdateCheck.Unavailable)
        }

    @Test
    fun `an offer can be dismissed, once, for the session`() =
        runTest(UnconfinedTestDispatcher()) {
            val update = AppUpdate({ offered }, this)
            update.start()
            update.dismiss()
            assertThat(update.state.value).isEqualTo(UpdateCheck.Dismissed)
            update.dismiss()
            assertThat(update.state.value).isEqualTo(UpdateCheck.Dismissed)
            update.start()
            assertThat(update.state.value).isEqualTo(UpdateCheck.Dismissed)
        }

    @Test
    fun `dismissing without an offer changes nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            val update = AppUpdate({ offered }, this)
            update.dismiss()
            assertThat(update.state.value).isEqualTo(UpdateCheck.Unchecked)

            val current = AppUpdate({ UpdateCheck.Current }, this)
            current.start()
            current.dismiss()
            assertThat(current.state.value).isEqualTo(UpdateCheck.Current)
        }
}
