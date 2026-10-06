import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.spotless)
}

spotless {
    val ktlintVersion = libs.versions.ktlint.get()
    // Declared explicitly so that edits to .editorconfig invalidate Spotless's up-to-date checks.
    val editorConfig = rootProject.file(".editorconfig")
    kotlin {
        target("*/src/**/*.kt")
        ktlint(ktlintVersion).setEditorConfigPath(editorConfig)
    }
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts")
        ktlint(ktlintVersion).setEditorConfigPath(editorConfig)
    }
    // The text files that neither ktlint nor rumdl (Markdown) formats. Room writes the schemas.
    format("misc") {
        target(
            ".editorconfig",
            ".gitattributes",
            ".gitignore",
            ".rumdl.toml",
            "*.properties",
            "*/*.pro",
            "gradle/*.toml",
            "gradle/*.properties",
            ".vscode/*.json",
            ".github/**/*.yml",
            "scripts/gradle",
            "config/**/*.yml",
            "*/src/**/*.xml",
            "*/lint.xml",
            "docs/**/*.html",
            "docs/**/*.css",
            "docs/**/*.js",
            "docs/**/*.svg",
        )
        trimTrailingWhitespace()
        leadingTabsToSpaces()
        endWithNewline()
    }
}

/**
 * Runs a linter's [command] in the root directory, and is up to date while its [sources] and the downloaded [tools]
 * are unchanged.
 */
abstract class ToolCheck : DefaultTask() {
    @get:InputFiles
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val tools: ConfigurableFileCollection

    @get:Input
    abstract val command: ListProperty<String>

    @get:Internal
    abstract val workingDirectory: DirectoryProperty

    @get:OutputFile
    abstract val stamp: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun run() {
        execOperations.exec {
            workingDir(workingDirectory.get().asFile)
            commandLine(command.get())
        }
        stamp.get().asFile.writeText("OK\n")
    }
}

/**
 * Downloads a linter's release archive for [platform] (one of the keys of [urls]), checks it against the pinned SHA-256
 * in [checksums] and unpacks its [executable] into [directory].
 */
abstract class ToolDownload : DefaultTask() {
    @get:Input
    abstract val platform: Property<String>

    @get:Input
    abstract val urls: MapProperty<String, String>

    @get:Input
    abstract val checksums: MapProperty<String, String>

    @get:Input
    abstract val executable: Property<String>

    @get:OutputDirectory
    abstract val directory: DirectoryProperty

    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun download() {
        val url = urls.get()[platform.get()] ?: throw GradleException("No $name binary is pinned for ${platform.get()}")
        val archive = temporaryDir.resolve("archive.tar.gz")
        URI(url).toURL().openStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
        val actual = MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) }
        val expected = checksums.get().getValue(platform.get())
        if (actual != expected) throw GradleException("$url has SHA-256 $actual, expected $expected")
        val binary = executable.get()
        files.sync {
            from(archives.tarTree(archives.gzip(archive))) {
                include("**/$binary")
                eachFile { relativePath = RelativePath(true, binary) }
            }
            includeEmptyDirs = false
            into(directory)
            filePermissions { user.execute = true }
        }
    }
}

// The platform key of the linters' release binaries below.
val hostPlatform: String =
    run {
        val os = providers.systemProperty("os.name").get()
        val arch = providers.systemProperty("os.arch").get()
        val system = if (os.startsWith("Mac")) "macos" else os.lowercase()
        "$system-${if (arch == "aarch64" || arch == "arm64") "arm64" else "x64"}"
    }

/**
 * Registers the download of [tool]'s official release binary [version], whose archive for each platform is at
 * [url] and has the SHA-256 in [checksums] (from the release's checksum files, or computed from an artifact whose
 * GitHub attestation was verified).
 */
fun registerDownload(
    tool: String,
    version: String,
    checksums: Map<String, String>,
    url: (version: String, platform: String) -> String,
) = tasks.register<ToolDownload>("${tool}Download") {
    description = "Downloads $tool $version for this machine and checks its SHA-256."
    platform.set(hostPlatform)
    urls.set(checksums.mapValues { url(version, it.key) })
    this.checksums.set(checksums)
    executable.set(tool)
    directory.set(layout.buildDirectory.dir("tools/$tool"))
}

val rustTargets =
    mapOf(
        "macos-arm64" to "aarch64-apple-darwin",
        "macos-x64" to "x86_64-apple-darwin",
        "linux-arm64" to "aarch64-unknown-linux-gnu",
        "linux-x64" to "x86_64-unknown-linux-gnu",
    )

val rumdlDownload =
    registerDownload(
        "rumdl",
        "0.2.76",
        mapOf(
            "macos-arm64" to "10ec95ee46e1d3f67560250db97725681a5fa4bc488f383615cc54b06c4bdbc7",
            "macos-x64" to "415df77d4c5d11f336733c9570a72cd0a188d8e6a7460a76134c4c033ec5ece7",
            "linux-arm64" to "a8a3a89bc0e1ebe92d1e5084a3785a1cce8189f02a1b3a08a69c55f53bbea967",
            "linux-x64" to "90a8589a34cdc9aa50c9cf9a2b1e41837afe16bb11b2a7ace2e05dfb56a51176",
        ),
    ) { version, platform ->
        "https://github.com/rvben/rumdl/releases/download/v$version/rumdl-v$version-${rustTargets.getValue(platform)}.tar.gz"
    }

val actionlintDownload =
    registerDownload(
        "actionlint",
        "1.7.12",
        mapOf(
            "macos-arm64" to "aba9ced2dee8d27fecca3dc7feb1a7f9a52caefa1eb46f3271ea66b6e0e6953f",
            "macos-x64" to "5b44c3bc2255115c9b69e30efc0fecdf498fdb63c5d58e17084fd5f16324c644",
            "linux-arm64" to "325e971b6ba9bfa504672e29be93c24981eeb1c07576d730e9f7c8805afff0c6",
            "linux-x64" to "8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8",
        ),
    ) { version, platform ->
        val target = platform.replace("macos", "darwin").replace("x64", "amd64").replace('-', '_')
        "https://github.com/rhysd/actionlint/releases/download/v$version/actionlint_${version}_$target.tar.gz"
    }

val shellcheckDownload =
    registerDownload(
        "shellcheck",
        "0.11.0",
        mapOf(
            "macos-arm64" to "339b930feb1ea764467013cc1f72d09cd6b869ebf1013296ba9055ab2ffbd26f",
            "macos-x64" to "c2c15e08df0e8fbc374c335b230a7ee958c313fa5714817a59aa59f1aa594f51",
            "linux-arm64" to "68a8133197a50beb8803f8d42f9908d1af1c5540d4bb05fdfca8c1fa47decefc",
            "linux-x64" to "b7af85e41cc99489dcc21d66c6d5f3685138f06d34651e6d34b42ec6d54fe6f6",
        ),
    ) { version, platform ->
        val target =
            platform
                .replace("macos", "darwin")
                .replace("arm64", "aarch64")
                .replace("x64", "x86_64")
                .replace('-', '.')
        "https://github.com/koalaman/shellcheck/releases/download/v$version/shellcheck-v$version.$target.tar.gz"
    }

val zizmorDownload =
    registerDownload(
        "zizmor",
        "1.30.1",
        mapOf(
            "macos-arm64" to "e28d22b087f9ebb8d99da6e740d348c930f559961c7c3f12badda54f882195a2",
            "macos-x64" to "10e6b18b11ea07e515a16f0f0518c7b07527bc9977c1fd5698181ce7f3554202",
            "linux-arm64" to "7ff1dce33bdd18fd2a4affe63bdd47efcccca97b2cec1c1863ec26e9e2647540",
            "linux-x64" to "e65324f4430c2717591937edcec90ccbefaf14c174f8ec9415e03ca875b46e1a",
        ),
    ) { version, platform ->
        "https://github.com/zizmorcore/zizmor/releases/download/v$version/zizmor-${rustTargets.getValue(platform)}.tar.gz"
    }

fun tool(name: String) =
    layout.buildDirectory
        .file("tools/$name/$name")
        .get()
        .asFile.path

fun registerCheck(
    name: String,
    description: String,
    configure: ToolCheck.() -> Unit,
) = tasks.register<ToolCheck>(name) {
    group = "verification"
    this.description = description
    workingDirectory.set(layout.projectDirectory)
    stamp.set(layout.buildDirectory.file("tool-checks/$name"))
    configure()
}

val markdown =
    fileTree(layout.projectDirectory) {
        include("**/*.md")
        exclude("**/build/**", ".gradle/**", ".idea/**", ".kotlin/**", ".git/**")
    }
val workflows = fileTree(layout.projectDirectory.dir(".github")) { include("**/*.yml") }

val markdownCheck =
    registerCheck("markdownCheck", "Lints and checks the formatting of Markdown files with rumdl (.rumdl.toml).") {
        sources.from(markdown, ".rumdl.toml")
        tools.from(rumdlDownload)
        command.set(listOf(tool("rumdl"), "check", "--no-cache", "--deny-config-warnings", "."))
    }

val markdownApply =
    tasks.register<Exec>("markdownApply") {
        description = "Fixes what rumdl can fix in the Markdown files (part of spotlessApply)."
        dependsOn(rumdlDownload)
        commandLine(tool("rumdl"), "fmt", "--no-cache", ".")
    }

tasks.named("spotlessApply") { dependsOn(markdownApply) }

val actionlint =
    registerCheck("actionlint", "Lints the GitHub workflows with actionlint, including shellcheck on their scripts.") {
        sources.from(workflows)
        tools.from(actionlintDownload, shellcheckDownload)
        // No Python scripts run in the workflows.
        command.set(listOf(tool("actionlint"), "-shellcheck", tool("shellcheck"), "-pyflakes="))
    }

val shellCheck =
    registerCheck("shellCheck", "Checks the concise Bash Gradle runner with shellcheck.") {
        sources.from("scripts/gradle")
        tools.from(shellcheckDownload)
        command.set(listOf(tool("shellcheck"), "scripts/gradle"))
    }

val zizmor =
    registerCheck("zizmor", "Audits the GitHub workflows for security problems with zizmor.") {
        sources.from(workflows)
        tools.from(zizmorDownload)
        // Offline: the online audits need a GitHub token and would make the result depend on the network.
        command.set(listOf(tool("zizmor"), "--offline", "--no-progress", ".github"))
    }

tasks.register("qualityGate") {
    group = "verification"
    description = "Runs formatting checks, lint, unit tests and coverage verification for every module, and checks the " +
        "Markdown, the website and the GitHub workflows."
    dependsOn(
        "spotlessCheck",
        markdownCheck,
        actionlint,
        shellCheck,
        zizmor,
        ":tooling:check",
        ":core:check",
        ":adyen:check",
        ":app:check",
        ":website-test:check",
    )
}
