package io.github.astiskala.minimpos.tooling

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class SigningPropertiesTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `Java properties parsing retains escapes continuations and private permissions`() {
        val root = temporary.root.toPath()
        Files.writeString(
            root.resolve("signing.properties"),
            "# comment\nstorePassword = leading\\ space\\u0021 \nkeyAlias:alias\nkeyPassword=second\\\n  half\n",
        )
        val result = Cli.run("signing", root.toString())
        assertThat(result.code).isEqualTo(0)
        assertThat(result.output).isEmpty()
        assertThat(Files.readString(root.resolve("storePassword"))).isEqualTo("leading space! \n")
        assertThat(Files.readString(root.resolve("keyPassword"))).isEqualTo("secondhalf\n")
        assertThat(Files.readString(root.resolve("keyAlias"))).isEqualTo("alias\n")
        for (key in listOf("storePassword", "keyPassword", "keyAlias")) {
            assertThat(Files.getPosixFilePermissions(root.resolve(key))).isEqualTo(PosixFilePermissions.fromString("rw-------"))
        }
    }

    @Test
    fun `missing or multiline signing properties fail without printing values or partial extraction`() {
        for (value in listOf("", "keyPassword=first\\nsecond\n", "keyPassword=first\\rsecond\n")) {
            val root = temporary.newFolder().toPath()
            Files.writeString(root.resolve("signing.properties"), "storePassword=fixture-not-for-output\nkeyAlias=alias\n$value")
            val result = Cli.run("signing", root.toString())
            assertThat(result.code).isNotEqualTo(0)
            assertThat(result.output).doesNotContain("fixture-not-for-output")
            assertThat(result.output).doesNotContain("first")
            assertThat(Files.exists(root.resolve("storePassword"))).isFalse()
        }
    }

    @Test
    fun `existing output is not overwritten or followed as a symlink`() {
        val root = temporary.root.toPath()
        Files.writeString(root.resolve("signing.properties"), "storePassword=secret\nkeyAlias=alias\nkeyPassword=key\n")
        val target = Files.writeString(root.resolve("existing"), "retained")
        Files.createSymbolicLink(root.resolve("storePassword"), target)
        val result = Cli.run("signing", root.toString())
        assertThat(result.code).isNotEqualTo(0)
        assertThat(Files.readString(target)).isEqualTo("retained")
        assertThat(result.output).doesNotContain("secret")
    }
}
