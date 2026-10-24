plugins {
    id("minimpos.kotlin-jvm-verified")
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.archunit)
}

// A standalone, tested executable: the signing job runs this CI-built JAR, never Gradle or dependency resolution.
val cliJar =
    tasks.register<Jar>("cliJar") {
        archiveFileName.set("minimpos-tooling.jar")
        manifest.attributes["Main-Class"] = "app.minimpos.tooling.Tooling"
        from(sourceSets.main.get().output)
        val runtime = configurations.runtimeClasspath
        from(runtime.map { files -> files.map(::zipTree) })
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class", "META-INF/versions/**/module-info.class")
    }

tasks.test {
    dependsOn(cliJar)
    val repository = isolated.rootProject.projectDirectory
    inputs.dir(repository.dir("scripts")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        repository.file(".agents/skills/docs-screenshots/social.html"),
        repository.file(".agents/skills/docs-screenshots/social-copy.json"),
    )
    inputs.dir(repository.dir(".github/workflows")).withPathSensitivity(PathSensitivity.RELATIVE)
    // BuildConventionTest reads each module's build script.
    inputs
        .files(fileTree(repository) { include("*/build.gradle.kts") })
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("buildScripts")
    systemProperty("minimpos.repository", repository.asFile.absolutePath)
    systemProperty(
        "minimpos.tooling.jar",
        cliJar
            .flatMap { it.archiveFile }
            .get()
            .asFile.absolutePath,
    )
}

kover {
    reports {
        verify {
            rule("Tooling line coverage") {
                minBound(99)
            }
            rule("Tooling branch coverage") {
                minBound(96, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH)
            }
        }
    }
}

tasks.named("check") {
    dependsOn(cliJar)
}

pitest {
    targetClasses.set(listOf("app.minimpos.tooling.*"))
    excludedTestClasses.set(
        listOf(
            "app.minimpos.tooling.ArchitectureTest",
            "app.minimpos.tooling.BuildConventionTest",
            "app.minimpos.tooling.CiScriptTest",
            "app.minimpos.tooling.DevRunnerTest",
            "app.minimpos.tooling.GradleRunnerTest",
            "app.minimpos.tooling.ReleaseReuseTest",
            "app.minimpos.tooling.WorkflowTest",
        ),
    )
    avoidCallsTo.add("kotlin.jdk7.AutoCloseableKt")
    mutationThreshold.set(100)
}
