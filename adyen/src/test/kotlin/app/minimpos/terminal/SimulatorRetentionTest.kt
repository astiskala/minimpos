package app.minimpos.terminal

import app.minimpos.terminal.checkout.ModificationAmount
import app.minimpos.terminal.checkout.ModificationResult
import app.minimpos.terminal.client.PaymentParams
import app.minimpos.terminal.client.PosApplication
import app.minimpos.terminal.client.TerminalClient
import app.minimpos.terminal.client.TerminalIdentity
import app.minimpos.terminal.client.TerminalTotalsOutcome
import app.minimpos.terminal.client.TransactionOutcome
import app.minimpos.terminal.simulator.SimulatorConfig
import app.minimpos.terminal.simulator.SimulatorRemoval
import app.minimpos.terminal.simulator.TerminalSimulator
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.random.Random

class SimulatorRetentionTest {
    private val simulator = TerminalSimulator({ SimulatorConfig(delayMillis = 0) }, random = Random(42))
    private val client =
        TerminalClient(
            currencyDigits = testCurrencyDigits,
            transport = simulator,
            identity = TerminalIdentity("POS1", "SIMULATOR-000000001"),
            application = PosApplication("Mini mPOS", "1.0", "Mini mPOS", "Android", "13"),
        )
    private val tokenizing = PaymentParams(1_200, "AUD", "MP-1")

    @Test
    fun `full history purge releases completed answers and ledger without resetting period totals`() =
        runBlocking {
            val paid = client.pay(tokenizing, serviceId = "purged") as TransactionOutcome.Completed
            val before = client.getTotals()
            val psp = checkNotNull(paid.details.pspReference)
            assertThat(simulator.modifications.capture(psp, ModificationAmount("AUD", 1_200), "capture", "capture"))
                .isInstanceOf(ModificationResult.Failed::class.java)
            simulator.forgetHistory()
            assertThat(client.status("purged")).isNotInstanceOf(TransactionOutcome.Completed::class.java)
            assertThat(client.getTotals()).isEqualTo(before)
            assertThat(simulator.modifications.capture(psp, ModificationAmount("AUD", 1_200), "fresh", "fresh"))
                .isInstanceOf(ModificationResult.Received::class.java)
        }

    @Test
    fun `history cleanup forgets completed identities but preserves unresolved answers and period totals`() =
        runBlocking {
            val old = client.pay(tokenizing, serviceId = "old") as TransactionOutcome.Completed
            val kept = client.pay(tokenizing, serviceId = "kept") as TransactionOutcome.Completed
            val before = client.getTotals() as TerminalTotalsOutcome.Answered
            assertThat(client.status("old")).isInstanceOf(TransactionOutcome.Completed::class.java)
            simulator.forget(
                SimulatorRemoval(
                    emptySet(),
                    setOf(old.details.poiTransactionId!!),
                    setOf(old.details.pspReference!!),
                    emptySet(),
                ),
            )
            assertThat(client.status("old")).isNotInstanceOf(TransactionOutcome.Completed::class.java)
            assertThat(client.status("kept")).isInstanceOf(TransactionOutcome.Completed::class.java)
            assertThat(client.getTotals()).isEqualTo(before)
            simulator.forget(
                SimulatorRemoval(
                    setOf("kept"),
                    setOf(kept.details.poiTransactionId!!),
                    setOf(kept.details.pspReference!!),
                    emptySet(),
                ),
            )
            assertThat(client.status("kept")).isNotInstanceOf(TransactionOutcome.Completed::class.java)
            assertThat(
                simulator.modifications.capture(
                    old.details.pspReference,
                    ModificationAmount("AUD", 1_200),
                    "old-capture",
                    "old-capture",
                ),
            ).isInstanceOf(ModificationResult.Received::class.java)
            simulator.forget(SimulatorRemoval(setOf("absent"), setOf("absent"), setOf("absent"), emptySet()))
        }
}
