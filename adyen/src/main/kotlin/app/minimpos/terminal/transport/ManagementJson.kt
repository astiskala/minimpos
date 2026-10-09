package app.minimpos.terminal.transport

import com.adyen.model.checkout.JSON
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
