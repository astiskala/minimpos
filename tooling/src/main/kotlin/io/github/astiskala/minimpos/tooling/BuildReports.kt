package io.github.astiskala.minimpos.tooling

import org.w3c.dom.Element
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal object BuildReports {
    private const val SLOWEST_SUITE_COUNT = 10
    private val modules = listOf("core", "adyen", "app", "website-test", "tooling")

    fun render(root: Path): String {
        require(Files.isDirectory(root)) { "Expected a repository directory" }
        val suites = modules.flatMap { readModule(root, it) }.sortedByDescending { it.seconds }
        return buildString {
            appendLine("Historical snapshots; timestamps below are not proof of the current checkout or one complete run.")
            if (suites.isEmpty()) {
                appendLine("No JUnit reports. Run matching tests first.")
            } else {
                appendLine("Slowest suites (suite time, not parallel build wall time):")
                suites.take(SLOWEST_SUITE_COUNT).forEach { suite ->
                    val seconds = String.format(Locale.ROOT, "%.3f", suite.seconds)
                    appendLine("${seconds}s ${suite.name} tests=${suite.tests} skipped=${suite.skipped} timestamp=${suite.timestamp}")
                }
                val failures = suites.flatMap { it.failures }
                appendLine("Recorded failures: ${failures.size}")
                failures.forEach { failure ->
                    appendLine("\n${failure.className} > ${failure.testName}")
                    appendLine(failure.assertion)
                    failure.frame?.let { appendLine(it) }
                    appendLine("Report: ${root.relativize(failure.report)}")
                    val pattern = quote("${failure.className}.${failure.testName}")
                    val task = quote(":${failure.module}:${failure.task}")
                    appendLine("Rerun: scripts/gradle $task --tests $pattern")
                }
                appendLine("UI artifacts (when captured): app/build/reports/ui-failures/")
            }
        }
    }

    private fun readModule(
        root: Path,
        module: String,
    ): List<Suite> {
        val directory = root.resolve("$module/build/test-results")
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.walk(directory).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().startsWith("TEST-") && it.toString().endsWith(".xml") }
                .sorted()
                .map { readSuite(root, module, it) }
                .toList()
        }
    }

    private fun readSuite(
        root: Path,
        module: String,
        report: Path,
    ): Suite {
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
        val builder =
            factory.newDocumentBuilder().apply {
                setErrorHandler(
                    object : DefaultHandler() {
                        override fun error(exception: SAXParseException): Nothing = throw exception

                        override fun fatalError(exception: SAXParseException): Nothing = throw exception
                    },
                )
            }
        val suite =
            try {
                Files.newInputStream(report).use { builder.parse(it).documentElement }
            } catch (exception: SAXException) {
                throw IllegalArgumentException("Cannot parse test report: ${root.relativize(report)}", exception)
            }
        require(suite.tagName == "testsuite") { "Expected testsuite: ${root.relativize(report)}" }
        val tests = suite.getElementsByTagName("testcase")
        val failures =
            (0 until tests.length).flatMap { index ->
                val test = tests.item(index) as Element
                listOf("failure", "error").flatMap { kind ->
                    val nodes = test.getElementsByTagName(kind)
                    (0 until nodes.length).map { failureIndex ->
                        val failure = nodes.item(failureIndex) as Element
                        val lines = failure.textContent.trim().lines()
                        val assertion =
                            lines
                                .takeWhile { !it.trimStart().startsWith("at ") }
                                .joinToString("\n")
                                .ifBlank { failure.getAttribute("message") }
                        Failure(
                            module,
                            report.parent.fileName.toString(),
                            report,
                            test.getAttribute("classname").ifBlank { suite.getAttribute("name") },
                            test.getAttribute("name"),
                            assertion,
                            lines.firstOrNull { it.trimStart().startsWith("at io.github.astiskala.minimpos.") }?.trim(),
                        )
                    }
                }
            }
        return Suite(
            suite.getAttribute("name"),
            suite.getAttribute("time").toDoubleOrNull() ?: 0.0,
            suite.getAttribute("tests").ifBlank { "unknown" },
            suite.getAttribute("skipped").ifBlank { "0" },
            suite.getAttribute("timestamp").ifBlank { "unknown" },
            failures,
        )
    }

    private fun quote(value: String) = "'${value.replace("'", "'\\''")}'"

    private data class Suite(
        val name: String,
        val seconds: Double,
        val tests: String,
        val skipped: String,
        val timestamp: String,
        val failures: List<Failure>,
    )

    private data class Failure(
        val module: String,
        val task: String,
        val report: Path,
        val className: String,
        val testName: String,
        val assertion: String,
        val frame: String?,
    )
}
