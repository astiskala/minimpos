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
        assertThat(release).contains("needs.prepare.outputs.run || needs.ci.outputs.run")
        assertThat(release).contains("needs.ci.result == 'success'")
        assertThat(release).contains("needs.ci.result == 'skipped' && needs.prepare.outputs.run != ''")
        assertThat(release).contains("!cancelled()")
        assertThat(release).contains("""test "${'$'}(git ls-remote origin refs/heads/main | cut -f1)" = "${'$'}COMMIT"""")
    }

    @Test
    fun `successful main CI packages unsigned APK and executable tooling together`() {
        val workflow = Files.readString(repository.resolve(".github/workflows/ci.yml"))
        assertThat(workflow).contains("scripts/ci")
        assertThat(workflow).contains("cp tooling/build/libs/minimpos-tooling.jar build/release-artifact/")
        assertThat(workflow).contains("java -jar tooling/build/libs/minimpos-tooling.jar release manifest")
        assertThat(workflow).contains("github.event_name != 'pull_request'")
        assertThat(workflow).doesNotContain("setup-node@")
        assertThat(workflow).contains("cache-encryption-key:")
        assertThat(workflow).contains("cancel-in-progress: \${{ github.event_name == 'pull_request' }}")
    }

    @Test
    fun `release calls CI directly or reuses verified successful artifacts without polling or rebuilding tooling twice`() {
        val workflow = Files.readString(repository.resolve(".github/workflows/release.yml"))
        val prepare = workflow.substringBefore("\n  ci:\n")
        val ci = workflow.substringAfter("\n  ci:\n").substringBefore("\n  release:\n")
        assertThat(ci).contains("scripts/ci")
        assertThat(ci).contains("if: needs.prepare.outputs.run == ''")
        assertThat(ci).contains("""ref: ${'$'}{{ needs.prepare.outputs.commit }}""")
        assertThat(ci).contains("""test "${'$'}(git rev-parse HEAD)" = "${'$'}COMMIT"""")
        assertThat(ci).contains("cache-encryption-key:")
        assertThat(ci).contains("release manifest --artifact build/release-artifact")
        assertThat(ci).contains("if-no-files-found: error")
        assertThat(ci).doesNotContain("secrets.RELEASE_")
        assertThat(workflow).doesNotContain("sleep ")
        assertThat(workflow).doesNotContain("gh workflow run")
        assertThat(workflow.split("./gradlew :tooling:cliJar")).hasSize(2)
        assertThat(prepare).contains("""java -jar tooling/build/libs/minimpos-tooling.jar release verify --artifact "${'$'}artifact"""")
        assertThat(prepare).doesNotContain("""java -jar "${'$'}artifact""")
        assertThat(prepare).contains("No reusable exact-commit artifact; the full CI workflow will run.")
        assertThat(prepare).doesNotContain("setup-gradle@")
    }

    @Test
    fun `CodeQL compiles all production modules without packaging or skipping Kotlin extraction`() {
        val workflow = Files.readString(repository.resolve(".github/workflows/codeql.yml"))
        assertThat(workflow).contains("pull_request:")
        assertThat(workflow).contains("schedule:")
        assertThat(workflow).contains("language: actions")
        assertThat(workflow).contains("language: java-kotlin")
        assertThat(workflow).contains("build-mode: manual")
        assertThat(workflow).contains(":core:classes :adyen:classes :tooling:classes :website-test:classes")
        assertThat(workflow).contains(":app:compileDebugKotlin :app:compileDebugJavaWithJavac")
        assertThat(workflow).contains("--no-build-cache --no-configuration-cache --rerun-tasks")
        assertThat(workflow).contains("cache-read-only: true")
        assertThat(workflow).doesNotContain("assemble")
        assertThat(workflow).doesNotContain("autobuild@")
        assertThat(workflow).doesNotContain("qualityGatePassed")
    }

    @Test
    fun `Pages deploys only verified website artifacts with isolated deployment permissions`() {
        val workflow = Files.readString(repository.resolve(".github/workflows/pages.yml"))
        val verify = workflow.substringBefore("\n  deploy:\n")
        val deploy = workflow.substringAfter("\n  deploy:\n")
        assertThat(verify).contains("'docs/**'")
        assertThat(verify).contains("'website-test/**'")
        assertThat(verify).contains("'core/**'")
        assertThat(verify).contains("./gradlew :website-test:check --continue")
        assertThat(verify).contains("path: docs")
        assertThat(verify).contains("github.ref == 'refs/heads/main'")
        assertThat(verify).doesNotContain("pages: write")
        assertThat(verify).doesNotContain("id-token: write")
        assertThat(deploy).contains("needs: verify")
        assertThat(deploy).contains("pages: write")
        assertThat(deploy).contains("id-token: write")
        assertThat(deploy).doesNotContain("./gradlew")
    }

    @Test
    fun `dependency submission covers all current resolution inputs with scheduled and manual backstops`() {
        val workflow = Files.readString(repository.resolve(".github/workflows/dependency-submission.yml"))
        assertThat(workflow).contains("'*.gradle.kts'")
        assertThat(workflow).contains("'*/build.gradle.kts'")
        assertThat(workflow).contains("'gradle/**'")
        assertThat(workflow).contains("'gradle.properties'")
        assertThat(workflow).contains("schedule:")
        assertThat(workflow).contains("workflow_dispatch:")
        assertThat(workflow).contains("gradle/actions/dependency-submission@")
    }
}
