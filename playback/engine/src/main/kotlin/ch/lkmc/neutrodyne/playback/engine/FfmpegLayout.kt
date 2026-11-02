// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * `ffmpeg-layout.json`: the struct offsets, sizes and constants `ffoffsets.c`
 * printed from the very headers of the build it was compiled against (11 FFM
 * bindings). The hand-written bindings read every public field they touch from
 * here instead of hard-coding it, so a same-major FFmpeg rebuild whose layout
 * shifted still binds and a different major is refused before any struct is
 * touched.
 */
@Serializable
data class FfmpegLayout(
    val ffmpegVersion: String,
    val libraries: Map<String, Int>,
    val structs: Map<String, StructLayout>,
    val codecIds: Map<String, Int>,
    val constants: Map<String, Long>,
) {
    val avcodecMajor: Int get() = majorOf("avcodec")

    val avformatMajor: Int get() = majorOf("avformat")

    val avutilMajor: Int get() = majorOf("avutil")

    val swresampleMajor: Int get() = majorOf("swresample")

    private fun majorOf(library: String): Int =
        requireNotNull(libraries[library]) { "ffmpeg-layout.json records no $library major" }

    fun struct(name: String): StructLayout = requireNotNull(structs[name]) { "ffmpeg-layout.json has no struct $name" }

    /** The offset of one field of a struct the layout records. */
    fun offset(
        struct: String,
        field: String,
    ): Long = struct(struct).offsetOf(field)

    fun codecId(codec: AudioCodec): Int =
        requireNotNull(codecIds[codec.name]) {
            "ffmpeg-layout.json records no codec id for ${codec.name}"
        }

    fun constant(name: String): Long =
        requireNotNull(constants[name]) { "ffmpeg-layout.json records no constant $name" }

    /** A named constant used as an enum argument, so it fits an Int. */
    fun intConstant(name: String): Int = constant(name).toInt()

    companion object {
        /** The file name `build.sh` writes next to the four libraries. */
        const val FILE_NAME = "ffmpeg-layout.json"

        fun decode(text: String): FfmpegLayout = Json.decodeFromString(serializer(), text)

        /** The layout of the FFmpeg build in [directory], or null when it has not been built. */
        fun readOrNull(directory: Path): FfmpegLayout? {
            val file = directory.resolve(FILE_NAME)
            if (!file.isRegularFile()) return null
            return decode(file.readText())
        }
    }
}

/** One struct's size and the offsets of the fields the bindings read. */
@Serializable
data class StructLayout(
    val size: Long,
    val fields: Map<String, Long>,
) {
    fun offsetOf(field: String): Long = requireNotNull(fields[field]) { "ffmpeg-layout.json records no field $field" }

    /** Every field this struct's readers need, checked once at load time. */
    fun requireFields(vararg names: String) {
        val missing = names.filterNot { fields.containsKey(it) }
        require(missing.isEmpty()) { "ffmpeg-layout.json's struct is missing $missing" }
    }
}
