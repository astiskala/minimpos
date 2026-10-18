package app.minimpos.app.data.security

/**
 * Optional Manager PIN approval of money-moving operations ("Manager approval"): whether one may be sent now, checked
 * again immediately before sending. Approval belongs to the operation it was given for, not to the screens: an
 * operation takes its [Grant] when the operator starts it ([grant]) and keeps it until it is sent, so navigating to a
 * waiting screen, which closes the approval session, cannot refuse an operation the operator just approved; inactivity
 * past the auto-lock time still does. Follow-ups without a start of their own (resending an unknown operation,
 * captures, link changes) check the session as it is now ([allowsNow]). Without a Manager PIN everything is allowed.
 * Only PIN entry opens the session; this never verifies a PIN.
 *
 * @param configured Whether a Manager PIN is set; read for each decision.
 * @param session The approval session the Manager PIN entry unlocks.
 * @param autoLockMillis The inactivity time-out in milliseconds, 0 or less for none; read for each decision.
 */
class ManagerApproval(
    private val configured: suspend () -> Boolean,
    private val session: SessionLock,
    private val autoLockMillis: () -> Long,
) {
    /**
     * The approval an operation started now holds: take it synchronously when the operator starts the operation, before
     * leaving the screen that approved it. Main-safe.
     */
    fun grant(): Grant = Grant(session.approvalTime(autoLockMillis()))

    /**
     * Whether an operation that took [grant] may be sent now: no Manager PIN is set, the session is open, or it was
     * open when the operation started and has not timed out since, even if navigation locked it meanwhile.
     */
    suspend fun allows(grant: Grant): Boolean {
        if (!configured()) return true
        val timeout = autoLockMillis()
        return session.allows(timeout) || grant.approvedAt?.let { session.unexpired(it, timeout) } == true
    }

    /** Whether a follow-up without a start of its own may be sent now: no Manager PIN is set, or the session is open. */
    suspend fun allowsNow(): Boolean = !configured() || session.allows(autoLockMillis())

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
        /** Allows everything: no Manager PIN can be set, as for operations whose runner is not approved. */
        val NOT_REQUIRED = ManagerApproval({ false }, SessionLock { 0L }, { 0L })
    }
}
