// SPDX-License-Identifier: Unlicense
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
// The task type, not org.gradle.process.Exec: buildFfmpeg is an Exec task, and the
// process-level interface exposes neither workingDir nor commandLine.
import org.gradle.api.tasks.Exec
import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlParseResult
import org.tomlj.TomlTable

/**
 * The native components of `:playback:native` (01 Python and native components, 11 Lockfiles).
 *
 * `native-components.lock` records every native source the build downloads, with its checksum
 * and its licence, so the licence envelope of D3 can be enforced by a gate instead of by
 * review: permissive licences always, LGPL-2.1/3.0 only when the component is dynamically
 * linked and separately replaceable, MPL-2.0 only for unmodified files and data.
 */
object NativeLicencePolicy {
    const val SCHEMA = 1

    /** D3's permissive set plus the two conditional classes. */
    val ALLOWED_SPDX =
        setOf(
            "Apache-2.0",
            "MIT",
            "MIT-0",
            "BSD-2-Clause",
            "BSD-3-Clause",
            "ISC",
            "0BSD",
            "PSF-2.0",
            "zlib",
            "Unlicense",
            "CC0-1.0",
            "LGPL-2.1-or-later",
            "LGPL-3.0-or-later",
            "MPL-2.0",
        )

    /** LGPL is allowed only for dynamically linked, separately replaceable libraries. */
    val DYNAMIC_ONLY_SPDX = setOf("LGPL-2.1-or-later", "LGPL-3.0-or-later")

    /** MPL-2.0 is allowed only for unmodified files and data (D3). */
    val UNMODIFIED_ONLY_SPDX = setOf("MPL-2.0")

    data class Component(
        val name: String,
        val version: String,
        val spdx: String,
        val sourceUrl: String,
        val sha256: String,
        val aboutLibrariesId: String,
        val dynamicOnly: Boolean,
        val unmodifiedOnly: Boolean,
    )

    data class Lock(
        val components: List<Component>,
    )

    fun parse(text: String): Lock {
        val parsed = Toml.parse(text)
        if (parsed.hasErrors()) {
            throw GradleException(
                "native-components.lock is not valid TOML: " +
                    parsed.errors().joinToString("; ") { "${it.position()}: ${it.message}" },
            )
        }
        val schema =
            parsed.getLong("schema")
                ?: throw GradleException("native-components.lock: missing `schema`")
        if (schema != SCHEMA.toLong()) {
            throw GradleException("native-components.lock: unsupported schema $schema")
        }
        val components = mutableListOf<Component>()
        for (section in listOf("ffmpeg", "miniaudio")) {
            val table: TomlTable = parsed.getTable(section) ?: continue
            components += component(table)
        }
        if (components.isEmpty()) {
            throw GradleException("native-components.lock: no components recorded")
        }
        return Lock(components)
    }

    private fun component(table: TomlTable): Component {
        fun required(key: String): String =
            table.getString(key) ?: throw GradleException("native-components.lock: `$key` is missing")

        val spdx = required("spdx")
        return Component(
            name = required("name"),
            version = required("version"),
            spdx = spdx,
            sourceUrl = required("sourceUrl"),
            sha256 = required("sha256"),
            aboutLibrariesId = required("aboutLibrariesId"),
            dynamicOnly = spdx in DYNAMIC_ONLY_SPDX,
            unmodifiedOnly = spdx in UNMODIFIED_ONLY_SPDX,
        )
    }

    /** The lock's own configure line, for comparison with the build script's. */
    fun configureLine(parsed: TomlParseResult): String =
        parsed.getString("ffmpeg.configure") ?: throw GradleException("native-components.lock: no configure line")

    /** The FFmpeg library names the lock records, in load order. */
    fun ffmpegLibraries(parsed: TomlParseResult): List<String> {
        val array: TomlArray =
            parsed.getArray("ffmpeg.libraries")
                ?: throw GradleException("native-components.lock: no ffmpeg libraries")
        return array.toList().map { it.toString() }
    }
}

/**
 * `buildFfmpeg`: the minimal FFmpeg build of 11 FFmpeg build on this host's target, run by
 * `playback/native/ffmpeg/build.sh`. It is deliberately not cacheable: no runner pushes a
 * build cache to another, so caching would only let a stale tree from an earlier step of the
 * same job skip the build that step is meant to perform. The build script, the layout probe
 * and the signing key are declared as inputs by the plugin, so editing one rebuilds.
 */
abstract class BuildFfmpegTask : Exec() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val lockFile: RegularFileProperty
}

/**
 * `checkNativeLicences` (01 Gradle-side policy tasks, PLAN M0's static gate, MD0 AC2): the
 * licence envelope of D3 for `:playback:native`. It runs after `buildFfmpeg`, because it
 * checks the built libraries, their `config.h` and their `avcodec_license()` string, not
 * only the lock.
 */
abstract class CheckNativeLicencesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val lockFile: RegularFileProperty

    /** The `buildFfmpeg` output of this target. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val ffmpegOutput: DirectoryProperty

    /** The target whose build is checked; the desktop matrix runs one per target. */
    @get:Input
    abstract val target: Property<String>

    /** PLAN MD0 AC2's budget: the four libraries total at most 4 MB per target. */
    @get:Input
    abstract val maxStrippedBytes: Property<Long>

    @TaskAction
    fun check() {
        val lock = NativeLicencePolicy.parse(lockFile.get().asFile.readText())
        val problems = mutableListOf<String>()
        for (component in lock.components) {
            if (component.spdx !in NativeLicencePolicy.ALLOWED_SPDX) {
                problems += "${component.name} declares '${component.spdx}', which D3 does not allow"
            }
        }

        val out = ffmpegOutput.get().asFile
        val config = out.resolve("ffmpeg-config.h")
        if (!config.isFile) {
            problems += "no ffmpeg-config.h in $out: run buildFfmpeg first"
        } else {
            val text = config.readText()
            for (macro in listOf("CONFIG_GPL", "CONFIG_VERSION3", "CONFIG_NONFREE")) {
                val value = Regex("^#define $macro\\s+(\\d+)$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
                if (value != "0") {
                    problems += "$macro is '$value' in $config; the build must stay LGPL-2.1+"
                }
            }
            if (Regex("^#define CONFIG_NETWORK\\s+1$", RegexOption.MULTILINE).containsMatchIn(text)) {
                problems += "CONFIG_NETWORK is 1 in $config; the build must disable networking"
            }
        }

        val license = out.resolve("ffmpeg-license.txt")
        if (!license.isFile) {
            problems += "no ffmpeg-license.txt in $out: run buildFfmpeg first"
        } else if (license.readText().trim() != "LGPL version 2.1 or later") {
            problems += "avcodec_license() reports '${license.readText().trim()}', not LGPL version 2.1 or later"
        }

        val protocols = out.resolve("ffmpeg-protocols.txt")
        if (!protocols.isFile) {
            problems += "no ffmpeg-protocols.txt in $out: run buildFfmpeg first"
        } else if (protocols.readText().trim().isNotEmpty()) {
            problems += "the build registered protocols: ${protocols.readText().trim()}"
        }

        val sizes = out.resolve("ffmpeg-sizes.txt")
        if (!sizes.isFile) {
            problems += "no ffmpeg-sizes.txt in $out: run buildFfmpeg first"
        } else {
            var total = 0L
            var libraries = 0
            for (line in sizes.readText().lines()) {
                val parts = line.trim().split(' ')
                if (parts.size != 2) {
                    problems += "ffmpeg-sizes.txt line '$line' is not '<library> <bytes>'"
                    continue
                }
                total += parts[1].toLongOrNull() ?: 0L
                libraries += 1
            }
            if (libraries != 4) {
                problems += "ffmpeg-sizes.txt records $libraries libraries, expected the four of D86"
            }
            if (total > maxStrippedBytes.get()) {
                problems += "the four libraries total $total bytes, over the ${maxStrippedBytes.get()} budget"
            }
            logger.lifecycle("checkNativeLicences: $total bytes for $libraries libraries ($target)")
        }

        val components = out.resolve("ffmpeg-components.txt")
        if (!components.isFile) {
            problems += "no ffmpeg-components.txt in $out: run buildFfmpeg first"
        } else {
            val enabled = components.readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }.toSet()
            val unexpected =
                enabled.filterNot { name ->
                    EXPECTED_COMPONENTS.contains(name)
                }
            if (unexpected.isNotEmpty()) {
                problems += "the build enabled components D86 does not list: ${unexpected.sorted()}"
            }
            val missing = EXPECTED_COMPONENTS - enabled
            if (missing.isNotEmpty()) {
                problems += "the build is missing components D86 lists: ${missing.sorted()}"
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "Native licence policy violations in native-components.lock:\n" +
                    problems.joinToString("\n") { "  - $it" },
            )
        }
        logger.lifecycle("checkNativeLicences: clean")
    }

    private companion object {
        /** Exactly what 11 FFmpeg build's configure line enables, as `config.h` names it. */
        val EXPECTED_COMPONENTS =
            setOf(
                "MOV_DEMUXER",
                "MATROSKA_DEMUXER",
                "OGG_DEMUXER",
                "MP3_DEMUXER",
                "FLAC_DEMUXER",
                "WAV_DEMUXER",
                "AAC_DEMUXER",
                "AAC_DECODER",
                "AAC_FIXED_DECODER",
                "MP3FLOAT_DECODER",
                "MP3_DECODER",
                "OPUS_DECODER",
                "VORBIS_DECODER",
                "FLAC_DECODER",
                "ALAC_DECODER",
                "PCM_S16LE_DECODER",
                "PCM_S24LE_DECODER",
                "PCM_F32LE_DECODER",
                "PCM_U8_DECODER",
                "AAC_PARSER",
                "MPEGAUDIO_PARSER",
                "OPUS_PARSER",
                "VORBIS_PARSER",
                "FLAC_PARSER",
                "AAC_ADTSTOASC_BSF",
            )
    }
}
