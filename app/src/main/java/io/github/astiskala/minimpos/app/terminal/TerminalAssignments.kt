package io.github.astiskala.minimpos.app.terminal

import io.github.astiskala.minimpos.app.data.db.SetupProblem
import io.github.astiskala.minimpos.app.data.settings.TerminalSettings
import io.github.astiskala.minimpos.terminal.transport.TerminalDetails

/** Pure reading of a terminal listing, without credentials, storage or remote calls. */
internal sealed interface TerminalAssignment {
    data class Assigned(
        val terminal: TerminalDetails,
    ) : TerminalMatch

    data class Choices(
        val ids: List<String>,
    ) : TerminalAssignment

    data class Blocked(
        val problem: SetupProblem,
    ) : TerminalMatch
}

/** An explicit POIID's reading, which can never offer choices: [TerminalAssignment.Assigned] or [TerminalAssignment.Blocked]. */
internal sealed interface TerminalMatch : TerminalAssignment

/** Owns assignment matching while keeping discovery, access, receipt lookup and key-creation strictness distinct. */
internal object TerminalAssignments {
    /** Listed terminals with identity [poiId], in listing order; null keeps all without filtering merchant accounts. */
    fun offered(
        terminals: List<TerminalDetails>,
        poiId: String?,
    ): List<TerminalDetails> = if (poiId == null) terminals else terminals.filter { it.id == poiId }

    /** Selects an explicit POIID like [select], or the sole nonblank eligible account; ambiguity offers choices. */
    fun discover(
        terminals: List<TerminalDetails>,
        poiId: String?,
        merchantAccount: String,
    ): TerminalAssignment {
        if (poiId != null) return select(terminals, poiId, merchantAccount)
        val account = merchantAccount.trim()
        val eligible = terminals.filter { it.merchantAccount.isNotBlank() && (account.isBlank() || it.merchantAccount == account) }
        return when {
            eligible.size == 1 -> TerminalAssignment.Assigned(eligible.single())
            eligible.size > 1 -> TerminalAssignment.Choices(eligible.map { it.id })
            else -> TerminalAssignment.Blocked(SetupProblem.TERMINAL_ACCESS)
        }
    }

    /** Selects [poiId]'s first match; a blank [merchantAccount] is filled from it, a different one is a mismatch. */
    fun select(
        terminals: List<TerminalDetails>,
        poiId: String,
        merchantAccount: String,
    ): TerminalMatch {
        val terminal = offered(terminals, poiId).firstOrNull() ?: return TerminalAssignment.Blocked(SetupProblem.TERMINAL_ACCESS)
        val account = merchantAccount.trim()
        return if (account.isNotBlank() && account != terminal.merchantAccount) {
            TerminalAssignment.Blocked(SetupProblem.MERCHANT_MISMATCH)
        } else {
            TerminalAssignment.Assigned(terminal)
        }
    }

    /** Verifies the first identity match and exact account; a blank listed account is specifically incomplete. */
    fun access(
        terminals: List<TerminalDetails>,
        poiId: String,
        merchantAccount: String,
    ): SetupProblem? {
        val terminal = offered(terminals, poiId).firstOrNull()
        return when {
            terminal == null -> SetupProblem.TERMINAL_ACCESS
            terminal.merchantAccount.isBlank() -> SetupProblem.MERCHANT_ACCOUNT
            terminal.merchantAccount != merchantAccount.trim() -> SetupProblem.MERCHANT_MISMATCH
            else -> null
        }
    }

    /** Reads the first identity match's store; unlike access, any differing account is a mismatch. */
    fun receipt(
        terminals: List<TerminalDetails>,
        poiId: String,
        merchantAccount: String,
    ): TerminalMatch {
        val terminal = offered(terminals, poiId).firstOrNull() ?: return TerminalAssignment.Blocked(SetupProblem.TERMINAL_ACCESS)
        return if (terminal.merchantAccount != merchantAccount.trim()) {
            TerminalAssignment.Blocked(SetupProblem.MERCHANT_MISMATCH)
        } else {
            TerminalAssignment.Assigned(terminal)
        }
    }

    /** Requires one identity match and unchanged account/store before key creation; duplicates mean no usable assignment. */
    fun unchanged(
        terminals: List<TerminalDetails>,
        poiId: String,
        original: TerminalSettings,
    ): SetupProblem? {
        val terminal = offered(terminals, poiId).singleOrNull() ?: return SetupProblem.TERMINAL_ACCESS
        return if (terminal.merchantAccount != original.merchantAccount.trim() || terminal.storeId != original.storeId) {
            SetupProblem.SETUP_CHANGED
        } else {
            null
        }
    }
}
