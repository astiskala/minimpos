import com.android.build.api.artifact.SingleArtifact
import com.android.tools.r8.BackportedMethodList
import com.android.tools.r8.BackportedMethodListCommand
import dev.detekt.gradle.Detekt
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.w3c.dom.Element
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
    alias(libs.plugins.dokka)
}

val keystoreProperties =
    Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

val appVersion =
    Properties().apply {
        rootProject.file("version.properties").inputStream().use { load(it) }
    }

android {
    namespace = "io.minimpos.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.astiskala.minimpos"
        // S1F2 (Android 9) is the lowest API level in Adyen's Android terminal app requirements.
        minSdk = 28
        targetSdk = 37
        versionCode = appVersion.getProperty("versionCode").toInt()
        versionName = appVersion.getProperty("versionName")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.maxHeapSize = "3g" }
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = true
        checkAllWarnings = true
        checkTestSources = true
        // These compare versions with the latest online releases, so an unchanged build would start failing whenever
        // anything is released. Dependabot proposes updates instead.
        disable +=
            setOf(
                "GradleDependency",
                "NewerVersionAvailable",
                "AndroidGradlePluginVersion",
            )
    }

    packaging {
        resources {
            excludes +=
                setOf(
                    "META-INF/LICENSE*",
                    "META-INF/NOTICE*",
                    "META-INF/DEPENDENCIES",
                    "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                    "META-INF/*.version",
                    // Xerces registers providers for APIs Android does not have (StAX) and for names that are system
                    // properties, not classes. Only its DatatypeFactory is used (see proguard-rules.pro).
                    "META-INF/services/javax.xml.stream.XMLEventFactory",
                    "META-INF/services/org.w3c.dom.DOMImplementationSourceList",
                    "META-INF/services/org.xml.sax.driver",
                )
        }
        jniLibs {
            // AndroidX ships these few-KB libraries prebuilt. Stripping them needs an NDK, which nothing else in the
            // build needs, so they are packaged as they come instead of warning on every build without one.
            keepDebugSymbols +=
                setOf(
                    "**/libandroidx.graphics.path.so",
                    "**/libdatastore_shared_counter.so",
                    "**/libimage_processing_util_jni.so",
                    "**/libsurface_util_jni.so",
                )
        }
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
        extraWarnings.set(true)
        // Room's generated DAOs trip these two extra checks; detekt's VarCouldBeVal and RedundantVisibilityModifier
        // cover them in hand-written code.
        freeCompilerArgs.addAll(
            "-Xwarning-level=CAN_BE_VAL:disabled",
            "-Xwarning-level=REDUNDANT_VISIBILITY_MODIFIER:disabled",
        )
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml", "config/detekt/compose.yml"))
}

// detekt's Android tasks read the Kotlin classpath through a mapped task provider, which hides the artifact transforms
// that produce it from Gradle (deprecated for Gradle 10), and they leave out javac's output, where BuildConfig is
// compiled (reported as compiler errors during analysis). Give each task its compilation's own classpath instead.
tasks.withType<Detekt>().configureEach {
    val component = name.removePrefix("detekt")
    if (component.isNotEmpty()) {
        val kotlinCompile = tasks.named<KotlinJvmCompile>("compile${component}Kotlin").get()
        val javaCompile = tasks.named<JavaCompile>("compile${component}JavaWithJavac")
        classpath.setFrom(kotlinCompile.libraries, javaCompile.flatMap { it.destinationDirectory })
    }
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

room {
    schemaDirectory("$projectDir/schemas")
    generateKotlin = true
}

dependencies {
    implementation(project(":core"))
    implementation(project(":terminal-api"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
    implementation(libs.android.mail)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.archunit)

    detektPlugins(libs.compose.rules.detekt)
}

kover {
    reports {
        filters {
            excludes {
                annotatedBy("androidx.compose.runtime.Composable")
                classes(
                    "*ComposableSingletons*",
                    "*_Impl",
                    "*_Impl\$*",
                    "*.BuildConfig",
                    "io.minimpos.app.MainActivity*",
                    "io.minimpos.app.MiniMposApplication*",
                )
                packages(
                    "io.minimpos.app.ui.theme",
                    "io.minimpos.app.scan",
                )
            }
        }
        variant("debug") {
            verify {
                rule("App line coverage (non-UI code)") {
                    minBound(80)
                }
            }
        }
    }
}

/**
 * Removes permissions that libraries add but Adyen terminals do not allow from the merged manifest. The same
 * `tools:node="remove"` in the app's manifest works too, but warns on every unit test build: AGP merges the tested
 * manifest again for Robolectric, and by then the permission is already gone.
 */
abstract class StripManifestPermissionsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val permissions: SetProperty<String>

    @get:OutputFile
    abstract val updatedManifest: RegularFileProperty

    @TaskAction
    fun strip() {
        val androidNs = "http://schemas.android.com/apk/res/android"
        val document =
            DocumentBuilderFactory
                .newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(mergedManifest.get().asFile)
        listOf("permission", "uses-permission")
            .flatMap { tag -> document.getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map(nodes::item) } }
            .filter { (it as Element).getAttributeNS(androidNs, "name") in permissions.get() }
            .forEach { it.parentNode.removeChild(it) }
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(document), StreamResult(updatedManifest.get().asFile))
    }
}

/**
 * Adyen rejects terminal apps that request permissions outside this allowlist, set testOnly, declare a
 * HOME launcher, or request BIND_DEVICE_ADMIN. See docs.adyen.com/point-of-sale/android-terminals/app-requirements.
 * The Customer Area also shows a blank icon unless the launcher icon is a bitmap in every density.
 */
abstract class VerifyTerminalManifestTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resDirectories: ConfigurableFileCollection

    @get:Input
    abstract val allowedPermissions: SetProperty<String>

    @get:Input
    abstract val maxMinSdk: Property<Int>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val androidNs = "http://schemas.android.com/apk/res/android"
        val document =
            DocumentBuilderFactory
                .newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(mergedManifest.get().asFile)
        val problems = mutableListOf<String>()
        val requested =
            listOf("uses-permission", "uses-permission-sdk-23").flatMap { tag ->
                val nodes = document.getElementsByTagName(tag)
                (0 until nodes.length).map {
                    nodes
                        .item(it)
                        .attributes
                        .getNamedItemNS(androidNs, "name")
                        .nodeValue
                }
            }
        requested.filterNot { it in allowedPermissions.get() }.forEach {
            problems +=
                "Permission not allowed on Adyen terminals: $it"
        }
        val application = document.getElementsByTagName("application").item(0)
        if (application?.attributes?.getNamedItemNS(androidNs, "testOnly")?.nodeValue == "true") {
            problems += "android:testOnly must not be set"
        }
        val icon = application?.attributes?.getNamedItemNS(androidNs, "icon")?.nodeValue
        val iconRef = icon?.let { Regex("@(mipmap|drawable)/(\\w+)").matchEntire(it) }?.destructured
        if (iconRef == null) {
            problems += "android:icon must reference a mipmap or drawable (was $icon)"
        } else {
            val (type, name) = iconRef
            val iconFiles = resDirectories.asFileTree.matching { include("$type*/$name.*") }.files
            val xmlIcons = iconFiles.filter { it.extension == "xml" }.map { "${it.parentFile.name}/${it.name}" }
            if (iconFiles.isEmpty() || xmlIcons.isNotEmpty()) {
                problems += "$icon must be a bitmap, not an adaptive/XML icon, or the Customer Area shows it blank " +
                    "(found: ${xmlIcons.ifEmpty { listOf("no bitmap") }.joinToString()})"
            }
        }
        val categories = document.getElementsByTagName("category")
        (0 until categories.length).forEach {
            if (categories
                    .item(it)
                    .attributes
                    .getNamedItemNS(androidNs, "name")
                    .nodeValue ==
                "android.intent.category.HOME"
            ) {
                problems += "Launcher apps (CATEGORY_HOME) are not allowed"
            }
        }
        val usesSdk = document.getElementsByTagName("uses-sdk").item(0)
        val minSdk =
            usesSdk
                ?.attributes
                ?.getNamedItemNS(androidNs, "minSdkVersion")
                ?.nodeValue
                ?.toIntOrNull()
        if (minSdk == null ||
            minSdk > maxMinSdk.get()
        ) {
            problems += "minSdkVersion must be <= ${maxMinSdk.get()} (was $minSdk)"
        }
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString(separator = "\n"))
        report.get().asFile.writeText("OK\n" + requested.sorted().joinToString(separator = "\n"))
    }
}

val adyenAllowedPermissions =
    setOf(
        "ACCESS_BACKGROUND_LOCATION",
        "ACCESS_COARSE_LOCATION",
        "ACCESS_FINE_LOCATION",
        "ACCESS_MEDIA_LOCATION",
        "ACCESS_NETWORK_STATE",
        "ACCESS_WIFI_STATE",
        "BATTERY_STATS",
        "BLUETOOTH",
        "BLUETOOTH_ADMIN",
        "BROADCAST_STICKY",
        "CAMERA",
        "FLASHLIGHT",
        "FOREGROUND_SERVICE",
        "GET_ACCOUNTS",
        "INTERNET",
        "READ_EXTERNAL_STORAGE",
        "READ_PHONE_STATE",
        "RECEIVE_BOOT_COMPLETED",
        "RECORD_AUDIO",
        "USE_BIOMETRIC",
        "USE_FINGERPRINT",
        "VIBRATE",
        "WAKE_LOCK",
        "WRITE_EXTERNAL_STORAGE",
    ).map { "android.permission.$it" }.toSet() + "com.android.alarm.permission.SET_ALARM"

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val stripTask =
            tasks.register<StripManifestPermissionsTask>("strip${name}ManifestPermissions") {
                // androidx.core declares this app-defined permission for its non-exported receivers on API < 33.
                permissions.set(variant.applicationId.map { setOf("$it.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") })
            }
        variant.artifacts
            .use(stripTask)
            .wiredWithFiles(StripManifestPermissionsTask::mergedManifest, StripManifestPermissionsTask::updatedManifest)
            .toTransform(SingleArtifact.MERGED_MANIFEST)
        val verifyTask =
            tasks.register<VerifyTerminalManifestTask>("verify${name}TerminalManifest") {
                group = "verification"
                description =
                    "Checks the merged ${variant.name} manifest against Adyen's Android terminal app requirements."
                mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                variant.sources.res?.let { resDirectories.from(it.static) }
                allowedPermissions.set(adyenAllowedPermissions)
                maxMinSdk.set(28)
                report.set(layout.buildDirectory.file("reports/terminal-manifest/${variant.name}.txt"))
            }
        tasks.named("check").configure { dependsOn(verifyTask) }
    }
}

/** Lists the Java and Android methods D8 backports (so every API level has them) when dexing for [minSdk], as Lint does. */
abstract class BackportedMethodsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val androidJar: RegularFileProperty

    @get:Input
    abstract val minSdk: Property<Int>

    @get:OutputFile
    abstract val list: RegularFileProperty

    @TaskAction
    fun write() {
        BackportedMethodList.run(
            BackportedMethodListCommand
                .builder()
                .setMinApiLevel(minSdk.get())
                .addLibraryFiles(androidJar.get().asFile.toPath())
                .setOutputPath(list.get().asFile.toPath())
                .build(),
        )
    }
}

/**
 * Points `AndroidApiLevelTest` at the compile SDK's API database, D8's backported methods and the minimum SDK: :core and
 * :terminal-api run on the terminals too, but as JVM modules Android Lint does not check which Android versions have
 * the Java APIs they call.
 */
abstract class AndroidApiArguments : CommandLineArgumentProvider {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apiDatabase: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val backportedMethods: RegularFileProperty

    @get:Input
    abstract val minSdk: Property<Int>

    override fun asArguments() =
        listOf(
            "-Dminimpos.apiDatabase=${apiDatabase.get().asFile.absolutePath}",
            "-Dminimpos.backportedMethods=${backportedMethods.get().asFile.absolutePath}",
            "-Dminimpos.minSdk=${minSdk.get()}",
        )
}

val sdkAndroidJar = androidComponents.sdkComponents.bootClasspath.map { jars -> jars.first { it.asFile.name == "android.jar" }.asFile }

val listBackportedMethods =
    tasks.register<BackportedMethodsTask>("listBackportedMethods") {
        androidJar.set(layout.file(sdkAndroidJar))
        minSdk.set(android.defaultConfig.minSdk)
        list.set(layout.buildDirectory.file("intermediates/backported-methods.txt"))
    }

tasks.withType<Test>().configureEach {
    jvmArgumentProviders +=
        objects.newInstance<AndroidApiArguments>().apply {
            apiDatabase.set(layout.file(sdkAndroidJar.map { it.resolveSibling("data/api-versions.xml") }))
            backportedMethods.set(listBackportedMethods.flatMap { it.list })
            minSdk.set(android.defaultConfig.minSdk)
        }
}

// The app has no variant-specific sources, so analysing the release variant too would only repeat the debug findings.
tasks.named("check") {
    dependsOn("koverVerifyDebug", "detektDebug", "detektDebugUnitTest", "dokkaGeneratePublicationHtml")
}
