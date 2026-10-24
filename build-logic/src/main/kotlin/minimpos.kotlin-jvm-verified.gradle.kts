// A Kotlin/JVM production module (:core, :adyen, :tooling): minimpos.kotlin-jvm plus Android Lint, KDoc link checks,
// coverage verification and mutation testing. Each module sets its own coverage bounds and mutation targets.
plugins {
    id("minimpos.kotlin-jvm")
    id("org.jetbrains.kotlinx.kover")
    id("com.android.lint")
    id("org.jetbrains.dokka")
    id("info.solidsoft.pitest")
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

tasks.named("check") {
    dependsOn("koverVerify", "detektMain", "dokkaGeneratePublicationHtml")
}

// Mutation testing (root mutationTest; not in the gate, as every mutant reruns the tests): whether the tests notice changed
// behaviour, which coverage does not show. Report: build/reports/pitest/index.html.
pitest {
    threads.set(4)
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
    // Kotlin's generated null checks, resource-closing finally blocks, coroutine state-machine checks and serializers are compiler output.
    avoidCallsTo.set(listOf("kotlin.jvm.internal", "kotlin.ResultKt", "kotlin.io.CloseableKt"))
    excludedClasses.set(listOf("*\$\$serializer"))
}
