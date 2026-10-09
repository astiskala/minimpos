package app.minimpos.app.data.db

import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.MalformedPart
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * A [Failure] as a compact JSON object inside a stored reason, read back leniently: a kind or value this version does
 * not know, or a missing required field, reads as null. Kinds and field names are stored: never rename one.
 */
internal object FailureJson {
    private const val KIND = "kind"
    private const val VALUE = "value"
    private const val SAID = "said"
    private const val HOST = "host"
    private const val TERMINAL = "terminal"
    private const val POI_ID = "poiId"
    private const val HTTP = "http"
    private const val ERROR_CODE = "errorCode"
    private const val KEY = "key"

    private val CODECS: List<Codec<out Fault>> =
        listOf(
            codec<Fault.Unreachable>("UNREACHABLE", { host(it.host, it.terminal) }) { it.host(Fault::Unreachable) },
            codec<Fault.UnknownHost>("UNKNOWN_HOST", { host(it.host, it.terminal) }) { it.host(Fault::UnknownHost) },
            codec<Fault.Untrusted>("UNTRUSTED", { put(HOST, it.host) }) { json -> json.string(HOST)?.let(Fault::Untrusted) },
            codec<Fault.KeyRejected>("KEY_REJECTED", { put(SAID, it.said?.text) }) { Fault.KeyRejected(it.text(SAID)) },
            codec<Fault.TerminalRejected>("TERMINAL_REJECTED", { put(SAID, it.said?.text) }) { Fault.TerminalRejected(it.text(SAID)) },
            codec<Fault.TerminalOffline>("TERMINAL_OFFLINE", {
                put(POI_ID, it.poiId)
                put(SAID, it.said?.text)
            }) { json -> json.string(POI_ID)?.let { Fault.TerminalOffline(it, json.text(SAID)) } },
            fixed("NOT_STARTED", Fault.NotStarted),
            codec<Fault.AppRefused>("APP_REFUSED", { put(SAID, it.said?.text) }) { Fault.AppRefused(it.text(SAID)) },
            fixed("UNSUPPORTED", Fault.Unsupported),
            fixed("NO_LATE_REPLY", Fault.NoLateReply),
            fixed("NO_RECORD", Fault.NoRecord),
            fixed("REQUEST_NOT_ENCRYPTED", Fault.RequestNotEncrypted),
            codec<Fault.Credential>("CREDENTIAL", { put(KEY, it.key.name) }) { json -> json.key()?.let(Fault::Credential) },
            codec<Fault.Permission>("PERMISSION", {
                put(KEY, it.key.name)
                it.role?.let { role -> put("role", role) }
            }) { json -> json.key()?.let { Fault.Permission(it, json.string("role")) } },
            codec<Fault.NotFound>("NOT_FOUND", { fault -> fault.poiId?.let { put(POI_ID, it) } }) { Fault.NotFound(it.string(POI_ID)) },
            codec<Fault.AdyenRejected>("ADYEN_REJECTED", { adyen(it.http, it.errorCode, it.said) }) { it.adyen(Fault::AdyenRejected) },
            fixed("LIST_TOO_LARGE", Fault.ListTooLarge),
            fixed("TIMED_OUT", Fault.TimedOut),
            fixed("CONNECTION_LOST", Fault.ConnectionLost),
            fixed("REPLY_UNVERIFIED", Fault.ReplyUnverified),
            codec<Fault.UnreadableReply>("UNREADABLE_REPLY", { put(SAID, it.said?.text) }) { Fault.UnreadableReply(it.text(SAID)) },
            codec<Fault.Malformed>("MALFORMED", { put("part", it.part.name) }) { json ->
                MalformedPart.entries.find { it.name == json.string("part") }?.let(Fault::Malformed)
            },
            codec<Fault.NoAnswerFromTerminal>("NO_ANSWER_FROM_TERMINAL", {
                put(POI_ID, it.poiId)
                put(SAID, it.said?.text)
            }) { json -> json.string(POI_ID)?.let { Fault.NoAnswerFromTerminal(it, json.text(SAID)) } },
            fixed("ABANDONED", Fault.Abandoned),
            codec<Fault.TerminalHttp>("TERMINAL_HTTP", { put(HTTP, it.code) }) { json -> json.int(HTTP)?.let(Fault::TerminalHttp) },
            codec<Fault.AdyenUnavailable>("ADYEN_UNAVAILABLE", { adyen(it.http, it.errorCode, it.said) }) {
                it.adyen(Fault::AdyenUnavailable)
            },
            fixed("STILL_IN_PROGRESS", Fault.StillInProgress),
        )

    /** [failure] as JSON. */
    fun encode(failure: Failure): String =
        buildJsonObject {
            when (failure) {
                is Failure.NotSetUp -> {
                    kind("setup", failure.problem.name)
                }

                is Failure.Device -> {
                    kind("device", failure.fault.name)
                }

                is Failure.Email -> {
                    kind("email", failure.fault.name)
                    put("reply", failure.reply?.text)
                }

                is Failure.Remote -> {
                    val codec = CODECS.first { it.type.isInstance(failure.fault) }
                    kind("fault", codec.kind)
                    codec.write(this, failure.fault)
                }
            }
        }.toString()

    /** The failure encoded as [json]; null when it is not one this version knows. */
    fun decode(json: String): Failure? =
        try {
            val fields = Json.parseToJsonElement(json).jsonObject
            fields.string(VALUE)?.let { value -> fields.failure(fields.string(KIND), value) }
        } catch (ignored: SerializationException) {
            null
        } catch (ignored: IllegalArgumentException) {
            null
        }

    private fun JsonObject.failure(
        kind: String?,
        value: String,
    ): Failure? =
        when (kind) {
            "setup" -> {
                SetupProblem.entries.find { it.name == value }?.let(Failure::NotSetUp)
            }

            "device" -> {
                DeviceFault.entries.find { it.name == value }?.let(Failure::Device)
            }

            "email" -> {
                EmailFault.entries.find { it.name == value }?.let { Failure.Email(it, text("reply")) }
            }

            "fault" -> {
                CODECS
                    .find { it.kind == value }
                    ?.read
                    ?.invoke(this)
                    ?.let(Failure::Remote)
            }

            else -> {
                null
            }
        }

    private fun JsonObjectBuilder.kind(
        kind: String,
        value: String,
    ) {
        put(KIND, kind)
        put(VALUE, value)
    }

    private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.text(name: String): ExternalText? = ExternalText.of(string(name))

    private fun JsonObject.int(name: String): Int? = (get(name) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun JsonObject.boolean(name: String): Boolean? = (get(name) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    private fun JsonObject.key(): ApiKey? = ApiKey.entries.find { it.name == string(KEY) }

    /** How one kind of [Fault] is stored: its [kind] name, the fields [write] adds and how [read] makes it again. */
    private class Codec<T : Fault>(
        val kind: String,
        val type: Class<T>,
        private val fields: JsonObjectBuilder.(T) -> Unit,
        val read: (JsonObject) -> T?,
    ) {
        fun write(
            builder: JsonObjectBuilder,
            fault: Fault,
        ) = builder.fields(checkNotNull(type.cast(fault)))
    }

    private inline fun <reified T : Fault> codec(
        kind: String,
        noinline write: JsonObjectBuilder.(T) -> Unit,
        noinline read: (JsonObject) -> T?,
    ) = Codec(kind, T::class.java, write, read)

    private fun <T : Fault> fixed(
        kind: String,
        fault: T,
    ) = Codec(kind, fault.javaClass, {}, { fault })

    private fun JsonObjectBuilder.host(
        host: String,
        terminal: Boolean,
    ) {
        put(HOST, host)
        put(TERMINAL, terminal)
    }

    private fun JsonObjectBuilder.adyen(
        http: Int,
        errorCode: String?,
        said: ExternalText?,
    ) {
        put(HTTP, http)
        errorCode?.let { put(ERROR_CODE, it) }
        put(SAID, said?.text)
    }

    private fun <T> JsonObject.host(make: (String, Boolean) -> T): T? =
        string(HOST)?.let { host -> boolean(TERMINAL)?.let { make(host, it) } }

    private fun <T> JsonObject.adyen(make: (Int, String?, ExternalText?) -> T): T? =
        int(HTTP)?.let { make(it, string(ERROR_CODE), text(SAID)) }
}
