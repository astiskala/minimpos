plugins {
    alias(libs.plugins.kotlin.jvm)
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
    // Official Adyen library: Terminal API (nexo) models, TerminalLocalAPI, NexoCrypto, certificate CN checks and
    // SaleToAcquirerData/ApplicationInfo. Its Apache HttpClient is replaced by OkHttp (see TerminalHttpClient).
    api(libs.adyen.java.api.library) {
        // Apache HttpClient 5 does not run on Android; TerminalHttpClient (OkHttp) is always installed instead.
        exclude(group = "org.apache.httpcomponents.client5")
        exclude(group = "org.apache.httpcomponents.core5")
    }
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    // The nexo models use XMLGregorianCalendar; Android ships the API but no DatatypeFactory implementation.
    runtimeOnly(libs.xerces) {
        exclude(group = "xml-apis")
    }

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.archunit)
}

kover {
    reports {
        verify {
            rule("Terminal API line coverage") {
                minBound(90)
            }
            rule("Terminal API branch coverage") {
                minBound(75, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH)
            }
        }
    }
}

tasks.named("check") {
    dependsOn("koverVerify", "detektMain", "detektTest", "dokkaGeneratePublicationHtml")
}
