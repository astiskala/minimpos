package app.minimpos.app.terminal

import app.minimpos.app.FakeDevice
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.core.payment.PaymentContext
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MexicanRoutingReadinessTest {
    @Test
    fun `only a verified Mexican assignment on a supported destination establishes routing availability`() {
        val ready =
            WalletAvailability(
                context = PaymentContext("TERMINAL", "AMS1-123", "POS", "Merchant", "TEST"),
                identity = "verified",
                checked = true,
                mexicanStore = true,
                mexicanDestination = true,
            )
        assertThat(ready.mexicanRouting).isTrue()
        listOf(
            WalletAvailability(),
            ready.copy(context = null),
            ready.copy(identity = null),
            ready.copy(checked = false),
            ready.copy(mexicanStore = false),
            ready.copy(mexicanDestination = false),
            ready.copy(problem = SetupProblem.POI_ID),
        ).forEach { assertThat(it.mexicanRouting).isFalse() }
        assertThat(ready.copy(mexicanStore = false, simulated = true).mexicanRouting).isTrue()
        assertThat(DestinationRules.of(TerminalMode.PAYMENTS_APP, FakeDevice()).mexicanInstallments).isFalse()
    }
}
