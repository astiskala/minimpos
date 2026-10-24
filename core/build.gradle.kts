plugins {
    id("minimpos.kotlin-jvm-verified")
    alias(libs.plugins.kotlin.serialization)
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
                minBound(100)
            }
            rule("Core branch coverage") {
                minBound(97, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH)
            }
        }
    }
}

pitest {
    targetClasses.set(listOf("app.minimpos.core.*"))
    // Just below the score reached when mutation testing was added; raise it as tests improve, never lower it.
    mutationThreshold.set(98)
}
