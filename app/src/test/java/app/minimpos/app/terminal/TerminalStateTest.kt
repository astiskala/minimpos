package app.minimpos.app.terminal

import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.settings.TerminalMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Plain JUnit: when actions reaching Adyen or a terminal wait for payments to be set up. */
class TerminalStateTest {
    private val real =
        TerminalState(loaded = true, mode = TerminalMode.TERMINAL, capabilities = DestinationCapabilities(checksConnection = true))

    @Test
    fun `actions wait while anything is missing or the setup has not been verified`() {
        assertThat(real.needsSetup).isFalse()
        assertThat(real.copy(setupProblem = SetupProblem.POI_ID).needsSetup).isTrue()
        assertThat(real.copy(setupProblem = SetupProblem.SETUP_NOT_VERIFIED).needsSetup).isTrue()
        assertThat(real.copy(connection = TerminalConnection.NotSetUp(SetupProblem.TERMINAL_ACCESS)).needsSetup).isTrue()
    }

    @Test
    fun `nothing waits before the status is read, for the simulator or while the terminal is merely unreachable`() {
        assertThat(TerminalState(setupProblem = SetupProblem.POI_ID).needsSetup).isFalse()
        // A check left over from a real destination does not hold the simulator back.
        val simulator = TerminalState(loaded = true, connection = TerminalConnection.NotSetUp(SetupProblem.POI_ID))
        assertThat(simulator.needsSetup).isFalse()
        val unreachable = real.copy(connection = TerminalConnection.Failed(Failure.NotSetUp(SetupProblem.HOST)))
        assertThat(unreachable.needsSetup).isFalse()
    }
}
