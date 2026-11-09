// SPDX-License-Identifier: Unlicense
import org.gradle.api.GradleException
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.work.FileChange
import org.gradle.work.InputChanges
import org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

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
    fun `setupBundledRuntime preserves legal notice links from the tar archive`() {
        val root = Files.createTempDirectory("bundled-runtime-links").toFile()
        try {
            val notice = "Pinned runtime notice\n".toByteArray()
            val prefix = "jdk-25.0.4.1+1/"
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { gzip ->
                fun file(
                    name: String,
                    bytes: ByteArray,
                ) {
                    gzip.write(tarHeader(prefix + name, bytes.size))
                    gzip.write(bytes)
                    gzip.write(ByteArray(tarPadding(bytes.size)))
                }
                file("bin/java", byteArrayOf(1))
                file("legal/java.base/LICENSE", notice)
                gzip.write(
                    tarHeader(
                        prefix + "legal/java.desktop/LICENSE",
                        0,
                        '2',
                        "../java.base/LICENSE",
                    ),
                )
                gzip.write(
                    tarHeader(
                        prefix + "legal/java.net/LICENSE",
                        0,
                        '1',
                        prefix + "legal/java.base/LICENSE",
                    ),
                )
                gzip.write(ByteArray(2 * TAR_BLOCK_BYTES))
            }
            setupRuntimeTask(root, out.toByteArray()).setup()

            val base = File(root, "jdk/legal/java.base/LICENSE").toPath()
            val symbolic = File(root, "jdk/legal/java.desktop/LICENSE").toPath()
            val hard = File(root, "jdk/legal/java.net/LICENSE").toPath()
            assertTrue(Files.isSymbolicLink(symbolic), "Keep the archive's relative symbolic link")
            assertEquals(notice.toList(), Files.readAllBytes(symbolic).toList())
            assertEquals(notice.toList(), Files.readAllBytes(hard).toList())
            assertTrue(Files.isSameFile(base, hard), "Keep the archive's hard link to its notice")
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

    @Test
    fun `the classpath manifest survives jar repacking`() {
        val root = Files.createTempDirectory("classpath-repacking").toFile()
        try {
            val entries = listOf("A.class" to "CODE-A".toByteArray())
            val original = writeJar(root, "runtime.jar", zipBytes(entries))
            val repacked = writeJar(root, "repacked.jar", zipBytes(entries, byteArrayOf(0x34, 0x12, 0, 0)))
            val task = classpathManifestTask(root, original)

            task.write()

            assertTrue(!original.readBytes().contentEquals(repacked.readBytes()))
            assertEquals(
                "${CanonicalJarHash.of(repacked)}  ${imageJarName(original)}\n",
                File(root, "manifest.txt").readText(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `signature metadata cannot hide linkedit data`() = assertSignatureMutationBound(SignatureMutation.NON_TERMINAL)

    @Test
    fun `signature commands cannot hide header padding`() =
        assertSignatureMutationBound(SignatureMutation.EXTENDED_COMMAND)

    @Test
    fun `overlapping signature commands cannot splice data`() =
        assertSignatureMutationBound(SignatureMutation.OVERLAPPING)

    @Test
    fun overlappingTextSegmentBytesStayBound() = assertUnsupportedLayoutRaw("overlap_code_a")

    @Test
    fun changedBytesInAnOverlappingTextSegmentStayBound() = assertUnsupportedLayoutRaw("overlap_code_b")

    @Test
    fun embeddedNulSegmentNamesStayBound() = assertUnsupportedLayoutRaw("embedded_nul")

    @Test
    fun shortSegmentCommandsStayBoundWithoutThrowing() = assertUnsupportedLayoutRaw("short_segment")

    @Test
    fun unsignedSegmentOffsetsCannotWrapIntoTheFile() = assertUnsupportedLayoutRaw("unsigned_offset")

    private fun assertUnsupportedLayoutRaw(label: String) {
        val vectors = File(repoRoot(), "scripts/ci/tests/fixtures/malformed-macho-vectors.txt")
        val hex = vectors.readLines().single { it.startsWith("$label=") }.substringAfter('=')
        val data = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val root = Files.createTempDirectory("unsupported-macho").toFile()
        try {
            val file = writeJar(root, "input.jar", zipBytes(listOf("lib.dylib" to data)))
            val rawFingerprint = sha256Hex("lib.dylib\u0000${sha256Hex(data)}\n".toByteArray())
            assertEquals(rawFingerprint, CanonicalJarHash.of(file), label)
            val process =
                ProcessBuilder(
                    "python3",
                    File(repoRoot(), "scripts/ci/canonical-jar-hash.py").absolutePath,
                    file.absolutePath,
                ).redirectErrorStream(true)
                    .start()
            val output =
                process.inputStream
                    .bufferedReader()
                    .use { it.readText() }
                    .trim()
            assertEquals(0, process.waitFor(), output)
            assertEquals(rawFingerprint, output, "Kotlin/Python raw fallback: $label")
        } finally {
            root.deleteRecursively()
        }
    }

    private enum class SignatureMutation { NON_TERMINAL, EXTENDED_COMMAND, OVERLAPPING }

    private fun assertSignatureMutationBound(mutation: SignatureMutation) {
        val root = Files.createTempDirectory("signature-boundary").toFile()
        try {
            val original =
                thinMacho(
                    code = ByteArray(16),
                    sig = ByteArray(128) { 0x53 },
                    linkeditExtra = ByteArray(64) { 0x41 },
                    dataoff = 0x1040,
                )
            val changed = original.copyOf()
            val header = ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN)
            when (mutation) {
                SignatureMutation.NON_TERMINAL -> {
                    header.putInt(112, 0x1000)
                    changed.fill(0x45, 0x1000, 0x1040)
                    original.copyInto(changed, changed.size - 64, 0x1000, 0x1040)
                }

                SignatureMutation.EXTENDED_COMMAND -> {
                    header.putInt(108, 32).putInt(20, 104)
                    changed.fill(0x45, 120, 136)
                }

                SignatureMutation.OVERLAPPING -> {
                    header.putInt(16, 3).putInt(20, 104).putInt(116, 64)
                    header.position(120)
                    header
                        .putInt(0x1D)
                        .putInt(16)
                        .putInt(0x1040)
                        .putInt(64)
                    changed.fill(0x45, 0x1000, 0x1040)
                    original.copyInto(changed, changed.size - 64, 0x1000, 0x1040)
                }
            }
            val approved = writeJar(root, "approved.jar", zipBytes(listOf("lib.dylib" to original)))
            val malformed = writeJar(root, "malformed.jar", zipBytes(listOf("lib.dylib" to changed)))
            val hash = CanonicalJarHash.of(malformed)
            assertTrue(CanonicalJarHash.of(approved) != hash, "non-signature data must stay bound: $mutation")
            val script = File(repoRoot(), "scripts/ci/canonical-jar-hash.py")
            val process =
                ProcessBuilder("python3", script.absolutePath, malformed.absolutePath)
                    .redirectErrorStream(true)
                    .start()
            val output =
                process.inputStream
                    .bufferedReader()
                    .use { it.readText() }
                    .trim()
            assertEquals(0, process.waitFor(), output)
            assertEquals(hash, output, "Kotlin/Python malformed-signature parity: $mutation")
        } finally {
            root.deleteRecursively()
        }
    }

    // ----- canonical JAR identity (11 Image scan rules) ------------------------

    @Test
    fun `canonical jar hash binds content not container bytes`() {
        val root = Files.createTempDirectory("canonical-identity").toFile()
        try {
            val entries =
                listOf(
                    "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray(),
                    "A.class" to "CODE-A".toByteArray(),
                )
            val deflated = writeJar(root, "deflated.jar", zipBytes(entries))
            val recompressed =
                writeJar(root, "recompressed.jar", zipBytes(entries.reversed(), extra = "PAD".toByteArray()))
            val nonZip = writeJar(root, "plain.jar", byteArrayOf(1, 2, 3, 4))
            // A non-zip falls back to its raw bytes; recompression, reordering and
            // an extra field are packaging metadata and do not change identity.
            assertEquals(sha256Hex(nonZip.readBytes()), CanonicalJarHash.of(nonZip))
            assertEquals(CanonicalJarHash.of(deflated), CanonicalJarHash.of(recompressed))
            // Content and entry-set mutations change identity.
            val mutated = writeJar(root, "mutated.jar", zipBytes(listOf("A.class" to "CODE-B".toByteArray())))
            val added = writeJar(root, "added.jar", zipBytes(entries + ("B.class" to "CODE-B".toByteArray())))
            listOf(mutated, added).forEach {
                assertTrue(CanonicalJarHash.of(deflated) != CanonicalJarHash.of(it))
            }
            // Java's ZipOutputStream refuses duplicate names; the fixture is a
            // precomputed two-entry central directory with the same name.
            val duplicate =
                java.util.Base64.getDecoder().decode(
                    "UEsDBBQAAAAAAC+/Z11RvXuhBgAAAAYAAAAHAAAAQS5jbGFzc0NPREUtQVBLAwQUAAAAAAAvv2dd6+xyOAYAAAAGAAAABwAAAEEuY2xhc3NDT0RFLUJQSwECFAMUAAAAAAAvv2ddUb17oQYAAAAGAAAABwAAAAAAAAAAAAAAgAEAAAAAQS5jbGFzc1BLAQIUAxQAAAAAAC+/Z13r7HI4BgAAAAYAAAAHAAAAAAAAAAAAAACAASsAAABBLmNsYXNzUEsFBgAAAAACAAIAagAAAFYAAAAAAA==",
                )
            assertThrows<IllegalArgumentException> { CanonicalJarHash.of(writeJar(root, "dup.jar", duplicate)) }
            assertThrows<IllegalArgumentException> {
                CanonicalJarHash.of(
                    writeJar(root, "bad.jar", zipBytes(listOf("../evil.class" to "CODE".toByteArray()))),
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `canonical jar hash normalizes re-signed macho and fat natives`() {
        val root = Files.createTempDirectory("canonical-macho").toFile()
        try {
            val original = writeJar(root, "a.jar", zipBytes(listOf("lib.dylib" to thinMacho())))
            val resigned =
                writeJar(root, "b.jar", zipBytes(listOf("lib.dylib" to thinMacho(sig = "OTHER".toByteArray()))))
            val codeMutation =
                writeJar(root, "c.jar", zipBytes(listOf("lib.dylib" to thinMacho(code = "CODE-B".toByteArray()))))
            val linkeditMutation =
                writeJar(root, "d.jar", zipBytes(listOf("lib.dylib" to thinMacho(linkeditExtra = ByteArray(8) { 1 }))))
            val outside = thinMacho(sig = "A".repeat(16).toByteArray(), dataoff = 0x400, total = 0x2000)
            val outsideLinkedit = writeJar(root, "e.jar", zipBytes(listOf("lib.dylib" to outside)))
            assertEquals(CanonicalJarHash.of(original), CanonicalJarHash.of(resigned))
            listOf(codeMutation, linkeditMutation, outsideLinkedit).forEach {
                assertTrue(CanonicalJarHash.of(original) != CanonicalJarHash.of(it))
            }

            val slices = listOf(thinMacho(), thinMacho())

            fun fatJar(
                name: String,
                s: List<ByteArray>,
                t: Int,
            ) = writeJar(root, name, zipBytes(listOf("lib.dylib" to fatMacho(s, ByteArray(t), 12))))
            val fat = fatJar("f.jar", slices, 8)
            val resignedSlices = listOf(thinMacho(sig = "X".toByteArray()), thinMacho())
            val fatResigned = fatJar("g.jar", resignedSlices, 8)
            val paddingMutation = fatMacho(slices, ByteArray(8), 12)
            val off1 = ByteBuffer.wrap(paddingMutation).order(ByteOrder.BIG_ENDIAN).getInt(16)
            paddingMutation[off1 - 1] = 0xFF.toByte()
            val padding = writeJar(root, "h.jar", zipBytes(listOf("lib.dylib" to paddingMutation)))
            val trailing = fatJar("i.jar", slices, 7)
            assertEquals(CanonicalJarHash.of(fat), CanonicalJarHash.of(fatResigned))
            listOf(padding, trailing).forEach { assertTrue(CanonicalJarHash.of(fat) != CanonicalJarHash.of(it)) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `canonical jar hash matches the python checker`() {
        val root = Files.createTempDirectory("canonical-parity").toFile()
        try {
            val script = File(repoRoot(), "scripts/ci/canonical-jar-hash.py")
            assertTrue(script.isFile, "canonical-jar-hash.py not found")
            val vectors =
                listOf(
                    "plain.jar" to byteArrayOf(1, 2, 3, 4),
                    "zip.jar" to zipBytes(listOf("A.class" to "CODE-A".toByteArray())),
                    "macho.jar" to zipBytes(listOf("lib.dylib" to thinMacho())),
                    "fat.jar" to zipBytes(listOf("lib.dylib" to fatMacho(listOf(thinMacho(), thinMacho())))),
                )
            vectors.forEach { (name, bytes) ->
                val file = writeJar(root, name, bytes)
                val kotlin = CanonicalJarHash.of(file)
                val process =
                    ProcessBuilder("python3", script.absolutePath, file.absolutePath)
                        .redirectErrorStream(true)
                        .start()
                val out = process.inputStream.use { it.readBytes().decodeToString().trim() }
                assertEquals(0, process.waitFor(), out)
                assertEquals(kotlin, out, name)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the skiko extraction rule matches the pinned compose plugin`() {
        // AbstractJPackageTaskKt.unpackSkikoForCurrentOS (compose-gradle-plugin 1.12.1).
        assertEquals(setOf("libskiko-macos-arm64.dylib"), skikoExtractedNames("macos", "arm64"))
        assertEquals(setOf("libskiko-linux-x64.so"), skikoExtractedNames("linux", "x64"))
        assertEquals(setOf("libskiko-linux-arm64.so"), skikoExtractedNames("linux", "arm64"))
        assertEquals(
            setOf("skiko-windows-x64.dll", "icudtl.dat"),
            skikoExtractedNames("windows", "x64"),
        )
        assertEquals(emptySet(), skikoExtractedNames("freebsd", "x64"))
    }

    @Test
    fun `the skiko stub derivation binds the repacked content and extracted natives`() {
        val root = Files.createTempDirectory("skiko-stub").toFile()
        try {
            val native = "libskiko-linux-x64.so"
            val entries =
                listOf(
                    "META-INF/" to ByteArray(0),
                    "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray(),
                    native to "NATIVE-X64".toByteArray(),
                    "$native.sha256" to "0".repeat(64).toByteArray(),
                    "libskiko-linux-arm64.so" to "NATIVE-ARM64".toByteArray(),
                )
            val source =
                writeJar(
                    root,
                    "skiko-awt-runtime-linux-x64-0.9.30.jar",
                    zipBytes(entries),
                )

            val derivation = CanonicalJarHash.stubOf(source, setOf(native))

            // The expected stub hash is the canonical identity of the source minus the
            // extracted entries: exactly Compose's repacked stub.
            val remaining =
                entries.filter { it.first != native && it.first != "$native.sha256" }
            val stub = writeJar(root, "stub.jar", zipBytes(remaining))
            assertEquals(CanonicalJarHash.of(stub), derivation.expectedHash)
            // The extracted natives are recorded by basename and normalized content hash.
            assertEquals(listOf(native, "$native.sha256"), derivation.extracted.map { it.first })
            assertEquals(
                sha256Hex("NATIVE-X64".toByteArray()),
                derivation.extracted.single { it.first == native }.second,
            )
            assertEquals(
                sha256Hex("0".repeat(64).toByteArray()),
                derivation.extracted.single { it.first == "$native.sha256" }.second,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the skiko stub derivation matches the python checker`() {
        val root = Files.createTempDirectory("skiko-parity").toFile()
        try {
            val script = File(repoRoot(), "scripts/ci/canonical-jar-hash.py")
            assertTrue(script.isFile, "canonical-jar-hash.py not found")
            val native = "libskiko-linux-x64.so"
            // A real Mach-O native: the extracted file is re-signed by packaging, so the
            // recorded hash must be the normalized content hash, not the raw bytes.
            val entries =
                listOf(
                    "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray(),
                    native to thinMacho(),
                    "$native.sha256" to "0".repeat(64).toByteArray(),
                    "libskiko-linux-arm64.so" to thinMacho(code = "CODE-B".toByteArray()),
                )
            val source =
                writeJar(
                    root,
                    "skiko-awt-runtime-linux-x64-0.9.30.jar",
                    zipBytes(entries),
                )
            val derivation = CanonicalJarHash.stubOf(source, setOf(native))
            val remaining = entries.filter { it.first != native && it.first != "$native.sha256" }
            val stub = writeJar(root, "stub.jar", zipBytes(remaining))

            val stubOut =
                ProcessBuilder("python3", script.absolutePath, stub.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                    .let { process ->
                        val text = process.inputStream.use { it.readBytes().decodeToString().trim() }
                        assertEquals(0, process.waitFor(), text)
                        text
                    }
            assertEquals(derivation.expectedHash, stubOut)

            val extracted = File(root, native)
            extracted.writeBytes(thinMacho(sig = "OTHER".toByteArray()))
            val contentOut =
                ProcessBuilder("python3", script.absolutePath, "--content", extracted.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                    .let { process ->
                        val text = process.inputStream.use { it.readBytes().decodeToString().trim() }
                        assertEquals(0, process.waitFor(), text)
                        text
                    }
            assertEquals(
                derivation.extracted.single { it.first == native }.second,
                contentOut,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the classpath manifest binds the skiko stub record`() {
        val root = Files.createTempDirectory("skiko-manifest").toFile()
        try {
            val native = "libskiko-linux-x64.so"
            val entries =
                listOf(
                    "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray(),
                    native to "NATIVE-X64".toByteArray(),
                    "$native.sha256" to "0".repeat(64).toByteArray(),
                )
            val source =
                writeJar(
                    root,
                    "skiko-awt-runtime-linux-x64-0.9.30.jar",
                    zipBytes(entries),
                )
            val task = classpathManifestTask(root, source)

            task.write()

            val stubFile = File(root, "manifest-stubs.txt")
            val lines = stubFile.readLines()
            assertEquals(1, lines.size)
            val parts = lines.single().split("  ")
            assertEquals(3, parts.size)
            assertEquals("skiko-awt-runtime-linux-x64-0.9.30.jar", parts[1])
            // The expected stub hash is the source minus the extracted pair.
            val remaining = entries.filter { it.first != native && it.first != "$native.sha256" }
            assertEquals(
                CanonicalJarHash.of(writeJar(root, "stub.jar", zipBytes(remaining))),
                parts[0],
            )
            // The extracted files are recorded as basename=normalized-hash, comma-separated.
            assertEquals(2, parts[2].split(",").size)
            assertTrue(parts[2].startsWith("$native=${sha256Hex("NATIVE-X64".toByteArray())}"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the macos stub record omits the foreign-architecture native from the expected stub`() {
        val root = Files.createTempDirectory("skiko-trim").toFile()
        try {
            // The pinned macos-arm64 artifact's measured layout (skiko 0.150.1):
            // the target's native pair plus the other macOS architecture's pair.
            val native = "libskiko-macos-arm64.dylib"
            val foreign = "libskiko-macos-x64.dylib"
            val entries =
                listOf(
                    "META-INF/" to ByteArray(0),
                    "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray(),
                    native to "NATIVE-ARM64".toByteArray(),
                    "$native.sha256" to "0".repeat(64).toByteArray(),
                    foreign to "NATIVE-X64".toByteArray(),
                    "$foreign.sha256" to "1".repeat(64).toByteArray(),
                )
            val source =
                writeJar(
                    root,
                    "skiko-awt-runtime-macos-arm64-0.150.1.jar",
                    zipBytes(entries),
                )

            classpathManifestTask(root, source).write()

            val parts = File(root, "manifest-stubs.txt").readLines().single().split("  ")
            // The expected stub is the source minus the extracted pair AND the
            // foreign pair: the derived stub ships no other-architecture native.
            val expected =
                entries.filter { !it.first.startsWith(native) && !it.first.startsWith(foreign) }
            assertEquals(
                CanonicalJarHash.of(writeJar(root, "expected.jar", zipBytes(expected))),
                parts[0],
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the skiko foreign-native rule matches the pinned upstream artifacts`() {
        // Measured 2026-11-09 on the four skiko-awt-runtime 0.150.1 artifacts: only the
        // macOS jar carries another matrix architecture's native beside the target's;
        // the linux and windows jars ship exactly their extracted set.
        assertEquals(setOf("libskiko-macos-x64.dylib"), skikoForeignNames("macos", "arm64"))
        assertEquals(setOf("libskiko-macos-arm64.dylib"), skikoForeignNames("macos", "x64"))
        assertEquals(emptySet(), skikoForeignNames("linux", "x64"))
        assertEquals(emptySet(), skikoForeignNames("linux", "arm64"))
        assertEquals(emptySet(), skikoForeignNames("windows", "x64"))
    }

    @Test
    fun `the skiko stub derivation removes the declared foreign natives`() {
        val root = Files.createTempDirectory("skiko-removed").toFile()
        try {
            val native = "libskiko-macos-arm64.dylib"
            val foreign = "libskiko-macos-x64.dylib"
            val entries =
                listOf(
                    "META-INF/" to ByteArray(0),
                    "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray(),
                    native to "NATIVE-ARM64".toByteArray(),
                    "$native.sha256" to "0".repeat(64).toByteArray(),
                    foreign to "NATIVE-X64".toByteArray(),
                    "$foreign.sha256" to "1".repeat(64).toByteArray(),
                )
            val source =
                writeJar(
                    root,
                    "skiko-awt-runtime-macos-arm64-0.150.1.jar",
                    zipBytes(entries),
                )

            val derivation =
                CanonicalJarHash.stubOf(source, setOf(native), setOf(foreign))

            // The expected stub keeps exactly the non-native entries: the foreign pair
            // is gone with its .sha256 sibling, like the extracted pair before it.
            val remaining = entries.take(2)
            assertEquals(
                CanonicalJarHash.of(writeJar(root, "stub.jar", zipBytes(remaining))),
                derivation.expectedHash,
            )
            assertEquals(listOf(native, "$native.sha256"), derivation.extracted.map { it.first })
            assertEquals(
                listOf(foreign, "$foreign.sha256"),
                derivation.removed.map { it.first },
            )
            assertEquals(
                sha256Hex("NATIVE-X64".toByteArray()),
                derivation.removed.single { it.first == foreign }.second,
            )
            // The removal is the extraction's mirror: refusing a name claimed by both.
            assertFailsWith<IllegalArgumentException> {
                CanonicalJarHash.stubOf(source, setOf(native, foreign), setOf(foreign))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the classpath manifest records the removed skiko entries`() {
        val root = Files.createTempDirectory("skiko-removed-manifest").toFile()
        try {
            val native = "libskiko-macos-arm64.dylib"
            val foreign = "libskiko-macos-x64.dylib"
            val macEntries =
                listOf(
                    "META-INF/" to ByteArray(0),
                    "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray(),
                    native to "NATIVE-ARM64".toByteArray(),
                    "$native.sha256" to "0".repeat(64).toByteArray(),
                    foreign to "NATIVE-X64".toByteArray(),
                    "$foreign.sha256" to "1".repeat(64).toByteArray(),
                )
            val macSource =
                writeJar(root, "skiko-awt-runtime-macos-arm64-0.150.1.jar", zipBytes(macEntries))
            val linuxEntries =
                listOf(
                    "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray(),
                    "libskiko-linux-x64.so" to "NATIVE-X64".toByteArray(),
                    "libskiko-linux-x64.so.sha256" to "0".repeat(64).toByteArray(),
                )
            val linuxSource =
                writeJar(root, "skiko-awt-runtime-linux-x64-0.150.1.jar", zipBytes(linuxEntries))

            classpathManifestTask(root, macSource, linuxSource).write()

            val lines = File(root, "manifest-stubs.txt").readLines()
            assertEquals(2, lines.size)
            val mac = lines.single { it.contains("macos-arm64") }.split("  ")
            assertEquals(4, mac.size)
            assertTrue(mac[3].startsWith("removed:libskiko-macos-x64.dylib="))
            // The trimmed expectation is the source minus both native pairs.
            assertEquals(
                CanonicalJarHash.of(writeJar(root, "mac-stub.jar", zipBytes(macEntries.take(2)))),
                mac[0],
            )
            // A target whose upstream jar carries no foreign native keeps the
            // three-field record: the trim is a no-op there, not a hidden change.
            val linux = lines.single { it.contains("linux-x64") }.split("  ")
            assertEquals(3, linux.size)
            assertEquals(
                CanonicalJarHash.of(
                    writeJar(
                        root,
                        "linux-stub.jar",
                        zipBytes(linuxEntries.filter { !it.first.startsWith("libskiko-linux-x64.so") }),
                    ),
                ),
                linux[0],
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the skiko stub trim rewrites only the foreign natives`() {
        val root = Files.createTempDirectory("skiko-trim-fn").toFile()
        try {
            val image = File(root, "Neutrodyne.app")
            val appDir = File(image, "Contents/app")
            appDir.mkdirs()
            val native = "libskiko-macos-arm64.dylib"
            val foreign = "libskiko-macos-x64.dylib"
            val entries =
                listOf(
                    "META-INF/" to ByteArray(0),
                    "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray(),
                    native to "NATIVE-ARM64".toByteArray(),
                    "$native.sha256" to "0".repeat(64).toByteArray(),
                    foreign to "NATIVE-X64".toByteArray(),
                    "$foreign.sha256" to "1".repeat(64).toByteArray(),
                )
            val source =
                writeJar(root, "skiko-awt-runtime-macos-arm64-0.150.1.jar", zipBytes(entries))
            // Compose's repack: the extracted pair out of the jar, into the app dir.
            val stubBytes = zipBytes(entries.filter { !it.first.startsWith(native) })
            val stub = writeJar(appDir, "skiko-awt-runtime-macos-arm64-0.150.1-cafe1.jar", stubBytes)
            File(appDir, native).writeBytes("NATIVE-ARM64".toByteArray())
            File(appDir, "$native.sha256").writeBytes("0".repeat(64).toByteArray())
            var resigned = 0

            val outcome =
                trimSkikoStub(image, source, "macos", "arm64") { resigned++ }

            assertEquals(stub, outcome.stubFile)
            assertEquals(listOf(foreign, "$foreign.sha256"), outcome.removed)
            assertTrue(outcome.afterBytes < outcome.beforeBytes)
            assertEquals(1, resigned)
            val trimmed = CanonicalJarHash.stubOf(source, setOf(native), setOf(foreign))
            assertEquals(trimmed.expectedHash, CanonicalJarHash.of(stub))
            // The extracted native beside the stub keeps its byte identity.
            assertEquals("NATIVE-ARM64", File(appDir, native).readText())
            // Re-running is a no-op that does not reseal.
            val rerun = trimSkikoStub(image, source, "macos", "arm64") { resigned++ }
            assertEquals(emptyList(), rerun.removed)
            assertEquals(outcome.afterBytes, rerun.afterBytes)
            assertEquals(1, resigned)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the skiko stub trim refuses a stub that is not the source's repack`() {
        val root = Files.createTempDirectory("skiko-trim-evil").toFile()
        try {
            val image = File(root, "Neutrodyne.app")
            val appDir = File(image, "Contents/app")
            appDir.mkdirs()
            val native = "libskiko-macos-arm64.dylib"
            val foreign = "libskiko-macos-x64.dylib"
            val entries =
                listOf(
                    "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray(),
                    native to "NATIVE-ARM64".toByteArray(),
                    "$native.sha256" to "0".repeat(64).toByteArray(),
                    foreign to "NATIVE-X64".toByteArray(),
                    "$foreign.sha256" to "1".repeat(64).toByteArray(),
                )
            val source =
                writeJar(root, "skiko-awt-runtime-macos-arm64-0.150.1.jar", zipBytes(entries))
            // A stub with an added class entry: the malicious-removal/addition case.
            val evil =
                zipBytes(
                    entries.filter { !it.first.startsWith(native) } +
                        ("org/unchecked/Surprise.class" to "EVIL".toByteArray()),
                )
            writeJar(appDir, "skiko-awt-runtime-macos-arm64-0.150.1-bad1.jar", evil)

            val failure =
                assertFailsWith<GradleException> {
                    trimSkikoStub(image, source, "macos", "arm64") {}
                }
            assertTrue(
                failure.message!!.contains("is not Compose's repack"),
                failure.message,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the skiko stub trim refuses a source without the declared foreign native`() {
        val root = Files.createTempDirectory("skiko-trim-missing").toFile()
        try {
            val image = File(root, "Neutrodyne.app")
            val appDir = File(image, "Contents/app")
            appDir.mkdirs()
            val native = "libskiko-macos-arm64.dylib"
            // A future macos artifact that ships only its own arch: the declared
            // foreign entry no longer exists, so no arbitrary removal may happen.
            val entries =
                listOf(
                    "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray(),
                    native to "NATIVE-ARM64".toByteArray(),
                    "$native.sha256" to "0".repeat(64).toByteArray(),
                )
            val source =
                writeJar(root, "skiko-awt-runtime-macos-arm64-0.160.0.jar", zipBytes(entries))

            val failure =
                assertFailsWith<GradleException> {
                    trimSkikoStub(image, source, "macos", "arm64") {}
                }
            assertTrue(
                failure.message!!.contains("no longer carries the foreign native"),
                failure.message,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the skiko stub trim refuses a stub that would keep a native`() {
        val root = Files.createTempDirectory("skiko-trim-leftover").toFile()
        try {
            val image = File(root, "Neutrodyne.app")
            val appDir = File(image, "Contents/app")
            appDir.mkdirs()
            val native = "libskiko-macos-arm64.dylib"
            val foreign = "libskiko-macos-x64.dylib"
            // A source that also carries a cross-OS native the rule does not name:
            // the derived stub would ship it, so the trim fails instead.
            val entries =
                listOf(
                    "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray(),
                    native to "NATIVE-ARM64".toByteArray(),
                    "$native.sha256" to "0".repeat(64).toByteArray(),
                    foreign to "NATIVE-X64".toByteArray(),
                    "$foreign.sha256" to "1".repeat(64).toByteArray(),
                    "libskiko-linux-x64.so" to "NATIVE-LINUX".toByteArray(),
                )
            val source =
                writeJar(root, "skiko-awt-runtime-macos-arm64-0.150.1.jar", zipBytes(entries))

            val failure =
                assertFailsWith<GradleException> {
                    trimSkikoStub(image, source, "macos", "arm64") {}
                }
            assertTrue(
                failure.message!!.contains("would still carry the native entry"),
                failure.message,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the skiko stub trim is a no-op for a target without foreign natives`() {
        val root = Files.createTempDirectory("skiko-trim-linux").toFile()
        try {
            val image = File(root, "Neutrodyne")
            val appDir = File(image, "lib/app")
            appDir.mkdirs()
            val native = "libskiko-linux-x64.so"
            val entries =
                listOf(
                    "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray(),
                    native to "NATIVE-X64".toByteArray(),
                    "$native.sha256" to "0".repeat(64).toByteArray(),
                )
            val source =
                writeJar(root, "skiko-awt-runtime-linux-x64-0.150.1.jar", zipBytes(entries))
            val stubBytes = zipBytes(entries.filter { !it.first.startsWith(native) })
            val stub = writeJar(appDir, "skiko-awt-runtime-linux-x64-0.150.1-feed2.jar", stubBytes)
            val before = stub.readBytes()

            val outcome = trimSkikoStub(image, source, "linux", "x64") { fail("no reseal off macOS") }

            assertEquals(emptyList(), outcome.removed)
            assertTrue(before.contentEquals(stub.readBytes()))
        } finally {
            root.deleteRecursively()
        }
    }

    // ----- the image's native/ and licenses/ payloads (11 Resources layout) -----

    @Test
    fun `the ffmpeg image filter keeps versioned sonames and the two build records`() {
        // The metadata the engine and the image scan read beside the libraries.
        for (record in listOf("ffmpeg-layout.json", "ffmpeg-license.txt")) {
            assertTrue(ffmpegImageEntryAllowed("macos", record), record)
        }
        // The versioned sonames, each under the subdir its OS installs them to.
        for (
        (os, path) in
        listOf(
            "windows" to "bin/avcodec-63.dll",
            "macos" to "lib/libavutil.61.dylib",
            "linux" to "lib/libswresample.so.7",
        )
        ) {
            assertTrue(ffmpegImageEntryAllowed(os, path), "$os $path")
        }
        // Everything else stays out: unversioned links, import and static libraries,
        // headers, the build's own records and anything nested below the soname dir.
        for (
        (os, path) in
        listOf(
            "windows" to "lib/avcodec-63.dll", // DLLs live in bin, never lib
            "windows" to "bin/avcodec.lib",
            "windows" to "bin/avcodec-63.lib",
            "windows" to "bin/avcodec.dll",
            "macos" to "lib/libavutil.dylib",
            "macos" to "lib/libavutil.a",
            "macos" to "bin/libavutil.61.dylib",
            "linux" to "lib/libavutil.so",
            "linux" to "lib/libavutil.so.61.0.100",
            "linux" to "bin/libavutil.so.61",
            "macos" to "ffmpeg-build.txt",
            "macos" to "ffmpeg-config.h",
            "macos" to "ffmpeg-protocols.txt",
            "macos" to "ffmpeg-components.txt",
            "macos" to "ffmpeg-sizes.txt",
            "macos" to "include/libavutil/version.h",
            "macos" to "lib/pkgconfig/libavutil.61.dylib",
            "macos" to "ffmpeg-layout.json/payload",
        )
        ) {
            assertFalse(ffmpegImageEntryAllowed(os, path), "$os $path")
        }
    }

    @Test
    fun `the staged natives check binds every layout soname per target`() {
        for (target in DesktopPackagingTarget.entries) {
            val root = Files.createTempDirectory("staged-natives").toFile()
            try {
                stageCompleteNatives(root, target)
                assertEquals(emptyList(), stagedNativeProblems(target, root), target.id)

                // A soname the layout records but the tree lacks is reported, with the
                // upstream name a replacement build would carry.
                val sonames = stagedSonames(target)
                File(root, sonames.first()).delete()
                val problems = stagedNativeProblems(target, root)
                assertTrue(
                    problems.any { it.contains(sonames.first()) },
                    "${target.id}: ${problems.joinToString()}",
                )
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `the staged natives check fails closed on an empty tree`() {
        for (target in DesktopPackagingTarget.entries) {
            val root = Files.createTempDirectory("staged-natives").toFile()
            try {
                val problems = stagedNativeProblems(target, root)
                assertTrue(
                    problems.any { it.contains("ffmpeg-layout.json") } &&
                        problems.any { it.contains("native/ndmedia") } &&
                        problems.any { it.contains("licenses/THIRD_PARTY_NOTICES.md") },
                    "${target.id}: ${problems.joinToString()}",
                )
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `the staged natives check rejects a layout without a library entry`() {
        val target = DesktopPackagingTarget.MACOS_ARM64
        val root = Files.createTempDirectory("staged-natives").toFile()
        try {
            stageCompleteNatives(root, target)
            File(root, "${target.id}/native/ffmpeg/ffmpeg-layout.json")
                .writeText(LAYOUT_JSON.replace("\"avutil\": 61", ""))
            val problems = stagedNativeProblems(target, root)
            assertTrue(
                problems.any { it.contains("avutil") },
                problems.joinToString(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the repository lock names the libraries the image filter ships`() {
        // The lock is the one list of FFmpeg libraries (01 Python and native components);
        // the filter must accept each recorded soname on every OS and nothing else.
        val lock =
            File("../../playback/native/native-components.lock").takeIf { it.isFile }
                ?: error("the repository lock is missing")
        val parsed = org.tomlj.Toml.parse(lock.readText())
        val libraries = NativeLicencePolicy.ffmpegLibraries(parsed)
        assertEquals(4, libraries.size)
        for (library in libraries) {
            assertTrue(ffmpegImageEntryAllowed("windows", "bin/$library-63.dll"), "windows $library")
            assertTrue(ffmpegImageEntryAllowed("macos", "lib/lib$library.63.dylib"), "macos $library")
            assertTrue(ffmpegImageEntryAllowed("linux", "lib/lib$library.so.63"), "linux $library")
        }
    }

    @Test
    fun `writeDesktopLicences writes the licences payload`() {
        val root = Files.createTempDirectory("desktop-licences").toFile()
        try {
            val definitions =
                File(root, "aboutlibraries.json").apply {
                    writeText(
                        """
                        {
                          "libraries": [
                            {"uniqueId": "neutrodyne:ffmpeg", "name": "ffmpeg", "artifactVersion": "9.0.2", "licenses": ["LGPL-2.1-or-later-ffmpeg"]},
                            {"uniqueId": "neutrodyne:miniaudio", "name": "miniaudio", "artifactVersion": "0.11.22", "licenses": ["MIT-0-miniaudio"]}
                          ],
                          "licenses": {
                            "LGPL-2.1-or-later-ffmpeg": {"name": "LGPL-2.1-or-later (FFmpeg)", "spdxId": "LGPL-2.1-or-later", "content": "GNU LESSER GENERAL PUBLIC LICENSE Version 2.1"},
                            "MIT-0-miniaudio": {"name": "MIT No Attribution (miniaudio)", "spdxId": "MIT-0", "content": "miniaudio permission"}
                          }
                        }
                        """.trimIndent(),
                    )
                }
            val lgpl = File(root, "COPYING.LGPLv2.1").apply { writeText("LGPL 2.1 text from source") }
            // The committed miniaudio entry — the same JSON the task reads for the image.
            val miniaudio = File("../../desktopApp/config/licenses/MIT-0-miniaudio.json")
            assertTrue(miniaudio.isFile, "the committed miniaudio licence entry is missing")
            val out = File(root, "licences")
            val project = ProjectBuilder.builder().withProjectDir(root).build()
            project.tasks
                .register("writeDesktopLicences", WriteDesktopLicencesTask::class.java) {
                    this.definitions.set(definitions.absoluteFile)
                    this.lgplText.set(lgpl.absoluteFile)
                    this.miniaudioLicense.set(miniaudio.absoluteFile)
                    this.outputDirectory.set(out)
                }.get()
                .write()

            val notices = File(out, "THIRD_PARTY_NOTICES.md").readText()
            assertTrue("ffmpeg" in notices && "miniaudio" in notices)
            assertEquals("LGPL 2.1 text from source", File(out, "COPYING.LGPLv2.1").readText())
            // The MIT-0 text of the committed entry, not an SPDX template.
            assertTrue(
                File(out, "miniaudio.txt").readText().contains("Permission is hereby granted"),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * The post-image hook installer names the script exactly as jpackage's `ScriptRunner`
     * looks it up (`<app name>-post-image<ext>`) and fails closed when the shipped hook is
     * missing — packaging without it would ship a strict-cache failure.
     */
    @Test
    fun `the post-image hook installer names the script for jpackage`() {
        val root = Files.createTempDirectory("post-image-hook").toFile()
        try {
            val hook = File(root, "shipped.sh").apply { writeText("#!/bin/sh\n") }
            val resources = File(root, "resources").apply { mkdirs() }

            writePostImageHook(hook, "Neutrodyne", ".sh", resources)
            assertEquals("#!/bin/sh\n", File(resources, "Neutrodyne-post-image.sh").readText())

            writePostImageHook(hook, "Neutrodyne", ".wsf", resources)
            assertEquals("#!/bin/sh\n", File(resources, "Neutrodyne-post-image.wsf").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * The replaced Compose task drives the hook installation through Compose's own
     * `run()` → `prepareWorkingDir()`: after the base clears and repopulates the resource
     * directory, the hook lands under the exact name jpackage's `ScriptRunner` looks up.
     * A fake jpackage executable stands in for the JDK tool, so the task's real
     * configuration path (initState, prepareWorkingDir, makeArgs) runs without a JDK.
     */
    @Test
    fun `the AOT package task installs the hook during prepareWorkingDir`() {
        val projectDir = Files.createTempDirectory("aot-package-task").toFile()
        try {
            val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
            val hook = File(projectDir, "shipped.sh").apply { writeText("#!/bin/sh\n") }
            val runtimeProperties = File(projectDir, "runtime.properties")
            writeRuntimeProperties(runtimeProperties)
            val fakeJdk = File(projectDir, "fake-jdk")
            val fakeBin = File(fakeJdk, "bin").apply { mkdirs() }
            // The fake tool produces the package file checkResult() looks for. The
            // Compose runner invokes it as `jpackage @<args-file>` (one arg per line).
            File(fakeBin, "jpackage").apply {
                writeText(
                    """
                    #!/bin/sh
                    dest=""
                    want_dest=0
                    for arg in "${'$'}@"; do
                      case "${'$'}arg" in
                        @*)
                          while IFS= read -r line; do
                            if [ "${'$'}want_dest" = 1 ]; then
                              # the argfile quotes each value
                              dest="${'$'}line"
                              dest="${'$'}{dest#\"}"
                              dest="${'$'}{dest%\"}"
                              want_dest=0
                            fi
                            case "${'$'}line" in --dest) want_dest=1 ;; esac
                          done < "${'$'}{arg#@}"
                          ;;
                      esac
                    done
                    mkdir -p "${'$'}dest"
                    printf 'DMG\n' > "${'$'}dest/Neutrodyne.dmg"
                    exit 0
                    """.trimIndent(),
                )
                setExecutable(true, false)
            }
            val task =
                project.tasks
                    .register("packageDmg", DmgAotTimestampJPackageTask::class.java)
                    .get()
            task.postImageHook.set(hook)
            task.packageName.set("Neutrodyne")
            task.launcherMainClass.set("ch.lkmc.neutrodyne.desktop.MainKt")
            task.packageVersion.set("1.0.0")
            task.appImage.set(File(projectDir, "app").apply { mkdirs() })
            task.javaHome.set(fakeJdk.absolutePath)
            task.javaRuntimePropertiesFile.set(runtimeProperties)
            task.destinationDir.set(File(projectDir, "dest"))
            task.verbose.set(false)

            task.run(EmptyInputChanges)

            val resources =
                project.layout.buildDirectory
                    .dir("compose/tmp/resources")
                    .get()
                    .asFile
            assertEquals("#!/bin/sh\n", File(resources, "Neutrodyne-post-image.sh").readText())
        } finally {
            projectDir.deleteRecursively()
        }
    }

    // ----- the installer tasks consume the trained image (11 AOT cache) ---------

    /**
     * `registerDesktopPackagingTasks` is the production wiring of every packaging task; this
     * fixture drives it on a ProjectBuilder project that carries the tasks and files it
     * reads, so the replaced `packageMsi`/`packageDmg` tasks are the real, production-wired
     * objects. The trained image is fabricated at `createDistributable`'s destination with
     * the exact layout the training task leaves ([aotImageLayout]: launcher, `.cfg` with the
     * `-XX:AOTCache` read flag, the trained archive and a classpath JAR).
     */
    private class TrainedImagePackagingFixture(
        val project: org.gradle.api.Project,
        val distributableDir: File,
    )

    private fun trainedImagePackagingFixture(target: DesktopPackagingTarget): TrainedImagePackagingFixture {
        val projectDir = Files.createTempDirectory("trained-packaging").toFile()
        // registerDesktopPackagingTasks reads the version property at wiring time; both
        // property locations ProjectBuilder consults carry it.
        File(projectDir, "gradle.properties").writeText("neutrodyne.versionName=0.1.0\n")
        val gradleHome = File(projectDir, "gradle-home").apply { mkdirs() }
        File(gradleHome, "gradle.properties").writeText("neutrodyne.versionName=0.1.0\n")
        val project =
            ProjectBuilder
                .builder()
                .withProjectDir(projectDir)
                .withGradleUserHomeDir(gradleHome)
                .build()
        val playback =
            ProjectBuilder
                .builder()
                .withParent(project)
                .withName("playback")
                .build()
        val nativeProject =
            ProjectBuilder
                .builder()
                .withParent(playback)
                .withName("native")
                .build()
        File(nativeProject.projectDir, "native-components.lock").apply {
            parentFile.mkdirs()
            writeText("[ffmpeg]\nversion = \"9.0.2\"\n")
        }

        val distributableDir = File(projectDir, "binaries")
        // Compose registers createRuntimeImage alongside createDistributable; the wiring's
        // replace targets it, so the fixture registers the same abstract base Compose does.
        project.tasks.register("createRuntimeImage", AbstractJLinkTask::class.java)
        project.tasks
            .register("createDistributable", FixtureCreateDistributableTask::class.java) {
                this.destinationDir.set(distributableDir)
            }.get()
        project.tasks
            .register("setupBundledRuntime", SetupBundledRuntime::class.java) {
                this.home.set(File(projectDir, "bundled-runtime/jdk"))
            }.get()
        project.configurations.create("runtimeClasspath")
        project.tasks.register("jar", org.gradle.api.tasks.bundling.Jar::class.java)
        // The Compose plugin has already registered packageMsi/packageDmg when the
        // convention plugin's afterEvaluate wiring runs and replaces them; Gradle 9's
        // replace needs a compatible declared type, so the fixture registers the final
        // types (the production replace of Compose's AbstractJPackageTask registration is
        // proven by the real Windows run's hook installation).
        project.tasks.register("packageMsi", MsiAotTimestampJPackageTask::class.java)
        project.tasks.register("packageDmg", DmgAotTimestampJPackageTask::class.java)

        project.registerDesktopPackagingTasks(
            target,
            BundledRuntimeLock(
                vendor = "Eclipse Adoptium",
                vendorVersion = "Temurin-25.0.4.1+1",
                version = "25.0.4.1+1",
                javaVersion = "25",
                archiveUrl = "https://example.invalid/jdk",
                sha256 = "00",
            ),
        )

        fabricateTrainedImage(distributableDir, target)
        return TrainedImagePackagingFixture(project, distributableDir)
    }

    /** A concrete createDistributable stand-in: the wiring consumes only its destinationDir. */
    abstract class FixtureCreateDistributableTask :
        org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask(
            org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage,
        )

    /** The trained image createDistributable + trainAotCache leave, per [aotImageLayout]. */
    private fun fabricateTrainedImage(
        distributableDir: File,
        target: DesktopPackagingTarget,
    ) {
        val layout = aotImageLayout(target.osWire)
        val image =
            File(
                distributableDir,
                if (target == DesktopPackagingTarget.MACOS_ARM64) "$DESKTOP_PACKAGE_NAME.app" else DESKTOP_PACKAGE_NAME,
            )
        File(image, layout.launcher).apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        File(image, layout.cfg).apply {
            parentFile.mkdirs()
            writeText(
                "[Application]\n" +
                    "[JavaOptions]\n" +
                    "java-options=-XX:AOTCache=${'$'}APPDIR/$AOT_CACHE_FILE\n",
            )
        }
        File(image, "${layout.appDir}/$AOT_CACHE_FILE").apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(16) { it.toByte() })
        }
        File(image, "${layout.appDir}/app-85f294.jar").apply {
            parentFile.mkdirs()
            writeBytes(zipBytes(listOf("A.class" to "CODE".toByteArray())))
        }
        File(image, layout.runtimeRelease).apply {
            parentFile.mkdirs()
            writeText("JAVA_VERSION=\"25.0.4.1\"\n")
        }
    }

    /**
     * The value Compose's `makeArgs` emits for `--app-image` from the configured
     * [configured] directory: on a macOS host (any format, pinned plugin) it appends
     * `<name>.app` as a child; non-macOS hosts pass the configured directory itself.
     */
    private fun hostAppImageArg(configured: File): String =
        if (System.getProperty("os.name") == "Mac OS X") {
            File(configured, DESKTOP_PACKAGE_NAME + ".app").path
        } else {
            configured.path
        }

    /**
     * Runs a production-wired package task through Compose's real `run()` with a fake
     * `jpackage` that records the argument file it was handed, and returns those argv
     * tokens (each args-file line's quotes stripped). The runtime files and main-jar
     * properties mirror what Compose's own configure action sets in the real build, so a
     * fresh-image argv (the defect) is produced unchanged when the wiring leaves `appImage`
     * unset.
     */
    private fun runPackagingTask(
        fixture: TrainedImagePackagingFixture,
        name: String,
    ): List<String> {
        val task =
            fixture.project.tasks
                .named(name, AotTimestampJPackageTask::class.java)
                .get()
        val projectDir = fixture.project.projectDir
        val fakeBin = File(projectDir, "fake-jdk-${task.name}/bin").apply { mkdirs() }
        File(fakeBin, "jpackage").apply {
            writeText(
                """
                #!/bin/sh
                dest=""
                want_dest=0
                args_file=""
                for arg in "${'$'}@"; do
                  case "${'$'}arg" in
                    @*) args_file="${'$'}{arg#@}" ;;
                  esac
                done
                while IFS= read -r line; do
                  if [ "${'$'}want_dest" = 1 ]; then
                    dest="${'$'}line"
                    dest="${'$'}{dest#\"}"
                    dest="${'$'}{dest%\"}"
                    want_dest=0
                  fi
                  case "${'$'}line" in --dest) want_dest=1 ;; esac
                done < "${'$'}args_file"
                mkdir -p "${'$'}dest"
                cp "${'$'}args_file" "${'$'}dest/argv.txt"
                printf 'fake' > "${'$'}dest/Neutrodyne-0.1.0.msi"
                printf 'fake' > "${'$'}dest/Neutrodyne-0.1.0.dmg"
                exit 0
                """.trimIndent(),
            )
            setExecutable(true, false)
        }
        val hook = File(projectDir, "hook-${task.name}.sh").apply { writeText("#!/bin/sh\n") }
        val mainJar = File(projectDir, "main-${task.name}.jar")
        mainJar.writeBytes(zipBytes(listOf("Main.class" to "MAIN".toByteArray())))
        writeRuntimeProperties(File(projectDir, "runtime-${task.name}.properties"))
        task.postImageHook.set(hook)
        task.packageName.set(DESKTOP_PACKAGE_NAME)
        task.launcherMainClass.set("ch.lkmc.neutrodyne.desktop.MainKt")
        task.packageVersion.set("0.1.0")
        task.launcherMainJar.set(mainJar)
        task.files.from(mainJar)
        task.javaHome.set(fakeBin.parentFile.absolutePath)
        task.javaRuntimePropertiesFile.set(File(projectDir, "runtime-${task.name}.properties"))
        task.destinationDir.set(File(projectDir, "dest-${task.name}"))

        task.run(EmptyInputChanges)

        return File(projectDir, "dest-${task.name}/argv.txt")
            .readLines()
            .map { line -> line.removePrefix("\"").removeSuffix("\"") }
    }

    /**
     * The wired `packageMsi` must hand jpackage the trained app image (`--app-image`), not
     * Compose's fresh-image layout (`--input` libs + `--main-jar`): a fresh image carries no
     * trained `neutrodyne.aot`, the post-image WSF correctly fails it closed, and the MSI
     * never reaches WiX (Windows run 42509091764, job 128818030637). This runs the real
     * production wiring and Compose's real argument builder against a capturing fake
     * jpackage; on a macOS host Compose's makeArgs appends the `.app` leaf to any
     * `--app-image` value (a host quirk of the pinned plugin; Windows passes the image
     * directory itself).
     */
    @Test
    fun `packageMsi jpackages the trained app image not a fresh jar image`() {
        val fixture = trainedImagePackagingFixture(DesktopPackagingTarget.WINDOWS_X64)
        try {
            val argv = runPackagingTask(fixture, "packageMsi")

            val appImage = argv.indexOf("--app-image")
            assertTrue(appImage >= 0, "no --app-image in: ${argv.joinToString(" ")}")
            assertEquals(
                hostAppImageArg(File(fixture.distributableDir, DESKTOP_PACKAGE_NAME)),
                argv[appImage + 1],
            )
            assertFalse("--input" in argv, "fresh-image --input in: ${argv.joinToString(" ")}")
            assertFalse("--main-jar" in argv, "fresh-image --main-jar in: ${argv.joinToString(" ")}")
            assertFalse("--runtime-image" in argv, "fresh-image --runtime-image in: ${argv.joinToString(" ")}")
        } finally {
            fixture.project.projectDir.deleteRecursively()
        }
    }

    /**
     * The wired `packageDmg` must consume the trained `.app` bundle the same way: Compose's
     * `makeArgs` derives `--app-image <destinationDir>/<name>.app` from the configured
     * `appImage` on a macOS host, so the wiring pins the destination directory itself.
     */
    @Test
    fun `packageDmg jpackages the trained app bundle not a fresh jar image`() {
        val fixture = trainedImagePackagingFixture(DesktopPackagingTarget.MACOS_ARM64)
        try {
            val argv = runPackagingTask(fixture, "packageDmg")

            val appImage = argv.indexOf("--app-image")
            assertTrue(appImage >= 0, "no --app-image in: ${argv.joinToString(" ")}")
            assertEquals(hostAppImageArg(fixture.distributableDir), argv[appImage + 1])
            assertFalse("--input" in argv, "fresh-image --input in: ${argv.joinToString(" ")}")
            assertFalse("--main-jar" in argv, "fresh-image --main-jar in: ${argv.joinToString(" ")}")
        } finally {
            fixture.project.projectDir.deleteRecursively()
        }
    }

    // ----- the runtime image producer: the locale restriction of 11 jlink modules ---------

    /** The production shape around createRuntimeImage on a ProjectBuilder project. */
    private class RuntimeImageFixture(
        val project: org.gradle.api.Project,
        val fakeJdk: File,
        val runtimeDir: File,
        val createRuntimeImageProvider: TaskProvider<AbstractJLinkTask>,
        val createDistributable: FixtureCreateDistributableTask,
    )

    /**
     * Mirrors Compose 1.12.1's own wiring on a ProjectBuilder project: `createRuntimeImage`
     * registered as the abstract [AbstractJLinkTask] with the configure action Compose's
     * `configureCommonJvmDesktopTasks` attaches (javaHome, modules, destinationDir — the
     * application data the convention plugin sets), and `createDistributable` wired to it
     * the way Compose's `configurePackageTask` does (dependsOn the provider and
     * `--runtime-image` from the same provider's destinationDir). [registerDesktopPackagingTasks]
     * then runs against this exactly as in the real build, so what the assertions exercise
     * is the production wiring, not a stand-in task.
     */
    private fun runtimeImageFixture(
        target: DesktopPackagingTarget,
        destinationDir: File? = null,
        withoutEngine: Boolean = false,
    ): RuntimeImageFixture {
        val projectDir = Files.createTempDirectory("runtime-image").toFile()
        File(projectDir, "gradle.properties").writeText("neutrodyne.versionName=0.1.0\n")
        if (withoutEngine) {
            File(projectDir, "gradle.properties").appendText("neutrodyne.youtubeEngine=false\n")
        }
        val gradleHome = File(projectDir, "gradle-home").apply { mkdirs() }
        File(gradleHome, "gradle.properties").writeText("neutrodyne.versionName=0.1.0\n")
        val project =
            ProjectBuilder
                .builder()
                .withProjectDir(projectDir)
                .withGradleUserHomeDir(gradleHome)
                .build()
        val playback =
            ProjectBuilder
                .builder()
                .withParent(project)
                .withName("playback")
                .build()
        val nativeProject =
            ProjectBuilder
                .builder()
                .withParent(playback)
                .withName("native")
                .build()
        File(nativeProject.projectDir, "native-components.lock").apply {
            parentFile.mkdirs()
            writeText("[ffmpeg]\nversion = \"9.0.2\"\n")
        }

        val fakeJdk = File(projectDir, "fake-jdk")
        File(fakeJdk, "bin").apply { mkdirs() }
        val runtimeDir = (destinationDir ?: File(projectDir, "compose/tmp/runtime")).apply { mkdirs() }

        val createRuntimeImage =
            project.tasks.register("createRuntimeImage", AbstractJLinkTask::class.java) {
                this.javaHome.set(fakeJdk.absolutePath)
                this.modules.set(JLINK_MODULES)
                this.includeAllModules.set(false)
                this.destinationDir.set(runtimeDir)
            }
        val createDistributable =
            project.tasks
                .register(
                    "createDistributable",
                    FixtureCreateDistributableTask::class.java,
                ) {
                    this.dependsOn(createRuntimeImage)
                    this.destinationDir.set(File(projectDir, "binaries"))
                    this.runtimeImage.set(createRuntimeImage.flatMap { task -> task.destinationDir })
                }.get()
        project.tasks.register("packageMsi", MsiAotTimestampJPackageTask::class.java)
        project.tasks.register("packageDmg", DmgAotTimestampJPackageTask::class.java)
        project.configurations.create("runtimeClasspath")
        project.tasks.register("jar", org.gradle.api.tasks.bundling.Jar::class.java)
        project.tasks.register("setupBundledRuntime", SetupBundledRuntime::class.java) {
            home.set(File(projectDir, "bundled-runtime/jdk"))
        }

        project.registerDesktopPackagingTasks(
            target,
            BundledRuntimeLock(
                vendor = "Eclipse Adoptium",
                vendorVersion = "Temurin-25.0.4.1+1",
                version = "25.0.4.1+1",
                javaVersion = "25",
                archiveUrl = "https://example.invalid/jdk",
                sha256 = "00",
            ),
        )
        return RuntimeImageFixture(project, fakeJdk, runtimeDir, createRuntimeImage, createDistributable)
    }

    /**
     * Runs the production-wired `createRuntimeImage` through Compose's real `run()` with a
     * fake `jlink` that records the argument file it was handed (one token per line, values
     * quoted when they contain spaces) and the tool path it was invoked as. Returns the argv
     * tokens with the argfile's value quoting stripped, paired with that tool path — the
     * javaHome JDK selection shows up as the executed binary.
     */
    private fun runCreateRuntimeImage(fixture: RuntimeImageFixture): Pair<List<String>, String> {
        val capture = File(fixture.project.projectDir, "captured").apply { mkdirs() }
        File(fixture.fakeJdk, "bin/jlink").apply {
            writeText(
                """
                #!/bin/sh
                args_file=""
                for arg in "${'$'}@"; do
                  case "${'$'}arg" in @*) args_file="${'$'}{arg#@}" ;; esac
                done
                cp "${'$'}args_file" "${capture.absolutePath}/argv.txt"
                printf '%s\n' "${'$'}0" > "${capture.absolutePath}/tool.txt"
                exit 0
                """.trimIndent(),
            )
            setExecutable(true, false)
        }
        val task =
            fixture.project.tasks
                .named("createRuntimeImage", AbstractJLinkTask::class.java)
                .get()
        task.run(EmptyInputChanges)

        val argv =
            File(capture, "argv.txt").readLines().map { line ->
                line.removePrefix("\"").removeSuffix("\"")
            }
        return argv to File(capture, "tool.txt").readText().trim()
    }

    /**
     * The runtime image producer must restrict `jdk.localedata` to the shipped locales
     * (`--include-locales=en,de`, 11 jlink modules; PB27's miss reserved the convention
     * plugin's own jlink step — Compose 1.12.1's `AbstractJLinkTask` has no such option).
     * Without the restriction the module ships whole: measured 26.5 MB more installed.
     */
    @Test
    fun `the runtime image producer restricts jdk locale data to the shipped locales`() {
        val fixture = runtimeImageFixture(DesktopPackagingTarget.WINDOWS_X64)
        try {
            val (argv, _) = runCreateRuntimeImage(fixture)

            val locales = argv.indexOf("--include-locales")
            assertTrue(locales >= 0, "no --include-locales in: ${argv.joinToString(" ")}")
            assertEquals("en,de", argv[locales + 1])
            val output = argv.indexOf("--output")
            assertTrue(output > locales, "--include-locales after --output in: ${argv.joinToString(" ")}")
            assertFalse(
                "--compress" in argv,
                "--compress grows the download size (measured): ${argv.joinToString(" ")}",
            )
        } finally {
            fixture.project.projectDir.deleteRecursively()
        }
    }

    /**
     * The restriction changes nothing else: the argv handed to jlink stays exactly the
     * Compose flags (module list in DSL order, the four strip options, `--output`) plus
     * the one locale pair, and the executed tool is the javaHome JDK's own `jlink` — the
     * bundled runtime the convention plugin pins, never the Gradle VM.
     */
    @Test
    fun `the runtime image producer keeps the exact compose flags module list and jdk`() {
        val fixture = runtimeImageFixture(DesktopPackagingTarget.LINUX_X64)
        try {
            val (argv, tool) = runCreateRuntimeImage(fixture)

            val expected =
                buildList {
                    for (module in JLINK_MODULES) {
                        add("--add-modules")
                        add(module)
                    }
                    add("--strip-debug")
                    add("--no-header-files")
                    add("--no-man-pages")
                    add("--strip-native-commands")
                    add("--include-locales")
                    add("en,de")
                    add("--output")
                    add(fixture.runtimeDir.absolutePath)
                }
            assertEquals(expected, argv)
            assertEquals(File(fixture.fakeJdk, "bin/jlink").absolutePath, tool)
        } finally {
            fixture.project.projectDir.deleteRecursively()
        }
    }

    /**
     * Compose hands jlink an argument file (one token per line, values with spaces
     * quoted); the inserted locale pair must not disturb that. A destination directory
     * with spaces stands in for the Windows `Program Files` case: `--output` still
     * round-trips to the exact path and the locale list stays one unquoted token.
     */
    @Test
    fun `the locale restriction keeps spaced destination paths intact`() {
        val spacedRuntimeDir =
            File(Files.createTempDirectory("runtime-image-spaced").toFile(), "dir with spaces/runtime")
        val fixture =
            runtimeImageFixture(DesktopPackagingTarget.WINDOWS_X64, destinationDir = spacedRuntimeDir)
        try {
            val (argv, _) = runCreateRuntimeImage(fixture)

            val output = argv.indexOf("--output")
            assertTrue(output >= 0, "no --output in: ${argv.joinToString(" ")}")
            assertEquals(spacedRuntimeDir.absolutePath, argv[output + 1])
            val locales = argv.indexOf("--include-locales")
            assertTrue(locales >= 0, "no --include-locales in: ${argv.joinToString(" ")}")
            assertEquals("en,de", argv[locales + 1])
        } finally {
            fixture.project.projectDir.deleteRecursively()
            spacedRuntimeDir.parentFile.deleteRecursively()
        }
    }

    /**
     * The runtime image is engine-independent: `-Pneutrodyne.youtubeEngine=false` omits
     * the YouTube engine host from the image but must not change the jlink invocation —
     * one module list and one locale set serve every build of a target. (The `--output`
     * value differs per fixture temp dir, so the comparison drops the trailing pair.)
     */
    @Test
    fun `the locale restriction is independent of the youtube engine property`() {
        val withEngine = runtimeImageFixture(DesktopPackagingTarget.LINUX_X64)
        val withoutEngine = runtimeImageFixture(DesktopPackagingTarget.LINUX_X64, withoutEngine = true)
        try {
            val (argvWithEngine, _) = runCreateRuntimeImage(withEngine)
            val (argvWithoutEngine, _) = runCreateRuntimeImage(withoutEngine)

            val locales = argvWithEngine.indexOf("--include-locales")
            assertTrue(locales >= 0, "no --include-locales in: ${argvWithEngine.joinToString(" ")}")
            // Everything up to and including the locale pair; --output/--output-dir vary by fixture.
            assertEquals(
                argvWithEngine.subList(0, locales + 2),
                argvWithoutEngine.subList(0, locales + 2),
            )
        } finally {
            withEngine.project.projectDir.deleteRecursively()
            withoutEngine.project.projectDir.deleteRecursively()
        }
    }

    /**
     * The production wiring replaces Compose's `createRuntimeImage` with the locale-restricted
     * type (the mechanism `packageMsi`/`packageDmg` established): the Compose configure action
     * survives the replace — javaHome, the module list and the destination dir keep the values
     * the Compose registration set — exactly one jlink task exists (one writer of the runtime
     * image), and `createDistributable` still consumes that task's destination dir as its
     * `--runtime-image`, with `trainAotCache` downstream of the image it builds from it.
     */
    @Test
    fun `the replaced jlink task owns the runtime image with compose wiring intact`() {
        val fixture = runtimeImageFixture(DesktopPackagingTarget.WINDOWS_X64)
        try {
            val producer = fixture.project.tasks.getByName("createRuntimeImage")
            assertTrue(producer is LocaleRestrictedJLinkTask, "createRuntimeImage is ${producer.javaClass.name}")
            producer as LocaleRestrictedJLinkTask
            assertEquals(JLINK_INCLUDED_LOCALES, producer.includedLocales.get())
            assertEquals(fixture.fakeJdk.absolutePath, producer.javaHome.get())
            assertEquals(JLINK_MODULES, producer.modules.get())
            assertEquals(
                fixture.runtimeDir.absolutePath,
                producer.destinationDir
                    .get()
                    .asFile.absolutePath,
            )

            val jlinkTasks =
                fixture.project.tasks
                    .withType(AbstractJLinkTask::class.java)
                    .map { task -> task.name }
            assertEquals(listOf("createRuntimeImage"), jlinkTasks)

            assertEquals(
                fixture.runtimeDir.absolutePath,
                fixture.createDistributable.runtimeImage
                    .get()
                    .asFile.absolutePath,
            )
            val depNames =
                fixture.createDistributable.dependsOn.map { dependency ->
                    (dependency as? TaskProvider<*>)?.name ?: dependency.toString()
                }
            assertTrue("createRuntimeImage" in depNames, depNames.toString())
            val trainAotCache =
                fixture.project.tasks
                    .named("trainAotCache")
                    .get()
            val trainDeps =
                trainAotCache.dependsOn.map { dependency ->
                    (dependency as? TaskProvider<*>)?.name ?: dependency.toString()
                }
            assertTrue("createDistributable" in trainDeps, trainDeps.toString())
        } finally {
            fixture.project.projectDir.deleteRecursively()
        }
    }

    /**
     * The wired installers and the training task must share one tree: each installer's
     * `appImage` names the trained image (`createDistributable`'s destination plus the
     * image leaf, per the target's [aotImageLayout]), that tree carries the trained
     * layout (launcher, `.cfg` with the read flag, archive), and neither installer runs
     * before `createDistributable` and `trainAotCache` have produced it. A leaf mismatch
     * (image vs bundle vs install dir) would train one tree and package another.
     */
    @Test
    fun `the trained archive lives inside the image each installer packages`() {
        for (target in listOf(DesktopPackagingTarget.WINDOWS_X64, DesktopPackagingTarget.MACOS_ARM64)) {
            val fixture = trainedImagePackagingFixture(target)
            try {
                val layout = aotImageLayout(target.osWire)
                val imageLeaf =
                    if (target ==
                        DesktopPackagingTarget.MACOS_ARM64
                    ) {
                        "$DESKTOP_PACKAGE_NAME.app"
                    } else {
                        DESKTOP_PACKAGE_NAME
                    }
                val msi =
                    fixture.project.tasks
                        .named("packageMsi", MsiAotTimestampJPackageTask::class.java)
                        .get()
                assertEquals(
                    File(fixture.distributableDir, imageLeaf).path,
                    msi.appImage
                        .get()
                        .asFile.path,
                    target.id,
                )
                val trainedTree = msi.appImage.get().asFile
                assertTrue(File(trainedTree, "${layout.appDir}/$AOT_CACHE_FILE").isFile, target.id)
                assertTrue(File(trainedTree, layout.cfg).isFile, target.id)
                assertTrue(File(trainedTree, layout.launcher).isFile, target.id)

                val dmg =
                    fixture.project.tasks
                        .named("packageDmg", DmgAotTimestampJPackageTask::class.java)
                        .get()
                assertEquals(
                    fixture.distributableDir.path,
                    dmg.appImage
                        .get()
                        .asFile.path,
                    target.id,
                )

                for (consumer in listOf<org.gradle.api.Task>(msi, dmg)) {
                    val names =
                        consumer.dependsOn.map { dependency ->
                            (dependency as? org.gradle.api.tasks.TaskProvider<*>)?.name ?: dependency.toString()
                        }
                    assertTrue("trainAotCache" in names, "${consumer.name} (${target.id}): $names")
                    assertTrue("createDistributable" in names, "${consumer.name} (${target.id}): $names")
                }
            } finally {
                fixture.project.projectDir.deleteRecursively()
            }
        }
    }

    /** Serializes the Compose `JvmRuntimeProperties` the task's `initState()` reads. */
    private fun writeRuntimeProperties(target: File) {
        val clazz =
            Class.forName("org.jetbrains.compose.desktop.application.internal.JvmRuntimeProperties")
        val constructor = clazz.constructors.first { it.parameterTypes.size == 2 }
        val instance = constructor.newInstance(25, listOf("java.base"))
        java.io.ObjectOutputStream(target.outputStream()).use { it.writeObject(instance) }
    }

    @Test
    fun `the post-image hook installer fails closed on a missing hook`() {
        val root = Files.createTempDirectory("post-image-hook-missing").toFile()
        try {
            val resources = File(root, "resources").apply { mkdirs() }
            assertFailsWith<GradleException> {
                writePostImageHook(File(root, "absent.sh"), "Neutrodyne", ".sh", resources)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * `addUtcTimestamps` rewrites a DOS-time-only ZIP — what the commons-compress
     * producer behind Gradle's Zip writes — so every entry also carries the
     * Info-ZIP 0x5455 extended timestamp: UTC seconds an extractor in any
     * timezone decodes to the one instant the DOS local time meant here.
     */
    @Test
    fun `addUtcTimestamps adds the UTC extended timestamp field`() {
        val root = Files.createTempDirectory("utc-timestamps").toFile()
        try {
            val zip = File(root, "plain.zip")
            zip.writeBytes(dosOnlyZip("Neutrodyne/app/tiny.jar", byteArrayOf(7, 8, 9)))

            addUtcTimestamps(zip)

            java.util.zip.ZipFile(zip).use { archive ->
                val entry = archive.getEntry("Neutrodyne/app/tiny.jar")
                assertNotNull(entry)
                assertEquals(byteArrayOf(7, 8, 9).toList(), archive.getInputStream(entry).readBytes().toList())
                // The recorded instant is the DOS local time decoded on this
                // host; the 0x5455 field must pin exactly that instant as UTC.
                val expectedUtc =
                    java.time.LocalDateTime
                        .of(1980, 2, 1, 0, 0)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toEpochSecond()
                assertEquals(expectedUtc, extendedTimestampUtc(entry.extra))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * A minimal valid ZIP with one STORED entry whose only timestamp is the
     * MS-DOS field (1980-02-01 00:00:00, local) — no 0x5455 extra. This is what
     * Gradle's Zip leaves on disk; [addUtcTimestamps] has to add the UTC field.
     */
    private fun dosOnlyZip(
        name: String,
        data: ByteArray,
    ): ByteArray {
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val crc =
            java.util.zip
                .CRC32()
                .also { it.update(data) }
                .value
                .toInt()
        val buffer =
            ByteBuffer
                .allocate(30 + nameBytes.size + data.size + 46 + nameBytes.size + 22)
                .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x04034B50) // local header
        buffer.putShort(20).putShort(0).putShort(0) // version, flags, STORED
        buffer.putShort(0).putShort(0x41) // 00:00:00, 1980-02-01
        buffer.putInt(crc).putInt(data.size).putInt(data.size)
        buffer.putShort(nameBytes.size.toShort()).putShort(0) // name len, no extra
        buffer.put(nameBytes).put(data)
        buffer.putInt(0x02014B50) // central header
        buffer
            .putShort(20)
            .putShort(20)
            .putShort(0)
            .putShort(0)
        buffer.putShort(0).putShort(0x41)
        buffer.putInt(crc).putInt(data.size).putInt(data.size)
        buffer.putShort(nameBytes.size.toShort()).putShort(0).putShort(0)
        buffer
            .putShort(0)
            .putShort(0)
            .putInt(0)
            .putInt(0)
        buffer.put(nameBytes)
        buffer.putInt(0x06054B50) // end of central directory
        buffer
            .putShort(0)
            .putShort(0)
            .putShort(1)
            .putShort(1)
        buffer.putInt(46 + nameBytes.size).putInt(30 + nameBytes.size + data.size)
        buffer.putShort(0)
        return buffer.array()
    }

    /** The UTC seconds the entry's 0x5455 extended timestamp records, or null. */
    private fun extendedTimestampUtc(extra: ByteArray?): Long? {
        val fields = ByteBuffer.wrap(extra ?: return null).order(ByteOrder.LITTLE_ENDIAN)
        while (fields.remaining() >= 4) {
            val id = fields.short.toInt() and 0xFFFF
            val size = fields.short.toInt() and 0xFFFF
            if (size > fields.remaining()) return null
            if (id == 0x5455 && size >= 5) {
                fields.get() // flags
                return fields.int.toLong() and 0xFFFFFFFFL
            }
            fields.position(fields.position() + size)
        }
        return null
    }

    /**
     * The four soname paths a complete staged tree carries for [target], relative to its
     * root, in the naming the OS's loader gives them (`bin/avcodec-63.dll`,
     * `lib/libavutil.61.dylib`, `lib/libavutil.so.61`).
     */
    private fun stagedSonames(target: DesktopPackagingTarget): List<String> {
        val subdir = if (target.osWire == "windows") "bin" else "lib"
        return LAYOUT_MAJORS.map { (library, major) ->
            val name =
                when (target.osWire) {
                    "windows" -> "$library-$major.dll"
                    "macos" -> "lib$library.$major.dylib"
                    else -> "lib$library.so.$major"
                }
            "${target.id}/native/ffmpeg/$subdir/$name"
        }
    }

    /** A complete staged `desktop-resources` tree for [target] under [root]. */
    private fun stageCompleteNatives(
        root: File,
        target: DesktopPackagingTarget,
    ) {
        val ffmpegDir = File(root, "${target.id}/native/ffmpeg")
        File(ffmpegDir, "ffmpeg-layout.json").apply {
            parentFile.mkdirs()
            writeText(LAYOUT_JSON)
        }
        File(ffmpegDir, "ffmpeg-license.txt").writeText("LGPL version 2.1 or later\n")
        for (soname in stagedSonames(target)) {
            File(root, soname).apply {
                parentFile.mkdirs()
                writeBytes(byteArrayOf(1))
            }
        }
        val ndmediaName =
            when (target.osWire) {
                "windows" -> "ndmedia.dll"
                "macos" -> "libndmedia.dylib"
                else -> "libndmedia.so"
            }
        File(root, "${target.id}/native/ndmedia/$ndmediaName").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(2))
        }
        for (name in listOf("THIRD_PARTY_NOTICES.md", "COPYING.LGPLv2.1", "miniaudio.txt")) {
            File(root, "common/licenses/$name").apply {
                parentFile.mkdirs()
                writeText("x")
            }
        }
    }

    private fun writeJar(
        dir: File,
        name: String,
        bytes: ByteArray,
    ): File = File(dir, name).apply { writeBytes(bytes) }

    private fun zipBytes(
        entries: List<Pair<String, ByteArray>>,
        extra: ByteArray = ByteArray(0),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                val entry = ZipEntry(name)
                entry.extra = extra
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun thinMacho(
        code: ByteArray = "CODE".toByteArray(),
        sig: ByteArray = "SIGNATURE".toByteArray(),
        page: Int = 0x1000,
        linkeditExtra: ByteArray = ByteArray(0),
        dataoff: Int = 0x1800,
        total: Int? = null,
    ): ByteArray {
        val seg = 72
        val sigCmd = 16
        val linkeditOff = page
        // Re-signing keeps the blob's offset and extends it to the end of the file.
        val actualTotal = total ?: (dataoff + sig.size)
        val fsize = actualTotal - linkeditOff
        val vmsize = (fsize + page - 1) / page * page
        val b = ByteBuffer.allocate(actualTotal).order(ByteOrder.LITTLE_ENDIAN)
        b
            .putInt(0xFEEDFACF.toInt())
            .putInt(0x01000007)
            .putInt(3)
            .putInt(2)
            .putInt(2)
            .putInt(seg + sigCmd)
        b.putInt(0).putInt(0) // flags, reserved
        b.putInt(0x19).putInt(seg) // LC_SEGMENT_64
        b.put("__LINKEDIT".toByteArray(Charsets.US_ASCII))
        b.position(56)
        b
            .putLong(0)
            .putLong(vmsize.toLong())
            .putLong(linkeditOff.toLong())
            .putLong(fsize.toLong())
        b.putInt(5).putInt(1)
        b.position(104)
        b
            .putInt(0x1D)
            .putInt(sigCmd)
            .putInt(dataoff)
            .putInt(sig.size)
        b.position(120)
        b.put(code)
        b.position(linkeditOff)
        b.put(linkeditExtra)
        b.position(dataoff)
        b.put(sig)
        return b.array()
    }

    private fun fatMacho(
        slices: List<ByteArray>,
        trailing: ByteArray = ByteArray(0),
        align: Int = 2,
    ): ByteArray {
        val header = 8 + 20 * slices.size
        val offsets = mutableListOf<Int>()
        var prev = header
        slices.forEach { s ->
            val bound = 1 shl align
            val off = (prev + bound - 1) / bound * bound
            offsets += off
            prev = off + s.size
        }
        val b = ByteBuffer.allocate(prev + trailing.size).order(ByteOrder.BIG_ENDIAN)
        b.put(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
        b.putInt(slices.size)
        slices.forEachIndexed { i, s ->
            b
                .putInt(0x01000007)
                .putInt(3)
                .putInt(offsets[i])
                .putInt(s.size)
                .putInt(align)
        }
        slices.forEachIndexed { i, s ->
            b.position(offsets[i])
            b.put(s)
        }
        b.position(prev)
        b.put(trailing)
        return b.array()
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "scripts/ci/canonical-jar-hash.py").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("repository root not found from ${System.getProperty("user.dir")}")
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
        task.stubManifest.fileValue(File(root, "manifest-stubs.txt"))
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
        type: Char = '0',
        linkName: String = "",
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
        header[156] = type.code.toByte()
        ascii(linkName, 157)
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

    private object EmptyInputChanges : InputChanges {
        override fun isIncremental(): Boolean = false

        override fun getFileChanges(property: FileCollection): Iterable<FileChange> = emptyList()

        override fun getFileChanges(property: Provider<out FileSystemLocation>): Iterable<FileChange> = emptyList()
    }

    private companion object {
        private const val TAR_BLOCK_BYTES = 512

        /** The FFmpeg 9.x majors 11's resources layout records, as `ffoffsets.c` prints them. */
        private val LAYOUT_MAJORS =
            listOf("avutil" to 61, "swresample" to 7, "avcodec" to 63, "avformat" to 63)

        private val LAYOUT_JSON =
            """{"ffmpegVersion": "9.0.2", "libraries": {${LAYOUT_MAJORS.joinToString(
                ", ",
            ) { (lib, major) -> "\"$lib\": $major" }}}, "structs": {}, "codecIds": {}, "constants": {}}"""
    }
}
