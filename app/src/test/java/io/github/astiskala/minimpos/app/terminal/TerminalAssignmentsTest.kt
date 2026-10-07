package io.github.astiskala.minimpos.app.terminal

import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.data.db.SetupProblem
import io.github.astiskala.minimpos.app.data.settings.TerminalSettings
import io.github.astiskala.minimpos.terminal.transport.TerminalDetails
import org.junit.Test

class TerminalAssignmentsTest {
    private val terminal = TerminalDetails("S1F2-1", "Merchant", "192.168.1.42", "Store")
    private val other = terminal.copy(id = "S1F2-2", merchantAccount = "Other")
    private val settings = TerminalSettings(merchantAccount = " Merchant ", storeId = "Store")

    @Test
    fun `discovery fills a blank account but never replaces an explicit mismatched account`() {
        assertThat(TerminalAssignments.discover(listOf(terminal), null, "")).isEqualTo(TerminalAssignment.Assigned(terminal))
        assertThat(
            TerminalAssignments.discover(listOf(terminal), terminal.id, " Merchant "),
        ).isEqualTo(TerminalAssignment.Assigned(terminal))
        assertThat(TerminalAssignments.discover(listOf(terminal), terminal.id, "Other")).isEqualTo(
            TerminalAssignment.Blocked(SetupProblem.MERCHANT_MISMATCH),
        )
        assertThat(TerminalAssignments.select(listOf(terminal), terminal.id, "")).isEqualTo(TerminalAssignment.Assigned(terminal))
        assertThat(TerminalAssignments.select(listOf(other), terminal.id, "")).isEqualTo(
            TerminalAssignment.Blocked(SetupProblem.TERMINAL_ACCESS),
        )
    }

    @Test
    fun `automatic discovery offers eligible choices in listing order and rejects absent identities`() {
        val second = terminal.copy(id = "S1F2-3")
        assertThat(TerminalAssignments.discover(listOf(other, terminal, second), null, "Merchant")).isEqualTo(
            TerminalAssignment.Choices(listOf(terminal.id, second.id)),
        )
        assertThat(TerminalAssignments.discover(listOf(other, terminal), null, "Merchant")).isEqualTo(TerminalAssignment.Assigned(terminal))
        assertThat(TerminalAssignments.discover(listOf(terminal), "missing", "Merchant")).isEqualTo(
            TerminalAssignment.Blocked(SetupProblem.TERMINAL_ACCESS),
        )
        assertThat(TerminalAssignments.discover(emptyList(), null, "")).isEqualTo(TerminalAssignment.Blocked(SetupProblem.TERMINAL_ACCESS))
    }

    @Test
    fun `blank listed accounts are not auto selected but explicit discovery can fill other fields`() {
        val unassigned = terminal.copy(merchantAccount = "")
        assertThat(
            TerminalAssignments.discover(listOf(unassigned), null, ""),
        ).isEqualTo(TerminalAssignment.Blocked(SetupProblem.TERMINAL_ACCESS))
        assertThat(TerminalAssignments.discover(listOf(unassigned), unassigned.id, "")).isEqualTo(TerminalAssignment.Assigned(unassigned))
        assertThat(TerminalAssignments.access(listOf(unassigned), terminal.id, "Merchant")).isEqualTo(SetupProblem.MERCHANT_ACCOUNT)
        assertThat(TerminalAssignments.receipt(listOf(unassigned), terminal.id, "Merchant")).isEqualTo(
            TerminalAssignment.Blocked(SetupProblem.MERCHANT_MISMATCH),
        )
    }

    @Test
    fun `access and receipt assignment require exact account and preserve first match behavior`() {
        assertThat(TerminalAssignments.access(listOf(terminal), terminal.id, " Merchant ")).isNull()
        assertThat(TerminalAssignments.access(listOf(terminal), terminal.id, "")).isEqualTo(SetupProblem.MERCHANT_MISMATCH)
        assertThat(TerminalAssignments.access(listOf(terminal), "missing", "Merchant")).isEqualTo(SetupProblem.TERMINAL_ACCESS)
        assertThat(TerminalAssignments.receipt(listOf(terminal), terminal.id, "Merchant")).isEqualTo(TerminalAssignment.Assigned(terminal))
        val duplicate = terminal.copy(merchantAccount = "Other")
        assertThat(TerminalAssignments.access(listOf(terminal, duplicate), terminal.id, "Merchant")).isNull()
        assertThat(
            TerminalAssignments.discover(listOf(terminal, duplicate), terminal.id, "Merchant"),
        ).isEqualTo(TerminalAssignment.Assigned(terminal))
    }

    @Test
    fun `shared key creation requires unique identity unchanged account and unchanged store`() {
        assertThat(TerminalAssignments.unchanged(listOf(terminal), terminal.id, settings)).isNull()
        assertThat(TerminalAssignments.unchanged(listOf(terminal, terminal), terminal.id, settings)).isEqualTo(SetupProblem.TERMINAL_ACCESS)
        assertThat(
            TerminalAssignments.unchanged(listOf(other.copy(id = terminal.id)), terminal.id, settings),
        ).isEqualTo(SetupProblem.SETUP_CHANGED)
        assertThat(
            TerminalAssignments.unchanged(listOf(terminal.copy(storeId = "New")), terminal.id, settings),
        ).isEqualTo(SetupProblem.SETUP_CHANGED)
    }

    @Test
    fun `on device offers preserve only matching identities without account filtering`() {
        assertThat(TerminalAssignments.offered(listOf(other, terminal), terminal.id)).containsExactly(terminal)
        assertThat(TerminalAssignments.offered(listOf(other, terminal), null)).containsExactly(other, terminal).inOrder()
        assertThat(TerminalAssignments.offered(listOf(terminal), "missing")).isEmpty()
    }
}
