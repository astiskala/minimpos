package io.github.astiskala.minimpos.terminal.transport

import com.google.gson.JsonObject

/** Reads a Management string field [name], empty when absent or not a string; [trim] is false for secret passphrases. */
internal fun JsonObject.text(
    name: String,
    trim: Boolean = true,
): String =
    get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
        ?.let { if (trim) it.trim() else it }
        .orEmpty()
