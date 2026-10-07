package io.github.astiskala.minimpos.terminal.transport

import com.adyen.model.checkout.JSON
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.BeanDescription
import com.fasterxml.jackson.databind.DeserializationConfig
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.module.SimpleDeserializers
import com.fasterxml.jackson.databind.module.SimpleModule
import java.time.OffsetDateTime

private val apiMapper =
    JSON.getMapper().copy().apply {
        disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
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
