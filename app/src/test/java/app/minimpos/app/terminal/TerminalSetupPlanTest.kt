package app.minimpos.app.terminal

import app.minimpos.app.FakeDevice
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.data.settings.TerminalSettings
import app.minimpos.app.terminal.SetupStepKind.ADYEN_API
import app.minimpos.app.terminal.SetupStepKind.CLOUD_TERMINAL
import app.minimpos.app.terminal.SetupStepKind.ENVIRONMENT
import app.minimpos.app.terminal.SetupStepKind.NETWORK_TERMINAL
import app.minimpos.app.terminal.SetupStepKind.PAYMENTS_APP
import app.minimpos.app.terminal.SetupStepKind.SHARED_KEY
import app.minimpos.app.terminal.SetupStepKind.TAP_TO_PAY
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [TerminalSetupPlan]: each destination's steps, numbering, reveal and ticks, without Compose. */
class TerminalSetupPlanTest {
    private fun steps(
        mode: TerminalMode,
        onTerminal: Boolean = false,
        environmentChosen: Boolean = true,
        paymentsApps: Int = 1,
        terminal: TerminalSettings = TerminalSettings(mode = mode),
        secrets: Set<Secret> = emptySet(),
        verified: Boolean = false,
        visit: SetupVisit = SetupVisit(),
    ): List<SetupStep> {
        val rules = DestinationRules.of(mode, FakeDevice(detectedPoiId = "S1F2-000158213605014".takeIf { onTerminal }))
        return TerminalSetupPlan.steps(
            rules,
            SetupProgress(onTerminal, environmentChosen, paymentsApps, terminal, secrets, verified, visit),
        )
    }

    @Test
    fun `each destination numbers its steps in the order they are done`() {
        val tested = SetupVisit(apiTested = true, terminalEntered = true, boarded = true)
        assertThat(steps(TerminalMode.TERMINAL, onTerminal = true, visit = tested).map { it.kind to it.number })
            .containsExactly(ADYEN_API to 1, SHARED_KEY to 2)
            .inOrder()
        assertThat(steps(TerminalMode.TERMINAL, visit = tested).map { it.kind })
            .containsExactly(ENVIRONMENT, ADYEN_API, NETWORK_TERMINAL, SHARED_KEY)
            .inOrder()
        assertThat(steps(TerminalMode.CLOUD, visit = tested).map { it.kind to it.number })
            .containsExactly(ENVIRONMENT to 1, ADYEN_API to 2, CLOUD_TERMINAL to 3)
            .inOrder()
        assertThat(steps(TerminalMode.PAYMENTS_APP, visit = tested).map { it.kind to it.number })
            .containsExactly(PAYMENTS_APP to 1, ADYEN_API to 2, TAP_TO_PAY to 3, SHARED_KEY to 4)
            .inOrder()
        assertThat(steps(TerminalMode.SIMULATOR)).isEmpty()
    }

    @Test
    fun `later steps stay hidden until earlier ones are done or their details are saved`() {
        assertThat(steps(TerminalMode.CLOUD, environmentChosen = false).map { it.kind }).containsExactly(ENVIRONMENT)
        assertThat(steps(TerminalMode.TERMINAL).map { it.kind }).containsExactly(ENVIRONMENT, ADYEN_API).inOrder()
        assertThat(steps(TerminalMode.TERMINAL, visit = SetupVisit(apiTested = true)).map { it.kind })
            .containsExactly(ENVIRONMENT, ADYEN_API, NETWORK_TERMINAL)
            .inOrder()
        val saved = TerminalSettings(mode = TerminalMode.TERMINAL, host = "192.0.2.10", keyIdentifier = "KEY")
        assertThat(steps(TerminalMode.TERMINAL, terminal = saved).map { it.kind }.last()).isEqualTo(SHARED_KEY)
        assertThat(steps(TerminalMode.PAYMENTS_APP, paymentsApps = 0).map { it.kind }).containsExactly(PAYMENTS_APP)
    }

    @Test
    fun `steps tick when done and all of them once the setup is verified`() {
        val tested = SetupVisit(apiTested = true, terminalEntered = true)
        assertThat(steps(TerminalMode.TERMINAL, visit = tested).filter { it.done }.map { it.kind })
            .containsExactly(ENVIRONMENT, ADYEN_API)
        assertThat(steps(TerminalMode.TERMINAL, visit = tested, verified = true).all { it.done }).isTrue()
    }

    @Test
    fun `any saved connection detail of any destination counts as entered`() {
        assertThat(TerminalSetupPlan.savedConnectionDetails(TerminalSettings(), emptySet())).isFalse()
        assertThat(TerminalSetupPlan.savedConnectionDetails(TerminalSettings(storeId = "S1"), emptySet())).isTrue()
        assertThat(TerminalSetupPlan.savedConnectionDetails(TerminalSettings(), setOf(Secret.PAYMENTS_APP_API_KEY))).isTrue()
    }
}
