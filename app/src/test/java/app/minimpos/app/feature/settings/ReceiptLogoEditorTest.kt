package app.minimpos.app.feature.settings

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.closeViewModels
import app.minimpos.core.logo.ArgbImage
import app.minimpos.core.logo.LogoMode
import app.minimpos.core.logo.LogoOptions
import app.minimpos.core.logo.LogoResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReceiptLogoEditorTest {
    private val env = TestEnvironment()
    private val main = UnconfinedTestDispatcher()
    private val viewModels = mutableListOf<ViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() {
        closeViewModels(viewModels)
        env.close()
        Dispatchers.resetMain()
    }

    private fun editor(
        pixels: IntArray = intArrayOf(-1, -16_777_216, -16_777_216, -1),
        cpu: CoroutineDispatcher = main,
    ) = ReceiptLogoEditorViewModel(ArgbImage(2, 2, pixels), env.container.receiptLogo, cpu).also(viewModels::add)

    @Test
    fun `initial state shows auto result with resolved controls`() {
        val state = editor().state.value
        val result = requireNotNull(state.result) as LogoResult.Converted
        assertThat(state.options.mode).isEqualTo(LogoMode.AUTO)
        assertThat(state.options.threshold).isEqualTo(result.threshold)
        assertThat(state.options.invert).isEqualTo(result.inverted)
        assertThat(state.canSave).isTrue()
    }

    @Test
    fun `conversion waits for the injected CPU dispatcher`() {
        val cpu = StandardTestDispatcher(main.scheduler)
        val editor = editor(cpu = cpu)
        assertThat(editor.state.value.result).isNull()
        main.scheduler.runCurrent()
        assertThat(editor.state.value.canSave).isTrue()
    }

    @Test
    fun `changing threshold clears preview until debounce finishes`() {
        val editor = editor()
        editor.update(LogoOptions(mode = LogoMode.MARK, threshold = 42, invert = false))
        assertThat(editor.state.value.canSave).isFalse()
        main.scheduler.advanceTimeBy(150)
        main.scheduler.runCurrent()
        assertThat((requireNotNull(editor.state.value.result) as LogoResult.Converted).threshold).isEqualTo(42)
    }

    @Test
    fun `obsolete queued conversion cannot replace the latest preview`() {
        val cpu = StandardTestDispatcher(main.scheduler)
        val editor = editor(cpu = cpu)
        editor.update(LogoOptions(mode = LogoMode.MARK, threshold = 20, invert = false))
        main.scheduler.advanceTimeBy(150)
        editor.update(LogoOptions(mode = LogoMode.MARK, threshold = 200, invert = false))
        main.scheduler.advanceTimeBy(150)
        main.scheduler.runCurrent()
        val result = requireNotNull(editor.state.value.result) as LogoResult.Converted
        assertThat(result.threshold).isEqualTo(200)
        await { editor.save() }
        assertThat(await { env.container.receiptLogo.current() }).isEqualTo(result.bitmap)
    }

    @Test
    fun `blank result disables save`() {
        val editor = editor(IntArray(4) { -1 })
        assertThat(editor.state.value.result).isEqualTo(LogoResult.Blank)
        assertThat(editor.state.value.canSave).isFalse()
        await { editor.save() }
        assertThat(await { env.container.receiptLogo.current() }).isNull()
    }

    @Test
    fun `save stores the bitmap shown and removal clears it`() {
        val editor = editor()
        val shown = (requireNotNull(editor.state.value.result) as LogoResult.Converted).bitmap
        await { editor.save() }
        assertThat(await { env.container.receiptLogo.current() }).isEqualTo(shown)
        await { env.container.receiptLogo.replace(null) }
        assertThat(await { env.container.receiptLogo.current() }).isNull()
    }

    @Test
    fun `unreadable file decodes to null`() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        assertThat(decodeLogo(resolver, "content://missing/logo".toUri())).isNull()
    }
}
