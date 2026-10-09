package app.minimpos.tooling

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

/** Parses Java properties without shell evaluation or secret output; creates private apksigner input files. */
internal object SigningProperties {
    fun extract(root: Path) {
        val values = Properties()
        Files.newInputStream(root.resolve("signing.properties")).use(values::load)
        val keys = listOf("storePassword", "keyAlias", "keyPassword")
        val inputs =
            keys.associateWith { key ->
                val value = values.getProperty(key)
                require(!value.isNullOrEmpty() && '\n' !in value && '\r' !in value) { "Missing or multiline signing property: $key" }
                value
            }
        inputs.forEach { (key, value) ->
            val output = root.resolve(key)
            Files.createFile(output, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            Files.writeString(output, "$value\n")
        }
    }
}
