package app.minimpos.app.payment

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class OperationRunnerTest {
    @Test
    fun `a finished operation publishes only after failure handling and request cleanup`() =
        runTest {
            for (fails in listOf(false, true)) {
                val runner = OperationRunner<String>(this, onTerminal = true) { OperationAmount(100, "AUD") }
                val steps = mutableListOf<String>()
                var duringCleanup: TransactionState? = null
                val id =
                    runner.start(
                        "request",
                        id = { "operation" },
                        interrupted = { _, _ -> steps += "interrupted" },
                        release = {
                            duringCleanup = runner.state.value
                            steps += "released"
                        },
                    ) {
                        steps += "run"
                        if (fails) error("Interrupted request")
                    }
                runner.state.first { it is TransactionState.Finished }

                assertThat(duringCleanup).isInstanceOf(TransactionState.Processing::class.java)
                assertThat(runner.state.value).isEqualTo(TransactionState.Finished(checkNotNull(id)))
                assertThat(steps)
                    .containsExactlyElementsIn(if (fails) listOf("run", "interrupted", "released") else listOf("run", "released"))
                    .inOrder()
            }
        }
}
