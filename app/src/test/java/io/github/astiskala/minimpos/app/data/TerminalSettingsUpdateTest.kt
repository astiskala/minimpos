package io.github.astiskala.minimpos.app.data

import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TerminalSettingsUpdateTest {
    private val env = TestEnvironment()
    private val settings = env.container.settings

    @After
    fun tearDown() = env.close()

    @Test
    fun `stale terminal settings never prepare or write dependent secrets`() =
        await {
            val origin = settings.current().terminal
            settings.update { it.copy(terminal = it.terminal.copy(merchantAccount = "Other")) }
            assertThat(settings.updateTerminal(origin) { error("Must not prepare stale secrets") }).isFalse()
            assertThat(settings.current().terminal.merchantAccount).isEqualTo("Other")
        }

    @Test
    fun `failed preparation preserves terminal fields`() =
        await {
            val origin = settings.current().terminal
            assertThat(settings.updateTerminal(origin) { null }).isFalse()
            assertThat(settings.current().terminal).isEqualTo(origin)
        }

    @Test
    fun `terminal edits wait for dependent secret storage and do not lose updated fields`() =
        await {
            coroutineScope {
                val origin = settings.current().terminal
                val preparing = CompletableDeferred<Unit>()
                val proceed = CompletableDeferred<Unit>()
                val lookup =
                    async {
                        settings.updateTerminal(origin) {
                            preparing.complete(Unit)
                            proceed.await()
                            origin.copy(keyIdentifier = "retrieved-key", keyVersion = 2)
                        }
                    }
                preparing.await()
                val edit =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        settings.update { it.copy(terminal = it.terminal.copy(merchantAccount = "Other")) }
                    }
                assertThat(edit.isCompleted).isFalse()
                proceed.complete(Unit)
                assertThat(lookup.await()).isTrue()
                edit.await()
                val terminal = settings.current().terminal
                assertThat(terminal.merchantAccount).isEqualTo("Other")
                assertThat(terminal.keyIdentifier).isEqualTo("retrieved-key")
                assertThat(terminal.keyVersion).isEqualTo(2)
            }
        }
}
