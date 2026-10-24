plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
    alias(libs.plugins.android.lint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.dokka)
    alias(libs.plugins.pitest)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
        allWarningsAsErrors.set(true)
        extraWarnings.set(true)
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

dokka {
    dokkaSourceSets.configureEach {
        documentedVisibilities.set(org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier.entries)
    }
    dokkaPublications.configureEach {
        failOnWarning.set(true)
        offlineMode.set(true)
    }
}

lint {
    abortOnError = true
    warningsAsErrors = true
    checkAllWarnings = true
    checkTestSources = true
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
    setDependsOn(dependsOn - tasks.named("detekt"))
    dependsOn("koverVerify", "detektMain", "detektTest", "dokkaGeneratePublicationHtml", cliJar)
}

pitest {
    targetClasses.set(listOf("app.minimpos.tooling.*"))
    excludedTestClasses.set(
        listOf(
            "app.minimpos.tooling.ArchitectureTest",
            "app.minimpos.tooling.CiScriptTest",
            "app.minimpos.tooling.DevRunnerTest",
            "app.minimpos.tooling.GradleRunnerTest",
            "app.minimpos.tooling.ReleaseReuseTest",
            "app.minimpos.tooling.WorkflowTest",
        ),
    )
    threads.set(4)
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
    avoidCallsTo.set(listOf("kotlin.jvm.internal", "kotlin.ResultKt", "kotlin.io.CloseableKt", "kotlin.jdk7.AutoCloseableKt"))
    mutationThreshold.set(100)
}
