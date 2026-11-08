// SPDX-License-Identifier: Unlicense
import org.gradle.api.GradleException
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.file.FileSystemLocation
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.work.FileChange
import org.gradle.work.InputChanges
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
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
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
                            if [ "${'$'}want_dest" = 1 ]; then dest="${'$'}line"; want_dest=0; fi
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

            val resources = project.layout.buildDirectory.dir("compose/tmp/resources").get().asFile
            assertEquals("#!/bin/sh\n", File(resources, "Neutrodyne-post-image.sh").readText())
        } finally {
            projectDir.deleteRecursively()
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

        override fun getFileChanges(
            property: Provider<out FileSystemLocation>,
        ): Iterable<FileChange> = emptyList()
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
