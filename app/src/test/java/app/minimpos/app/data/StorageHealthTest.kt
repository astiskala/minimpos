package app.minimpos.app.data

import androidx.datastore.core.DataStore
import app.minimpos.app.FakeUpdateCheck
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.StorageHealth
import app.minimpos.app.data.settings.StorageState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StorageHealthTest {
    @Test
    fun `malformed configuration is preserved and startup never probes a terminal or checks updates`() {
        listOf("settings.json", "secrets.json").forEach { name ->
            val updates = FakeUpdateCheck()
            val probes = AtomicInteger()
            val malformed = "{\"retained-recovery-material\":"
            val env =
                TestEnvironment(storedFiles = mapOf(name to malformed), updates = updates, terminalEnvironment = {
                    probes.incrementAndGet()
                    null
                })
            try {
                env.container.start()
                await {
                    env.container.storage.state
                        .first { it == StorageState.UNAVAILABLE }
                }
                assertThat(env.dir.resolve(name).readText()).isEqualTo(malformed)
                assertThat(probes.get()).isEqualTo(0)
                assertThat(updates.checks).isEqualTo(0)
                assertThat(env.container.sessionLock.unlocked.value).isFalse()
                assertThat(env.container.managerLock.unlocked.value).isFalse()
            } finally {
                env.close()
            }
        }
    }

    @Test
    fun `failed writes cancel success continuations and latch unavailable storage without emitting defaults`() =
        runTest {
            val health = StorageHealth()
            val store =
                health.protect(
                    object : DataStore<AppSettings> {
                        override val data = flowOf(AppSettings())

                        override suspend fun updateData(transform: suspend (t: AppSettings) -> AppSettings): AppSettings =
                            throw IOException("disk full")
                    },
                )
            supervisorScope {
                val write = async { store.updateData { it } }
                write.join()
                assertThat(write.isCancelled).isTrue()
                assertThat(write.getCompletionExceptionOrNull()?.cause).isInstanceOf(IOException::class.java)
            }
            assertThat(health.state.value).isEqualTo(StorageState.UNAVAILABLE)
            assertThat(withTimeoutOrNull(1) { store.data.first() }).isNull()
            assertThat(withTimeoutOrNull(1) { health.awaitReady() }).isNull()
        }

    @Test
    fun `new installations become ready without replacing a file`() {
        val env = TestEnvironment()
        try {
            await { env.container.storage.awaitReady() }
            assertThat(env.container.storage.state.value).isEqualTo(StorageState.READY)
        } finally {
            env.close()
        }
    }
}
