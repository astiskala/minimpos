import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress

// Checks the static website in docs/ (published as is by GitHub Pages, so the build files cannot live there): JUnit
// tests of its links, languages, metadata and screenshots, and the W3C Nu Html Checker on its pages and styles.
plugins {
    id("minimpos.kotlin-jvm")
}

val htmlChecker =
    configurations.create("htmlChecker") {
        isCanBeConsumed = false
        // The command-line checker does not use the web service's form uploads.
        exclude(group = "org.apache.commons", module = "commons-fileupload2-core")
        exclude(group = "org.apache.commons", module = "commons-fileupload2-jakarta-servlet6")
        // It logs with log4j 1.2.17, which is end of life with unfixed advisories: reload4j is its drop-in fork with
        // them fixed.
        resolutionStrategy.dependencySubstitution {
            substitute(module("log4j:log4j"))
                .using(module(libs.reload4j.get().toString()))
                .because("log4j 1.x is end of life with unfixed security advisories")
        }
    }

dependencies {
    htmlChecker(libs.nu.validator)
    // The validator's HTTP client uses SLF4J; keep its diagnostics through the JDK logger.
    htmlChecker(libs.slf4j.jdk14)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.jsoup)
    testImplementation(project(":core"))
    testImplementation(libs.kotlinx.serialization.json)
}

val website = isolated.rootProject.projectDirectory.dir("docs")

// Troubleshooting quotes the app's messages, so the guide tests read its string resources.
val appResources = isolated.rootProject.projectDirectory.dir("app/src/main/res")
val screenshotSamples = isolated.rootProject.projectDirectory.file("app/src/main/assets/sample-data.json")

tasks.test {
    inputs
        .dir(website)
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("website")
    systemProperty("minimpos.website", website.asFile.absolutePath)
    systemProperty(
        "minimpos.layoutReports",
        layout.buildDirectory
            .dir("reports/guide-layout")
            .get()
            .asFile.absolutePath,
    )
    inputs
        .files(fileTree(appResources) { include("values*/strings.xml") })
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("appStrings")
    systemProperty("minimpos.appResources", appResources.asFile.absolutePath)
    inputs
        .file(screenshotSamples)
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("screenshotSamples")
    inputs
        .file(isolated.rootProject.projectDirectory.file("core/src/test/resources/logo/vectors.json"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("logoVectors")
    // Browser paths affect helper integration tests even when the published website has not changed.
    inputs.property("chrome", providers.environmentVariable("MINIMPOS_CHROME").orElse(""))
}

/**
 * Runs the W3C Nu Html Checker on [pages] (HTML and CSS) and fails on anything it reports, informational notes
 * included, so the site stays free of them.
 */
abstract class HtmlCheck : DefaultTask() {
    @get:Classpath
    abstract val checker: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pages: ConfigurableFileCollection

    /** Source root for public routes; [pages] tracks the inputs independently of this checkout's absolute path. */
    @get:Internal
    abstract val siteRoot: DirectoryProperty

    @get:OutputFile
    abstract val stamp: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun check() {
        val output = ByteArrayOutputStream()
        val documents =
            pages.files.associateBy {
                "/" +
                    it
                        .relativeTo(siteRoot.get().asFile)
                        .invariantSeparatorsPath
                        .removeSuffix("index.html")
                        .removeSuffix(".html")
            }
        // CSP 'self' needs an HTTP origin: file URLs have opaque origins in the checker. Serve only the exact
        // validation inputs on loopback so every policy, HTML and CSS diagnostic remains enabled offline.
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.use {
                val file = documents[exchange.requestURI.path]
                if (file == null) {
                    exchange.sendResponseHeaders(404, -1)
                } else {
                    val content = file.readBytes()
                    val type = if (file.extension == "css") "text/css" else "text/html"
                    exchange.responseHeaders.set("Content-Type", "$type; charset=utf-8")
                    exchange.sendResponseHeaders(200, content.size.toLong())
                    exchange.responseBody.write(content)
                }
            }
        }
        server.start()
        val origin = "http://127.0.0.1:${server.address.port}"
        val result =
            try {
                execOperations.javaexec {
                    classpath(checker)
                    mainClass.set("nu.validator.client.SimpleCommandLineValidator")
                    // This process only checks the loopback URLs supplied below, never untrusted remote URLs.
                    systemProperty("nu.validator.servlet.allow-forbidden-hosts", "true")
                    systemProperty("nu.validator.xml.promiscuous-ssl", "false")
                    args(listOf("--also-check-css", "--format", "gnu", "--exit-zero-always") + documents.keys.sorted().map { origin + it })
                    standardOutput = output
                    errorOutput = output
                    isIgnoreExitValue = true
                }
            } finally {
                server.stop(0)
            }
        val messages =
            documents.entries.sortedByDescending { it.key.length }.fold(output.toString(Charsets.UTF_8).trim()) { messages, (route, file) ->
                messages.replace(origin + route, file.toURI().toString())
            }
        if (result.exitValue != 0 || messages.isNotEmpty()) {
            throw GradleException("The W3C Nu Html Checker (exit value ${result.exitValue}) reported:\n$messages")
        }
        stamp.get().asFile.writeText("OK\n")
    }
}

val htmlCheck =
    tasks.register<HtmlCheck>("htmlCheck") {
        group = "verification"
        description = "Checks the website's HTML and CSS with the W3C Nu Html Checker."
        checker.from(htmlChecker)
        pages.from(fileTree(website) { include("**/*.html", "**/*.css") })
        siteRoot.set(website)
        stamp.set(layout.buildDirectory.file("html-check/OK"))
    }

tasks.named("check") {
    dependsOn(htmlCheck)
}
