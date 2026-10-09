import java.io.ByteArrayOutputStream

// Checks the static website in docs/ (published as is by GitHub Pages, so the build files cannot live there): JUnit
// tests of its links, languages, metadata and screenshots, and the W3C Nu Html Checker on its pages and styles.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
        extraWarnings.set(true)
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
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

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.jsoup)
    testImplementation(project(":core"))
    testImplementation(libs.kotlinx.serialization.json)
}

val website = isolated.rootProject.projectDirectory.dir("docs")

// Troubleshooting quotes the app's messages, so the guide tests read its string resources.
val appResources = isolated.rootProject.projectDirectory.dir("app/src/main/res")

tasks.test {
    inputs
        .dir(website)
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("website")
    systemProperty("minimpos.website", website.asFile.absolutePath)
    inputs
        .files(fileTree(appResources) { include("values*/strings.xml") })
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("appStrings")
    systemProperty("minimpos.appResources", appResources.asFile.absolutePath)
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

    @get:OutputFile
    abstract val stamp: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun check() {
        val output = ByteArrayOutputStream()
        val result =
            execOperations.javaexec {
                classpath(checker)
                mainClass.set("nu.validator.client.SimpleCommandLineValidator")
                args(listOf("--also-check-css", "--format", "gnu", "--exit-zero-always") + pages.files.map { it.path }.sorted())
                standardOutput = output
                errorOutput = output
                isIgnoreExitValue = true
            }
        val messages = output.toString(Charsets.UTF_8).trim()
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
        stamp.set(layout.buildDirectory.file("html-check/OK"))
    }

tasks.named("check") {
    setDependsOn(dependsOn - tasks.named("detekt"))
    dependsOn(htmlCheck, "detektTest")
}
