// SPDX-License-Identifier: Unlicense
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Negative fixtures for the `checkSpdxHeaders` tree itself (01 Gradle-side policy tasks): the
 * scanned kinds must cover the design's list, and a restricted header in an `.aidl` interface —
 * the kind missing from the scan until 2026-10-06 — must fail the task.
 */
class CheckSpdxHeadersTaskTest {
    @Test
    fun `the tree scans every source kind of the design`() {
        // 01's task table: kt/java/kts/py/aidl/c/cpp/h/m/mm (plus the script and config kinds)
        val designedKinds = listOf("kt", "kts", "java", "aidl", "c", "cpp", "h", "m", "mm")
        for (kind in designedKinds) {
            assertTrue(
                "**/*.$kind" in SPDX_TREE_INCLUDES,
                "checkSpdxHeaders must scan *.$kind files",
            )
        }
    }

    @Test
    fun `a restricted spdx header in an aidl interface fails the scan`() {
        val violations =
            scan(
                "IYtxEngine.aidl" to restrictedHeader("GPL-3.0-only") + "interface IYtxEngine {}\n",
                "Engine.kt" to "// SPDX-License-Identifier: Unlicense\n",
            )
        assertTrue(
            violations.any { it.startsWith("IYtxEngine.aidl declares restricted licence") },
            "expected the GPL .aidl fixture to be reported, got: $violations",
        )
    }

    @Test
    fun `unlicense sources and unscanned kinds pass`() {
        // .txt is not a scanned kind: its (restricted) header must be ignored, not reported
        val violations =
            scan(
                "Engine.kt" to "// SPDX-License-Identifier: Unlicense\n",
                "notes.txt" to restrictedHeader("MPL-2.0"),
            )
        assertTrue(violations.isEmpty(), "unexpected violations: $violations")
    }

    /**
     * Fixture header of a restricted licence. Assembled in two pieces because the gate scans this
     * repository's Kotlin sources too — a spelled-out restricted header here would fail it.
     */
    private fun restrictedHeader(id: String) = "// SPDX-License-Identifier" + ": $id\n"

    /**
     * Runs the registered task's action over a throwaway project holding the given files and
     * returns its violation lines (empty when the gate passes).
     */
    private fun scan(vararg files: Pair<String, String>): List<String> {
        val root =
            java.nio.file.Files
                .createTempDirectory("spdx-scan")
                .toFile()
        try {
            for ((name, text) in files) {
                File(root, name).writeText(text)
            }
            val project = ProjectBuilder.builder().withProjectDir(root).build()
            project.registerSourceScanTasks()
            val task = project.tasks.named("checkSpdxHeaders").get() as CheckSpdxHeadersTask
            return try {
                task.check()
                emptyList()
            } catch (e: GradleException) {
                e.message
                    .orEmpty()
                    .lineSequence()
                    .filter { it.trimStart().startsWith("- ") }
                    .map { it.trim().removePrefix("- ") }
                    .toList()
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
