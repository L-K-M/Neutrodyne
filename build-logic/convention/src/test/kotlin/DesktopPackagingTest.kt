// SPDX-License-Identifier: Unlicense
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Packaging helpers of 11 (Packaging pipeline, Links and files from the OS):
 * `writeDesktopRuntimeClasspath` names each JAR as Compose mangles it into the image, so the
 * same-named JARs several KMP modules produce stay distinguishable; and the hicolor icon
 * mapping fails loudly on a missing or icon-less `icons/png` instead of silently shipping a
 * package without icons.
 */
class DesktopPackagingTest {
    @Test
    fun `the packaging matrix resolves the official JVM os names`() {
        // The CI runners report the versioned JVM names, not the wire ids.
        assertEquals(
            DesktopPackagingTarget.WINDOWS_X64,
            desktopPackagingTargetOf("Windows 11", "amd64"),
        )
        assertEquals(
            DesktopPackagingTarget.WINDOWS_X64,
            desktopPackagingTargetOf("Windows Server 2025", "amd64"),
        )
        assertEquals(
            DesktopPackagingTarget.MACOS_ARM64,
            desktopPackagingTargetOf("Mac OS X", "aarch64"),
        )
        assertEquals(
            DesktopPackagingTarget.LINUX_X64,
            desktopPackagingTargetOf("Linux", "amd64"),
        )
        assertEquals(
            DesktopPackagingTarget.LINUX_ARM64,
            desktopPackagingTargetOf("Linux", "aarch64"),
        )
    }

    @Test
    fun `hosts outside the packaging matrix resolve to no target`() {
        // An Intel Mac and a Windows arm64 laptop are dev hosts; an unrecognized os.name
        // is never guessed into a target.
        assertNull(desktopPackagingTargetOf("Mac OS X", "x86_64"))
        assertNull(desktopPackagingTargetOf("Windows 11", "aarch64"))
        assertNull(desktopPackagingTargetOf("FreeBSD", "amd64"))
        assertNull(desktopPackagingTargetOf("SunOS", "x86"))
    }

    @Test
    fun `setupBundledRuntime unpacks a windows zip and accepts bin java exe`() {
        val root = Files.createTempDirectory("bundled-runtime").toFile()
        try {
            // Temurin's Windows archive is a ZIP stored under the extensionless name
            // "archive"; a Windows JDK carries bin/java.exe, not bin/java.
            val archive = zipArchive("jdk-25.0.4.1+1/bin/java.exe" to byteArrayOf(1))
            val task = setupRuntimeTask(root, archive)

            task.setup()

            assertTrue(File(root, "jdk/bin/java.exe").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `setupBundledRuntime unpacks a tar gz and accepts bin java`() {
        val root = Files.createTempDirectory("bundled-runtime").toFile()
        try {
            val archive = tarGzArchive("jdk-25.0.4.1+1/bin/java" to byteArrayOf(1))
            val task = setupRuntimeTask(root, archive)

            task.setup()

            assertTrue(File(root, "jdk/bin/java").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `setupBundledRuntime unpacks a macos tar gz under Contents Home`() {
        val root = Files.createTempDirectory("bundled-runtime").toFile()
        try {
            val archive =
                tarGzArchive("jdk-25.0.4.1+1/Contents/Home/bin/java" to byteArrayOf(1))
            val task = setupRuntimeTask(root, archive)

            task.setup()

            assertTrue(File(root, "jdk/Contents/Home/bin/java").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `setupBundledRuntime rejects an archive that is neither zip nor gzip`() {
        val root = Files.createTempDirectory("bundled-runtime").toFile()
        try {
            val task = setupRuntimeTask(root, byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 1, 2))

            assertThrows<GradleException> { task.setup() }
        } finally {
            root.deleteRecursively()
        }
    }

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
    fun `the classpath manifest hash survives jar recompression`() {
        val root = Files.createTempDirectory("classpath-manifest").toFile()
        try {
            // The macOS packaging pipeline rewrites the shipped jar's container
            // bytes after the name is mangled (entry order and compression
            // change); the manifest pins entry names + content, not bytes. Two
            // jars with identical content but different container bytes must
            // carry the same canonical hash — that is what lets the image's
            // repacked jar match the classpath row.
            val original =
                File(root, "one.jar").apply {
                    writeBytes(zipArchive("a/x.txt" to byteArrayOf(1), "b/y.txt" to byteArrayOf(2)))
                }
            val repacked =
                File(root, "two.jar").apply {
                    val entries =
                        java.util.zip.ZipFile(original).use { z ->
                            z
                                .entries()
                                .toList()
                                .reversed()
                                .map { it.name to z.getInputStream(it).readBytes() }
                        }
                    writeBytes(zipArchive(*entries.toTypedArray()))
                }
            assert(sha256Hex(repacked.readBytes()) != sha256Hex(original.readBytes())) {
                "repacked jar kept identical bytes"
            }

            val task = classpathManifestTask(root, original, repacked)
            val out = File(root, "manifest.txt")
            task.write()

            val lines = out.readLines()
            assertEquals(2, lines.size)
            // Same canonical hash, different mangled names.
            assertEquals(1, lines.map { it.substringBefore("  ") }.toSet().size)
            assertEquals(
                lines.map { it.substringAfter("  ") }.toSet(),
                setOf(imageJarName(original), imageJarName(repacked)),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the classpath manifest is duplicated under the install kind's name`() {
        // installKind sits in build-info.properties inside the own-app jar, so
        // every packaging invocation's desktopApp jar mangles differently and
        // one shared manifest cannot cover all of a matrix job's packages
        // (nightly 37868304885: the deb and rpm jars missed the manifest the
        // tar.gz invocation wrote). The checker resolves the image's
        // runtime-classpath-<kind>.txt, so the task must write it.
        val root = Files.createTempDirectory("classpath-manifest").toFile()
        try {
            val jar = jar(File(root, "j"), "one.jar", 1)
            val task = classpathManifestTask(root, jar)

            task.write()

            val shared = File(root, "manifest.txt").readText()
            val kind = File(root, "runtime-classpath-deb.txt").readText()
            assertEquals(shared, kind)
            assert(kind.contains("  ${imageJarName(jar)}\n")) { kind }
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
        task.installKind.set("deb")
        task.manifest.fileValue(File(root, "manifest.txt"))
        task.kindManifest.fileValue(File(root, "runtime-classpath-deb.txt"))
        return task
    }

    /**
     * A `setupBundledRuntime` fixture with the archive already at its output path and a
     * matching `sha256`, so `setup()` verifies and unpacks without downloading. The
     * extensionless name mirrors the plugin's `bundled-runtime/<target>/archive`.
     */
    private fun setupRuntimeTask(
        root: File,
        archiveBytes: ByteArray,
    ): SetupBundledRuntime {
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        val task =
            project.tasks
                .register("setupBundledRuntime", SetupBundledRuntime::class.java)
                .get()
        val archiveFile = File(root, "archive")
        archiveFile.writeBytes(archiveBytes)
        task.archiveUrl.set("https://example.invalid/runtime")
        task.sha256.set(sha256Hex(archiveBytes))
        task.archive.fileValue(archiveFile)
        task.home.fileValue(File(root, "jdk"))
        return task
    }

    private fun zipArchive(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun tarGzArchive(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gzip ->
            entries.forEach { (name, bytes) ->
                gzip.write(tarHeader(name, bytes.size))
                gzip.write(bytes)
                gzip.write(ByteArray(tarPadding(bytes.size)))
            }
            gzip.write(ByteArray(2 * TAR_BLOCK_BYTES))
        }
        return out.toByteArray()
    }

    /** A minimal ustar header: enough for tarTree to see one regular file entry. */
    private fun tarHeader(
        name: String,
        size: Int,
    ): ByteArray {
        val header = ByteArray(TAR_BLOCK_BYTES)

        fun ascii(
            value: String,
            offset: Int,
        ) = value.toByteArray(Charsets.US_ASCII).copyInto(header, offset)

        ascii(name, 0)
        ascii("0000644", 100) // mode
        ascii("0000000", 108) // uid
        ascii("0000000", 116) // gid
        ascii("%011o".format(size), 124) // size
        ascii("%011o".format(0), 136) // mtime
        header[156] = '0'.code.toByte() // typeflag: regular file
        ascii("ustar\u0000", 257) // magic
        ascii("00", 263) // ustar version
        // The checksum field reads as spaces while it is computed, then six octal digits,
        // a NUL and a space (POSIX tar).
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        val sum = header.sumOf { it.toInt() and 0xFF }
        ascii("%06o\u0000 ".format(sum), 148)
        return header
    }

    private fun tarPadding(size: Int): Int = (TAR_BLOCK_BYTES - size % TAR_BLOCK_BYTES) % TAR_BLOCK_BYTES

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        private const val TAR_BLOCK_BYTES = 512
    }
}
