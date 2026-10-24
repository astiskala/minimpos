plugins {
    `kotlin-dsl`
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

// The plugin marker artifact of a catalog plugin, so convention plugins can apply it by id.
fun marker(plugin: Provider<PluginDependency>) = plugin.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }

// compileOnly: the root build script declares every plugin (apply false), so at run time they load once, from its
// classpath, rather than again for each module that applies a convention plugin.
dependencies {
    compileOnly(marker(libs.plugins.kotlin.jvm))
    compileOnly(marker(libs.plugins.detekt))
    compileOnly(marker(libs.plugins.kover))
    compileOnly(marker(libs.plugins.android.lint))
    compileOnly(marker(libs.plugins.dokka))
    compileOnly(marker(libs.plugins.pitest))
}
