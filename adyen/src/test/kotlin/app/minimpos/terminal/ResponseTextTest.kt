package app.minimpos.terminal

import app.minimpos.terminal.transport.responseText
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ResponseTextTest {
    @Test
    fun `empty diagnostics stay absent and duplicate messages appear once`() {
        assertThat(responseText(emptyList(), null, null)).isNull()
        assertThat(responseText(listOf(null, "", " "), " ", " ")).isNull()
        assertThat(responseText(listOf(" Refused ", "Refused", null), null, null)?.text).isEqualTo("Refused")
        assertThat(responseText(emptyList(), " 42 ", null)?.text).isEqualTo("errorCode=42")
        assertThat(responseText(emptyList(), null, null, " request-1 ")?.text).isEqualTo("requestId=request-1")
        assertThat(responseText(emptyList(), null, null, " ")?.text).isNull()
    }

    @Test
    fun `sampled and unsampled W3C parents expose the trace ID not the span ID`() {
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        listOf("00", "01").forEach { flags ->
            val parent = "00-$traceId-00f067aa0ba902b7-$flags"
            assertThat(responseText(emptyList(), null, " $parent ")?.text)
                .isEqualTo("traceparent=$parent\ntraceId=$traceId")
        }
    }

    @Test
    fun `malformed or zero trace contexts remain visible without a purported support ID`() {
        listOf(
            "invalid",
            "00-4bf92f3577b34da6a3ce929d0e0e473-00f067aa0ba902b7-01",
            "00-4bf92f3577b34da6a3ce929d0e0e473g-00f067aa0ba902b7-01",
            "00-00000000000000000000000000000000-00f067aa0ba902b7-01",
            "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01",
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-extra",
        ).forEach { parent ->
            assertThat(responseText(emptyList(), null, parent)?.text).isEqualTo("traceparent=$parent")
        }
    }
}
