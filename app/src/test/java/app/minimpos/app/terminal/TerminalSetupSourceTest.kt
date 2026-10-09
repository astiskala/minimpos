package app.minimpos.app.terminal

import app.minimpos.app.FakeDevice
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.terminal.transport.CloudRegion
import app.minimpos.terminal.transport.TerminalEnvironment
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TerminalSetupSourceTest {
    private val env = TestEnvironment(FakeDevice(detectedPoiId = "AMS1-1"))
    private val container = env.container
    private val source = TerminalSetupSource(container.settings, container.secrets, container.device)

    @After
    fun tearDown() = env.close()

    @Test
    fun `certificate-only discovery remembers the environment without credentials`() =
        await {
            source.readLocalEnvironment { TerminalEnvironment.LIVE }
            assertThat(
                container.settings
                    .current()
                    .terminal.environment,
            ).isEqualTo(TerminalEnvironment.LIVE)
            var readAgain = false
            source.readLocalEnvironment {
                readAgain = true
                TerminalEnvironment.TEST
            }
            assertThat(readAgain).isFalse()
        }

    @Test
    fun `a delayed certificate read cannot update a changed destination`() =
        await {
            source.readLocalEnvironment {
                container.settings.update {
                    it.copy(terminal = it.terminal.selectDestination(TerminalMode.SIMULATOR, TerminalMode.TERMINAL))
                }
                TerminalEnvironment.LIVE
            }
            assertThat(
                container.settings
                    .current()
                    .terminal.mode,
            ).isEqualTo(TerminalMode.SIMULATOR)
            assertThat(
                container.settings
                    .current()
                    .terminal.environment,
            ).isNull()
        }

    @Test
    fun `cloud learning rejects a different environment and preserves unrelated settings writes`() =
        await {
            container.settings.update { it.copy(terminal = it.terminal.withConnection(TerminalMode.CLOUD, TerminalEnvironment.LIVE)) }
            val original = source.current()
            source.remember(DetectedEnvironment(original, TerminalEnvironment.TEST, CloudRegion.AU))
            assertThat(container.settings.current().terminal).isEqualTo(original.settings.terminal)
            container.settings.update { it.copy(receipt = it.receipt.copy(title = "New merchant text")) }
            source.remember(DetectedEnvironment(original, TerminalEnvironment.LIVE, CloudRegion.AU))
            assertThat(
                container.settings
                    .current()
                    .terminal.cloudRegion,
            ).isEqualTo(CloudRegion.AU)
            assertThat(
                container.settings
                    .current()
                    .receipt.title,
            ).isEqualTo("New merchant text")
        }
}
