// SPDX-License-Identifier: Unlicense
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.RelativePath
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.register
import org.gradle.process.CommandLineArgumentProvider
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import javax.inject.Inject

/** The four desktop packaging targets of 11's platform matrix (D88, D89). */
internal enum class DesktopPackagingTarget(
    val id: String,
    val osWire: String,
    val archWire: String,
    val osName: String,
    val osArch: String,
    /** Subdirectory of the unpacked Temurin tree that is the JDK home ("" where the root is). */
    val homeSubdir: String,
) {
    WINDOWS_X64("windows-x64", "windows", "x64", "windows", "amd64", ""),

    // Temurin's macOS tarball carries Contents/Home under the top directory.
    MACOS_ARM64("macos-arm64", "macos", "arm64", "macos", "aarch64", "Contents/Home"),
    LINUX_X64("linux-x64", "linux", "x64", "linux", "amd64", ""),
    LINUX_ARM64("linux-arm64", "linux", "arm64", "linux", "aarch64", ""),
    ;

    /** The install kinds (wire names of `InstallKind`, 11 DesktopAppGraph) D89 gives this target. */
    val installKinds: List<String>
        get() =
            when (this) {
                WINDOWS_X64 -> listOf("msi", "zip")
                MACOS_ARM64 -> listOf("dmg", "mac-zip")
                LINUX_X64, LINUX_ARM64 -> listOf("deb", "rpm", "tar.gz")
            }
}

/** `neutrodyne-<size>.png`, the committed hicolor source names (desktopApp/icons/png). */
private val HICOLOR_ICON_NAME = Regex("neutrodyne-(\\d+)\\.png")

/**
 * Maps `icons/png` to (file, size) pairs for the hicolor tree, sorted by name. Stray non-PNG
 * entries (`.DS_Store`, editor backups, directories) are ignored; a PNG outside the naming
 * scheme or a missing/empty directory fails loudly rather than ship an icon-less package.
 */
internal fun hicolorIconEntries(iconsPngDir: File): List<Pair<File, String>> {
    val icons =
        iconsPngDir
            .listFiles { f -> f.isFile && f.extension.equals("png", ignoreCase = true) }
            .orEmpty()
            .sortedBy { it.name }
    if (icons.isEmpty()) {
        throw GradleException("icons/png carries no neutrodyne-<size>.png icons (hicolor mapping)")
    }
    return icons.map { icon ->
        val size =
            HICOLOR_ICON_NAME.matchEntire(icon.name)?.groupValues?.get(1)
                ?: throw GradleException("unexpected icon name ${icon.name} (hicolor mapping)")
        icon to size
    }
}

/**
 * The target this host can package for, or null on a host outside the matrix (dev machines).
 * `os.name` is the JVM's official versioned name ("Windows 11", "Windows Server 2025",
 * "Mac OS X"), so only the documented families are normalized to the matrix's OS ids; any
 * other name stays off-matrix rather than being guessed into a target.
 */
internal fun desktopPackagingTargetOf(
    osName: String,
    osArch: String,
): DesktopPackagingTarget? {
    // os.name starts with "Windows" on every release ("Windows 10", "Windows Server 2025"),
    // is exactly "Mac OS X" on macOS and exactly "Linux" on Linux.
    val name = osName.lowercase()
    val os =
        when {
            name.startsWith("windows") -> "windows"
            name == "mac os x" -> "macos"
            name == "linux" -> "linux"
            else -> return null
        }
    return DesktopPackagingTarget.entries.firstOrNull { it.osName == os && it.osArch == osArch }
}

/** `desktopApp/runtime.lock`, already narrowed to the current target's archive (11 Lockfiles). */
internal data class BundledRuntimeLock(
    val vendor: String,
    val vendorVersion: String,
    val version: String,
    val javaVersion: String,
    val archiveUrl: String,
    val sha256: String,
)

internal fun parseBundledRuntimeLock(
    lockFile: File,
    target: DesktopPackagingTarget,
): BundledRuntimeLock {
    val props = java.util.Properties()
    lockFile.inputStream().use { props.load(it) }

    fun key(name: String): String =
        props.getProperty(name)
            ?: throw GradleException("runtime.lock: missing '$name' (11 Lockfiles)")

    return BundledRuntimeLock(
        vendor = key("vendor"),
        vendorVersion = key("vendorVersion"),
        version = key("version"),
        javaVersion = key("javaVersion"),
        archiveUrl = key("${target.id}.archiveUrl"),
        sha256 = key("${target.id}.sha256"),
    )
}

/**
 * The packaging facts `desktopApp/build.gradle.kts` merges into `build-info.properties`
 * (11 DesktopAppGraph): the target's `os`, `arch` and `runtime`, and `""` on a host outside
 * the packaging matrix (every local `dev` build derives them from the running JVM).
 */
abstract class NeutrodyneDesktopPackagingExtension {
    /** Target id like `linux-x64`, or the empty string when this host packages nothing. */
    abstract val targetId: Property<String>

    /** `os` wire value (`windows`, `macos`, `linux`) of the packaging target; empty off-matrix. */
    abstract val os: Property<String>

    /** `arch` wire value (`x64`, `arm64`) of the packaging target; empty off-matrix. */
    abstract val arch: Property<String>

    /** The bundled runtime's vendor-version string (11: "Temurin-25.0.4.1+1"); empty off-matrix. */
    abstract val runtime: Property<String>

    /** The install kinds D89 gives this target, comma-separated (`msi,zip`); empty off-matrix. */
    abstract val installKinds: Property<String>
}

/** Downloads the pinned Temurin archive, checks its SHA-256 and unpacks it (11 Packaging pipeline). */
abstract class SetupBundledRuntime : DefaultTask() {
    @get:Input
    abstract val archiveUrl: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    /** The unpacked JDK tree root (`Contents/` inside on macOS), never the toolchain JDK. */
    @get:OutputDirectory
    abstract val home: DirectoryProperty

    /** The verified archive, kept beside [home] for `check-runtime-sources.sh --archive`. */
    @get:OutputFile
    abstract val archive: RegularFileProperty

    @get:Inject
    protected abstract val archiveOperations: ArchiveOperations

    @get:Inject
    protected abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun setup() {
        val archiveFile = archive.get().asFile
        downloadIfChanged(archiveFile)
        verifyChecksum(archiveFile)

        val homeDir = home.get().asFile
        val staging = File(homeDir.parentFile, homeDir.name + ".unpacking")
        fileSystemOperations.delete { delete(staging, homeDir) }
        staging.mkdirs()

        // The staged file is named "archive" (no suffix), so the unpacker is chosen by
        // magic bytes: Temurin ships ZIP for Windows ("PK") and gzip'd tar elsewhere (1f 8b).
        val tree =
            when (archiveFormatOf(archiveFile)) {
                RuntimeArchiveFormat.ZIP -> {
                    archiveOperations.zipTree(archiveFile)
                }

                RuntimeArchiveFormat.TAR_GZ -> {
                    archiveOperations.tarTree(archiveOperations.gzip(archiveFile))
                }
            }
        fileSystemOperations.copy {
            from(tree)
            into(staging)
            // Temurin archives carry one top-level directory (jdk-25.0.4.1+1/); drop it.
            eachFile {
                relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray())
            }
        }
        // eachFile dropped the archive's single top-level directory, so staging is the JDK root.
        // Windows JDKs carry bin/java.exe where POSIX ones carry bin/java; the macOS tarball
        // nests the tree under Contents/Home.
        val javaCandidates = listOf("bin/java", "bin/java.exe", "Contents/Home/bin/java")
        if (javaCandidates.none { File(staging, it).isFile }) {
            throw GradleException(
                "the pinned Temurin archive has no Java launcher (expected ${javaCandidates.joinToString()})",
            )
        }
        if (!staging.renameTo(homeDir)) {
            throw GradleException("cannot move the unpacked runtime to ${homeDir.absolutePath}")
        }
    }

    /** Downloads only when the local file is absent or its recorded checksum changed. */
    private fun downloadIfChanged(target: File) {
        if (target.isFile && sha256Of(target) == sha256.get()) return

        target.parentFile.mkdirs()
        val tmp = File(target.parentFile, target.name + ".download")
        logger.lifecycle("setupBundledRuntime: downloading ${archiveUrl.get()}")
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build().use { client ->
            val request = HttpRequest.newBuilder(URI.create(archiveUrl.get())).build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofFile(tmp.toPath()))
            if (response.statusCode() !in 200..299) {
                throw GradleException("downloading the pinned runtime failed: HTTP ${response.statusCode()}")
            }
        }
        if (!tmp.renameTo(target)) {
            throw GradleException("cannot move the downloaded runtime archive to ${target.absolutePath}")
        }
    }

    private fun verifyChecksum(archiveFile: File) {
        val actual = sha256Of(archiveFile)
        if (actual != sha256.get()) {
            throw GradleException(
                "runtime.lock: SHA-256 mismatch for ${archiveFile.name}: expected ${sha256.get()}, got $actual",
            )
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun archiveFormatOf(file: File): RuntimeArchiveFormat {
        val magic = file.inputStream().use { it.readNBytes(4) }
        return when {
            // Every ZIP record starts "PK"; 1f 8b is the gzip member header.
            magic.size >= 2 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte() -> {
                RuntimeArchiveFormat.ZIP
            }

            magic.size >= 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte() -> {
                RuntimeArchiveFormat.TAR_GZ
            }

            else -> {
                throw GradleException(
                    "the pinned runtime archive is neither ZIP nor gzip'd tar (unrecognized format)",
                )
            }
        }
    }

    private enum class RuntimeArchiveFormat {
        ZIP,
        TAR_GZ,
    }

    private companion object {
        private const val BUFFER_BYTES = 1 shl 16
    }
}

/**
 * The name a classpath file carries inside the image. Compose mangles JARs to
 * `<base>-<md5>.jar` when it fills the app dir (`FileUtilsKt.mangledName`, compose-gradle-plugin
 * 1.12.1; `mangleJarFilesNames` defaults on — only the never-used uber-jar path disables it);
 * other files keep their name. The manifest lists image names so `check-desktop-image.sh`
 * matches the actual `app/` entries.
 */
internal fun imageJarName(file: File): String {
    if (!file.isFile || !file.name.endsWith(".jar", ignoreCase = true)) return file.name
    // Compose's contentHash is the file's MD5 rendered with Integer.toHexString per byte —
    // no zero padding — so a byte below 0x10 gives a single hex digit.
    val md5 = MessageDigest.getInstance("MD5").digest(file.readBytes())
    val hash = md5.joinToString("") { Integer.toHexString(it.toInt() and 0xFF) }
    return "${file.nameWithoutExtension}-$hash.${file.extension}"
}

/**
 * Writes the Licensee-checked runtime classpath as `<canonical sha256>  name` lines
 * (09 Build-output checks): `check-desktop-image.sh` asserts every JAR of an image is
 * one of these files. Same-named JARs — `api-desktop.jar` comes from four modules, and
 * androidx artefacts appear twice via the `org.jetbrains.androidx` republish — are no
 * collision here: the recorded name is the mangled image name, which differs by content.
 *
 * The hash is canonical jar content, not the file's bytes: packaging recompresses the
 * image JARs on macOS after Compose has already mangled their names (nightly
 * 37860407043), so a byte hash would report every JAR as differing. The digest covers
 * each entry's name and uncompressed content SHA-256, sorted by entry name — the same
 * canonicalisation `jar_content_hash` performs in the checker.
 */
abstract class WriteDesktopRuntimeClasspath : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeClasspath: ConfigurableFileCollection

    @get:Input
    abstract val installKind: Property<String>

    @get:OutputFile
    abstract val manifest: RegularFileProperty

    /**
     * The same rows under `runtime-classpath-<installKind>.txt`: the own-app jar embeds
     * `installKind` in build-info.properties, so each packaging invocation's jar mangles
     * differently and one shared manifest cannot cover every package built in one job
     * (nightly 37868304885: the deb and rpm jars missed the manifest the tar.gz
     * invocation wrote). check-desktop-image.sh picks the image's kind file.
     */
    @get:OutputFile
    abstract val kindManifest: RegularFileProperty

    @TaskAction
    fun write() {
        val lines = sortedSetOf<String>()
        runtimeClasspath.files.filter { it.isFile }.forEach { file ->
            val imageName = imageJarName(file)
            lines += "${canonicalSha256Of(file)}  $imageName"
            if (file.name.startsWith("skiko-awt-runtime-")) {
                lines += skikoEntryRows(file, imageName)
            }
        }
        val text = lines.joinToString("\n", postfix = "\n")
        manifest.get().asFile.writeText(text)
        kindManifest.get().asFile.writeText(text)
    }

    /**
     * One `<entry sha256>  <image name>#<entry>` row per native or .sha256 sidecar a
     * skiko-awt-runtime classpath jar carries. Packaging rewrites that jar into a stub:
     * the host arch's libskiko lands loose beside it and the other targets' stay inside.
     * The stub's own .sha256 pins only self-consistency, so the scan binds each stub
     * native/sidecar byte to these rows of the Licensee-checked artifact.
     */
    private fun skikoEntryRows(
        file: File,
        imageName: String,
    ): List<String> {
        val zip =
            try {
                java.util.zip.ZipFile(file)
            } catch (e: java.util.zip.ZipException) {
                return emptyList()
            }
        val rows = mutableListOf<String>()
        zip.use { z ->
            z.entries().toList().forEach { entry ->
                if (entry.isDirectory) return@forEach
                val native = SKIKO_NATIVE_ENTRY.matches(entry.name)
                val sidecar = entry.name.endsWith(".sha256") && '/' !in entry.name
                if (!native && !sidecar) return@forEach
                val bytes =
                    normalizedEntryBytes(
                        entry.name,
                        z.getInputStream(entry).use { it.readBytes() },
                    )
                val hash = sha256Hex(bytes)
                rows += "$hash  $imageName#${entry.name}"
            }
        }
        return rows
    }

    /**
     * SHA-256 over `<entry name>\0<sha256hex(uncompressed content)>\n` per entry, sorted
     * by name — identical to `jar_content_hash` in check-desktop-image.sh. A non-zip file
     * falls back to its raw byte hash on both sides.
     */
    private fun canonicalSha256Of(file: File): String {
        val zip =
            try {
                java.util.zip.ZipFile(file)
            } catch (e: java.util.zip.ZipException) {
                return sha256Of(file)
            }
        val outer = MessageDigest.getInstance("SHA-256")
        zip.use { z ->
            z.entries().toList().sortedBy { it.name }.forEach { entry ->
                val inner = MessageDigest.getInstance("SHA-256")
                if (!entry.isDirectory) {
                    z.getInputStream(entry).use { input ->
                        inner.update(
                            normalizedEntryBytes(entry.name, input.readBytes()),
                        )
                    }
                }
                outer.update(entry.name.toByteArray(Charsets.UTF_8))
                outer.update(0)
                outer.update(
                    inner
                        .digest()
                        .joinToString("") { "%02x".format(it) }
                        .toByteArray(Charsets.US_ASCII),
                )
                outer.update('\n'.code.toByte())
            }
        }
        return outer.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * Mach-O entries are normalized before hashing: macOS packaging re-signs
     * natives inside image JARs in place, and re-signing a published dylib is
     * not byte-reversible (`codesign --remove-signature` cannot restore the
     * pre-sign bytes — it neither shrinks the grown __LINKEDIT segment nor
     * removes an LC_CODE_SIGNATURE slot inserted over header padding, nightly
     * 37874042553). The normalized form — shared byte-for-byte with
     * `norm_macho` in check-desktop-image.sh — emits `ncmds`/`sizeofcmds`
     * minus the signature slots (re-signing may insert one over header
     * padding), emits each LC_CODE_SIGNATURE command as zeros (covering both
     * the re-filled pre-allocated slot and the freshly-inserted command),
     * emits `__LINKEDIT`'s filesize minus the blob bytes inside it (vmsize is
     * zeroed — the signing slack is not invertible), and cuts the
     * `[dataoff, dataoff+datasize)` blob region. Anything else — including
     * every load command besides the signature bookkeeping and the effective
     * non-signature layout — stays byte-exact, so mutated content still
     * fails. Fat binaries normalize per slice: the fat_arch offset is zeroed
     * (it packs blob-carrying lengths) and size is emitted minus the slice's
     * signature bytes.
     */
    private fun normalizedEntryBytes(
        name: String,
        data: ByteArray,
    ): ByteArray = if (name.substringAfterLast('.') in MACHO_EXTS) normalizeMacho(data) else data

    private fun normalizeMacho(b: ByteArray): ByteArray {
        if (b.size >= 8 && u32(b, 0, be = true) == FAT_MAGIC) {
            val nfat = u32(b, 4, be = true).toInt()
            if (nfat < 0 || nfat > 64 || 8 + 20L * nfat > b.size) return b
            val out = java.io.ByteArrayOutputStream(b.size)
            out.write(b, 0, 8)
            for (i in 0 until nfat) {
                val ro = 8 + 20 * i
                val off = u32(b, ro + 8, be = true)
                val size = u32(b, ro + 12, be = true)
                if (off + size > b.size) return b
                val thin = normalizeThinMacho(b.copyOfRange(off.toInt(), (off + size).toInt()))
                if (thin.sigSize > size) return b
                out.write(b, ro, 8)
                out.write(ZERO4, 0, 4)
                out.write(u32Bytes(size - thin.sigSize, be = true), 0, 4)
                out.write(b, ro + 16, 4)
                out.write(thin.emit)
            }
            return out.toByteArray()
        }
        return normalizeThinMacho(b).emit
    }

    private class ThinResult(
        val emit: ByteArray,
        val sigSize: Long,
    )

    private fun normalizeThinMacho(b: ByteArray): ThinResult {
        if (b.size < 28) return ThinResult(b, 0)
        val is64: Boolean
        val be: Boolean
        when (u32(b, 0, be = false)) {
            MH_MAGIC_64 -> {
                is64 = true
                be = false
            }

            MH_MAGIC -> {
                is64 = false
                be = false
            }

            MH_CIGAM_64 -> {
                is64 = true
                be = true
            }

            MH_CIGAM -> {
                is64 = false
                be = true
            }

            else -> {
                return ThinResult(b, 0)
            }
        }
        val hdr = if (is64) 32 else 28
        val ncmds = u32(b, 16, be)
        val sizeofcmds = u32(b, 20, be)
        if (ncmds > 4096) return ThinResult(b, 0)
        val cmds = mutableListOf<LongArray>()
        val blobs = mutableListOf<LongArray>()
        var off = hdr
        for (i in 0 until ncmds.toInt()) {
            if (off + 8 > b.size) return ThinResult(b, 0)
            val cmd = u32(b, off, be)
            val csz = u32(b, off + 4, be)
            if (csz < 8 || off + csz > b.size) return ThinResult(b, 0)
            cmds += longArrayOf(cmd, csz, off.toLong())
            if (cmd == LC_CODE_SIGNATURE) {
                blobs += longArrayOf(u32(b, off + 8, be), u32(b, off + 12, be))
            }
            off += csz.toInt()
        }
        val cszSum = cmds.filter { it[0] == LC_CODE_SIGNATURE }.sumOf { it[1] }
        if (blobs.size.toLong() > ncmds || cszSum > sizeofcmds) return ThinResult(b, 0)
        val out = java.io.ByteArrayOutputStream(b.size)
        out.write(b, 0, 16)
        out.write(u32Bytes(ncmds - blobs.size, be), 0, 4)
        out.write(u32Bytes(sizeofcmds - cszSum, be), 0, 4)
        out.write(b, 24, hdr - 24)
        for (c in cmds) {
            val cmd = c[0]
            val csz = c[1].toInt()
            val co = c[2].toInt()
            if (cmd == LC_CODE_SIGNATURE) {
                out.write(ByteArray(csz))
                continue
            }
            val cb = b.copyOfRange(co, co + csz)
            if ((cmd == LC_SEGMENT || cmd == LC_SEGMENT_64) && isLinkeditName(cb)) {
                val foff: Long
                val fsize: Long
                if (is64) {
                    foff = u64(cb, 40, be)
                    fsize = u64(cb, 48, be)
                } else {
                    foff = u32(cb, 32, be)
                    fsize = u32(cb, 36, be)
                }
                val inside =
                    blobs
                        .filter { foff <= it[0] && it[0] + it[1] <= foff + fsize }
                        .sumOf { it[1] }
                if (inside > fsize) return ThinResult(b, 0)
                if (is64) {
                    // vmsize is signing slack and not invertible.
                    java.util.Arrays.fill(cb, 32, 40, 0.toByte())
                    put64(cb, 48, fsize - inside, be)
                } else {
                    java.util.Arrays.fill(cb, 28, 32, 0.toByte())
                    put32(cb, 36, fsize - inside, be)
                }
            }
            out.write(cb)
        }
        var emit = out.toByteArray() + b.copyOfRange(off, b.size)
        val base = out.size().toLong() - off
        var shift = 0L
        for (blob in blobs.sortedWith(compareBy({ it[0] }, { it[1] }))) {
            val s = blob[0] + base - shift
            if (s in 0 until emit.size) {
                val e = minOf(emit.size.toLong(), s + blob[1]).toInt()
                emit = emit.copyOfRange(0, s.toInt()) + emit.copyOfRange(e, emit.size)
            }
            shift += blob[1]
        }
        return ThinResult(emit, blobs.sumOf { it[1] })
    }

    private fun isLinkeditName(cmdBytes: ByteArray): Boolean {
        if (cmdBytes.size < 24) return false
        var end = 24
        while (end > 8 && cmdBytes[end - 1] == 0.toByte()) end--
        return cmdBytes.copyOfRange(8, end).contentEquals("__LINKEDIT".toByteArray())
    }

    private fun u32(
        b: ByteArray,
        o: Int,
        be: Boolean,
    ): Long =
        if (be) {
            ((b[o].toLong() and 0xff) shl 24) or
                ((b[o + 1].toLong() and 0xff) shl 16) or
                ((b[o + 2].toLong() and 0xff) shl 8) or
                (b[o + 3].toLong() and 0xff)
        } else {
            (b[o].toLong() and 0xff) or
                ((b[o + 1].toLong() and 0xff) shl 8) or
                ((b[o + 2].toLong() and 0xff) shl 16) or
                ((b[o + 3].toLong() and 0xff) shl 24)
        }

    private fun u64(
        b: ByteArray,
        o: Int,
        be: Boolean,
    ): Long =
        if (be) {
            (u32(b, o, true) shl 32) or u32(b, o + 4, true)
        } else {
            (u32(b, o + 4, false) shl 32) or
                u32(b, o, false)
        }

    private fun u32Bytes(
        v: Long,
        be: Boolean,
    ): ByteArray {
        val w = ByteArray(4)
        put32(w, 0, v, be)
        return w
    }

    private fun put32(
        b: ByteArray,
        o: Int,
        v: Long,
        be: Boolean,
    ) {
        if (be) {
            b[o] = (v shr 24).toByte()
            b[o + 1] = (v shr 16).toByte()
            b[o + 2] = (v shr 8).toByte()
            b[o + 3] = v.toByte()
        } else {
            b[o] = v.toByte()
            b[o + 1] = (v shr 8).toByte()
            b[o + 2] = (v shr 16).toByte()
            b[o + 3] = (v shr 24).toByte()
        }
    }

    private fun put64(
        b: ByteArray,
        o: Int,
        v: Long,
        be: Boolean,
    ) {
        if (be) {
            put32(b, o, v shr 32, true)
            put32(b, o + 4, v, true)
        } else {
            put32(b, o, v, false)
            put32(b, o + 4, v shr 32, false)
        }
    }

    private companion object {
        private const val BUFFER_BYTES = 1 shl 16
        private val ZERO4 = ByteArray(4)
        private val MACHO_EXTS = setOf("dylib", "jnilib", "so")
        private const val MH_MAGIC = 0xfeedfaceL
        private const val MH_MAGIC_64 = 0xfeedfacfL
        private const val MH_CIGAM = 0xcefaedfeL
        private const val MH_CIGAM_64 = 0xcffaedfeL
        private const val FAT_MAGIC = 0xcafebabeL
        private const val LC_SEGMENT = 0x1L
        private const val LC_SEGMENT_64 = 0x19L
        private const val LC_CODE_SIGNATURE = 0x1dL

        /** Root-level `libskiko-*`/`skiko-*` natives — the same allowlist jar_stub_only uses. */
        private val SKIKO_NATIVE_ENTRY = Regex("^(?:libskiko-|skiko-)[^/]+\\.(?:so|dylib|dll)$")
    }
}

/**
 * One `jpackage` call from the runtime.lock JDK over the prepared app image (11 Packaging
 * pipeline). The Compose plugin has no `--resource-dir` hook (recorded 2026-10-06 in 11
 * nativeDistributions configuration), so the DEB — which needs our own `control` and
 * maintainer scripts — and the RPM — which pins its soname `Requires` — are packaged here
 * instead of through Compose's `packageDeb`/`packageRpm` (names reused so the documented
 * invocations stay `:desktopApp:packageDeb` and `:desktopApp:packageRpm`).
 */
abstract class JPackageImageTask : Exec()

/** The Windows portable ZIP: the app image archived (11 Windows MSI and ZIP). */
abstract class WindowsZipTask : Zip()

/** The Linux tar.gz: the app image as `Neutrodyne/` (11 Linux DEB, RPM and tar.gz). */
abstract class LinuxTarGzTask : Tar()

internal fun Project.registerDesktopPackagingTasks(
    target: DesktopPackagingTarget,
    lock: BundledRuntimeLock,
) {
    val versionName = providers.gradleProperty("neutrodyne.versionName").get()
    val setupRuntime = tasks.named("setupBundledRuntime", SetupBundledRuntime::class.java)
    val jpackage =
        setupRuntime.map { task ->
            File(File(task.home.get().asFile, target.homeSubdir), "bin/jpackage")
        }

    // The app image is createDistributable's destinationDir/<name>[.app]; taking it from the
    // task keeps this in step with the plugin's layout instead of hardcoding compose/binaries.
    val appImageLeaf =
        if (target ==
            DesktopPackagingTarget.MACOS_ARM64
        ) {
            "$DESKTOP_PACKAGE_NAME.app"
        } else {
            DESKTOP_PACKAGE_NAME
        }
    val appImage =
        tasks
            .named(
                "createDistributable",
                org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask::class.java,
            ).flatMap { it.destinationDir }
            .map { it.dir(appImageLeaf) }

    // The desktop entry and hicolor icons the DEB/RPM scriptlets install travel inside the
    // image through appResourcesRootDir (11 Links and files from the OS). The PNG set comes
    // from the committed brand assets (desktopApp/icons/png), mapped into the hicolor layout.
    // This is the one task writing desktop-resources: Gradle's Sync deletes destination files
    // it did not copy, so a second Sync into the same root would wipe this task's payload
    // (a separate syncWindowIcons did exactly that until 2026-10-08).
    // Every from() below is a directory or an eachFile mapping so the file listing is walked
    // at execution: a per-file spec would freeze today's listing in the configuration cache
    // and silently drop an added icon on reuse (and the empty-dir check would never run).
    val iconsPngDir = file("icons/png")
    val iconsTrayDir = file("icons/tray")
    tasks.register<org.gradle.api.tasks.Sync>("syncDesktopIntegrationResources") {
        // Compose merges appResourcesRootDir/{common,<os>,<os>-<arch>} into the image's
        // resources dir, so the Linux payload nests under its <os> dir.
        into(layout.buildDirectory.dir("desktop-resources"))
        // The window and tray icons ride along on every target under common/icons, so a
        // packaged app loads the same committed PNGs a dev run reads from icons/ (11
        // Resources layout).
        from(iconsPngDir) { into("common/icons/png") }
        from(iconsTrayDir) { into("common/icons/tray") }
        if (target.osWire == "linux") {
            from("packaging/desktop-integration") { into("linux/desktop-integration") }
            // neutrodyne-48.png -> linux/desktop-integration/hicolor/48x48/apps/neutrodyne.png
            // (eachFile's path is relative to the task destination, not this spec's into).
            from(iconsPngDir) {
                eachFile {
                    if (isDirectory || !name.endsWith(".png", ignoreCase = true)) {
                        exclude()
                    } else {
                        val size =
                            HICOLOR_ICON_NAME.matchEntire(name)?.groupValues?.get(1)
                                ?: throw GradleException("unexpected icon name $name (hicolor mapping)")
                        path = "linux/desktop-integration/hicolor/${size}x$size/apps/neutrodyne.png"
                    }
                }
            }
        }
        // Presence and naming are validated at execution, where a configuration-cache hit
        // cannot skip them.
        doLast {
            hicolorIconEntries(iconsPngDir)
            if (iconsTrayDir.listFiles().isNullOrEmpty()) {
                throw GradleException("icons/tray carries no icons (11 Resources layout)")
            }
        }
    }

    tasks.named("createDistributable") {
        dependsOn("syncDesktopIntegrationResources", "writeDesktopRuntimeClasspath")
    }
    // Compose merges appResourcesRootDir/{common,<os>,<os>-<arch>} into the image through its own
    // Sync; ours must run first or Gradle flags the shared output dir as an implicit dependency.
    tasks.matching { it.name == "prepareAppResources" }.configureEach {
        dependsOn("syncDesktopIntegrationResources")
    }

    // Values the task actions and argument providers need, captured as plain types up front so
    // the configuration cache can serialize the tasks (no Project/layout/providers inside).
    val installKindProperty = providers.gradleProperty("neutrodyne.installKind")
    val licenseFile = rootProject.file("LICENSE")
    val debResourceDir = file("packaging/deb")
    val rpmResourceDir = file("packaging/rpm")
    val debDest =
        layout.buildDirectory
            .dir("desktop-packaging/deb")
            .get()
            .asFile
    val rpmDest =
        layout.buildDirectory
            .dir("desktop-packaging/rpm")
            .get()
            .asFile
    val jpackagePath = jpackage.get().absolutePath
    // appImage stays a provider: it maps over createDistributable's destinationDir, which
    // Gradle refuses to query before that task has run, so the packaging tasks resolve it
    // inside their argument providers and from() specs — the lazy wiring the comment above
    // describes is for the values that *can* be read at configuration time.

    /** Packaging tasks refuse when the image does not carry their install kind (11 Resources layout). */
    fun org.gradle.api.Task.requireInstallKind(kind: String) =
        doFirst {
            val actual = installKindProperty.orNull
            if (actual != kind) {
                throw GradleException(
                    "${this@requireInstallKind.name} needs -Pneutrodyne.installKind=$kind " +
                        "(found ${actual ?: "dev"}), so the image carries its install kind (11 Resources layout)",
                )
            }
        }

    // --- DEB: jpackage with our own control file and maintainer scripts -----------------------
    tasks.register<JPackageImageTask>("packageDeb") {
        group = "distribution"
        description = "jpackages the app image into the DEB with our control and maintainer scripts (11)."
        dependsOn("createDistributable")
        requireInstallKind("deb")
        executable(jpackagePath)
        argumentProviders.add(
            CommandLineArgumentProvider {
                listOf(
                    "--type",
                    "deb",
                    "--name",
                    DESKTOP_PACKAGE_NAME,
                    "--app-image",
                    appImage.get().asFile.absolutePath,
                    "--app-version",
                    versionName,
                    "--description",
                    DESKTOP_DESCRIPTION,
                    "--vendor",
                    DESKTOP_VENDOR,
                    "--license-file",
                    licenseFile.absolutePath,
                    "--install-dir",
                    LINUX_INSTALL_PARENT,
                    "--linux-package-name",
                    LINUX_PACKAGE_NAME,
                    "--linux-app-release",
                    "1",
                    "--linux-app-category",
                    "AudioVideo",
                    "--linux-deb-maintainer",
                    DESKTOP_DEB_MAINTAINER,
                    "--resource-dir",
                    debResourceDir.absolutePath,
                    "--dest",
                    debDest.absolutePath,
                )
            },
        )
        // jpackage runs dpkg-deb, whose default zstd is unreadable inside our glibc floor (11).
        environment("DPKG_DEB_COMPRESSOR_TYPE", "xz")
        doFirst { debDest.mkdirs() }
    }

    // --- RPM: jpackage with our own spec template (soname Requires + scriptlets, 11) ---------
    tasks.register<JPackageImageTask>("packageRpm") {
        group = "distribution"
        description = "jpackages the app image into the RPM with our spec (11)."
        dependsOn("createDistributable")
        requireInstallKind("rpm")
        executable(jpackagePath)
        argumentProviders.add(
            CommandLineArgumentProvider {
                listOf(
                    "--type",
                    "rpm",
                    "--name",
                    DESKTOP_PACKAGE_NAME,
                    "--app-image",
                    appImage.get().asFile.absolutePath,
                    "--app-version",
                    versionName,
                    "--description",
                    DESKTOP_DESCRIPTION,
                    "--vendor",
                    DESKTOP_VENDOR,
                    "--license-file",
                    licenseFile.absolutePath,
                    "--install-dir",
                    LINUX_INSTALL_PARENT,
                    "--linux-package-name",
                    LINUX_PACKAGE_NAME,
                    "--linux-app-release",
                    "1",
                    "--linux-app-category",
                    "AudioVideo",
                    "--linux-rpm-license-type",
                    "Unlicense",
                    "--resource-dir",
                    rpmResourceDir.absolutePath,
                    "--dest",
                    rpmDest.absolutePath,
                )
            },
        )
        doFirst { rpmDest.mkdirs() }
    }

    // --- Windows ZIP: the image, with its Neutrodyne/ top directory ----------------------------
    tasks.register<WindowsZipTask>("packageZip") {
        group = "distribution"
        description = "Archives the app image as the portable Windows ZIP (11 Windows MSI and ZIP)."
        dependsOn("createDistributable")
        requireInstallKind("zip")
        archiveFileName.set("neutrodyne-$versionName-${target.id}.zip")
        destinationDirectory.set(layout.buildDirectory.dir("desktop-packaging/zip"))
        from(appImage.map { it.asFile.parentFile }) { include("$appImageLeaf/**") }
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    // --- Linux tar.gz: the image as Neutrodyne/, executable bits carried from the image -------
    tasks.register<LinuxTarGzTask>("packageTarGz") {
        group = "distribution"
        description = "Archives the app image as the portable Linux tar.gz (11 Linux DEB, RPM and tar.gz)."
        dependsOn("createDistributable")
        requireInstallKind("tar.gz")
        archiveFileName.set("neutrodyne-$versionName-${target.id}.tar.gz")
        destinationDirectory.set(layout.buildDirectory.dir("desktop-packaging/tar-gz"))
        compression = org.gradle.api.tasks.bundling.Compression.GZIP
        from(appImage.map { it.asFile.parentFile }) { include("$appImageLeaf/**") }
        // Gradle 9's replacement for dirMode/fileMode: the tar keeps the modes the image files
        // already have (jpackage writes the launcher and jspawnhelper with 0755).
        useFileSystemPermissions()
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    tasks.register<WriteDesktopRuntimeClasspath>("writeDesktopRuntimeClasspath") {
        description = "Writes the runtime-classpath manifest check-desktop-image.sh checks image JARs against."
        // Compose resolves project dependencies to their jar artifacts for the image,
        // so the manifest must ask for jar artifacts too (otherwise project deps show
        // up as classes directories) and include this project's own jar.
        runtimeClasspath.from(
            configurations.named("runtimeClasspath").map { conf ->
                conf.incoming
                    .artifactView {
                        attributes {
                            attribute(
                                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                                ArtifactTypeDefinition.JAR_TYPE,
                            )
                        }
                    }.files
            },
        )
        runtimeClasspath.from(tasks.named("jar"))
        installKind.set(installKindProperty.orElse("dev"))
        manifest.set(layout.buildDirectory.file("desktop-packaging/runtime-classpath.txt"))
        kindManifest.set(
            installKind.flatMap { kind ->
                layout.buildDirectory.file("desktop-packaging/runtime-classpath-$kind.txt")
            },
        )
    }
}
