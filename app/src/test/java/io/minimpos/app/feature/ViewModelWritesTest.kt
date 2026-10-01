package io.minimpos.app.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelWritesTest {
    private class Screen : ViewModel()

    private val store = ViewModelStore()
    private val screen = ViewModelProvider.create(store, viewModelFactory { initializer { Screen() } })[Screen::class]

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a write finishes and is followed up while the screen is open`() {
        val written = CompletableDeferred<Unit>()
        var result: String? = null
        screen.launchWrite({
            written.await()
            "saved"
        }) { result = it }
        written.complete(Unit)
        assertThat(result).isEqualTo("saved")
    }

    @Test
    fun `a write still finishes when the screen closes, but is not followed up`() {
        val written = CompletableDeferred<Unit>()
        var saved = false
        var followedUp = false
        screen.launchWrite({
            written.await()
            saved = true
        }) { followedUp = true }
        // Leaving the screen clears its view model, which cancels viewModelScope while the write is under way.
        store.clear()
        written.complete(Unit)
        assertThat(saved).isTrue()
        assertThat(followedUp).isFalse()
    }
}
