package app.minimpos.app.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Helpers for the stored JSON formats (settings, secrets and JSON columns): a value of a stored type with every field
 * set away from its default, the stored fields a newer version loses or changes, and a reviewable text form of a type's
 * format. Written for kotlinx.serialization descriptors only, so the same code can write fixtures with a released
 * version's classes.
 */
@OptIn(ExperimentalSerializationApi::class)
object StoredFixtures {
    /**
     * [default] with every field changed away from its default as JSON allows ([label] marks the text fields), decoded
     * again with [serializer], so the result is a value the stored type accepts.
     */
    fun <T> filled(
        json: Json,
        serializer: KSerializer<T>,
        default: T,
        label: String,
    ): T = json.decodeFromJsonElement(serializer, fill(serializer.descriptor, json.encodeToJsonElement(serializer, default), label, "$"))

    private fun fill(
        descriptor: SerialDescriptor,
        current: JsonElement,
        label: String,
        path: String,
    ): JsonElement {
        val present = current.takeUnless { it is JsonNull }
        if (descriptor.isInline) return fill(descriptor.getElementDescriptor(0), current, label, path)
        return when (val kind = descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                val fields = (present as? JsonObject).orEmpty()
                JsonObject(
                    (0..<descriptor.elementsCount).associate { index ->
                        val name = descriptor.getElementName(index)
                        name to fill(descriptor.getElementDescriptor(index), fields[name] ?: JsonNull, label, "$path.$name")
                    },
                )
            }

            StructureKind.LIST -> {
                val items = (present as? JsonArray).orEmpty()
                val item = descriptor.getElementDescriptor(0)
                JsonArray(items.ifEmpty { listOf(JsonNull) }.mapIndexed { index, value -> fill(item, value, label, "$path[$index]") })
            }

            StructureKind.MAP -> {
                val key = (fill(descriptor.getElementDescriptor(0), JsonNull, label, path) as JsonPrimitive).content
                val entries = (present as? JsonObject).orEmpty().ifEmpty { mapOf(key to JsonNull) }
                JsonObject(entries.mapValues { (key, value) -> fill(descriptor.getElementDescriptor(1), value, label, "$path.$key") })
            }

            SerialKind.ENUM -> {
                val names = (0..<descriptor.elementsCount).map(descriptor::getElementName)
                val at = names.indexOf((present as? JsonPrimitive)?.content)
                JsonPrimitive(names[(at + 1) % names.size])
            }

            PrimitiveKind.STRING -> {
                JsonPrimitive("${path.substringAfterLast('.')} $label")
            }

            PrimitiveKind.CHAR -> {
                JsonPrimitive("x")
            }

            PrimitiveKind.BOOLEAN -> {
                JsonPrimitive((present as? JsonPrimitive)?.booleanOrNull != true)
            }

            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> {
                JsonPrimitive(((present as? JsonPrimitive)?.longOrNull ?: 0) + 1)
            }

            else -> {
                error("$path: stored formats do not use $kind")
            }
        }
    }

    /**
     * Where [current] (the current version's encoding of what it read) lost or changed a field of [stored] (a file an
     * earlier version wrote), one line per field as `<path>: <stored> → <current>`; empty when everything was kept.
     */
    fun lost(
        stored: JsonElement,
        current: JsonElement,
        path: String = "$",
    ): List<String> =
        when {
            stored is JsonObject && current is JsonObject -> {
                stored.flatMap { (key, value) ->
                    current[key]?.let { lost(value, it, "$path.$key") } ?: listOf("$path.$key: $value → (no longer stored)")
                }
            }

            stored is JsonArray && current is JsonArray && stored.size == current.size -> {
                stored.indices.flatMap { lost(stored[it], current[it], "$path[$it]") }
            }

            stored == current -> {
                emptyList()
            }

            else -> {
                listOf("$path: $stored → $current")
            }
        }

    /**
     * [descriptor]'s stored format as indented lines: each field with its kind, `?` when it may be null and `required`
     * when a file without it cannot be read. Enum constants and the subtypes of sealed types are listed, as they are
     * stored by name.
     */
    fun format(descriptor: SerialDescriptor): String = buildString { describe(descriptor, "", mutableSetOf()) }.trimEnd() + "\n"

    private fun StringBuilder.describe(
        descriptor: SerialDescriptor,
        indent: String,
        seen: MutableSet<String>,
    ) {
        when (val kind = descriptor.kind) {
            SerialKind.ENUM -> {
                val names = (0..<descriptor.elementsCount).joinToString { descriptor.getElementName(it) }
                appendLine(" enum ${descriptor.serialName.removeSuffix("?")} [$names]")
            }

            StructureKind.LIST -> {
                appendLine(" list of")
                append("$indent  -")
                describe(descriptor.getElementDescriptor(0), "$indent  ", seen)
            }

            StructureKind.MAP -> {
                appendLine(" map")
                append("$indent  key:")
                describe(descriptor.getElementDescriptor(0), "$indent  ", seen)
                append("$indent  value:")
                describe(descriptor.getElementDescriptor(1), "$indent  ", seen)
            }

            StructureKind.CLASS, StructureKind.OBJECT, PolymorphicKind.SEALED -> {
                describeFields(descriptor, indent, seen)
            }

            is PrimitiveKind, SerialKind.CONTEXTUAL, PolymorphicKind.OPEN -> {
                appendLine(" ${kind.toString().lowercase()}")
            }
        }
    }

    /** A class's fields, or a sealed type's subtypes, each with whether it may be null and whether it is required. */
    private fun StringBuilder.describeFields(
        descriptor: SerialDescriptor,
        indent: String,
        seen: MutableSet<String>,
    ) {
        val name = descriptor.serialName.removeSuffix("?")
        if (!seen.add(name)) {
            appendLine(" $name (as above)")
            return
        }
        val sealed = descriptor.kind == PolymorphicKind.SEALED
        appendLine(if (sealed) " sealed $name" else " $name")
        val owner = if (sealed) descriptor.getElementDescriptor(1) else descriptor
        (0..<owner.elementsCount).forEach { index ->
            val element = owner.getElementDescriptor(index)
            val nullable = if (element.isNullable) "?" else ""
            val required = if (!sealed && !owner.isElementOptional(index)) " required" else ""
            append("$indent  ${owner.getElementName(index)}$nullable$required:")
            describe(element, "$indent  ", seen)
        }
    }
}
