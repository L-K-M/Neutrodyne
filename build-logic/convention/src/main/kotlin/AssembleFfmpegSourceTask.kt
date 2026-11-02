// SPDX-License-Identifier: Unlicense
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.tomlj.Toml
import org.tomlj.TomlParseResult

/**
 * `assembleFfmpegSource` (11 FFmpeg LGPL obligations, D89): the exact corresponding source
 * of the built FFmpeg, as the bundle every release from MD1b attaches.
 *
 * The bundle is the pristine upstream tarball with its signature, an empty
 * `changes.diff` (nothing of ours is inside FFmpeg), `BUILD.md` with the configure line
 * and toolchain of the target that built it, the build script and the layout probe, and
 * FFmpeg's own LGPL text. It is reproducible from the lock alone, which is what makes it
 * a source offer rather than a copy of whatever happened to be built.
 *
 * The download cache is never declared as an input: it holds the whole extracted FFmpeg
 * source tree, and snapshotting it would cost more than the build. The five files the
 * bundle actually copies are the inputs, so a changed tarball or a rebuilt toolchain
 * re-assembles while an untouched cache does not.
 */
abstract class AssembleFfmpegSourceTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val lockFile: RegularFileProperty

    /** The files of `buildFfmpeg`'s download cache this bundle copies. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceInputs: ConfigurableFileCollection

    /** `buildFfmpeg`'s download cache, where the extracted source tree lives. */
    @get:Internal
    abstract val cache: DirectoryProperty

    /** The actual target build record, not the download cache. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val buildOutput: DirectoryProperty

    /** Where the bundle is written; the file name is the one D89 names. */
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:Input
    abstract val target: Property<String>

    @TaskAction
    fun assemble() {
        val parsed: TomlParseResult = Toml.parse(lockFile.get().asFile.readText())
        val version =
            parsed.getString("ffmpeg.version")
                ?: error("native-components.lock: no ffmpeg.version")
        val sourceUrl =
            parsed.getString("ffmpeg.sourceUrl")
                ?: error("native-components.lock: no ffmpeg.sourceUrl")
        val signatureUrl = parsed.getString("ffmpeg.signatureUrl")
        val configure = parsed.getString("ffmpeg.configure")
        val cache = this.cache.get().asFile
        val tarball = cache.resolve("ffmpeg-$version.tar.xz")
        val signature = cache.resolve("ffmpeg-$version.tar.xz.asc")
        val buildTree = cache.resolve("ffmpeg-$version")
        require(tarball.isFile) { "the FFmpeg source tarball is missing: run buildFfmpeg first" }

        verifyNativeSourceHash(tarball, requireNotNull(parsed.getString("ffmpeg.sha256")))
        require(signature.isFile) { "the FFmpeg source signature is missing" }
        verifyNativeSourceHash(signature, requireNotNull(parsed.getString("ffmpeg.signatureSha256")))
        require(buildTree.resolve("COPYING.LGPLv2.1").isFile) { "the FFmpeg LGPL text is missing" }
        require(buildTree.resolve("LICENSE.md").isFile) { "the FFmpeg licence inventory is missing" }
        val record = buildOutput.get().asFile.resolve("ffmpeg-build.txt")
        require(record.isFile) { "the target's FFmpeg build record is missing" }

        val stage = cache.resolve("ffmpeg-source-$version")
        stage.deleteRecursively()
        stage.mkdirs()
        tarball.copyTo(stage.resolve("ffmpeg-$version.tar.xz"))
        if (signature.isFile) {
            signature.copyTo(stage.resolve("ffmpeg-$version.tar.xz.asc"))
        }
        stage.resolve("changes.diff").writeText("")
        stage.resolve("BUILD.md").writeText(buildMarkdown(version, sourceUrl, signatureUrl, configure, record))
        val nativeRoot = lockFile.get().asFile.parentFile
        val bundledNative = stage.resolve("playback/native")
        bundledNative.mkdirs()
        lockFile.get().asFile.copyTo(bundledNative.resolve("native-components.lock"))
        nativeRoot.resolve("ffmpeg").copyRecursively(bundledNative.resolve("ffmpeg"))
        record.copyTo(stage.resolve("ffmpeg-build.txt"))
        buildTree.resolve("COPYING.LGPLv2.1").copyTo(stage.resolve("COPYING.LGPLv2.1"))
        buildTree.resolve("LICENSE.md").copyTo(stage.resolve("LICENSE.md"))

        val out = outputFile.get().asFile
        out.parentFile.mkdirs()
        out.delete()
        val files = stage.listFiles()?.sortedBy { it.name } ?: emptyList()
        val command =
            listOf("tar", "--create", "--xz", "--file", out.absolutePath) +
                files.map { it.name }
        val process =
            ProcessBuilder(command)
                .directory(stage)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        val status = process.waitFor()
        if (status != 0) {
            error("tar failed ($status): $output")
        }
        stage.deleteRecursively()
        logger.lifecycle("assembleFfmpegSource: wrote ${out.name} (${out.length()} bytes)")
    }

    private fun buildMarkdown(
        version: String,
        sourceUrl: String,
        signatureUrl: String?,
        configure: String?,
        buildRecord: java.io.File,
    ): String {
        val recorded = buildRecord.readText()
        val compiler = recorded.lineSequence().firstOrNull { it.startsWith("compiler ") }?.removePrefix("compiler ")
        return buildString {
            appendLine("# FFmpeg $version as built for Neutrodyne")
            appendLine()
            appendLine("This bundle is the exact corresponding source of the FFmpeg libraries this")
            appendLine("build of Neutrodyne ships, offered under LGPL-2.1 or later (11 FFmpeg LGPL")
            appendLine("obligations, FFmpeg's compliance checklist).")
            appendLine()
            appendLine("## Source")
            appendLine()
            appendLine("- Upstream tarball: $sourceUrl")
            if (signatureUrl != null) {
                appendLine("- Upstream signature: $signatureUrl")
            }
            appendLine("- Checksum and signature: `playback/native/native-components.lock`")
            appendLine()
            appendLine("## Configure line")
            appendLine()
            appendLine("```sh")
            appendLine("./configure $configure \\")
            appendLine("    \$TARGET_FLAGS   # never --enable-gpl, --enable-version3 or --enable-nonfree")
            appendLine("```")
            appendLine()
            appendLine("## Per-target flags and toolchains")
            appendLine()
            appendLine("| Target | Flags | Toolchain |")
            appendLine("| --- | --- | --- |")
            appendLine(
                "| `windows-x64` | `--toolchain=msvc --target-os=win64 --arch=x86_64` | " +
                    "MSVC from the runner's Visual Studio, driven through the MSYS2 shell; NASM when present |",
            )
            appendLine(
                "| `macos-arm64` | `--arch=arm64 --install_name_dir=@rpath " +
                    "--extra-cflags=-mmacosx-version-min=13.0 " +
                    "--extra-ldflags=-mmacosx-version-min=13.0 -Wl,-rpath,@loader_path` | Apple clang |",
            )
            appendLine(
                "| `linux-x64`, `linux-arm64` | `--cc=gcc -Wl,-rpath,'\\\$ORIGIN'` | " +
                    "gcc inside `manylinux_2_28` (glibc 2.28 baseline, below the app's 2.31 floor) |",
            )
            appendLine()
            appendLine("## Build")
            appendLine()
            appendLine("```sh")
            appendLine("mkdir -p playback/native/build/ffmpeg-cache")
            appendLine("cp ffmpeg-$version.tar.xz* playback/native/build/ffmpeg-cache/")
            appendLine("bash playback/native/ffmpeg/build.sh ${target.get()}")
            appendLine("```")
            appendLine()
            if (compiler != null) {
                appendLine("## Recorded build")
                appendLine()
                appendLine("```")
                append(recorded)
                appendLine("```")
            }
            appendLine("## Changes")
            appendLine()
            appendLine("None: `changes.diff` is empty, because nothing of Neutrodyne's is inside FFmpeg.")
        }
    }
}

/** The one place the bundle's name is spelled (D89). */
internal fun ffmpegSourceBundleName(version: String): String = "ffmpeg-$version-neutrodyne-src.tar.xz"

/** Source offers must bind to the archive that the native lock approved. */
internal fun verifyNativeSourceHash(file: java.io.File, expected: String) {
    require(file.isFile) { "native source is missing: $file" }
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8_192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    require(actual == expected) { "native source checksum mismatch for $file" }
}
