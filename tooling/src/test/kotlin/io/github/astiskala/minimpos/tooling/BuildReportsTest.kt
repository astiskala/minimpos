package io.github.astiskala.minimpos.tooling

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class BuildReportsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `report decodes assertions shows project frame timestamp timing and safely quoted rerun`() {
        val root = temporary.newFolder().toPath()
        report(
            root,
            "app",
            "testDebugUnitTest",
            """
            <testsuite name="io.github.astiskala.minimpos.app.ExampleTest" tests="2" skipped="1" failures="1" errors="0"
                timestamp="2026-10-06T13:01:00Z" time="12.5">
              <testcase classname="io.github.astiskala.minimpos.app.ExampleTest" name="shopper's amount" time="12.5">
                <failure message="expected: &lt;100&gt; but was: &lt;90&gt;">java.lang.AssertionError: expected: &lt;100&gt; but was: &lt;90&gt;
            at org.junit.Assert.fail(Assert.java:89)
            at io.github.astiskala.minimpos.app.ExampleTest.shopper(ExampleTest.kt:42)</failure>
              </testcase>
            </testsuite>
            """.trimIndent(),
        )
        val result = Cli.run("report", root.toString())
        assertThat(result.code).isEqualTo(0)
        assertThat(result.output).contains("expected: <100> but was: <90>")
        assertThat(result.output).contains("ExampleTest.shopper(ExampleTest.kt:42)")
        assertThat(result.output).contains("2026-10-06T13:01:00Z")
        assertThat(result.output).contains("12.500s")
        assertThat(result.output).contains("skipped=1")
        val rerun =
            "scripts/gradle ':app:testDebugUnitTest' --tests " +
                "'io.github.astiskala.minimpos.app.ExampleTest.shopper'\\''s amount'"
        assertThat(result.output).contains(rerun)
        assertThat(result.output).contains("app/build/test-results/testDebugUnitTest/TEST-Example.xml")
        assertThat(result.output).contains("Historical snapshots")
    }

    @Test
    fun `suite timings sort slowest first and empty checkout reports no results`() {
        val root = temporary.newFolder().toPath()
        assertThat(Cli.run("report", root.toString()).output).contains("No JUnit reports")
        report(root, "core", "test", "<testsuite name=\"Fast\" tests=\"1\" time=\"0.25\"/>")
        report(root, "adyen", "test", "<testsuite name=\"Slow\" tests=\"1\" time=\"3.5\"/>")
        val result = Cli.run("report", root.toString())
        assertThat(result.code).isEqualTo(0)
        assertThat(result.output.indexOf("Slow")).isLessThan(result.output.indexOf("Fast"))
    }

    @Test
    fun `external entities and malformed XML fail without disclosing file contents`() {
        val root = temporary.newFolder().toPath()
        val secret = Files.writeString(root.resolve("private.txt"), "DO_NOT_DISCLOSE")
        for (xml in listOf("<!DOCTYPE testsuite [<!ENTITY x SYSTEM '${secret.toUri()}'>]><testsuite name='&x;'/>", "<testsuite>")) {
            report(root, "core", "test", xml)
            val result = Cli.run("report", root.toString())
            assertThat(result.code).isEqualTo(1)
            assertThat(result.output).contains("Cannot parse test report")
            assertThat(result.output).doesNotContain("DO_NOT_DISCLOSE")
        }
    }

    @Test
    fun `unknown commands and surplus report arguments fail`() {
        assertThat(Cli.run("report").code).isEqualTo(1)
        assertThat(Cli.run("report", temporary.root.toString(), "extra").code).isEqualTo(1)
    }

    private fun report(
        root: Path,
        module: String,
        task: String,
        xml: String,
    ) {
        val directory = Files.createDirectories(root.resolve("$module/build/test-results/$task"))
        Files.writeString(directory.resolve("TEST-Example.xml"), xml)
    }
}
