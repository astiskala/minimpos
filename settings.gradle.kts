pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Libraries that come in transitively (the Adyen library's Jackson, and AGP's, Android Lint's and Dokka's own
// dependencies) and have published security advisories: wherever they are resolved, including each build script's
// classpath, an older version is raised to the patched release of its line (newer versions are left alone). Dependabot
// cannot update these itself, since no build file declares them.
gradle.lifecycle.beforeProject {
    val patched =
        mapOf(
            // The BOM aligns every Jackson module.
            "com.fasterxml.jackson:jackson-bom" to listOf("2.18.11", "2.22.3"),
            "com.fasterxml.jackson.core:jackson-core" to listOf("2.18.11", "2.22.3"),
            "com.fasterxml.jackson.core:jackson-databind" to listOf("2.18.11", "2.22.3"),
            "org.apache.commons:commons-lang3" to listOf("3.18.0"),
            "org.apache.httpcomponents:httpclient" to listOf("4.5.14"),
            "org.bitbucket.b_c:jose4j" to listOf("0.9.6"),
            "org.bouncycastle:bcpkix-jdk18on" to listOf("1.85"),
            "org.bouncycastle:bcprov-jdk18on" to listOf("1.85"),
            "org.bouncycastle:bcutil-jdk18on" to listOf("1.85"),
            "org.freemarker:freemarker" to listOf("2.3.35"),
            "org.jdom:jdom2" to listOf("2.0.6.1"),
            "org.jsoup:jsoup" to listOf("1.23.1"),
        )

    fun parts(version: String) = version.split('.', '-').map { it.toIntOrNull() ?: 0 }

    fun older(
        version: String,
        than: String,
    ): Boolean {
        val (a, b) = parts(version) to parts(than)
        return (0 until maxOf(a.size, b.size))
            .map { a.getOrElse(it) { 0 } compareTo b.getOrElse(it) { 0 } }
            .firstOrNull { it != 0 }
            ?.let { it < 0 } ?: false
    }

    // The patched release on the requested version's major.minor line, or the lowest one (the lists are in ascending
    // order) for versions below them all.
    fun floor(
        version: String,
        floors: List<String>,
    ): String? =
        floors.firstOrNull { parts(it).take(2) == parts(version).take(2) }
            ?: floors.first().takeIf { older(version, it) }

    val raise =
        Action<DependencyResolveDetails> {
            val floors = patched["${requested.group}:${requested.name}"] ?: return@Action
            val version = requested.version ?: return@Action
            val target = floor(version, floors) ?: return@Action
            if (older(version, target)) {
                useVersion(target)
                because("security advisories fixed in $target")
            }
        }
    buildscript.configurations.configureEach { resolutionStrategy.eachDependency(raise) }
    configurations.configureEach { resolutionStrategy.eachDependency(raise) }
}

rootProject.name = "minimpos"

include(":app", ":core", ":adyen", ":website-test")
