plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
    alias(libs.plugins.dokka)
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

dokka {
    dokkaSourceSets.configureEach {
        documentedVisibilities.set(org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier.entries)
    }
    dokkaPublications.configureEach {
        failOnWarning.set(true)
        offlineMode.set(true)
    }
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
        manifest.attributes["Main-Class"] = "io.github.astiskala.minimpos.tooling.Tooling"
        from(sourceSets.main.get().output)
        val runtime = configurations.runtimeClasspath
        from(runtime.map { files -> files.map(::zipTree) })
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class", "META-INF/versions/**/module-info.class")
    }

tasks.test {
    dependsOn(cliJar)
    val repository = isolated.rootProject.projectDirectory
    inputs.files(
        repository.file("scripts/gradle"),
        repository.file(".github/workflows/ci.yml"),
        repository.file(".github/workflows/release.yml"),
    )
    systemProperty("minimpos.repository", repository.asFile.absolutePath)
    systemProperty(
        "minimpos.tooling.jar",
        cliJar
            .flatMap { it.archiveFile }
            .get()
            .asFile.absolutePath,
    )
}

tasks.named("check") {
    setDependsOn(dependsOn - tasks.named("detekt"))
    dependsOn("detektMain", "detektTest", "dokkaGeneratePublicationHtml", cliJar)
}
