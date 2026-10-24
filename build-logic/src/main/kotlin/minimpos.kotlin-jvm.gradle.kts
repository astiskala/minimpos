// Every Kotlin/JVM module: Java 17 bytecode, warnings as errors and type-resolved detekt on the shared configuration.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("dev.detekt")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
        allWarningsAsErrors.set(true)
        extraWarnings.set(true)
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(isolated.rootProject.projectDirectory.file("config/detekt/detekt.yml"))
}

// The plain detekt task analyses without type resolution; check runs the type-resolved tasks instead.
tasks.named("check") {
    setDependsOn(dependsOn - tasks.named("detekt"))
    dependsOn("detektTest")
}
