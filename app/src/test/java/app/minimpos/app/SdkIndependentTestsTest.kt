package app.minimpos.app

import com.google.common.truth.Truth.assertThat
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * `testDebugUnitTestMinSdk` skips the tests the build names in `sdkIndependentTests`, since they never start Robolectric
 * and a run on another Android version could only repeat theirs. This keeps every Robolectric test out of those names.
 */
class SdkIndependentTestsTest {
    private val patterns =
        checkNotNull(System.getProperty("minimpos.sdkIndependentTests")) { "minimpos.sdkIndependentTests is set by Gradle" }
            .split(',')
            .map { Regex(it.split('*').joinToString(".*") { part -> Regex.escape(part) }) }

    private fun skipped(type: JavaClass) = !type.isNestedClass && patterns.any { it.matches(type.simpleName) }

    /** Why [type] needs Robolectric, so must run on the minimum SDK too; null when it never starts it. */
    private fun robolectricUse(type: JavaClass): String? =
        when {
            type.isAnnotatedWith(RunWith::class.java) -> "runs with a JUnit runner"
            type.isAnnotatedWith("org.robolectric.annotation.Config") -> "configures Robolectric"
            type.directDependenciesFromSelf.any { it.targetClass.packageName.startsWith("org.robolectric") } -> "uses Robolectric"
            else -> null
        }

    @Test
    fun `tests skipped on the minimum SDK never start Robolectric`() {
        val tests =
            ClassFileImporter()
                .importPackages("app.minimpos.app")
                .filter { skipped(it) && it.methods.any { method -> method.isAnnotatedWith(Test::class.java) } }
        assertThat(tests.map { it.simpleName }).containsAtLeast("ArchitectureTest", "DeadCodeTest", "AndroidApiLevelTest")
        assertThat(tests.mapNotNull { type -> robolectricUse(type)?.let { "${type.name} $it" } }).isEmpty()
    }

    @Test
    fun `a Robolectric test is caught`() {
        val fixture = ClassFileImporter().importClass(RobolectricFixture::class.java)
        assertThat(robolectricUse(fixture)).isEqualTo("configures Robolectric")
    }

    // Configured rather than run with Robolectric's runner, which JUnit would try to run as a test of its own.
    @Config(sdk = [28])
    private class RobolectricFixture
}
