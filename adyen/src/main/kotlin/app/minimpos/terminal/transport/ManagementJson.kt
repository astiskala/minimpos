package app.minimpos.terminal.transport

import com.adyen.model.checkout.JSON
import com.adyen.model.management.DefaultErrorResponseEntity
import com.adyen.model.management.MeApiCredential
import com.adyen.model.management.TerminalSettings
import com.fasterxml.jackson.annotation.JsonIncludeProperties
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.BeanDescription
import com.fasterxml.jackson.databind.DeserializationConfig
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.cfg.CoercionAction
import com.fasterxml.jackson.databind.cfg.CoercionInputShape
import com.fasterxml.jackson.databind.module.SimpleDeserializers
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.type.LogicalType
import java.time.OffsetDateTime

private const val HTTP_UNPROCESSABLE_ENTITY = 422

private val apiMapper =
    JSON.getMapper().copy().apply {
        disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        coercionConfigFor(LogicalType.Boolean)
            .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
        registerModule(
            SimpleModule().apply {
                setDeserializers(
                    object : SimpleDeserializers() {
                        override fun findEnumDeserializer(
                            type: Class<*>,
                            config: DeserializationConfig,
                            beanDescription: BeanDescription,
                        ): JsonDeserializer<*> =
                            object : JsonDeserializer<Enum<*>>() {
                                override fun deserialize(
                                    parser: JsonParser,
                                    context: DeserializationContext,
                                ): Enum<*>? {
                                    val value = parser.valueAsString
                                    parser.skipChildren()
                                    return type.enumConstants
                                        .filterIsInstance<Enum<*>>()
                                        .firstOrNull { it.toString().equals(value, ignoreCase = true) }
                                }
                            }
                    },
                )
                addDeserializer(
                    String::class.java,
                    object : JsonDeserializer<String>() {
                        override fun deserialize(
                            parser: JsonParser,
                            context: DeserializationContext,
                        ): String? =
                            if (parser.currentToken == JsonToken.VALUE_STRING) {
                                parser.text
                            } else {
                                parser.skipChildren()
                                null
                            }
                    },
                )
                addDeserializer(
                    OffsetDateTime::class.java,
                    object : JsonDeserializer<OffsetDateTime>() {
                        override fun deserialize(
                            parser: JsonParser,
                            context: DeserializationContext,
                        ): OffsetDateTime? = runCatching { OffsetDateTime.parse(parser.valueAsString) }.getOrNull()
                    },
                )
            },
        )
    }

/** Reads official API models without coercing strings or fractional integers; unknown enums and invalid optional dates are null. */
internal fun <T> decodeAdyenModel(
    text: String,
    type: Class<T>,
): T? = runCatching { apiMapper.readValue(text, type) }.getOrNull()

private val credentialMapper =
    apiMapper.copy().registerModule(
        SimpleModule().addDeserializer(
            String::class.java,
            object : JsonDeserializer<String>() {
                override fun deserialize(
                    parser: JsonParser,
                    context: DeserializationContext,
                ): String {
                    if (parser.currentToken != JsonToken.VALUE_STRING) {
                        throw JsonMappingException.from(parser, "Expected a credential string")
                    }
                    return parser.text
                }
            },
        ),
    )

/** Reads Management v1's documented credential schema without coercing invalid diagnostic fields into missing keys. */
internal fun decodeMeCredential(text: String): MeApiCredential? =
    runCatching { credentialMapper.readValue(text, MeApiCredential::class.java) }.getOrNull()

@JsonIncludeProperties("nexo")
private interface NexoSettings

private val settingsMapper =
    apiMapper.copy().apply {
        addMixIn(TerminalSettings::class.java, NexoSettings::class.java)
        enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        coercionConfigFor(LogicalType.Integer).setCoercion(CoercionInputShape.String, CoercionAction.Fail)
        coercionConfigFor(LogicalType.Boolean)
            .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
        registerModule(
            SimpleModule().addDeserializer(
                String::class.java,
                object : JsonDeserializer<String>() {
                    override fun deserialize(
                        parser: JsonParser,
                        context: DeserializationContext,
                    ): String {
                        if (parser.currentToken != JsonToken.VALUE_STRING) {
                            throw JsonMappingException.from(parser, "Expected a settings string")
                        }
                        return parser.text
                    }
                },
            ),
        )
    }

/** Reads official nexo settings strictly; unrelated sections are never sent in the shared-key PATCH. */
internal fun decodeTerminalSettings(text: String): TerminalSettings? =
    runCatching { settingsMapper.readValue(text, TerminalSettings::class.java) }.getOrNull()

/** Reads official terminal capability settings without failing on unrelated sections. */
internal fun decodeCapabilitySettings(text: String): TerminalSettings? = decodeAdyenModel(text, TerminalSettings::class.java)

internal fun adyenField(
    text: String,
    name: String,
): String? =
    runCatching {
        apiMapper
            .readTree(text)
            ?.get(name)
            ?.takeIf { it.isTextual }
            ?.textValue()
    }.getOrNull()

internal const val MANAGEMENT_PAGE_SIZE = 100
internal const val MAX_MANAGEMENT_PAGES = 100

/** Management API base URL for [environment] and API [version], with no trailing slash. */
internal fun managementEndpoint(
    environment: TerminalEnvironment,
    version: Int = 3,
): String =
    when (environment) {
        TerminalEnvironment.TEST -> "https://management-test.adyen.com/v$version"
        TerminalEnvironment.LIVE -> "https://management-live.adyen.com/v$version"
    }

/** Why [reply] gave no usable Management answer; a 2xx reply that failed parsing is [Fault.UnreadableReply], and a 403 lacks [role]. */
internal fun managementFault(
    reply: AdyenReply,
    key: ApiKey = ApiKey.ADYEN,
    role: String? = null,
): Fault =
    when (reply) {
        is AdyenReply.Failed -> {
            reply.fault
        }

        is AdyenReply.Answered if reply.ok -> {
            Fault.UnreadableReply()
        }

        is AdyenReply.Answered -> {
            val error = managementError(reply.body)
            // Management also reports absent entities as HTTP 422, not just HTTP 404.
            if (reply.code == HTTP_UNPROCESSABLE_ENTITY && error.code == "30_112") {
                Fault.NotFound(null)
            } else {
                reply.fault(key, role, error)
            }
        }
    }

private fun managementError(body: String): AdyenError {
    val error = decodeAdyenModel(body, DefaultErrorResponseEntity::class.java)
    return AdyenError(ExternalText.of(error?.detail) ?: ExternalText.of(error?.title), error?.errorCode)
}

/** Complete multi-page Management collection, or the fault that stopped it without partial results. */
internal sealed interface ManagementPages<out T> {
    data class Listed<T>(
        val items: List<T>,
    ) : ManagementPages<T>

    data class Failed(
        val fault: Fault,
    ) : ManagementPages<Nothing>
}

/** Collects up to [MAX_MANAGEMENT_PAGES] pages using 1-based [fetch] without following server links. */
internal suspend fun <T> collectManagementPages(
    role: String? = null,
    fetch: suspend (pageNumber: Int) -> AdyenReply,
    parse: (String) -> Pair<List<T>, Boolean>?,
): ManagementPages<T> {
    val items = mutableListOf<T>()
    var number = 1
    var next = true
    var failure: Fault? = null
    while (next && number <= MAX_MANAGEMENT_PAGES && failure == null) {
        val reply = fetch(number++)
        val parsed = (reply as? AdyenReply.Answered)?.takeIf { it.ok }?.let { parse(it.body) }
        if (parsed == null) {
            failure = managementFault(reply, role = role)
        } else {
            items += parsed.first
            next = parsed.second
        }
    }
    return when {
        failure != null -> ManagementPages.Failed(failure)
        next -> ManagementPages.Failed(Fault.ListTooLarge)
        else -> ManagementPages.Listed(items)
    }
}
