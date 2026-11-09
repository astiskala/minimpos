package app.minimpos.terminal

import app.minimpos.terminal.client.TerminalClient
import app.minimpos.terminal.client.TerminalSoftware
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TerminalSoftwareTest {
    @Test
    fun `current tip options require 101 not the incompatible earlier 97 format`() {
        listOf(
            null,
            "",
            "unknown",
            "1",
            "1.101x",
            "1.101.0 extra",
            "999999999999.101",
            "1.999999999999",
            "0.999",
            "1.95.1",
            "1.97.0",
            "1.100.9",
        ).forEach { version ->
            assertThat(TerminalSoftware.atLeast(version, TerminalClient.TIP_OPTIONS_MINIMUM_SOFTWARE)).isFalse()
        }
        listOf("1.101", "1.101.0", " 1.101.1 ", "1.101.1-build.2", "1.101.1_123", "1.129.0", "2.0.0")
            .forEach { version ->
                assertThat(TerminalSoftware.atLeast(version, TerminalClient.TIP_OPTIONS_MINIMUM_SOFTWARE)).isTrue()
            }
        assertThat(TerminalSoftware.atLeast("1.137.0", "invalid")).isFalse()
    }

    @Test
    fun `Brazilian account selection requires 129 and audio guidance retains its model minimums`() {
        assertThat(TerminalSoftware.atLeast("1.128.9", TerminalClient.MEAL_VOUCHER_MINIMUM_SOFTWARE)).isFalse()
        assertThat(TerminalSoftware.atLeast("1.129.0", TerminalClient.MEAL_VOUCHER_MINIMUM_SOFTWARE)).isTrue()
        assertThat(TerminalClient.audioGuidanceMinimumSoftware("S1F2-123")).isEqualTo("1.117")
        assertThat(TerminalClient.audioGuidanceMinimumSoftware("P630-123")).isEqualTo("1.124")
    }
}
