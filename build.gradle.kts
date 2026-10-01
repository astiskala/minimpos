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
}

tasks.register("qualityGate") {
    group = "verification"
    description = "Runs formatting checks, lint, unit tests and coverage verification for every module."
    dependsOn(
        "spotlessCheck",
        ":core:check",
        ":terminal-api:check",
        ":app:check",
    )
}
