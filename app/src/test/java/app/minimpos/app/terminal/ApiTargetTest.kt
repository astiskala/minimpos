package app.minimpos.app.terminal

import app.minimpos.app.FakeLinkApi
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.core.money.PaymentContext
import app.minimpos.terminal.checkout.ModificationAmount
import app.minimpos.terminal.checkout.ModificationResult
import app.minimpos.terminal.checkout.PaymentModifications
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ApiTargetTest {
    private val context = PaymentContext("TERMINAL", "AMS1-1", "POS", "Merchant", "TEST")
    private val links = FakeLinkApi()
    private val modifications =
        object : PaymentModifications {
            override suspend fun capture(
                paymentPspReference: String,
                amount: ModificationAmount,
                reference: String,
                idempotencyKey: String,
            ): ModificationResult = ModificationResult.Received(null)

            override suspend fun updateAmount(
                paymentPspReference: String,
                amount: ModificationAmount,
                reference: String,
                adjustAuthorisationData: String?,
                idempotencyKey: String,
            ): ModificationResult = ModificationResult.Received(null)

            override suspend fun verify(): String? = null
        }

    @Test
    fun `both adapters require the same merchant and known environment, not the same terminal`() {
        val target = ApiTarget(ApiSetup.Complete, modifications, links, context)
        val sameAccount = context.copy(poiId = "AMS1-2", saleId = "OTHER")
        assertThat(target.modifications(sameAccount)).isEqualTo(ApiAccess.Ready(modifications))
        assertThat(target.links(sameAccount)).isEqualTo(ApiAccess.Ready(links))
        listOf(null, context.copy(merchantAccount = "Other"), context.copy(environment = "LIVE"), context.copy(environment = null))
            .forEach { expected ->
                assertThat(target.modifications(expected)).isEqualTo(ApiAccess.ContextMismatch)
                assertThat(target.links(expected)).isEqualTo(ApiAccess.ContextMismatch)
            }
    }

    @Test
    fun `unavailable adapters report setup before context mismatch and preserve the fallback`() {
        val missing = ApiTarget(ApiSetup.Incomplete(SetupProblem.UNREADABLE_API_KEY), context = context)
        assertThat(missing.modifications(null)).isEqualTo(ApiAccess.Unavailable(SetupProblem.UNREADABLE_API_KEY))
        assertThat(missing.links(null)).isEqualTo(ApiAccess.Unavailable(SetupProblem.UNREADABLE_API_KEY))
        val noAdapter = ApiTarget(ApiSetup.Complete)
        assertThat(noAdapter.modifications(context)).isEqualTo(ApiAccess.Unavailable(SetupProblem.API_REQUIRED))
        assertThat(noAdapter.links(context)).isEqualTo(ApiAccess.Unavailable(SetupProblem.API_REQUIRED))
        assertThat(ApiAccess.ContextMismatch.problem).isEqualTo(SetupProblem.PAYMENT_CONTEXT)
    }

    @Test
    fun `context-free fake adapters remain usable while simulated links reject real payment contexts`() {
        val fake = ApiTarget(ApiSetup.Complete, modifications, links)
        assertThat(fake.modifications(null)).isEqualTo(ApiAccess.Ready(modifications))
        assertThat(fake.links(context.copy(merchantAccount = "Other"))).isEqualTo(ApiAccess.Ready(links))
        val simulatedContext = context.copy(simulated = true, environment = null)
        val simulated = ApiTarget(ApiSetup.Simulated, modifications, links, simulatedContext)
        assertThat(simulated.modifications(simulatedContext)).isEqualTo(ApiAccess.Ready(modifications))
        assertThat(simulated.modifications(context)).isEqualTo(ApiAccess.ContextMismatch)
        assertThat(simulated.links(simulatedContext)).isEqualTo(ApiAccess.Ready(links))
        assertThat(simulated.links(context)).isEqualTo(ApiAccess.ContextMismatch)
    }
}
