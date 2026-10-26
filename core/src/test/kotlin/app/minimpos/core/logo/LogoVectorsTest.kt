package app.minimpos.core.logo

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import kotlin.io.encoding.Base64

class LogoVectorsTest {
    @Test
    fun `shared conversion vectors match every output bit and resolved control`() {
        val text = checkNotNull(javaClass.getResource("/logo/vectors.json")).readText()
        val vectors = Json.parseToJsonElement(text).jsonArray
        assertThat(generatedInputs()).isEqualTo(vectors.map { JsonObject(it.jsonObject - "expected") })
        if (System.getProperty("logo.vectors.print").toBoolean()) printVectors(vectors.map { it.jsonObject })
        vectors.forEach { element ->
            val vector = element.jsonObject
            val input = ByteBuffer.wrap(Base64.decode(vector.getValue("argb").jsonPrimitive.content))
            val pixels = IntArray(input.remaining() / 4) { input.int }
            val options = vector.getValue("options").jsonObject
            val result =
                LogoConverter.convert(
                    ArgbImage(vector.getValue("width").jsonPrimitive.int, vector.getValue("height").jsonPrimitive.int, pixels),
                    LogoOptions(
                        mode = LogoMode.valueOf(options.getValue("mode").jsonPrimitive.content),
                        threshold = options["threshold"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.int,
                        invert = options["invert"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.boolean,
                        trim = options.getValue("trim").jsonPrimitive.boolean,
                        size = LogoSize.valueOf(options.getValue("size").jsonPrimitive.content),
                    ),
                ) as LogoResult.Converted
            val expected = vector.getValue("expected").jsonObject
            assertThat(result.bitmap.width).isEqualTo(expected.getValue("width").jsonPrimitive.int)
            assertThat(result.bitmap.height).isEqualTo(expected.getValue("height").jsonPrimitive.int)
            assertThat(result.mode.name).isEqualTo(expected.getValue("mode").jsonPrimitive.content)
            assertThat(result.threshold).isEqualTo(expected.getValue("threshold").jsonPrimitive.int)
            assertThat(result.inverted).isEqualTo(expected.getValue("inverted").jsonPrimitive.boolean)
            assertThat(
                result.bitmap.bits().toHexString(),
            ).isEqualTo(expected.getValue("bits").jsonPrimitive.content)
        }
    }

    private companion object {
        val prettyJson = Json { prettyPrint = true }
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = -1

        fun generatedInputs(): List<JsonObject> {
            var noise = 0x12345678L
            return listOf(
                input("antialiased disc mark", 19, 19) { x, y ->
                    val radius = (x - 9) * (x - 9) + (y - 9) * (y - 9)
                    grey(
                        if (radius < 36) {
                            0
                        } else if (radius < 49) {
                            128
                        } else {
                            255
                        },
                    )
                },
                input("white-on-transparent wordmark", 24, 12) { x, y ->
                    if (x in 3..<21 && y in 3..<9 && x % 4 != 0) WHITE else 0
                },
                input("black-background badge", 15, 15) { x, y ->
                    if (x in 4..<11 && y in 4..<11) WHITE else BLACK
                },
                input("grey ramp photo", 256, 4, LogoOptions(trim = false)) { x, _ -> grey(x) },
                input("colour photo-like noise", 32, 24) { _, _ ->
                    noise = (1_664_525L * noise + 1_013_904_223L) and 0xFFFFFFFFL
                    BLACK or (noise.toInt() and 0xFFFFFF)
                },
                input("tall strip hitting SMALL", 7, 400, LogoOptions(size = LogoSize.SMALL, invert = false)) { x, y ->
                    if (x == 3 || y % 7 < 3) BLACK else WHITE
                },
                input("trim off", 15, 9, LogoOptions(trim = false)) { x, y ->
                    if (x in 4..<10 && y in 2..<7) BLACK else WHITE
                },
                input("forced PHOTO on a mark", 18, 10, LogoOptions(LogoMode.PHOTO, 100, false)) { x, y ->
                    if (x in 2..<16 && y in 2..<8) BLACK else WHITE
                },
            )
        }

        fun grey(value: Int): Int = BLACK or (value shl 16) or (value shl 8) or value

        fun input(
            name: String,
            width: Int,
            height: Int,
            options: LogoOptions = LogoOptions(),
            pixel: (Int, Int) -> Int,
        ): JsonObject =
            buildJsonObject {
                put("name", name)
                put("width", width)
                put("height", height)
                val bytes = ByteBuffer.allocate(width * height * 4)
                repeat(height) { y -> repeat(width) { x -> bytes.putInt(pixel(x, y)) } }
                put("argb", Base64.encode(bytes.array()))
                put(
                    "options",
                    buildJsonObject {
                        put("mode", options.mode.name)
                        put("threshold", options.threshold?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("invert", options.invert?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("trim", options.trim)
                        put("size", options.size.name)
                    },
                )
            }

        fun printVectors(vectors: List<JsonObject>) {
            val directory = File("build/generated/logo-vectors").apply { mkdirs() }
            val generated = buildJsonArray { vectors.forEach { add(generatedVector(it)) } }
            File(directory, "vectors.json").writeText(prettyJson.encodeToString(generated) + "\n")
            File(directory, "preview.txt").writeText(vectors.take(2).joinToString("\n", transform = ::ascii))
        }

        fun generatedVector(vector: JsonObject): JsonObject {
            val source = ByteBuffer.wrap(Base64.decode(vector.getValue("argb").jsonPrimitive.content))
            val options = vector.getValue("options").jsonObject
            val converted =
                LogoConverter.convert(
                    ArgbImage(
                        vector.getValue("width").jsonPrimitive.int,
                        vector.getValue("height").jsonPrimitive.int,
                        IntArray(source.remaining() / 4) { source.int },
                    ),
                    LogoOptions(
                        LogoMode.valueOf(options.getValue("mode").jsonPrimitive.content),
                        options["threshold"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.int,
                        options["invert"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.boolean,
                        options.getValue("trim").jsonPrimitive.boolean,
                        LogoSize.valueOf(options.getValue("size").jsonPrimitive.content),
                    ),
                ) as LogoResult.Converted
            return buildJsonObject {
                vector.forEach { (key, value) -> if (key != "expected") put(key, value) }
                put(
                    "expected",
                    buildJsonObject {
                        put("width", converted.bitmap.width)
                        put("height", converted.bitmap.height)
                        put("mode", converted.mode.name)
                        put("threshold", converted.threshold)
                        put("inverted", converted.inverted)
                        put("bits", converted.bitmap.bits().toHexString())
                    },
                )
            }
        }

        fun ascii(vector: JsonObject): String {
            val expected = vector.getValue("expected").jsonObject
            val width = expected.getValue("width").jsonPrimitive.int
            val height = expected.getValue("height").jsonPrimitive.int
            val stride = (width + 7) / 8
            val bits =
                expected
                    .getValue("bits")
                    .jsonPrimitive.content
                    .hexToByteArray()
            return buildString {
                appendLine(vector.getValue("name").jsonPrimitive.content)
                repeat(height) { y ->
                    repeat(width) { x -> append(if (bits[y * stride + x / 8].toInt() and (128 ushr (x % 8)) != 0) '#' else '.') }
                    appendLine()
                }
            }
        }
    }
}
