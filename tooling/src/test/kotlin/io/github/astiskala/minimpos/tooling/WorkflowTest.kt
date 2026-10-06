package io.github.astiskala.minimpos.tooling

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class WorkflowTest {
    private val repository = Path.of(System.getProperty("minimpos.repository"))

    @Test
    fun `signing job uses only exact CI tooling with no Gradle or compiler`() {
        val workflow = Files.readString(repository.resolve(".github/workflows/release.yml"))
        val release = workflow.substringAfter("\n  release:\n")
        assertThat(release).contains("environment: release")
        assertThat(release).contains("""ref: ${'$'}{{ needs.prepare.outputs.commit }}""")
        assertThat(release).contains("""--name "release-${'$'}COMMIT-${'$'}RUN_ATTEMPT"""")
        assertThat(release).contains(
            """java -jar "${'$'}RUNNER_TEMP/release-artifact/minimpos-tooling.jar" release verify""",
        )
        assertThat(release).contains("""java -jar "${'$'}RUNNER_TEMP/release-artifact/minimpos-tooling.jar" signing""")
        assertThat(release).contains("""java -jar "${'$'}RUNNER_TEMP/release-artifact/minimpos-tooling.jar" release update""")
        for (forbidden in listOf("./gradlew", "setup-gradle@", "kotlinc", "setup-node@", "node ")) {
            assertThat(release).doesNotContain(forbidden)
        }
        val beforeSigning = workflow.substringBefore("\n  release:\n")
        assertThat(beforeSigning).contains("./gradlew :tooling:cliJar")
        assertThat(beforeSigning).doesNotContain("secrets.RELEASE_")
    }

    @Test
    fun `successful main CI packages unsigned APK and executable tooling together`() {
        val workflow = Files.readString(repository.resolve(".github/workflows/ci.yml"))
        assertThat(workflow).contains("./gradlew qualityGate --continue")
        assertThat(workflow).contains("./gradlew :app:assembleRelease -PqualityGatePassed=true")
        assertThat(workflow).contains("cp tooling/build/libs/minimpos-tooling.jar build/release-artifact/")
        assertThat(workflow).contains("java -jar tooling/build/libs/minimpos-tooling.jar release manifest")
        assertThat(workflow).contains("github.event_name != 'pull_request'")
        assertThat(workflow).doesNotContain("setup-node@")
    }
}
