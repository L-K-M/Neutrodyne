// SPDX-License-Identifier: Unlicense
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The fat JAR's notices task (01 Licensing): every dependency jar keeps its licence files under
 * `META-INF/licenses/<group>/<artifact>/`, and an entry name taken from a jar never writes
 * outside the task's output directory.
 */
class ThirdPartyNoticesTaskTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `licence files are kept per artifact`() {
        val out = generate(jar("META-INF/LICENSE.txt" to "Copyright (c) 2024 Example Authors"))

        assertEquals(
            "Copyright (c) 2024 Example Authors",
            File(out, "META-INF/licenses/org.example/lib/LICENSE.txt").readText(),
        )
    }

    @Test
    fun `a licence entry that climbs out of the output directory fails the task`() {
        // The legitimate entry first creates META-INF/licenses/org.example/lib/, which the
        // crafted name then climbs out of.
        val crafted =
            jar(
                "META-INF/LICENSE" to "Copyright (c) 2024 Example Authors",
                "META-INF/../../../../../escaped/NOTICE" to "written outside",
            )

        assertThrows<GradleException> { generate(crafted) }
        assertFalse(File(tmp.root, "out/escaped/NOTICE").exists(), "the crafted entry was written")
    }

    @Test
    fun `a licence entry that climbs out with backslashes fails the task`() {
        // Inert on Linux and macOS, but java.io.File on Windows splits on backslashes.
        val crafted = jar("META-INF/LICENSE\\..\\..\\..\\..\\..\\escaped/NOTICE" to "written outside")

        assertThrows<GradleException> { generate(crafted) }
    }

    private fun jar(vararg entries: Pair<String, String>): File =
        File(tmp.root, "lib-1.0.jar").apply {
            ZipOutputStream(outputStream()).use { zip ->
                for ((name, text) in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
        }

    /** Runs the task's action for one dependency jar and returns its output directory. */
    private fun generate(jar: File): File {
        val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder("project")).build()
        val definitionsFile = File(tmp.root, "aboutlibraries.json").apply { writeText(DEFINITIONS) }
        val out = File(tmp.root, "out/notices")
        project.tasks
            .register("generateThirdPartyNotices", ThirdPartyNoticesTask::class.java) {
                definitions.set(definitionsFile)
                artifactFiles.from(jar)
                coordinatesByFileName.put(jar.name, "org.example:lib")
                outputDirectory.set(out)
            }.get()
            .write()
        return out
    }

    private companion object {
        val DEFINITIONS =
            """
            {
              "libraries": [
                {"uniqueId": "org.example:lib", "name": "Example", "artifactVersion": "1.0", "licenses": ["Apache-2.0"]}
              ],
              "licenses": {
                "Apache-2.0": {"name": "Apache License 2.0", "spdxId": "Apache-2.0", "content": "Apache License text"}
              }
            }
            """.trimIndent()
    }
}
