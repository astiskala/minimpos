package app.minimpos.app.data

import app.minimpos.app.data.security.ManagerApproval
import app.minimpos.app.data.security.ManagerPolicy
import app.minimpos.app.data.security.SessionLock
import app.minimpos.core.access.ManagerAction
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** [ManagerApproval]: approval belongs to the operation it was given for, not to the screens. */
class ManagerApprovalTest {
    private var now = 1_000L
    private val policy = MutableStateFlow(ManagerPolicy(adminPin = true, managerPin = true))
    private val session = SessionLock { now }
    private val approval = ManagerApproval(policy, session) { TIMEOUT }.forAction(ManagerAction.REFERENCED_REFUND)

    @Test
    fun `an operation started while approved keeps its approval when navigation locks the session`() =
        runBlocking {
            session.unlock()
            val grant = approval.grant()
            session.lock()
            assertThat(approval.allows(grant)).isTrue()
            assertThat(approval.allowsNow()).isFalse()
        }

    @Test
    fun `inactivity past the auto-lock time still expires a started operation's approval`() =
        runBlocking {
            session.unlock()
            val grant = approval.grant()
            session.lock()
            now += TIMEOUT
            assertThat(approval.allows(grant)).isFalse()
        }

    @Test
    fun `an operation started without approval is refused unless the session is open when it is sent`() =
        runBlocking {
            val grant = approval.grant()
            assertThat(approval.allows(grant)).isFalse()
            session.unlock()
            assertThat(approval.allows(grant)).isTrue()
        }

    @Test
    fun `without an admin PIN everything is allowed`() =
        runBlocking {
            policy.value = ManagerPolicy(adminPin = false, managerPin = true)
            assertThat(approval.allows(approval.grant())).isTrue()
            assertThat(approval.allowsNow()).isTrue()
            assertThat(ManagerApproval.NOT_REQUIRED.forAction(ManagerAction.TOKEN_DELETE).allows(ManagerApproval.NOT_REQUIRED.grant()))
                .isTrue()
        }

    @Test
    fun `a protected action needs a configured Manager PIN and does not accept admin or absent Manager access`() =
        runBlocking {
            policy.value = ManagerPolicy(adminPin = true, managerPin = false)
            session.unlock()
            val grant = approval.grant()
            assertThat(approval.allows(grant)).isFalse()
            assertThat(approval.available.first()).isFalse()
            policy.value = ManagerPolicy(adminPin = true, managerPin = true)
            assertThat(approval.allows(grant)).isTrue()
            assertThat(approval.available.first()).isTrue()
            session.lock()
            assertThat(approval.allows(grant)).isTrue()
            now += TIMEOUT
            assertThat(approval.allows(grant)).isFalse()
        }

    @Test
    fun `offers and PIN entry follow the same policy the send checks`() =
        runBlocking {
            val unscoped = ManagerApproval(policy, session) { TIMEOUT }
            assertThat(unscoped.required.first()).isFalse()
            assertThat(approval.required.first()).isTrue()
            policy.value = ManagerPolicy(adminPin = false)
            assertThat(approval.required.first()).isFalse()
            assertThat(approval.available.first()).isTrue()
        }

    private companion object {
        const val TIMEOUT = 60_000L
    }
}
