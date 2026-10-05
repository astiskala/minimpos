package io.github.astiskala.minimpos.app.data

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.data.settings.ObservedStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ObservedStoreTest {
    private val dir: File = Files.createTempDirectory("observed-store").toFile()

    /** Real threads, so the held write blocks one while the collector reads on another. */
    private val threads = Executors.newFixedThreadPool(THREADS).asCoroutineDispatcher()
    private val storeScope = CoroutineScope(threads + SupervisorJob())
    private val collectors = CoroutineScope(threads + SupervisorJob())
    private val writeStarted = CountDownLatch(1)
    private val release = CountDownLatch(1)

    /** Holds a write inside DataStore, after it has raised the store's version, until [release] opens. */
    private val heldWrites =
        object : Serializer<String> {
            override val defaultValue = "before"

            override suspend fun readFrom(input: InputStream) = input.readBytes().decodeToString()

            override suspend fun writeTo(
                t: String,
                output: OutputStream,
            ) {
                writeStarted.countDown()
                release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                output.write(t.toByteArray())
            }
        }

    @After
    fun tearDown() {
        release.countDown()
        collectors.cancel()
        storeScope.cancel()
        threads.close()
        dir.deleteRecursively()
    }

    @Test
    fun `a collector that starts while a write is underway still receives that write`() =
        runTest {
            withContext(threads) { collectorStartedDuringWrite() }
        }

    private suspend fun collectorStartedDuringWrite() {
        val store = ObservedStore(DataStoreFactory.create(heldWrites, scope = storeScope) { File(dir, "value") })
        assertThat(store.data.first()).isEqualTo("before")
        val write = collectors.async { store.update { "after" } }
        assertThat(writeStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
        val state = store.data.stateIn(collectors, SharingStarted.Eagerly, "unread")
        // Its first read happens during the write, which is where plain DataStore 1.2.1 collectors stay behind.
        assertThat(withTimeout(WAIT_MILLIS) { state.first { it != "unread" } }).isEqualTo("before")
        release.countDown()
        assertThat(write.await()).isEqualTo("after")
        assertThat(withTimeout(WAIT_MILLIS) { state.first { it == "after" } }).isEqualTo("after")
    }

    private companion object {
        const val THREADS = 4
        const val WAIT_SECONDS = 10L
        const val WAIT_MILLIS = WAIT_SECONDS * 1_000
    }
}
