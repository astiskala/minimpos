package app.minimpos.app.terminal

import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.settings.TerminalSettings
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalDetails
import app.minimpos.terminal.transport.TerminalDetailsApi
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalListing

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

/** Verified terminal listing for interactive setup discovery. */
internal sealed interface DiscoveredTerminals {
    data class Found(
        val terminals: List<TerminalDetails>,
    ) : DiscoveredTerminals

    data class Failed(
        val failure: Failure,
    ) : DiscoveredTerminals
}

/** Verified terminal assignment outcome for automatic or explicit setup import. */
internal sealed interface DiscoveredAssignment {
    data class Assigned(
        val terminal: TerminalDetails,
    ) : DiscoveredAssignment

    data class Choices(
        val ids: List<String>,
    ) : DiscoveredAssignment

    data class Failed(
        val failure: Failure,
    ) : DiscoveredAssignment
}

/** Verified single-terminal assignment for store receipt and POS-wallet lookups. */
internal sealed interface AssignedTerminal {
    data class Found(
        val terminal: TerminalDetails,
    ) : AssignedTerminal

    data class Blocked(
        val problem: SetupProblem,
    ) : AssignedTerminal

    data class Failed(
        val fault: Fault,
    ) : AssignedTerminal
}

/**
 * Owns Management terminal listing queries, environment verification and assignment matching while keeping
 * discovery, access, receipt lookup and key-creation strictness distinct.
 */
internal object TerminalAssignments {
    /** Lists eligible terminals in [environment], filtering to [poiId] when [onDevice]. */
    suspend fun listOffered(
        api: TerminalDetailsApi,
        environment: TerminalEnvironment,
        onDevice: Boolean,
        poiId: String?,
    ): DiscoveredTerminals {
        val target = poiId.takeIf { onDevice }
        return when (val listed = query(api, environment, target)) {
            is ListingResult.Failed -> {
                DiscoveredTerminals.Failed(listed.failure)
            }

            is ListingResult.Listed -> {
                val matched = if (onDevice && target == null) emptyList() else offered(listed.terminals, target)
                if (matched.isEmpty()) {
                    DiscoveredTerminals.Failed(Failure.NotSetUp(SetupProblem.TERMINAL_ACCESS))
                } else {
                    DiscoveredTerminals.Found(matched)
                }
            }
        }
    }

    /** Resolves an automatic or explicit [poiId] in [environment] for [merchantAccount]. */
    suspend fun discover(
        api: TerminalDetailsApi,
        environment: TerminalEnvironment,
        poiId: String?,
        merchantAccount: String,
    ): DiscoveredAssignment =
        when (val listed = query(api, environment, poiId)) {
            is ListingResult.Failed -> {
                DiscoveredAssignment.Failed(listed.failure)
            }

            is ListingResult.Listed -> {
                when (val assignment = discover(listed.terminals, poiId, merchantAccount)) {
                    is TerminalAssignment.Assigned -> DiscoveredAssignment.Assigned(assignment.terminal)
                    is TerminalAssignment.Choices -> DiscoveredAssignment.Choices(assignment.ids)
                    is TerminalAssignment.Blocked -> DiscoveredAssignment.Failed(Failure.NotSetUp(assignment.problem))
                }
            }
        }

    /** Verifies that [poiId] in [environment] is accessible and assigned to [merchantAccount]; null when valid. */
    suspend fun verifyAccess(
        api: TerminalDetailsApi,
        environment: TerminalEnvironment,
        poiId: String,
        merchantAccount: String,
    ): Failure? =
        when (val listed = query(api, environment, poiId)) {
            is ListingResult.Failed -> listed.failure
            is ListingResult.Listed -> access(listed.terminals, poiId, merchantAccount)?.let(Failure::NotSetUp)
        }

    /** Verifies that [poiId] in [environment] has a unique identity and unchanged account/store in [original]. */
    suspend fun verifyUnchanged(
        api: TerminalDetailsApi,
        environment: TerminalEnvironment,
        poiId: String,
        original: TerminalSettings,
    ): Failure? =
        when (val listed = query(api, environment, poiId)) {
            is ListingResult.Failed -> listed.failure
            is ListingResult.Listed -> unchanged(listed.terminals, poiId, original)?.let(Failure::NotSetUp)
        }

    /** Reads the assigned terminal [poiId] in [environment] for [merchantAccount], checking the response environment. */
    suspend fun assigned(
        api: TerminalDetailsApi,
        environment: TerminalEnvironment,
        poiId: String,
        merchantAccount: String,
    ): AssignedTerminal =
        when (val listed = query(api, environment, poiId)) {
            is ListingResult.Failed -> {
                AssignedTerminal.Failed(listed.fault)
            }

            is ListingResult.Listed -> {
                when (val assignment = receipt(listed.terminals, poiId, merchantAccount)) {
                    is TerminalAssignment.Assigned -> AssignedTerminal.Found(assignment.terminal)
                    is TerminalAssignment.Blocked -> AssignedTerminal.Blocked(assignment.problem)
                }
            }
        }

    private suspend fun query(
        api: TerminalDetailsApi,
        environment: TerminalEnvironment,
        poiId: String?,
    ): ListingResult =
        when (val result = api.terminals(environment, poiId)) {
            is TerminalListing.Failed -> {
                ListingResult.Failed(result.fault)
            }

            is TerminalListing.Listed -> {
                if (result.environment != environment) {
                    ListingResult.Failed(Fault.UnreadableReply())
                } else {
                    ListingResult.Listed(result.terminals)
                }
            }
        }

    private sealed interface ListingResult {
        data class Listed(
            val terminals: List<TerminalDetails>,
        ) : ListingResult

        data class Failed(
            val fault: Fault,
        ) : ListingResult {
            val failure: Failure get() = Failure.Remote(fault)
        }
    }

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
