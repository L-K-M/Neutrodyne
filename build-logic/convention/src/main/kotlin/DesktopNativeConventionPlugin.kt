// SPDX-License-Identifier: Unlicense
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.gradle.kotlin.dsl.register

/**
 * Per-host native builds of `:playback:native` (`buildNdmedia`, `buildFfmpeg`,
 * `assembleFfmpegSource`, `checkNativeLicences`; 01 Convention plugins,
 * 11 Desktop playback engine).
 *
 * The builds are per host: jpackage cannot cross-package and neither can a shared
 * library, so `buildFfmpeg <target>` picks the target this machine serves unless
 * `-Pneutrodyne.ffmpegTarget` names one (the desktop matrix uses the default; a
 * developer on an off-matrix machine cannot build a target it cannot run).
 */
class DesktopNativeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("neutrodyne.desktop.library")
        with(target) {
            val nativeTarget = nativeTargetOf()
            val nativeLock = layout.projectDirectory.file("native-components.lock")
            val ffmpegOutputDir = layout.buildDirectory.dir("ffmpeg/$nativeTarget")
            val sourceCacheDir = layout.buildDirectory.dir("ffmpeg-cache")

            val buildFfmpeg =
                tasks.register<BuildFfmpegTask>("buildFfmpeg") {
                    group = "build"
                    description = "Build the minimal FFmpeg libraries of D86 for $nativeTarget (11 FFmpeg build)."
                    workingDir = layout.projectDirectory.asFile
                    commandLine(bashExecutable(), "ffmpeg/build.sh", nativeTarget)
                    outputDirectory.set(ffmpegOutputDir)
                    lockFile.set(nativeLock)
                    // build.sh, foffsets.c and the signing key: a change to any of them
                    // rebuilds, which the Exec task's own inputs would not notice.
                    inputs.files("ffmpeg")
                }

            val ndmediaOutputDir = layout.buildDirectory.dir("ndmedia/$nativeTarget")
            tasks.register<Exec>("buildNdmedia") {
                group = "build"
                description = "Build libndmedia (miniaudio device + SPSC ring) for $nativeTarget (11 ndmedia interface)."
                workingDir = layout.projectDirectory.asFile
                commandLine(bashExecutable(), "ndmedia/build.sh", nativeTarget)
                inputs.files("ndmedia")
                outputs.dir(ndmediaOutputDir)
            }

            tasks.register<CheckNativeLicencesTask>("checkNativeLicences") {
                group = "verification"
                description = "Apply D3's licence envelope to the native components (01 Native components)."
                dependsOn(buildFfmpeg)
                this.lockFile.set(nativeLock)
                ffmpegOutput.set(ffmpegOutputDir)
                this.target.set(nativeTarget)
                // PLAN MD0 AC2's budget, counted conservatively in bytes (11 FFmpeg build).
                maxStrippedBytes.set(4_000_000L)
            }

            // `Directory.file(String)` returns the RegularFile itself, not a provider.
            val ffmpegVersion = ffmpegVersionOf(nativeLock.asFile)
            tasks.register<AssembleFfmpegSourceTask>("assembleFfmpegSource") {
                group = "build"
                description = "Assemble the LGPL source bundle of the built FFmpeg (11 FFmpeg LGPL obligations)."
                dependsOn(buildFfmpeg)
                this.lockFile.set(nativeLock)
                this.cache.set(sourceCacheDir)
                sourceInputs.setFrom(
                    sourceCacheDir.get().file("ffmpeg-$ffmpegVersion.tar.xz"),
                    sourceCacheDir.get().file("ffmpeg-$ffmpegVersion.tar.xz.asc"),
                    sourceCacheDir.get().file("ffmpeg-$ffmpegVersion/ffmpeg-build.txt"),
                    sourceCacheDir.get().file("ffmpeg-$ffmpegVersion/COPYING.LGPLv2.1"),
                    sourceCacheDir.get().file("ffmpeg-$ffmpegVersion/LICENSE.md"),
                )
                outputFile.set(
                    layout.buildDirectory.file("ffmpeg-source/${ffmpegSourceBundleName(ffmpegVersion)}"),
                )
                this.target.set(nativeTarget)
            }
        }
    }
}

/** The target this machine serves: the property, else the host (11 Platform matrix). */
internal fun Project.nativeTargetOf(): String {
    val requested = providers.gradleProperty("neutrodyne.ffmpegTarget").orNull
    if (requested != null) {
        require(requested in NATIVE_TARGETS) {
            "neutrodyne.ffmpegTarget is '$requested'; expected one of $NATIVE_TARGETS"
        }
        return requested
    }
    val os = System.getProperty("os.name")
    val arch = System.getProperty("os.arch")
    return when {
        os.startsWith("Windows") && (arch == "amd64" || arch == "x86_64") -> "windows-x64"
        os == "Mac OS X" && (arch == "aarch64" || arch == "arm64") -> "macos-arm64"
        os == "Linux" && (arch == "amd64" || arch == "x86_64") -> "linux-x64"
        os == "Linux" && (arch == "aarch64" || arch == "arm64") -> "linux-arm64"
        else -> error("this host ($os/$arch) serves no FFmpeg target of D88")
    }
}

internal val NATIVE_TARGETS = listOf("windows-x64", "macos-arm64", "linux-x64", "linux-arm64")

/** The lock's FFmpeg version, read at configuration time to name the source bundle. */
internal fun ffmpegVersionOf(lockFile: java.io.File): String {
    val text = lockFile.readText()
    val version =
        Regex("""^\[ffmpeg\][\s\S]*?^version\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .find(text)
            ?.groupValues
            ?.get(1)
    require(!version.isNullOrBlank()) { "native-components.lock has no [ffmpeg] version" }
    return version
}

/**
 * The shell `buildFfmpeg` runs under. A bare `bash` is ambiguous on Windows: the
 * runner's `PATH` reaches `C:\Windows\System32\bash.exe` (WSL's launcher) before
 * Git Bash, so the build died with "Windows Subsystem for Linux has no installed
 * distributions" and no output of its own. FFmpeg's documented MSVC shell is
 * MSYS2 (doc/platform.texi), so MSYS2's bash is named explicitly, with Git Bash
 * as the fallback for a developer who has one but not the other.
 */
internal fun bashExecutable(): String {
    if (!System.getProperty("os.name").startsWith("Windows")) return "bash"
    val candidates =
        listOfNotNull(
            System.getenv("MSYS2_ROOT")?.let { "$it/usr/bin/bash.exe" },
            "C:/msys64/usr/bin/bash.exe",
            "C:/Program Files/MSYS2/usr/bin/bash.exe",
            "C:/Program Files/Git/bin/bash.exe",
        )
    return candidates.firstOrNull { java.io.File(it).isFile } ?: "bash"
}
