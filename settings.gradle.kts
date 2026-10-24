pluginManagement {
    // Convention plugins shared by the Kotlin/JVM modules.
    includeBuild("build-logic")
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

// Security floors for transitive libraries, shared with build-logic so every build applies them.
apply(from = "gradle/dependency-floors.settings.gradle.kts")

rootProject.name = "minimpos"

include(":app", ":core", ":adyen", ":website-test", ":tooling")
