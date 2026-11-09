// SPDX-License-Identifier: Unlicense
import groovy.json.JsonSlurper
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
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.register
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.process.ExecOperations
import org.gradle.work.InputChanges
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
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

/**
 * The package-level licence metadata (DEB/RPM `--license-file`, MSI/DMG licence page, RPM
 * `License:`): the package bundles the OpenJDK runtime and third-party libraries besides our
 * Unlicense code, so the file lists them and the SPDX expression covers them. Extended for the
 * python-build-standalone CPython stack, the vendored yt-dlp and certifi's CA bundle (MD3).
 */
internal const val DESKTOP_PACKAGE_LICENSE_FILE = "packaging/LICENSE-PACKAGE.txt"
internal const val DESKTOP_PACKAGE_LICENSE =
    "Unlicense AND Apache-2.0 AND MIT AND MIT-0 AND MIT-Modern-Variant AND BSD-2-Clause AND BSD-3-Clause " +
        "AND FTL AND libpng-2.0 AND IJG AND Zlib AND Unicode-3.0 AND MPL-2.0 AND blessing AND bzip2-1.0.6 " +
        "AND 0BSD AND ISC AND Python-2.0 AND X11 AND LGPL-2.1-or-later " +
        "AND GPL-2.0-only WITH Classpath-exception-2.0"

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

// --- the image's native/ and licenses/ payloads (11 Resources layout) ------------------------------

/** The four FFmpeg libraries the lock records, in the order the engine loads them (11 FFM bindings). */
private val FFMPEG_LIBRARIES = listOf("avutil", "swresample", "avcodec", "avformat")

/** The upstream soname a shipped FFmpeg library carries on one OS (11 FFmpeg LGPL obligations). */
private val FFMPEG_SONAME =
    mapOf(
        "windows" to Regex("^(avcodec|avformat|avutil|swresample)-\\d+\\.dll$"),
        "macos" to Regex("^lib(avcodec|avformat|avutil|swresample)\\.\\d+\\.dylib$"),
        "linux" to Regex("^lib(avcodec|avformat|avutil|swresample)\\.so\\.\\d+$"),
    )

/** The build records the engine and the image scan read beside the libraries (11 Resources layout). */
private val FFMPEG_IMAGE_METADATA = setOf("ffmpeg-layout.json", "ffmpeg-license.txt")

/** The licences payload every image carries under `common/licenses/` (11 Resources layout). */
private val IMAGE_LICENCE_FILES = listOf("THIRD_PARTY_NOTICES.md", "COPYING.LGPLv2.1", "miniaudio.txt")

/** FFmpeg installs its shared libraries under lib/, its Windows DLLs under bin/. */
private fun ffmpegLibSubdir(osWire: String): String = if (osWire == "windows") "bin" else "lib"

/** The file name one FFmpeg library carries on the target's OS, upstream naming kept. */
private fun ffmpegFileName(
    osWire: String,
    library: String,
    major: Int,
): String =
    when (osWire) {
        "windows" -> "$library-$major.dll"
        "macos" -> "lib$library.$major.dylib"
        else -> "lib$library.so.$major"
    }

private fun ndmediaFileName(osWire: String): String =
    when (osWire) {
        "windows" -> "ndmedia.dll"
        "macos" -> "libndmedia.dylib"
        else -> "libndmedia.so"
    }

/**
 * Whether a file under the `buildFfmpeg` output joins the image (11 Resources layout):
 * `ffmpeg-layout.json` and `ffmpeg-license.txt` at the root, and the four shared libraries
 * under the target's library directory — versioned sonames only, never headers, import
 * libraries, static archives, unversioned links or the build's own records
 * (`ffmpeg-build.txt`, `ffmpeg-components.txt`, …). [relativePath] is the path below the
 * build output, with `/` separators.
 */
internal fun ffmpegImageEntryAllowed(
    osWire: String,
    relativePath: String,
): Boolean {
    val segments = relativePath.split('/')
    if (segments.size == 1) return relativePath in FFMPEG_IMAGE_METADATA
    if (segments.size != 2) return false
    return segments[0] == ffmpegLibSubdir(osWire) &&
        FFMPEG_SONAME.getValue(osWire).matches(segments[1])
}

/**
 * The missing pieces of the staged `desktop-resources` tree, empty when complete
 * (11 Resources layout): `ffmpeg-layout.json`, every soname it records, the licence
 * text, the ndmedia library and the licences payload. `createDistributable` fails
 * closed on any of them rather than ship an image the engine cannot load or whose
 * LGPL notices are absent.
 */
internal fun stagedNativeProblems(
    target: DesktopPackagingTarget,
    destinationDir: File,
): List<String> {
    val problems = mutableListOf<String>()
    val ffmpegDir = destinationDir.resolve("${target.id}/native/ffmpeg")
    val layoutFile = ffmpegDir.resolve("ffmpeg-layout.json")
    if (!layoutFile.isFile) {
        problems += "ffmpeg-layout.json missing from the image (11 Resources layout)"
    } else {
        // The majors come from the image's own layout record: the sonames move with the
        // pinned FFmpeg version, so the check never hardcodes a version.
        val majors =
            try {
                (JsonSlurper().parse(layoutFile) as? Map<*, *>)
                    ?.get("libraries") as? Map<*, *>
            } catch (invalid: RuntimeException) {
                problems += "ffmpeg-layout.json is not valid JSON (11 Resources layout)"
                null
            }
        for (library in FFMPEG_LIBRARIES) {
            val major =
                (majors?.get(library) as? Number)?.toInt()
                    ?: run {
                        problems += "ffmpeg-layout.json carries no $library entry"
                        continue
                    }
            val soname =
                ffmpegDir
                    .resolve(ffmpegLibSubdir(target.osWire))
                    .resolve(ffmpegFileName(target.osWire, library, major))
            if (!soname.isFile) {
                problems += "${soname.relativeTo(destinationDir)} missing from the image (11 FFmpeg LGPL obligations)"
            }
        }
        if (!ffmpegDir.resolve("ffmpeg-license.txt").isFile) {
            problems += "ffmpeg-license.txt missing from the image (11 FFmpeg LGPL obligations)"
        }
    }
    val ndmedia =
        destinationDir.resolve("${target.id}/native/ndmedia/${ndmediaFileName(target.osWire)}")
    if (!ndmedia.isFile) {
        problems += "${ndmedia.relativeTo(destinationDir)} missing from the image (11 Resources layout)"
    }
    for (name in IMAGE_LICENCE_FILES) {
        val licence = destinationDir.resolve("common/licenses/$name")
        if (!licence.isFile || licence.length() == 0L) {
            problems += "common/licenses/$name missing or empty (11 licences/ payload)"
        }
    }
    return problems
}

/**
 * The missing pieces of the staged `desktop-resources` engine payload, empty when complete
 * (11 Resources layout): the trimmed interpreter at `<os>-<arch>/engine/python/` (its entry
 * `python/bin/python3` off Windows, `python/python.exe` on Windows) and the shared inputs at
 * `common/engine/` — the CA bundle, the yt-dlp zip and manifest, the signing keys and the
 * shim package the stdio child runs. `createDistributable` fails closed on any of them
 * rather than ship an image whose engine cannot start.
 */
internal fun stagedEngineProblems(
    target: DesktopPackagingTarget,
    destinationDir: File,
): List<String> {
    val problems = mutableListOf<String>()
    val python = destinationDir.resolve("${target.id}/engine/python")
    val interpreter =
        if (target.osWire == "windows") {
            python.resolve("python.exe")
        } else {
            python.resolve("bin/python3")
        }
    if (!interpreter.isFile) {
        problems += "${interpreter.relativeTo(destinationDir)} missing from the image (11 Resources layout)"
    }
    val engine = destinationDir.resolve("common/engine")
    for (
    name in
    listOf(
        "cacert.pem",
        "ytdlp/yt-dlp",
        "ytdlp/bundled.json",
        "keys/engine-manifest-ed25519.pub",
        "keys/yt-dlp-release-key.asc",
    )
    ) {
        if (!engine.resolve(name).isFile) {
            problems += "common/engine/$name missing from the image (11 Resources layout)"
        }
    }
    if (!engine.resolve("shim/neutrodyne_ytx").isDirectory) {
        problems += "common/engine/shim/neutrodyne_ytx missing from the image (11 Resources layout)"
    }
    return problems
}

/**
 * Writes the image's `licenses/` payload (11 Resources layout): the AboutLibraries notices
 * of everything the package ships, FFmpeg's own LGPL-2.1 text out of the pinned source
 * archive's extracted cache (a separate entry from JNA's variant) and miniaudio's MIT-0.
 */
abstract class WriteDesktopLicencesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val definitions: RegularFileProperty

    /** FFmpeg's own COPYING.LGPLv2.1, extracted from the pinned source by `buildFfmpeg`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val lgplText: RegularFileProperty

    /** The miniaudio licence entry (desktopApp/config/licenses/MIT-0-miniaudio.json). */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val miniaudioLicense: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun write() {
        val out = outputDirectory.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        // renderThirdPartyNotices throws when a shipped component's licence text is missing.
        out.resolve("THIRD_PARTY_NOTICES.md").writeText(renderThirdPartyNotices(definitions.get().asFile))
        out.resolve("COPYING.LGPLv2.1").writeBytes(lgplText.get().asFile.readBytes())
        val miniaudio = JsonSlurper().parse(miniaudioLicense.get().asFile) as Map<*, *>
        val content =
            miniaudio["content"]?.toString()?.trim()
                ?: throw GradleException("${miniaudioLicense.get().asFile.name} carries no licence content")
        out.resolve("miniaudio.txt").writeText(content + "\n")
    }
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

    @get:Inject
    protected abstract val execOperations: ExecOperations

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
        when (archiveFormatOf(archiveFile)) {
            RuntimeArchiveFormat.ZIP -> {
                fileSystemOperations.copy {
                    from(archiveOperations.zipTree(archiveFile))
                    into(staging)
                    eachFile {
                        relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray())
                    }
                }
            }

            RuntimeArchiveFormat.TAR_GZ -> {
                // Gradle's tarTree turns Temurin's legal-notice links into regular files.
                // Unpack the checksum-verified archive with tar so the reference JDK keeps
                // its real notice content, link targets and executable permissions.
                execOperations.exec {
                    commandLine(
                        "tar",
                        "-xzf",
                        archiveFile.absolutePath,
                        "--strip-components=1",
                        "-C",
                        staging.absolutePath,
                    )
                }
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
 * The canonical JAR identity of 11 (Image scan rules): SHA-256 over each entry's name and
 * uncompressed content SHA-256, sorted by entry name. Packaging recompresses image JARs and
 * re-signs their Mach-O natives, so a raw file hash would report every JAR as differing;
 * `canonical-jar-hash.py` is the checker's identical implementation. Mach-O entries are
 * normalized (LC_CODE_SIGNATURE zeroed, blobs cut, __LINKEDIT bound to the non-signature
 * extent); every other byte — code, data, unknown commands, zero FAT alignment padding and
 * trailing bytes — is bound. A non-ZIP file falls back to its raw byte hash. Malformed or
 * ambiguous ZIP names and duplicate entries fail closed.
 */
internal object CanonicalJarHash {
    private val MACHO_EXTS = setOf("dylib", "jnilib", "so")

    /**
     * The Compose-repacked skiko stub and the native files it extracts, derived from the
     * Licensee-checked source artifact [file] and the target's extraction rule [extracted]
     * (an entry is extracted when its name, with any `.sha256` suffix removed, is in the set).
     * The expected stub hash is the canonical content identity of the remaining entries, so
     * the image checker can bind the stub without ever trusting a hash taken from the image.
     */
    data class StubDerivation(
        val expectedHash: String,
        val extracted: List<Pair<String, String>>,
    )

    fun of(file: File): String {
        val raw = file.readBytes()
        val zip =
            try {
                java.util.zip.ZipFile(file)
            } catch (_: java.util.zip.ZipException) {
                return sha256Hex(raw)
            }
        return zip.use { z -> compute(z) }
    }

    fun stubOf(
        file: File,
        extracted: Set<String>,
    ): StubDerivation {
        val zip = java.util.zip.ZipFile(file)
        return zip.use { z -> computeStub(z, extracted) }
    }

    private fun compute(zip: java.util.zip.ZipFile): String {
        val entries = zip.entries().toList()
        val seen = HashSet<String>()
        for (entry in entries) {
            validateName(entry.name)
            if (!seen.add(entry.name.lowercase(Locale.ROOT))) {
                throw IllegalArgumentException("duplicate ZIP entry name: ${entry.name}")
            }
        }
        val outer = MessageDigest.getInstance("SHA-256")
        for (entry in entries.sortedBy { it.name }) {
            val inner = MessageDigest.getInstance("SHA-256")
            if (!entry.isDirectory) {
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                inner.update(normalizedEntryBytes(entry.name, bytes))
            }
            outer.update(entry.name.toByteArray(Charsets.UTF_8))
            outer.update(0)
            outer.update(hexToBytes(inner.digest()))
            outer.update('\n'.code.toByte())
        }
        return hexToString(outer.digest())
    }

    private fun computeStub(
        zip: java.util.zip.ZipFile,
        extracted: Set<String>,
    ): StubDerivation {
        val entries = zip.entries().toList()
        val seen = HashSet<String>()
        for (entry in entries) {
            validateName(entry.name)
            if (!seen.add(entry.name.lowercase(Locale.ROOT))) {
                throw IllegalArgumentException("duplicate ZIP entry name: ${entry.name}")
            }
        }
        val remaining = mutableListOf<Pair<String, ByteArray>>()
        val extractedFiles = mutableListOf<Pair<String, String>>()
        for (entry in entries) {
            val base =
                if (entry.name.endsWith(".sha256")) {
                    entry.name.removeSuffix(".sha256")
                } else {
                    entry.name
                }
            val bytes =
                if (entry.isDirectory) {
                    ByteArray(0)
                } else {
                    zip.getInputStream(entry).use { it.readBytes() }
                }
            if (base in extracted) {
                extractedFiles += entry.name.substringAfterLast('/') to
                    sha256Hex(normalizedEntryBytes(entry.name, bytes))
            } else {
                remaining += entry.name to normalizedEntryBytes(entry.name, bytes)
            }
        }
        val outer = MessageDigest.getInstance("SHA-256")
        for ((name, bytes) in remaining.sortedBy { it.first }) {
            outer.update(name.toByteArray(Charsets.UTF_8))
            outer.update(0)
            outer.update(hexToBytes(MessageDigest.getInstance("SHA-256").digest(bytes)))
            outer.update('\n'.code.toByte())
        }
        return StubDerivation(hexToString(outer.digest()), extractedFiles.sortedBy { it.first })
    }

    private fun hexToString(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(bytes: ByteArray): ByteArray = hexToString(bytes).toByteArray(Charsets.US_ASCII)

    private fun validateName(name: String) {
        require(name.isNotEmpty()) { "empty ZIP entry name" }
        require(!name.startsWith("/")) { "absolute ZIP entry name: $name" }
        require(!name.contains('\\')) { "backslash in ZIP entry name: $name" }
        require(!name.contains('\u0000')) { "NUL in ZIP entry name" }
        require(name.split('/').none { it == ".." }) { "parent-directory ZIP entry name: $name" }
        require(name.all { it.code < 128 }) { "non-ASCII ZIP entry name: $name" }
    }

    private fun normalizedEntryBytes(
        name: String,
        data: ByteArray,
    ): ByteArray = if (name.substringAfterLast('.') in MACHO_EXTS) normalizeMacho(data) else data

    private fun normalizeMacho(b: ByteArray): ByteArray =
        if (b.size >= 8 && u32(b, 0, be = true) == FAT_MAGIC) {
            normalizeFatMacho(b)
        } else {
            normalizeThinMacho(b).emit
        }

    private fun normalizeFatMacho(b: ByteArray): ByteArray {
        val nfat = u32(b, 4, be = true).toInt()
        if (nfat < 1 || nfat > 64 || 8 + 20L * nfat > b.size) return b
        val out = ByteArrayOutputStream(b.size)
        out.write(b, 0, 8)
        var prevEnd = 8 + 20L * nfat
        for (i in 0 until nfat) {
            val ro = 8 + 20 * i
            val off = u32(b, ro + 8, be = true)
            val size = u32(b, ro + 12, be = true)
            val align = u32(b, ro + 16, be = true)
            if (align > 30) return b
            val bound = 1L shl align.toInt()
            val expected = (prevEnd + bound - 1) / bound * bound
            if (off != expected) return b
            if (off + size > b.size) return b
            // Alignment padding is metadata-derived: require it zero and bind nothing.
            if (!isZero(b, prevEnd.toInt(), (off - prevEnd).toInt())) return b
            val thin = normalizeThinMacho(b.copyOfRange(off.toInt(), (off + size).toInt()))
            if (thin.sigSize > size) return b
            out.write(b, ro, 8)
            out.write(ZERO4, 0, 4) // the slice offset is metadata-derived (validated above)
            out.write(u32Bytes(size - thin.sigSize, be = true), 0, 4)
            out.write(b, ro + 16, 4)
            out.write(thin.emit)
            prevEnd = off + size
        }
        // Trailing bytes are not metadata-derived: bind them.
        if (prevEnd < b.size) out.write(b, prevEnd.toInt(), (b.size - prevEnd).toInt())
        return out.toByteArray()
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
        if (b.size < hdr) return ThinResult(b, 0)
        val ncmds = u32(b, 16, be)
        val sizeofcmds = u32(b, 20, be)
        if (ncmds > 4096 || hdr + sizeofcmds > b.size) return ThinResult(b, 0)
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
                if (csz != 16L) return ThinResult(b, 0)
                val dataoff = u32(b, off + 8, be)
                val datasize = u32(b, off + 12, be)
                if (dataoff + datasize > b.size) return ThinResult(b, 0)
                blobs += longArrayOf(dataoff, datasize)
            }
            off += csz.toInt()
        }
        // The commands must exactly cover the declared command region.
        if (off.toLong() != hdr + sizeofcmds) return ThinResult(b, 0)
        if (blobs.size > 1) return ThinResult(b, 0)
        val cszSum = cmds.filter { it[0] == LC_CODE_SIGNATURE }.sumOf { it[1] }
        if (cszSum > sizeofcmds) return ThinResult(b, 0)
        // arm64/arm64_32 pages are 16K, other slices 4K — codesign rounds the
        // linkedit VM reservation to the target's page.
        val page =
            if (u32(b, 4, be) == CPU_TYPE_ARM64 || u32(b, 4, be) == CPU_TYPE_ARM64_32) {
                0x4000L
            } else {
                0x1000L
            }
        val segments = cmds.filter { it[0] == LC_SEGMENT || it[0] == LC_SEGMENT_64 }
        val segmentKind = if (is64) LC_SEGMENT_64 else LC_SEGMENT
        val segmentHeaderSize = if (is64) 72L else 56L
        if (segments.any { it[0] != segmentKind || it[1] < segmentHeaderSize }) return ThinResult(b, 0)
        val linkedits = segments.map { it[2].toInt() }.filter { isLinkeditName(b, it) }
        if (linkedits.size != 1) return ThinResult(b, 0)
        val linkOff = linkedits[0]
        val linkSize = cmds.first { it[2] == linkOff.toLong() }[1].toInt()
        if (linkSize.toLong() != segmentHeaderSize) return ThinResult(b, 0)
        if (u32(b, linkOff + if (is64) 64 else 48, be) != 0L) return ThinResult(b, 0)
        val linkBytes = b.copyOfRange(linkOff, linkOff + linkSize)
        val vmsize: Long
        val foff: Long
        val fsize: Long
        if (is64) {
            vmsize = u64(linkBytes, 32, be)
            foff = u64(linkBytes, 40, be)
            fsize = u64(linkBytes, 48, be)
        } else {
            vmsize = u32(linkBytes, 28, be)
            foff = u32(linkBytes, 32, be)
            fsize = u32(linkBytes, 36, be)
        }
        // Only a final, disjoint linkedit range may contain masked signature bytes.
        // Check subtraction bounds before addition: u64 values may decode as signed longs.
        if (foff < off || foff > b.size || fsize < 0 || fsize > b.size - foff) return ThinResult(b, 0)
        if (foff + fsize != b.size.toLong()) return ThinResult(b, 0)
        for (segment in segments) {
            if (segment[2] == linkOff.toLong()) continue
            val co = segment[2].toInt()
            val otherOff = if (is64) u64(b, co + 40, be) else u32(b, co + 32, be)
            val otherSize = if (is64) u64(b, co + 48, be) else u32(b, co + 36, be)
            if (otherOff < 0 || otherOff > b.size || otherSize < 0 || otherSize > b.size - otherOff) {
                return ThinResult(b, 0)
            }
            if (otherSize > 0 && otherOff < foff + fsize && foff < otherOff + otherSize) return ThinResult(b, 0)
        }
        // A single terminal blob keeps all preceding linkedit data bound.
        for (blob in blobs) {
            if (blob[0] < foff || blob[0] + blob[1] != foff + fsize) return ThinResult(b, 0)
        }
        val inside = blobs.sumOf { it[1] }
        if (inside > fsize) return ThinResult(b, 0)
        val rounded = (fsize + page - 1) / page * page
        if (vmsize != fsize && vmsize != rounded) return ThinResult(b, 0)
        val out = ByteArrayOutputStream(b.size)
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
            if ((cmd == LC_SEGMENT || cmd == LC_SEGMENT_64) && isLinkeditName(b, co)) {
                val adjusted = cb.copyOf()
                val order = if (be) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
                val buf = ByteBuffer.wrap(adjusted).order(order)
                val nonSig = fsize - inside
                val nonSigRounded = (nonSig + page - 1) / page * page
                if (is64) {
                    buf.putLong(32, nonSigRounded)
                    buf.putLong(48, nonSig)
                } else {
                    buf.putInt(28, nonSigRounded.toInt())
                    buf.putInt(36, nonSig.toInt())
                }
                out.write(adjusted)
            } else {
                out.write(cb)
            }
        }
        var emit = out.toByteArray() + b.copyOfRange(off, b.size)
        var shift = 0L
        for (blob in blobs.sortedBy { it[0] }) {
            val start = blob[0] + (out.size() - off) - shift
            if (0 <= start && start < emit.size) {
                val end = minOf(emit.size.toLong(), start + blob[1]).toInt()
                emit = emit.copyOfRange(0, start.toInt()) + emit.copyOfRange(end, emit.size)
            }
            shift += blob[1]
        }
        return ThinResult(emit, inside)
    }

    private fun isLinkeditName(
        b: ByteArray,
        cmdOff: Int,
    ): Boolean = b.copyOfRange(cmdOff + 8, cmdOff + 24).contentEquals(LINKEDIT_SEGMENT_NAME)

    private fun isZero(
        b: ByteArray,
        start: Int,
        length: Int,
    ): Boolean {
        if (start < 0 || length < 0 || start + length > b.size) return false
        for (i in start until start + length) {
            if (b[i] != 0.toByte()) return false
        }
        return true
    }

    private fun u32(
        b: ByteArray,
        o: Int,
        be: Boolean,
    ): Long =
        if (o < 0 || o + 4 > b.size) {
            0
        } else {
            ByteBuffer
                .wrap(b)
                .order(if (be) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
                .getInt(o)
                .toLong() and 0xFFFFFFFFL
        }

    private fun u64(
        b: ByteArray,
        o: Int,
        be: Boolean,
    ): Long =
        if (o < 0 || o + 8 > b.size) {
            0
        } else {
            ByteBuffer
                .wrap(b)
                .order(if (be) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
                .getLong(o)
        }

    private fun u32Bytes(
        v: Long,
        be: Boolean,
    ): ByteArray =
        ByteBuffer
            .allocate(4)
            .order(if (be) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
            .putInt(v.toInt())
            .array()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private const val FAT_MAGIC = 0xCAFEBABEL
    private const val MH_MAGIC_64 = 0xFEEDFACFL
    private const val MH_MAGIC = 0xFEEDFACEL
    private const val MH_CIGAM_64 = 0xCFFAEDFEL
    private const val MH_CIGAM = 0xCEFAEDFEL
    private const val LC_SEGMENT = 0x1L
    private const val LC_SEGMENT_64 = 0x19L
    private const val LC_CODE_SIGNATURE = 0x1DL
    private const val CPU_TYPE_ARM64 = 0x0100000CL
    private const val CPU_TYPE_ARM64_32 = 0x0200000CL
    private val ZERO4 = ByteArray(4)
    private val LINKEDIT_SEGMENT_NAME = "__LINKEDIT".toByteArray(Charsets.US_ASCII).copyOf(16)
}

/** The Licensee-checked skiko runtime jar: `skiko-awt-runtime-<os>-<arch>-<version>.jar`. */
private val SKIKO_RUNTIME_JAR = Regex("skiko-awt-runtime-(linux|windows|macos)-(x64|arm64)-(.+)\\.jar")

/**
 * The native entries Compose extracts from a skiko runtime jar for the target
 * (`AbstractJPackageTaskKt.unpackSkikoForCurrentOS`, compose-gradle-plugin 1.12.1): the
 * host-arch library by the OS's naming scheme, plus Windows' `icudtl.dat`. Each extracted
 * entry's `.sha256` sibling is extracted too (the rule strips the suffix before matching).
 */
internal fun skikoExtractedNames(
    os: String,
    arch: String,
): Set<String> =
    when (os) {
        "macos" -> setOf("libskiko-macos-$arch.dylib")
        "linux" -> setOf("libskiko-linux-$arch.so")
        "windows" -> setOf("skiko-windows-$arch.dll", "icudtl.dat")
        else -> emptySet()
    }

/**
 * Writes the Licensee-checked runtime classpath as `<canonical sha256>  name` lines
 * (09 Build-output checks): `check-desktop-image.sh` asserts every JAR of an image is
 * one of these files. Same-named JARs — `api-desktop.jar` comes from four modules, and
 * androidx artefacts appear twice via the `org.jetbrains.androidx` republish — are no
 * collision here: the recorded name is the mangled image name, which differs by content.
 *
 * The skiko runtime jar is the one image entry whose name the build cannot predict: Compose
 * extracts the target's native library and repacks the rest under a fresh content-md5 stamp.
 * Its stub record (`<expected stub hash>  <source name>  <extracted file>=<hash>,…`) is
 * derived here from the source artifact, so the checker binds the stub's canonical content
 * instead of accepting a name prefix.
 */
abstract class WriteDesktopRuntimeClasspath : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeClasspath: ConfigurableFileCollection

    @get:OutputFile
    abstract val manifest: RegularFileProperty

    @get:OutputFile
    abstract val stubManifest: RegularFileProperty

    @TaskAction
    fun write() {
        val files = runtimeClasspath.files.filter { it.isFile }
        val lines =
            files
                .map { "${CanonicalJarHash.of(it)}  ${imageJarName(it)}" }
                .toSortedSet()
                .joinToString("\n", postfix = "\n")
        manifest.get().asFile.writeText(lines)

        val stubLines =
            files
                .mapNotNull { skikoStubRecord(it) }
                .toSortedSet()
                .joinToString("\n", postfix = "\n")
        stubManifest.get().asFile.writeText(stubLines)
    }

    private fun skikoStubRecord(file: File): String? {
        val match = SKIKO_RUNTIME_JAR.matchEntire(file.name) ?: return null
        val os = match.groupValues[1]
        val arch = match.groupValues[2]
        val stub = CanonicalJarHash.stubOf(file, skikoExtractedNames(os, arch))
        val extracted = stub.extracted.joinToString(",") { "${it.first}=${it.second}" }
        return "${stub.expectedHash}  ${file.name}  $extracted"
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

/**
 * Compose's `packageMsi`/`packageDmg` task carrying the AOT post-image hook (11 AOT cache).
 *
 * Compose 1.12.1 exposes no `--resource-dir` hook: its `freeArgs` are prepended while the
 * task's own `--resource-dir` is written last (the JDK's `DeployParams.addBundleArgument`
 * overwrites, so the last one wins), and `prepareWorkingDir` clears that directory after
 * `doFirst`. The supported extension point is the task itself: this subclass overrides
 * `prepareWorkingDir` to call the Compose implementation and then drop the format's
 * `<app name>-post-image` script into Compose's own resource directory, so jpackage's
 * `ScriptRunner` finds it after the attribute-losing `copyAppImage` and before the WiX/DMG
 * bundler reads the staging tree. Everything else — the DSL-driven arguments, the WiX
 * toolchain wiring, the macOS Info.plist generation, the ad-hoc seal, the output
 * directory — is inherited unchanged; [Project.registerDesktopPackagingTasks] swaps the
 * Compose task for this type with `TaskContainer.replace`, which keeps the registered
 * configure actions.
 */
abstract class AotTimestampJPackageTask(
    private val format: TargetFormat,
) : AbstractJPackageTask(format) {
    /** The shipped hook for this format, copied into Compose's resource directory. */
    @get:Internal
    abstract val postImageHook: RegularFileProperty

    /** jpackage's `ScriptRunner` looks up `<app name>-post-image<ext>` in the resource dir. */
    @get:Internal
    protected open val scriptExtension: String =
        if (format == TargetFormat.Msi) ".wsf" else ".sh"

    override fun prepareWorkingDir(inputChanges: InputChanges) {
        super.prepareWorkingDir(inputChanges)
        writePostImageHook(
            hook = postImageHook.get().asFile,
            packageName = packageName.get(),
            scriptExtension = scriptExtension,
            resourcesDir = jpackageResources.get().asFile,
        )
    }
}

/**
 * Drops the hook into jpackage's resource directory under the exact name its
 * `ScriptRunner` looks up (`<app name>-post-image<ext>`). Fails closed when the
 * shipped hook is missing: packaging without it would ship a strict-cache failure.
 */
internal fun writePostImageHook(
    hook: File,
    packageName: String,
    scriptExtension: String,
    resourcesDir: File,
) {
    if (!hook.isFile) {
        throw GradleException("the AOT post-image hook is missing: $hook")
    }
    File(resourcesDir, "$packageName-post-image$scriptExtension").writeBytes(hook.readBytes())
}

/** Compose's `packageMsi` with the Windows post-image hook. */
abstract class MsiAotTimestampJPackageTask : AotTimestampJPackageTask(TargetFormat.Msi)

/** Compose's `packageDmg` with the macOS post-image hook. */
abstract class DmgAotTimestampJPackageTask : AotTimestampJPackageTask(TargetFormat.Dmg)

/**
 * Rewrites [zip] so every entry carries the Info-ZIP extended timestamp field
 * (0x5455): the entry's modification time as UTC seconds. Gradle's Zip records
 * only the MS-DOS local-time fields — commons-compress writes no 0x5455 — so an
 * extractor in another timezone would decode a different instant and a strict
 * AOT launch could pass or fail depending on where the ZIP was built. The
 * `java.util.zip` itself only emits the field for times outside the DOS range,
 * so the rewrite sets the entry extra bytes explicitly; decoding the DOS time
 * back happens on this host, so the recorded instant round-trips exactly.
 * Entry order and names are preserved; unix-mode external attributes are not —
 * java.util.zip exposes none, and the Windows image this archives carries none
 * to lose.
 */
internal fun addUtcTimestamps(zip: File) {
    val rewritten = zip.resolveSibling("${zip.name}.utc")
    try {
        rewriteZipWithUtcTimestamps(zip, rewritten)
    } catch (e: Exception) {
        rewritten.delete()
        throw e
    }
    if (!zip.delete() || !rewritten.renameTo(zip)) {
        rewritten.delete()
        throw GradleException("could not rewrite $zip with UTC timestamps")
    }
}

private fun rewriteZipWithUtcTimestamps(
    zip: File,
    rewritten: File,
) {
    ZipFile(zip).use { source ->
        ZipOutputStream(rewritten.outputStream().buffered()).use { out ->
            for (entry in source.entries().asSequence()) {
                val copy = ZipEntry(entry.name)
                if (entry.time >= 0) {
                    copy.time = entry.time
                    copy.extra = withoutUtcField(entry.extra) + utcTimestampField(entry.time / 1000)
                } else {
                    copy.extra = entry.extra
                }
                if (entry.comment != null) {
                    copy.comment = entry.comment
                }
                out.putNextEntry(copy)
                if (!entry.isDirectory) {
                    source.getInputStream(entry).copyTo(out)
                }
                out.closeEntry()
            }
        }
    }
}

/**
 * Restores the executable bit on the bundled Python tree inside a packaged image.
 * Compose's `createDistributable` copies `appResourcesRootDir` with `File.copyTo`, which
 * does not carry POSIX permissions, so the interpreter arrives non-executable and the
 * engine child cannot start (the image scan's required-layout rule catches it). The staged
 * tree's modes are the truth; `chmod` does not touch the Mach-O bytes, so the ad-hoc
 * signatures jpackage sealed over the nested binaries stay valid. A no-op on Windows,
 * where the execute bit does not exist.
 */
abstract class RestoreDesktopPythonPermissions : DefaultTask() {
    @get:InputDirectory
    abstract val stagedPython: DirectoryProperty

    @get:InputDirectory
    abstract val imagePython: DirectoryProperty

    @TaskAction
    fun restore() {
        if (System.getProperty("os.name").startsWith("Windows")) return
        val staged = stagedPython.get().asFile
        val image = imagePython.get().asFile
        staged.walkTopDown().forEach { file ->
            val target = image.toPath().resolve(file.relativeTo(staged).path).toFile()
            if (file.isFile && target.isFile) {
                Files.setPosixFilePermissions(target.toPath(), Files.getPosixFilePermissions(file.toPath()))
            }
        }
    }
}

/** The Info-ZIP 0x5455 extended timestamp field: flags=1 (mtime present) plus UTC seconds. */
private fun utcTimestampField(utcSeconds: Long): ByteArray =
    ByteBuffer
        .allocate(9)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(0x5455)
        .putShort(5)
        .put(1)
        .putInt(utcSeconds.toInt())
        .array()

/**
 * [extra] minus any 0x5455 fields, so the rewrite stays idempotent and a second
 * run records one timestamp. Other producers' extra fields pass through.
 */
private fun withoutUtcField(extra: ByteArray?): ByteArray {
    if (extra == null) return ByteArray(0)
    val out = java.io.ByteArrayOutputStream(extra.size)
    val fields = ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN)
    while (fields.remaining() >= 4) {
        val start = fields.position()
        val size = fields.getShort(start + 2).toInt() and 0xFFFF
        val end = start + 4 + size
        if (end > extra.size) break
        if ((fields.getShort(start).toInt() and 0xFFFF) != 0x5455) {
            out.write(extra, start, end - start)
        }
        fields.position(end)
    }
    return out.toByteArray()
}

/** The Windows portable ZIP: the app image archived (11 Windows MSI and ZIP). */
abstract class WindowsZipTask : Zip()

/** The Linux tar.gz: the app image as `Neutrodyne/` (11 Linux DEB, RPM and tar.gz). */
abstract class LinuxTarGzTask : Tar()

internal fun Project.registerDesktopPackagingTasks(
    target: DesktopPackagingTarget,
    lock: BundledRuntimeLock,
) {
    val versionName = providers.gradleProperty("neutrodyne.versionName").get()
    // The emergency build omits the engine stack and its resources entirely (01 Emergency
    // build without the engine); the property mirrors desktopApp's youtubeEngine switch.
    val youtubeEngine =
        providers
            .gradleProperty("neutrodyne.youtubeEngine")
            .map { it.toBoolean() }
            .orElse(true)
            .get()
    val setupRuntime = tasks.named("setupBundledRuntime", SetupBundledRuntime::class.java)
    val jpackage =
        setupRuntime.map { task ->
            File(File(task.home.get().asFile, target.homeSubdir), "bin/jpackage")
        }
    val nativeTarget = nativeTargetOf()

    // The app image is createDistributable's destinationDir/<name>[.app]; taking it from the
    // task keeps this in step with the plugin's layout instead of hardcoding compose/binaries.
    // The dir() derivation (not .map) keeps the chain readable at any time: a .map over the
    // task provider cannot be queried before createDistributable has completed, which the
    // packaging tasks' appImage reads below would trip.
    val appImageLeaf =
        if (target ==
            DesktopPackagingTarget.MACOS_ARM64
        ) {
            "$DESKTOP_PACKAGE_NAME.app"
        } else {
            DESKTOP_PACKAGE_NAME
        }
    val createDistributable =
        tasks.named(
            "createDistributable",
            org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask::class.java,
        )
    val distributableDir = createDistributable.flatMap { it.destinationDir }
    val appImage = createDistributable.flatMap { it.destinationDir.dir(appImageLeaf) }

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

    // The packaged image's licenses/ payload (11 Resources layout): the AboutLibraries
    // notices of everything the package ships, FFmpeg's own LGPL-2.1 text and miniaudio's
    // MIT-0. FFmpeg's text comes out of the pinned source archive's extracted cache, so a
    // lock bump keeps it honest; buildFfmpeg populates that cache before the read.
    val playbackNative = project(":playback:native")
    val ffmpegVersion =
        ffmpegVersionOf(
            playbackNative.layout.projectDirectory
                .file("native-components.lock")
                .asFile,
        )
    val writeDesktopLicences =
        tasks.register<WriteDesktopLicencesTask>("writeDesktopLicences") {
            description = "Writes the packaged image's licenses/ payload (11 Resources layout)."
            dependsOn("exportLibraryDefinitions", ":playback:native:buildFfmpeg")
            definitions.set(
                layout.buildDirectory.file("generated/aboutLibraries/aboutlibraries.json"),
            )
            lgplText.set(
                playbackNative.layout.buildDirectory.file(
                    "ffmpeg-cache/ffmpeg-$ffmpegVersion/COPYING.LGPLv2.1",
                ),
            )
            miniaudioLicense.set(file("config/licenses/MIT-0-miniaudio.json"))
            outputDirectory.set(layout.buildDirectory.dir("desktop-licences"))
        }

    tasks.register<org.gradle.api.tasks.Sync>("syncDesktopIntegrationResources") {
        // Compose merges appResourcesRootDir/{common,<os>,<os>-<arch>} into the image's
        // resources dir, so the Linux payload nests under its <os> dir.
        into(layout.buildDirectory.dir("desktop-resources"))
        // The window and tray icons ride along on every target under common/icons, so a
        // packaged app loads the same committed PNGs a dev run reads from icons/ (11
        // Resources layout).
        from(iconsPngDir) { into("common/icons/png") }
        from(iconsTrayDir) { into("common/icons/tray") }
        // FFmpeg's shared libraries (LGPL-2.1: dynamically linked, separately replaceable)
        // plus the layout JSON and licence text, and ndmedia's one library. They land at
        // resources/native/{ffmpeg,ndmedia}, the paths DesktopEnginePaths resolves.
        // `sourcePath` is the path below the build output — `path` would carry this spec's
        // `into` prefix and exclude everything. The tasks are named lazily because
        // :playback:native may not be evaluated when this project is configured.
        dependsOn(":playback:native:buildFfmpeg", ":playback:native:buildNdmedia")
        from(playbackNative.layout.buildDirectory.dir("ffmpeg/$nativeTarget")) {
            into("${target.id}/native/ffmpeg")
            eachFile {
                if (!ffmpegImageEntryAllowed(target.osWire, sourcePath)) exclude()
            }
        }
        from(playbackNative.layout.buildDirectory.dir("ndmedia/$nativeTarget")) {
            into("${target.id}/native/ndmedia")
            eachFile {
                if (name != ndmediaFileName(target.osWire)) exclude()
            }
        }
        from(writeDesktopLicences) { into("common/licenses") }
        // The bundled engine runtime (11 Resources layout): `:youtube:ytdlp-desktop` fetches and
        // trims the pinned python-build-standalone archive; the trimmed tree lands at
        // <os>-<arch>/engine/python/, the yt-dlp zip, keys, shim and certifi's CA bundle at
        // common/engine/, where Compose's prepareAppResources merges them into the image.
        // Only when the engine is bundled; the emergency build omits engine/ entirely.
        if (youtubeEngine) {
            dependsOn(
                ":youtube:ytdlp-desktop:fetchPythonStandalone",
                ":youtube:ytdlp-desktop:trimPythonStandalone",
                ":youtube:ytdlp-desktop:syncEngineResources",
            )
            val pythonStandalone =
                project(":youtube:ytdlp-desktop")
                    .layout.buildDirectory
                    .dir("python-standalone/${target.id}/trimmed")
            from(pythonStandalone.map { it.dir("python") }) {
                into("${target.id}/engine/python")
            }
            from(pythonStandalone.map { it.file("cacert.pem") }) {
                into("common/engine")
            }
            from(project(":youtube:ytdlp-desktop").layout.buildDirectory.dir("engine-resources")) {
                into("common/engine")
            }
        }
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
            val problems =
                stagedNativeProblems(target, destinationDir) +
                    if (youtubeEngine) stagedEngineProblems(target, destinationDir) else emptyList()
            if (problems.isNotEmpty()) {
                throw GradleException(
                    "the staged image resources are incomplete:\n" +
                        problems.joinToString("\n") { "  - $it" },
                )
            }
        }
    }

    tasks.named("createDistributable") {
        dependsOn("syncDesktopIntegrationResources", "writeDesktopRuntimeClasspath")
    }
    if (youtubeEngine) {
        // Compose's createDistributable copies the staged resources without POSIX
        // permissions, so the bundled Python interpreter arrives non-executable; this
        // task restores the staged modes after the image is sealed (11 Image scan rules).
        val restorePythonPermissions =
            tasks.register<RestoreDesktopPythonPermissions>("restoreDesktopPythonPermissions") {
                group = "build"
                dependsOn(createDistributable)
                description =
                    "Restores the executable bit on the bundled Python tree inside the app image (11 Image scan rules)."
                stagedPython.set(layout.buildDirectory.dir("desktop-resources/${target.id}/engine/python"))
                imagePython.set(
                    appImage.map { img ->
                        img.dir(
                            when (target.osWire) {
                                "macos" -> "Contents/app/resources/engine/python"
                                "windows" -> "app/resources/engine/python"
                                else -> "lib/app/resources/engine/python"
                            },
                        )
                    },
                )
            }
        tasks.named("createDistributable") { finalizedBy(restorePythonPermissions) }
        // Training is another image finalizer; registration order does not order finalizers.
        // Restore executable modes before image resealing and before the training launch.
        tasks.matching { it.name == "trimSkikoStub" || it.name == "trainAotCache" }.configureEach {
            dependsOn(restorePythonPermissions)
        }
    }
    // Compose merges appResourcesRootDir/{common,<os>,<os>-<arch>} into the image through its own
    // Sync; ours must run first or Gradle flags the shared output dir as an implicit dependency.
    tasks.matching { it.name == "prepareAppResources" }.configureEach {
        dependsOn("syncDesktopIntegrationResources")
    }

    // Values the task actions and argument providers need, captured as plain types up front so
    // the configuration cache can serialize the tasks (no Project/layout/providers inside).
    val installKindProperty = providers.gradleProperty("neutrodyne.installKind")
    val licenseFile = file(DESKTOP_PACKAGE_LICENSE_FILE)
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

    // The runtime-classpath manifest is registered here, ahead of the AOT training task:
    // the training inputs bind it so a JAR change retrains the archive.
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
        manifest.set(layout.buildDirectory.file("desktop-packaging/runtime-classpath.txt"))
        stubManifest.set(
            layout.buildDirectory.file("desktop-packaging/runtime-classpath-stubs.txt"),
        )
    }

    // --- AOT cache: one training launch writes $APPDIR/neutrodyne.aot into the image (PO-42) ---
    // Every packaging invocation trains: package* tasks depend on the task, and
    // createDistributable's finalizer covers the bare-image path (mac-zip.sh archives the
    // image directly — no package task runs). The graph stays acyclic: train needs
    // createDistributable's output, package* need the trained image, and createDistributable
    // never depends on a package task.
    val aotLayout = aotImageLayout(target.osWire)
    val image = appImage
    val runtimeJavaName = "java" + if (target.osWire == "windows") ".exe" else ""
    val trainAotCache =
        tasks.register<TrainAotCacheTask>("trainAotCache") {
            group = "distribution"
            description =
                "Runs one AOT training launch of the packaged app, verifies the archive under strict mode and leaves it in the image (PO-42, 11 AOT cache)."
            dependsOn(
                "createDistributable",
                setupRuntime,
                "writeDesktopRuntimeClasspath",
                ":playback:native:buildFfmpeg",
                ":playback:native:buildNdmedia",
            )
            this.appImage.set(image)
            launcher.set(image.map { it.file(aotLayout.launcher) })
            launcherCfg.set(image.map { it.file(aotLayout.cfg) })
            runtimeRelease.set(image.map { it.file(aotLayout.runtimeRelease) })
            aotArchive.set(image.map { it.file("${aotLayout.appDir}/$AOT_CACHE_FILE") })
            runtimeHome.set(setupRuntime.flatMap { it.home })
            runtimeJavaSource.set(
                setupRuntime.flatMap { setup ->
                    setup.home.map { home ->
                        if (target.homeSubdir.isEmpty()) {
                            home.file("bin/$runtimeJavaName")
                        } else {
                            home.dir(target.homeSubdir).file("bin/$runtimeJavaName")
                        }
                    }
                },
            )
            runtimeClasspathManifest.set(
                tasks
                    .named(
                        "writeDesktopRuntimeClasspath",
                        WriteDesktopRuntimeClasspath::class.java,
                    ).flatMap { it.manifest },
            )
            nativeLibraries.from(
                playbackNative.layout.buildDirectory.dir("ffmpeg/$nativeTarget"),
                playbackNative.layout.buildDirectory.dir("ndmedia/$nativeTarget"),
            )
            targetOs.set(target.osWire)
            installKind.set(installKindProperty.orElse(""))
            expectedInstallKinds.set(target.installKinds)
            // No install kind means a dev/assemble image: no AOT flag in its .cfg either,
            // so there is nothing to train and nothing missing — the task simply does not run.
            onlyIf { installKind.get().isNotBlank() }
        }
    tasks.named("createDistributable") { finalizedBy(trainAotCache) }
    tasks
        .matching { it.name in AOT_CACHE_CONSUMERS }
        .configureEach { dependsOn(trainAotCache) }

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
                    DESKTOP_PACKAGE_LICENSE,
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
        // The AOT cache records the classpath JARs' modification times (11 AOT cache), so
        // the archive must preserve them; trainAotCache normalized every image file to a
        // fixed timestamp first, which keeps this archive reproducible.
        isPreserveFileTimestamps = true
        isReproducibleFileOrder = true
        // The DOS fields above record local time only; add the UTC 0x5455 field so the
        // artifact's timestamps are unambiguous for any extractor's timezone.
        doLast { addUtcTimestamps(archiveFile.get().asFile) }
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
        // The AOT cache records the classpath JARs' modification times (11 AOT cache), so
        // the archive must preserve them; trainAotCache normalized every image file to a
        // fixed timestamp first, which keeps this archive reproducible.
        isPreserveFileTimestamps = true
        isReproducibleFileOrder = true
    }

    // --- MSI/DMG: Compose's tasks carrying the AOT post-image hook ---------------------------
    // Compose registers packageMsi/packageDmg for the host's target formats. Swap each for
    // the subclass that drops the format's post-image hook into Compose's own resource
    // directory during prepareWorkingDir — after the attribute-losing copyAppImage and before
    // WiX/hdiutil read the staging tree. TaskContainer.replace keeps the configure actions
    // Compose registered on the provider (the DSL arguments, the WiX toolchain wiring, the
    // macOS Info.plist generation), so the entry points, the output names and every current
    // packaging behavior stay; only the task type changes. The `freeArgs` route cannot work:
    // they are prepended while Compose writes its own --resource-dir last, and the JDK's
    // last-wins option handling keeps that one.
    //
    // The installer argv must consume the trained image: Compose 1.12.1 leaves its non-macOS
    // package tasks in fresh-image mode (`--input` libs + `--main-jar`, which the JDK turns
    // into a newly generated image that carries no trained neutrodyne.aot, so the post-image
    // hook fails the packaging closed — Windows run 42509091764, job 128818030637). Setting
    // the task's appImage switches Compose's own makeArgs to `--app-image` and drops
    // --input/--main-jar/--runtime-image; on macOS hosts makeArgs appends <name>.app to the
    // configured directory itself (the form Compose wires for packageDmg), so packageDmg gets
    // the destination directory and packageMsi the image inside it. The dependsOn pairing is
    // the one Compose itself adds when it wires appImage (the archive the installers package
    // is the one createDistributable + trainAotCache leave there).
    val packageMsi = tasks.replace("packageMsi", MsiAotTimestampJPackageTask::class.java)
    packageMsi.postImageHook.set(file("packaging/msi/Neutrodyne-post-image.wsf"))
    packageMsi.dependsOn(createDistributable)
    packageMsi.appImage.set(appImage)
    val packageDmg = tasks.replace("packageDmg", DmgAotTimestampJPackageTask::class.java)
    packageDmg.postImageHook.set(file("packaging/dmg/Neutrodyne-post-image.sh"))
    packageDmg.dependsOn(createDistributable)
    packageDmg.appImage.set(distributableDir)
}
