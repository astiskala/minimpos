package app.minimpos.terminal.transport

import com.adyen.terminal.serialization.TerminalAPIGsonBuilder
import com.google.gson.Gson
import kotlin.io.encoding.Base64

/**
 * The Adyen library's Terminal API (nexo) Gson, made once: it is immutable and safe to share between threads. Terminal
 * API messages and the JSON this module builds itself for them are written and read with it; only the Checkout and
 * Management models, which the library annotates for Jackson, are not (see `ManagementJson.kt`).
 */
internal val nexoGson: Gson = TerminalAPIGsonBuilder.create()

/**
 * [value] (maps, lists, strings, numbers and booleans) as UTF-8 JSON in Base64, the form nexo carries JSON in a text
 * field: an `AdminRequest` service identification, the tip operation and an `AdditionalResponse`.
 */
internal fun base64Json(value: Any): String = Base64.encode(nexoGson.toJson(value).toByteArray(Charsets.UTF_8))
