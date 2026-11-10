package app.minimpos.app.data

import app.minimpos.app.data.db.FailureJson
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** A damaged or unknown stored failure reads as no failure this version knows: never a crash or an invented fault. */
class FailureJsonTest {
    @Test
    fun `damaged or unknown stored failures decode to nothing`() {
        listOf(
            "not json",
            "[]",
            """{"value":1}""",
            """{"kind":"other","value":"X"}""",
            """{"kind":"setup","value":"FUTURE"}""",
            """{"kind":"device","value":"FUTURE"}""",
            """{"kind":"email","value":"FUTURE"}""",
            """{"kind":"fault","value":"FUTURE"}""",
            """{"kind":"fault","value":"TERMINAL_HTTP"}""",
            """{"kind":"fault","value":"TERMINAL_HTTP","http":"500"}""",
            """{"kind":"fault","value":"MALFORMED","part":"NOPE"}""",
            """{"kind":"fault","value":"NO_ANSWER_FROM_TERMINAL"}""",
            """{"kind":"fault","value":"PERMISSION","key":"NOPE"}""",
            """{"kind":"fault","value":"CREDENTIAL"}""",
            """{"kind":"fault","value":"UNREACHABLE"}""",
            """{"kind":"fault","value":"UNKNOWN_HOST","host":"x"}""",
            """{"kind":"fault","value":"UNTRUSTED"}""",
            """{"kind":"fault","value":"TERMINAL_OFFLINE"}""",
        ).forEach { stored -> assertWithMessage(stored).that(FailureJson.decode(stored)).isNull() }
    }
}
