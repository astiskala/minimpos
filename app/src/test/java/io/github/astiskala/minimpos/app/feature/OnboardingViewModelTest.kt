package io.github.astiskala.minimpos.app.feature

import android.database.sqlite.SQLiteException
import androidx.lifecycle.ViewModel
import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.closeViewModels
import io.github.astiskala.minimpos.app.data.repo.SampleData
import io.github.astiskala.minimpos.app.data.settings.TerminalMode
import io.github.astiskala.minimpos.app.feature.settings.OnboardingChoice
import io.github.astiskala.minimpos.app.feature.settings.OnboardingViewModel
import io.github.astiskala.minimpos.app.feature.settings.SampleDataViewModel
import io.github.astiskala.minimpos.app.feature.settings.sampleDataWrite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OnboardingViewModelTest {
    private val env = TestEnvironment(onboardingCompleted = false)
    private val viewModels = mutableListOf<ViewModel>()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        closeViewModels(viewModels)
        env.close()
        Dispatchers.resetMain()
    }

    private fun model(samples: SampleData = env.container.sampleData) =
        OnboardingViewModel(env.container.pricingChanges, samples, TerminalMode.TERMINAL).also(viewModels::add)

    @Test
    fun `simulator choice persists samples and clears learned connection facts without replacing merchant fields`() {
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.TERMINAL, host = "merchant-host")) }
        val vm = model()
        var done: OnboardingChoice? = null
        vm.choose(OnboardingChoice.SIMULATOR) { done = it }
        await { vm.state.first { !it.running } }
        assertThat(done).isEqualTo(OnboardingChoice.SIMULATOR)
        val stored = await { env.container.settings.current() }
        assertThat(stored.onboardingCompleted).isTrue()
        assertThat(stored.terminal.mode).isEqualTo(TerminalMode.SIMULATOR)
        assertThat(stored.terminal.host).isEqualTo("merchant-host")
        assertThat(stored.shared().onboardingCompleted).isFalse()
        assertThat(stored.shared().withDeviceFieldsOf(stored).onboardingCompleted).isTrue()
    }

    @Test
    fun `import and terminal choices create no sample data and preserve destination`() {
        val vm = model()
        OnboardingChoice.entries.filter { it != OnboardingChoice.SIMULATOR }.forEach { choice ->
            env.updateSettings { it.copy(onboardingCompleted = false, terminal = it.terminal.copy(mode = TerminalMode.TERMINAL)) }
            var done: OnboardingChoice? = null
            vm.choose(choice) { done = it }
            await { vm.state.first { !it.running } }
            assertThat(done).isEqualTo(choice)
            assertThat(await { env.container.settings.current() }.terminal.mode).isEqualTo(TerminalMode.TERMINAL)
            assertThat(
                await {
                    env.container.catalog.products
                        .first()
                },
            ).isEmpty()
        }
    }

    @Test
    fun `failed sample writes keep onboarding incomplete and expose retryable error`() {
        val container = env.container
        val broken =
            SampleData(
                container.database,
                container.catalog,
                container.sales,
                container.history,
                texts = { error("Unavailable sample names") },
                starterRates = container::starterTaxRates,
                country = "AU",
            )
        val vm = model(broken)
        vm.choose(OnboardingChoice.SIMULATOR) { error("Must not navigate after failure") }
        await { vm.state.first { it.failed } }
        assertThat(await { container.settings.current() }.onboardingCompleted).isFalse()
        var done = false
        vm.choose(OnboardingChoice.IMPORT) { done = true }
        await { vm.state.first { !it.running } }
        assertThat(done).isTrue()
        assertThat(vm.state.value.failed).isFalse()
    }

    @Test
    fun `Data actions add idempotently and purge without changing the destination or onboarding choice`() {
        val container = env.container
        val vm = SampleDataViewModel(container.pricingChanges, container.sampleData).also(viewModels::add)
        val stored = await { container.settings.current() }
        vm.populate()
        vm.populate()
        await { vm.state.first { it.added == true } }
        vm.populate()
        await { vm.state.first { !it.running && it.added == true } }
        assertThat(await { container.catalog.products.first() }).hasSize(4)
        vm.purge()
        await { vm.state.first { it.added == false } }
        assertThat(await { container.catalog.products.first() }).isEmpty()
        assertThat(await { container.settings.current() }).isEqualTo(stored)
    }

    @Test
    fun `Data write failures are reported and can be retried with removal`() {
        val container = env.container
        val broken =
            SampleData(
                container.database,
                container.catalog,
                container.sales,
                container.history,
                texts = { error("Unavailable sample names") },
                starterRates = container::starterTaxRates,
                country = "AU",
            )
        val vm = SampleDataViewModel(container.pricingChanges, broken).also(viewModels::add)
        vm.populate()
        await { vm.state.first { it.failed } }
        vm.purge()
        await { vm.state.first { it.added == false } }
        assertThat(vm.state.value.failed).isFalse()
    }

    @Test
    fun `sample write presentation handles storage failures but preserves cancellation`() =
        await {
            val failures =
                listOf(IOException("Storage unavailable"), SQLiteException("Database unavailable"), IllegalStateException("Not ready"))
            failures.forEach { failure ->
                assertThat(sampleDataWrite<Unit> { throw failure }.exceptionOrNull()).isSameInstanceAs(failure)
            }
        }

    @Test
    fun `sample write presentation does not swallow cancellation`() {
        val cancellation = CancellationException("Screen closed")
        try {
            await { sampleDataWrite<Unit> { throw cancellation } }
            fail("Cancellation must propagate")
        } catch (e: CancellationException) {
            assertThat(e).hasMessageThat().isEqualTo(cancellation.message)
        }
    }
}
