package app.minimpos.app.data

import app.minimpos.app.data.security.ManagerApproval
import app.minimpos.app.data.security.SessionLock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** [ManagerApproval]: approval belongs to the operation it was given for, not to the screens. */
class ManagerApprovalTest {
    private var now = 1_000L
    private var configured = true
    private val session = SessionLock { now }
    private val approval = ManagerApproval({ configured }, session) { TIMEOUT }

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
    fun `without a Manager PIN everything is allowed`() =
        runBlocking {
            configured = false
            assertThat(approval.allows(approval.grant())).isTrue()
            assertThat(approval.allowsNow()).isTrue()
            assertThat(ManagerApproval.NOT_REQUIRED.allows(ManagerApproval.NOT_REQUIRED.grant())).isTrue()
        }

    private companion object {
        const val TIMEOUT = 60_000L
    }
}
