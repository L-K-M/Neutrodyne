// SPDX-License-Identifier: Unlicense
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real Test task executions over mutable state outside Gradle's declared outputs. */
class CrossDeviceExecutionTest {
    @get:Rule
    val temporary = TemporaryFolder.builder().assureDeletion().build()

    @Test
    fun `cross-device phases execute after cache restoration and without cleaning`() {
        val fixture = fixture()
        fixture.run(crossDevice = true)
        assertTrue(fixture.result.isFile)

        fixture.result.delete()
        val afterClean = fixture.run(crossDevice = true, clean = true)
        assertEquals(TaskOutcome.SUCCESS, afterClean.task(":desktopApp:test")?.outcome)
        assertTrue(fixture.result.isFile, "A cached XML report cannot replace the phase side effect")

        fixture.result.delete()
        val withoutClean = fixture.run(crossDevice = true)
        assertEquals(TaskOutcome.SUCCESS, withoutClean.task(":desktopApp:test")?.outcome)
        assertTrue(fixture.result.isFile, "An up-to-date check cannot skip a requested phase")
    }

    @Test
    fun `ordinary desktop tests retain caching and up-to-date checks`() {
        val fixture = fixture()
        fixture.run(crossDevice = false)
        assertTrue(fixture.result.isFile)

        fixture.result.delete()
        val cached = fixture.run(crossDevice = false, clean = true)
        assertEquals(TaskOutcome.FROM_CACHE, cached.task(":desktopApp:test")?.outcome)
        assertFalse(fixture.result.exists())

        val unchanged = fixture.run(crossDevice = false)
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":desktopApp:test")?.outcome)
    }

    private fun fixture(): Fixture {
        val root = temporary.newFolder()
        val project = root.resolve("project").apply { mkdir() }
        val result = root.resolve("phase-result.txt")
        val conventions =
            File(
                Class
                    .forName("TestConventionsKt")
                    .protectionDomain.codeSource.location
                    .toURI(),
            )
        val junit =
            File(
                Test::class.java.protectionDomain.codeSource.location
                    .toURI(),
            )
        val hamcrest =
            File(
                org.hamcrest.Matcher::class.java.protectionDomain.codeSource.location
                    .toURI(),
            )
        project.resolve("settings.gradle").writeText(
            """
            rootProject.name = 'phase-execution-fixture'
            include 'desktopApp'
            buildCache { local { directory = file('../cache') } }
            """.trimIndent(),
        )
        val desktop = project.resolve("desktopApp").apply { mkdir() }
        desktop.resolve("build.gradle").writeText(
            """
            buildscript { dependencies { classpath files(${quoted(conventions)}) } }
            apply plugin: 'java'
            dependencies { testImplementation files(${quoted(junit)}, ${quoted(hamcrest)}) }
            tasks.named('test') {
                useJUnit()
                systemProperty 'phaseResult', ${quoted(result)}
            }
            TestConventionsKt.configureNeutrodyneTestTasks(project)
            """.trimIndent(),
        )
        val source = desktop.resolve("src/test/java/PhaseTest.java")
        source.parentFile.mkdirs()
        source.writeText(
            """
            public class PhaseTest {
                @org.junit.Test public void executePhase() throws Exception {
                    java.nio.file.Files.writeString(
                        java.nio.file.Path.of(System.getProperty("phaseResult")), "executed");
                }
            }
            """.trimIndent(),
        )
        val category = desktop.resolve("src/test/java/ch/lkmc/neutrodyne/core/testing/CrossDevice.java")
        category.parentFile.mkdirs()
        category.writeText("package ch.lkmc.neutrodyne.core.testing; public interface CrossDevice {}")
        return Fixture(project, root.resolve("test-kit"), result)
    }

    private fun quoted(file: File): String = "'${file.absolutePath.replace("\\", "\\\\").replace("'", "\\'")}'"

    private class Fixture(
        val project: File,
        val testKit: File,
        val result: File,
    ) {
        fun run(
            crossDevice: Boolean,
            clean: Boolean = false,
        ) = GradleRunner
            .create()
            .withProjectDir(project)
            .withTestKitDir(testKit)
            // Keep the fixture build in this JVM; no daemon outlives its owned temp directory.
            .withDebug(true)
            .withArguments(
                buildList {
                    if (clean) add(":desktopApp:cleanTest")
                    add(":desktopApp:test")
                    add("--build-cache")
                    add("--offline")
                    add("--max-workers=1")
                    if (crossDevice) add("-PcrossDevice")
                },
            ).build()
    }
}
