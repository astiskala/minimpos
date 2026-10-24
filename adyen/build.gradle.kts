plugins {
    id("minimpos.kotlin-jvm-verified")
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
    implementation(libs.libphonenumber)
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
                minBound(100)
            }
            rule("Terminal API branch coverage") {
                minBound(86, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH)
            }
        }
    }
}

pitest {
    targetClasses.set(listOf("app.minimpos.terminal.*"))
    // Just below the score reached when mutation testing was added; raise it as tests improve, never lower it.
    mutationThreshold.set(93)
}
