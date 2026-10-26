package app.minimpos.app.data

import app.minimpos.app.data.security.ManagerApproval
import app.minimpos.app.data.security.SessionLock
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.SecuritySettings
import app.minimpos.core.access.ManagerAction
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Test

class ManagerPermissionsTest {
    private var admin = false
    private var manager = false
    private var now = 1_000L
    private var settings = SecuritySettings()
    private val session = SessionLock { now }
    private val approval =
        ManagerApproval(
            configured = { manager },
            session = session,
            autoLockMillis = { 60_000L },
            requirement = { admin && settings.requiresManager(it) },
        )

    @Test
    fun `every action is public without admin and explicit public choices bypass an existing Manager PIN`() =
        runBlocking {
            ManagerAction.entries.forEach { action ->
                settings = settings.copy(managerRequirements = mapOf(action.name to true))
                assertThat(approval.forAction(action).allowsConfigured(approval.grant())).isTrue()
                admin = true
                assertThat(approval.forAction(action).available()).isFalse()
                assertThat(approval.forAction(action).allows(approval.grant())).isFalse()
                manager = true
                assertThat(approval.forAction(action).available()).isTrue()
                assertThat(approval.forAction(action).allowsNow()).isFalse()
                settings = settings.copy(managerRequirements = mapOf(action.name to false))
                assertThat(approval.forAction(action).allowsNow()).isTrue()
                assertThat(approval.forAction(action).allowsConfigured(approval.grant())).isTrue()
                admin = false
                manager = false
            }
        }

    @Test
    fun `live permission changes and PIN removal cannot reuse an unapproved grant`() =
        runBlocking {
            admin = true
            manager = true
            val sale = approval.forAction(ManagerAction.SALE)
            val grant = sale.grant()
            assertThat(sale.allows(grant)).isTrue()
            settings = settings.copy(managerRequirements = mapOf(ManagerAction.SALE.name to true))
            assertThat(sale.allows(grant)).isFalse()
            session.unlock()
            val approved = sale.grant()
            session.lock()
            assertThat(sale.allows(approved)).isTrue()
            assertThat(sale.allowsNow()).isFalse()
            manager = false
            assertThat(sale.allows(approved)).isFalse()
            manager = true
            now += 60_000L
            assertThat(sale.allows(approved)).isFalse()
            admin = false
            assertThat(sale.allows(approved)).isTrue()
            assertThat(settings.requiresManager(ManagerAction.SALE)).isTrue()
        }

    @Test
    fun `combined actions require every permission while different risk profiles stay independent`() =
        runBlocking {
            admin = true
            val combined = approval.forActions(setOf(ManagerAction.SALE, ManagerAction.CASH_PAYMENT))
            settings = settings.copy(managerRequirements = mapOf(ManagerAction.CASH_PAYMENT.name to true))
            assertThat(approval.forAction(ManagerAction.SALE).allowsNow()).isTrue()
            assertThat(combined.allowsNow()).isFalse()
            settings = settings.copy(managerRequirements = mapOf(ManagerAction.REFERENCED_REFUND.name to false))
            assertThat(approval.forAction(ManagerAction.REFERENCED_REFUND).allowsNow()).isTrue()
            assertThat(approval.forAction(ManagerAction.UNREFERENCED_REFUND).allowsNow()).isFalse()
            assertThat(approval.forAction(ManagerAction.CASH_REFUND).allowsNow()).isFalse()
        }

    @Test
    fun `stored settings retain choices and omitted actions keep baseline defaults`() {
        val codec = serializer<AppSettings>()
        val original = Json.decodeFromString(codec, "{}")
        assertThat(original.security.requiresManager(ManagerAction.SALE)).isFalse()
        assertThat(original.security.requiresManager(ManagerAction.REFERENCED_REFUND)).isTrue()
        val changed =
            original.copy(
                security =
                    SecuritySettings(
                        managerRequirements =
                            mapOf(
                                ManagerAction.SALE.name to true,
                                ManagerAction.REFERENCED_REFUND.name to false,
                            ),
                    ),
            )
        val restored = Json.decodeFromString(codec, Json.encodeToString(codec, changed))
        assertThat(restored).isEqualTo(changed)
        assertThat(restored.security.requiresManager(ManagerAction.UNREFERENCED_REFUND)).isTrue()
        assertThat(restored.shared().security).isEqualTo(changed.security)
    }
}
