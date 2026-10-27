package app.minimpos.app.data.security

import app.minimpos.app.data.settings.SecuritySettings
import app.minimpos.core.access.ManagerAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The live Manager PIN policy, the one reading of whether actions need Manager approval: without an admin PIN every
 * action is public; with one, the actions the admin protected need a configured Manager PIN and its approval.
 *
 * @property adminPin Whether an admin PIN is set.
 * @property managerPin Whether a Manager PIN is set to approve protected actions.
 * @property security Which actions the admin protected ([SecuritySettings.requiresManager]).
 */
data class ManagerPolicy(
    val adminPin: Boolean = false,
    val managerPin: Boolean = false,
    val security: SecuritySettings = SecuritySettings(),
) {
    /** Whether any of [actions] needs Manager approval now; false for none. */
    fun required(actions: Set<ManagerAction>): Boolean = adminPin && actions.any(security::requiresManager)

    /** Whether [actions] can be offered: public, or a Manager PIN exists to approve them. */
    fun available(actions: Set<ManagerAction>): Boolean = !required(actions) || managerPin
}

/**
 * Manager PIN approval of money-moving operations ("Manager approval"): whether one may be offered, and whether it may
 * be sent now, checked again immediately before sending, all under one live [ManagerPolicy]. Approval belongs to the
 * operation it was given for, not to the screens: an operation takes its [Grant] when the operator starts it ([grant])
 * and keeps it until it is sent, so navigating to a waiting screen, which closes the approval session, cannot refuse an
 * operation the operator just approved; inactivity past the auto-lock time still does. Follow-ups without a start of
 * their own (resending an unknown operation, captures, link changes) check the session as it is now ([allowsNow]).
 * A scope ([forAction], [forActions]) requires every protected action it names; the unscoped approval names none.
 * Only PIN entry opens the session; this never verifies a PIN.
 *
 * @param policy The live policy, read for each decision and followed by [required] and [available].
 * @param session The approval session the Manager PIN entry unlocks.
 * @param autoLockMillis The inactivity time-out in milliseconds, 0 or less for none; read for each decision.
 * @param actions Actions whose requirements must all be met; empty for the unscoped approval.
 */
class ManagerApproval private constructor(
    private val policy: Flow<ManagerPolicy>,
    private val session: SessionLock,
    private val autoLockMillis: () -> Long,
    private val actions: Set<ManagerAction>,
) {
    /** Creates the unscoped approval of [policy], with its [session] and [autoLockMillis]. */
    constructor(policy: Flow<ManagerPolicy>, session: SessionLock, autoLockMillis: () -> Long) :
        this(policy, session, autoLockMillis, emptySet())

    /**
     * The approval an operation started now holds: take it synchronously when the operator starts the operation, before
     * leaving the screen that approved it. Main-safe.
     */
    fun grant(): Grant = Grant(session.approvalTime(autoLockMillis()))

    /** Shares the approval session and live policy for [action]. */
    fun forAction(action: ManagerAction): ManagerApproval = forActions(setOf(action))

    /** Shares the approval session, requiring every protected action in [selected]. */
    fun forActions(selected: Set<ManagerAction>): ManagerApproval = ManagerApproval(policy, session, autoLockMillis, selected)

    /** Whether this scope needs Manager approval, as the policy changes; PIN entry follows it. */
    val required: Flow<Boolean> get() = policy.map { it.required(actions) }.distinctUntilChanged()

    /** Whether this scope can be offered (public, or a Manager PIN exists to approve it), as the policy changes. */
    val available: Flow<Boolean> get() = policy.map { it.available(actions) }.distinctUntilChanged()

    /**
     * Whether an operation holding [grant] may be sent under the current policy: public, or with a configured PIN and
     * an open session or unexpired grant, even if navigation locked the session meanwhile.
     */
    suspend fun allows(grant: Grant): Boolean {
        val now = policy.first()
        return !now.required(actions) || (now.managerPin && unexpired(grant))
    }

    /** Whether a follow-up may be sent now: public, or with a configured PIN and a currently open session. */
    suspend fun allowsNow(): Boolean {
        val now = policy.first()
        return !now.required(actions) || (now.managerPin && session.allows(autoLockMillis()))
    }

    private fun unexpired(grant: Grant): Boolean {
        val timeout = autoLockMillis()
        return session.allows(timeout) || grant.approvedAt?.let { session.unexpired(it, timeout) } == true
    }

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
        /** The policy without an admin PIN: every action is public. */
        val NOT_REQUIRED = ManagerApproval(flowOf(ManagerPolicy()), SessionLock { 0L }) { 0L }
    }
}
