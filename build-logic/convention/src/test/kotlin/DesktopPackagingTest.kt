// SPDX-License-Identifier: Unlicense
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Packaging helpers of 11 (Packaging pipeline, Links and files from the OS):
 * `writeDesktopRuntimeClasspath` names each JAR as Compose mangles it into the image, so the
 * same-named JARs several KMP modules produce stay distinguishable; and the hicolor icon
 * mapping fails loudly on a missing or icon-less `icons/png` instead of silently shipping a
 * package without icons.
 */
class DesktopPackagingTest {
    @Test
    fun `the classpath manifest mangles same-named jars like the image does`() {
        val root = Files.createTempDirectory("classpath-manifest").toFile()
        try {
            val first = jar(File(root, "a"), "dup.jar", 1)
            val second = jar(File(root, "b"), "dup.jar", 2)
            val task = classpathManifestTask(root, first, second)
            val out = File(root, "manifest.txt")

            task.write()

            val names = out.readLines().map { it.substringAfterLast("  ") }
            assertEquals(2, names.size)
            names.forEach {
                assert(Regex("dup-[0-9a-f]+\\.jar").matches(it)) { it }
            }
            // The MD5 content suffix differs: both sources survive as distinct image entries.
            assertEquals(names.toSet(), setOf(imageJarName(first), imageJarName(second)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the classpath manifest writes one sha256 name line per distinct jar`() {
        val root = Files.createTempDirectory("classpath-manifest").toFile()
        try {
            val first = jar(File(root, "b"), "two.jar", 2)
            val second = jar(File(root, "a"), "one.jar", 1)
            // An identical twin (same bytes, same name) is one image entry, not two.
            val twin = jar(File(root, "c"), "one.jar", 1)
            val task = classpathManifestTask(root, first, second, twin)
            val out = File(root, "manifest.txt")

            task.write()

            val lines = out.readLines()
            assertEquals(2, lines.size)
            lines.forEach { assert(Regex("[0-9a-f]{64}  .+-[0-9a-f]+\\.jar").matches(it)) { it } }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the hicolor mapping fails when icons png is missing or empty`() {
        val root = Files.createTempDirectory("hicolor").toFile()
        try {
            assertThrows<GradleException> { hicolorIconEntries(File(root, "absent")) }
            assertThrows<GradleException> { hicolorIconEntries(File(root, "empty").apply { mkdirs() }) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the hicolor mapping reads only neutrodyne-sized pngs`() {
        val root = Files.createTempDirectory("hicolor").toFile()
        try {
            val icons = File(root, "icons/png").apply { mkdirs() }
            File(icons, "neutrodyne-48.png").writeBytes(byteArrayOf(1))
            File(icons, "neutrodyne-256.png").writeBytes(byteArrayOf(2))
            // Harmless entries a checkout can carry: metadata files and stray directories.
            File(icons, ".DS_Store").writeBytes(byteArrayOf(3))
            File(icons, "backup~").writeBytes(byteArrayOf(4))
            File(icons, "subdir.png").mkdirs()

            val entries = hicolorIconEntries(icons)

            // Sorted by file name (lexicographic, so 256 precedes 48).
            assertEquals(
                listOf("neutrodyne-256.png" to "256", "neutrodyne-48.png" to "48"),
                entries.map { (file, size) -> file.name to size },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the hicolor mapping rejects a png outside the naming scheme`() {
        val root = Files.createTempDirectory("hicolor").toFile()
        try {
            val icons = File(root, "icons/png").apply { mkdirs() }
            File(icons, "tray.png").writeBytes(byteArrayOf(1))

            assertThrows<GradleException> { hicolorIconEntries(icons) }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun jar(
        dir: File,
        name: String,
        content: Int,
    ): File {
        dir.mkdirs()
        return File(dir, name).apply { writeBytes(byteArrayOf(content.toByte())) }
    }

    private fun classpathManifestTask(
        root: File,
        vararg jars: File,
    ): WriteDesktopRuntimeClasspath {
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        val task =
            project.tasks
                .register("writeDesktopRuntimeClasspath", WriteDesktopRuntimeClasspath::class.java)
                .get()
        task.runtimeClasspath.from(*jars)
        task.manifest.fileValue(File(root, "manifest.txt"))
        return task
    }
}
