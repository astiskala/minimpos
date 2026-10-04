plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.android.lint)
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

// Dokka checks that every KDoc [link] resolves (detekt only checks that KDoc exists). All visibilities are included so
// links in private documentation are checked too. The pages are not published, so Dokka stays offline instead of
// downloading the JDK, Kotlin and Android package lists (which warns whenever the network is slow or down).
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

kover {
    reports {
        filters {
            excludes {
                classes("*\$\$serializer")
            }
        }
        verify {
            rule("Core line coverage") {
                minBound(95)
            }
            rule("Core branch coverage") {
                minBound(85, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH)
            }
        }
    }
}

tasks.named("check") {
    setDependsOn(dependsOn - tasks.named("detekt"))
    dependsOn("koverVerify", "detektMain", "detektTest", "dokkaGeneratePublicationHtml")
}
