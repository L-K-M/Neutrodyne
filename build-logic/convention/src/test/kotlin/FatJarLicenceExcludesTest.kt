// SPDX-License-Identifier: Unlicense
import org.gradle.api.file.FileVisitDetails
import org.gradle.api.file.FileVisitor
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The fat JAR repacks every dependency jar: same-named licence files would collide (first one
 * wins), so [FAT_JAR_LICENCE_EXCLUDES] must strip them in every spelling upstream jars use —
 * upper- and lowercase META-INF names, lowercase META-INF licence directories, and jar-root
 * files — while [ThirdPartyNoticesTask] keeps each jar's real copy.
 */
class FatJarLicenceExcludesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `licence entries in every spelling drop out of the repacked jars`() {
        val jar =
            jar(
                "META-INF/LICENSE" to "x",
                "META-INF/LICENSE.txt" to "x",
                "META-INF/NOTICE" to "x",
                "META-INF/COPYING" to "x",
                "META-INF/license" to "x",
                "META-INF/notice.md" to "x",
                "META-INF/copying" to "x",
                "META-INF/license/third-party.txt" to "x",
                "META-INF/notice/more.txt" to "x",
                "META-INF/copying/deep.txt" to "x",
                "LICENSE" to "x",
                "LICENSE.txt" to "x",
                "NOTICE" to "x",
                "COPYING" to "x",
                "META-INF/MANIFEST.MF" to "x",
                "org/example/lib/Foo.class" to "x",
            )

        assertEquals(
            setOf("META-INF/MANIFEST.MF", "org/example/lib/Foo.class"),
            filteredEntries(jar),
        )
    }

    /** Entry paths of [jar] after the fat JAR's licence excludes, as the zipTree sees them. */
    private fun filteredEntries(jar: File): Set<String> {
        val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder("project")).build()
        val names = sortedSetOf<String>()
        project
            .zipTree(jar)
            .matching { exclude(*FAT_JAR_LICENCE_EXCLUDES) }
            .visit(
                object : FileVisitor {
                    override fun visitDir(dirDetails: FileVisitDetails) = Unit

                    override fun visitFile(fileDetails: FileVisitDetails) {
                        names += fileDetails.path
                    }
                },
            )
        return names
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
}
