package app.minimpos.terminal

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/** Damaged copies of a JSON answer, for checking that no damage changes what an answer means. */
object JsonDamage {
    /** Bodies that are not the expected JSON object at all. */
    val unreadable: List<String> = listOf("", "not json", "{", "null", "[]", "\"text\"", "42")

    /**
     * Every damaged copy of [reply] with what was changed: each object field removed, set to null and given the wrong
     * JSON type, each text replaced by a value this version does not know, each list item removed.
     */
    fun variants(reply: JsonObject): List<Pair<String, JsonObject>> {
        val variants = mutableListOf<Pair<String, JsonObject>>()

        fun copy(
            path: List<Any>,
            change: (JsonElement) -> Unit,
        ): JsonObject =
            reply.deepCopy().also { root ->
                change(
                    path.fold<Any, JsonElement>(
                        root,
                    ) { node, step -> if (step is String) node.asJsonObject[step] else node.asJsonArray[step as Int] },
                )
            }

        fun visit(
            path: List<Any>,
            element: JsonElement,
        ) {
            val at = path.joinToString(".")
            when {
                element.isJsonObject -> {
                    element.asJsonObject.keySet().toList().forEach { key ->
                        val child = element.asJsonObject[key]
                        variants += "$at.$key removed" to copy(path) { it.asJsonObject.remove(key) }
                        variants += "$at.$key null" to copy(path) { it.asJsonObject.add(key, JsonNull.INSTANCE) }
                        val wrongType = if (child.isJsonObject || child.isJsonArray) JsonPrimitive("UNKNOWN_VALUE") else JsonObject()
                        variants += "$at.$key wrong type" to copy(path) { it.asJsonObject.add(key, wrongType) }
                        if (child.isJsonPrimitive && child.asJsonPrimitive.isString) {
                            variants += "$at.$key unknown" to copy(path) { it.asJsonObject.add(key, JsonPrimitive("UNKNOWN_VALUE")) }
                        }
                        visit(path + key, child)
                    }
                }

                element.isJsonArray -> {
                    element.asJsonArray.forEachIndexed { index, child ->
                        variants += "$at[$index] removed" to copy(path) { it.asJsonArray.remove(index) }
                        visit(path + index, child)
                    }
                }
            }
        }
        visit(emptyList(), reply)
        return variants
    }
}
