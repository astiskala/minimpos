package app.minimpos.app.data.security

import app.minimpos.core.access.ManagerAction

/**
 * Optional Manager PIN approval of money-moving operations ("Manager approval"): whether one may be sent now, checked
 * again immediately before sending. Approval belongs to the operation it was given for, not to the screens: an
 * operation takes its [Grant] when the operator starts it ([grant]) and keeps it until it is sent, so navigating to a
 * waiting screen, which closes the approval session, cannot refuse an operation the operator just approved; inactivity
 * past the auto-lock time still does. Follow-ups without a start of their own (resending an unknown operation,
 * captures, link changes) check the session as it is now ([allowsNow]). Action scopes use the live admin-configured
 * policy: public actions need no PIN, while protected actions need a configured Manager PIN and approval.
 * Standalone approvals without a policy retain optional approval and explicit configured-PIN checks.
 * Only PIN entry opens the session; this never verifies a PIN.
 *
 * @param configured Whether a Manager PIN is set; read for each decision.
 * @param session The approval session the Manager PIN entry unlocks.
 * @param autoLockMillis The inactivity time-out in milliseconds, 0 or less for none; read for each decision.
 * @param requirement Current action policy; null for standalone optional approvals.
 * @param actions Actions whose requirements must all be met.
 */
class ManagerApproval private constructor(
    private val configured: suspend () -> Boolean,
    private val session: SessionLock,
    private val autoLockMillis: () -> Long,
    private val requirement: (suspend (ManagerAction) -> Boolean)?,
    private val actions: Set<ManagerAction>,
) {
    /** Creates an optional standalone approval with [configured], [session] and [autoLockMillis]. */
    constructor(configured: suspend () -> Boolean, session: SessionLock, autoLockMillis: () -> Long) :
        this(configured, session, autoLockMillis, null, emptySet())

    /** Creates action-scoped approval using the live [requirement] policy. */
    constructor(
        configured: suspend () -> Boolean,
        session: SessionLock,
        autoLockMillis: () -> Long,
        requirement: suspend (ManagerAction) -> Boolean,
    ) : this(configured, session, autoLockMillis, requirement, emptySet())

    /**
     * The approval an operation started now holds: take it synchronously when the operator starts the operation, before
     * leaving the screen that approved it. Main-safe.
     */
    fun grant(): Grant = Grant(session.approvalTime(autoLockMillis()))

    /** Shares the approval session and live policy for [action]. */
    fun forAction(action: ManagerAction): ManagerApproval = forActions(setOf(action))

    /** Shares the approval session, requiring every protected action in [selected]. */
    fun forActions(selected: Set<ManagerAction>): ManagerApproval =
        ManagerApproval(configured, session, autoLockMillis, requirement, selected)

    /** Whether this scope currently needs a PIN, independent of its approval session. */
    suspend fun required(): Boolean =
        requirement?.let { policy -> actions.any { policy(it) } }
            ?: ((actions.isEmpty() || actions.any { it.defaultRequired }) && configured())

    /** Whether missing PIN configuration permits this scope to be used at all. */
    suspend fun available(): Boolean = !required() || configured()

    /**
     * Whether an operation holding [grant] may be sent under the current policy: public, or with a configured PIN and
     * an open session or unexpired grant, even if navigation locked the session meanwhile.
     */
    suspend fun allows(grant: Grant): Boolean {
        if (!required()) return true
        if (!configured()) return false
        return unexpired(grant)
    }

    /** Checks [grant] under the action policy; standalone callers also require a configured PIN. */
    suspend fun allowsConfigured(grant: Grant): Boolean =
        if (requirement == null &&
            (actions.isEmpty() || actions.any { it.defaultRequired })
        ) {
            configured() && unexpired(grant)
        } else {
            allows(grant)
        }

    private fun unexpired(grant: Grant): Boolean {
        val timeout = autoLockMillis()
        return session.allows(timeout) || grant.approvedAt?.let { session.unexpired(it, timeout) } == true
    }

    /** Whether a follow-up may be sent now: public, or with a configured PIN and a currently open session. */
    suspend fun allowsNow(): Boolean = !required() || (configured() && session.allows(autoLockMillis()))

    /**
     * Approval held by one started operation.
     *
     * @property approvedAt When the session approved it, in the session's clock; null when it was not open then.
     */
    class Grant internal constructor(
        internal val approvedAt: Long?,
    )

    /** Approval for code without a Manager PIN. */
    companion object {
        /** Permits optional-approval operations; actions requiring a configured PIN remain blocked. */
        val NOT_REQUIRED = ManagerApproval({ false }, SessionLock { 0L }, { 0L })
    }
}
