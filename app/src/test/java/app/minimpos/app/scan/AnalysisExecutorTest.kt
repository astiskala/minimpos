package app.minimpos.app.scan

import android.os.Process
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnalysisExecutorTest {
    @Test
    fun `camera frames are decoded below the UI thread's priority`() {
        val executor = analysisExecutor()
        try {
            val priority = executor.submit<Int> { Process.getThreadPriority(Process.myTid()) }.get()
            assertThat(priority).isEqualTo(Process.THREAD_PRIORITY_BACKGROUND)
        } finally {
            executor.shutdown()
        }
    }
}
