// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.Files
import java.nio.file.Path

/**
 * The minimal LGPL FFmpeg build of D86, loaded as four separate shared libraries
 * with their upstream names (11 FFmpeg build, FFM bindings).
 *
 * The libraries keep those names so a user can replace them (LGPL-2.1 §6(b)); no
 * FFmpeg symbol is linked into `ndmedia`. [load] refuses a build whose library
 * majors differ from the layout's, so a replacement library of the same major
 * binds and a different major fails with a clear error before any struct field
 * is read.
 *
 * Every downcall is a hand-written [MethodHandle]; jextract is never used
 * (GPL-2.0, banned by `verifyDependencyPolicy`).
 */
class FfmpegLibrary
    private constructor(
        /** The layout generated from the very headers these libraries were built with. */
        val layout: FfmpegLayout,
        private val arena: Arena,
        private val lookups: Map<String, SymbolLookup>,
    ) : AutoCloseable {
        private val linker = Linker.nativeLinker()
        private val resolved = mutableMapOf<String, MethodHandle>()

        /**
         * The downcall handle of [name] in [library], resolved once and cached. A
         * missing symbol is an error: it means this build was configured without
         * the component the bindings need.
         */
        fun downcall(
            library: String,
            name: String,
            descriptor: FunctionDescriptor,
        ): MethodHandle =
            resolved.getOrPut("$library.$name") {
                val address =
                    requireNotNull(lookups[library]) { "the $library library is not loaded" }
                        .find(name)
                        .orElseThrow {
                            IllegalStateException("$name is missing from lib$library of this FFmpeg build")
                        }
                linker.downcallHandle(address, descriptor)
            }

        // ------------------------------------------------------------ versions and licence

        val avcodecVersion: Int get() = callInt("avcodec", "avcodec_version", VERSION)

        val avformatVersion: Int get() = callInt("avformat", "avformat_version", VERSION)

        val avutilVersion: Int get() = callInt("avutil", "avutil_version", VERSION)

        // libswresample 7 renamed swr_version to swresample_version.
        val swresampleVersion: Int get() = callInt("swresample", "swresample_version", VERSION)

        /**
         * `avcodec_license()`, the string MD0 AC2 checks: a build configured with
         * `--enable-gpl`, `--enable-version3` or `--enable-nonfree` reports
         * something else, so the licence gate reads it from the built libraries.
         */
        val license: String get() = readCString(callAddress("avcodec", "avcodec_license", LICENSE))

        /** `av_strerror`, for a message that names the failure instead of a number. */
        fun errorString(code: Int): String {
            val size = layout.intConstant("AV_ERROR_MAX_STRING_SIZE").toLong()
            val buffer = arena.allocate(size)
            val written = callInt("avutil", "av_strerror", STRERROR, code, buffer, size)
            return if (written < 0) "FFmpeg error $code" else readCString(buffer)
        }

        // ------------------------------------------------------------ memory and strings

        /**
         * `av_malloc`: the one allocator the AVIO buffer must come from, because
         * the format context releases it when it is closed.
         */
        fun malloc(size: Long): MemorySegment = callAddress("avutil", "av_malloc", MALLOC, size)

        /** The C strings the bindings pass as option and dictionary keys. */
        fun cString(value: String): MemorySegment = arena.allocateFrom(value)

        /** Reads a NUL-terminated C string out of native memory. */
        fun readCString(pointer: MemorySegment): String {
            if (pointer.address() == 0L) return ""
            return pointer.reinterpret(MAX_C_STRING).getString(0)
        }

        /** `av_log_set_level(AV_LOG_QUIET)`: FFmpeg's own logging stays off. */
        fun setQuietLogging() =
            callVoid("avutil", "av_log_set_level", LOG_SET_LEVEL, layout.intConstant("AV_LOG_QUIET"))

        // ------------------------------------------------------------ format context

        fun allocFormatContext(): MemorySegment = callAddress("avformat", "avformat_alloc_context", ALLOC_CONTEXT)

        /**
         * `avformat_open_input`. [contextSlot] holds the context pointer and is
         * overwritten by FFmpeg; on failure the context is released and the slot
         * is set to null, which is what keeps the caller from freeing it twice.
         */
        fun openInput(
            contextSlot: MemorySegment,
            fileName: MemorySegment,
        ): Int =
            callInt(
                "avformat",
                "avformat_open_input",
                OPEN_INPUT,
                contextSlot,
                fileName,
                MemorySegment.NULL,
                MemorySegment.NULL,
            )

        fun findStreamInfo(context: MemorySegment): Int =
            callInt(
                "avformat",
                "avformat_find_stream_info",
                FIND_STREAM_INFO,
                context,
                MemorySegment.NULL,
            )

        fun closeInput(contextSlot: MemorySegment) =
            callVoid("avformat", "avformat_close_input", CLOSE_INPUT, contextSlot)

        fun readFrame(
            context: MemorySegment,
            packet: MemorySegment,
        ): Int = callInt("avformat", "av_read_frame", READ_FRAME, context, packet)

        fun seekFile(
            context: MemorySegment,
            streamIndex: Int,
            minTs: Long,
            targetTs: Long,
            maxTs: Long,
            flags: Int,
        ): Int =
            callInt(
                "avformat",
                "avformat_seek_file",
                SEEK_FILE,
                context,
                streamIndex,
                minTs,
                targetTs,
                maxTs,
                flags,
            )

        fun findBestStream(
            context: MemorySegment,
            mediaType: Int,
        ): Int =
            callInt(
                "avformat",
                "av_find_best_stream",
                FIND_BEST_STREAM,
                context,
                mediaType,
                -1,
                -1,
                MemorySegment.NULL,
                0,
            )

        // ------------------------------------------------------------ packets and frames

        fun allocPacketSlot(arena: Arena): MemorySegment =
            arena.allocate(ValueLayout.ADDRESS).also {
                it.set(ValueLayout.ADDRESS, 0L, callAddress("avcodec", "av_packet_alloc", ALLOC_PACKET))
            }

        /**
         * `av_packet_ref`: the send packet becomes a refcounted copy of the
         * demuxer's packet — data, timestamps and the side data (skip samples,
         * discard padding) that FFmpeg's own decoders trim by. Copying the
         * data bytes alone would drop every one of those properties.
         */
        fun refPacket(
            target: MemorySegment,
            source: MemorySegment,
        ): Int = callInt("avcodec", "av_packet_ref", REF_PACKET, target, source)

        fun freePacket(slot: MemorySegment) = callVoid("avcodec", "av_packet_free", FREE_PACKET, slot)

        fun unrefPacket(packet: MemorySegment) = callVoid("avcodec", "av_packet_unref", UNREF_PACKET, packet)

        fun allocFrameSlot(arena: Arena): MemorySegment =
            arena.allocate(ValueLayout.ADDRESS).also {
                it.set(ValueLayout.ADDRESS, 0L, callAddress("avutil", "av_frame_alloc", ALLOC_FRAME))
            }

        fun freeFrame(slot: MemorySegment) = callVoid("avutil", "av_frame_free", FREE_FRAME, slot)

        fun unrefFrame(frame: MemorySegment) = callVoid("avutil", "av_frame_unref", UNREF_FRAME, frame)

        // ------------------------------------------------------------ decoder

        fun findDecoder(codecId: Int): MemorySegment =
            callAddress("avcodec", "avcodec_find_decoder", FIND_DECODER, codecId)

        fun allocCodecContext(codec: MemorySegment): MemorySegment =
            callAddress("avcodec", "avcodec_alloc_context3", ALLOC_CODEC_CONTEXT, codec)

        fun parametersToContext(
            context: MemorySegment,
            parameters: MemorySegment,
        ): Int =
            callInt(
                "avcodec",
                "avcodec_parameters_to_context",
                PARAMETERS_TO_CONTEXT,
                context,
                parameters,
            )

        /**
         * `av_opt_set_int` on the decoder's `threads`: FFmpeg starts no decoder
         * thread, so no FFmpeg thread ever touches the pipeline's memory (11 FFM
         * bindings).
         */
        fun setDecoderThreads(
            context: MemorySegment,
            threads: Int,
        ): Int = callInt("avutil", "av_opt_set_int", OPT_SET_INT, context, cString("threads"), threads.toLong(), 0)

        fun openCodec(
            context: MemorySegment,
            codec: MemorySegment,
        ): Int = callInt("avcodec", "avcodec_open2", OPEN_CODEC, context, codec, MemorySegment.NULL)

        fun freeCodecContext(slot: MemorySegment) =
            callVoid("avcodec", "avcodec_free_context", FREE_CODEC_CONTEXT, slot)

        fun sendPacket(
            context: MemorySegment,
            packet: MemorySegment?,
        ): Int = callInt("avcodec", "avcodec_send_packet", SEND_PACKET, context, packet ?: MemorySegment.NULL)

        fun receiveFrame(
            context: MemorySegment,
            frame: MemorySegment,
        ): Int = callInt("avcodec", "avcodec_receive_frame", RECEIVE_FRAME, context, frame)

        fun flushCodecBuffers(context: MemorySegment) =
            callVoid("avcodec", "avcodec_flush_buffers", FLUSH_BUFFERS, context)

        // ------------------------------------------------------------ resampler

        fun allocResampler(): MemorySegment = callAddress("swresample", "swr_alloc", SWR_ALLOC)

        /**
         * Configures [resampler] through AVOptions: the output is interleaved
         * s16 stereo at the source rate, so the DSP chain of 11 runs at the
         * source rate and only the output stage resamples to the device's.
         * libswresample 7 removed `swr_alloc_set_opts` for the
         * AVChannelLayout-taking `swr_alloc_set_opts2`; the option strings
         * carry the same masks through `av_channel_layout_from_string`, so no
         * struct is marshalled.
         */
        fun setResamplerOptions(
            resampler: MemorySegment,
            outChannelMask: Long,
            outRate: Int,
            inChannelMask: Long,
            inFormat: Int,
            inRate: Int,
        ) {
            optionSet(resampler, "in_chlayout", "0x${java.lang.Long.toHexString(inChannelMask)}")
            optionSet(resampler, "out_chlayout", "0x${java.lang.Long.toHexString(outChannelMask)}")
            optionSetInt(resampler, "in_sample_fmt", inFormat)
            optionSetInt(resampler, "out_sample_fmt", layout.intConstant("AV_SAMPLE_FMT_S16"))
            optionSetInt(resampler, "in_sample_rate", inRate)
            optionSetInt(resampler, "out_sample_rate", outRate)
        }

        fun initResampler(resampler: MemorySegment): Int = callInt("swresample", "swr_init", SWR_INIT, resampler)

        /**
         * `swr_convert` into a caller-owned buffer. Both sample-pointer
         * arguments are `uint8_t **`: [out] is a slot that holds the output
         * buffer's address, [input] is the frame's `extended_data` passed
         * straight through, so the bindings never dereference a plane pointer
         * themselves.
         */
        fun convert(
            resampler: MemorySegment,
            out: MemorySegment,
            outSamplesPerChannel: Int,
            input: MemorySegment?,
            inSamplesPerChannel: Int,
        ): Int =
            callInt(
                "swresample",
                "swr_convert",
                SWR_CONVERT,
                resampler,
                out,
                outSamplesPerChannel,
                input ?: MemorySegment.NULL,
                inSamplesPerChannel,
            )

        fun freeResampler(slot: MemorySegment) = callVoid("swresample", "swr_free", SWR_FREE, slot)

        // ------------------------------------------------------------ metadata

        private fun optionSet(
            resampler: MemorySegment,
            name: String,
            value: String,
        ) {
            val set = callInt("avutil", "av_opt_set", OPT_SET, resampler, cString(name), cString(value), 0)
            require(set >= 0) { "the resampler refused $name=$value: ${errorString(set)}" }
        }

        private fun optionSetInt(
            resampler: MemorySegment,
            name: String,
            value: Int,
        ) {
            val set = callInt("avutil", "av_opt_set_int", OPT_SET_INT, resampler, cString(name), value.toLong(), 0)
            require(set >= 0) { "the resampler refused $name=$value: ${errorString(set)}" }
        }

        /** `av_dict_get`, or null when the entry is absent. */
        fun dictGet(
            dictionary: MemorySegment,
            key: MemorySegment,
        ): MemorySegment? =
            callAddress("avutil", "av_dict_get", DICT_GET, dictionary, key, MemorySegment.NULL, 0)
                .takeIf { it.address() != 0L }

        override fun close() {
            resolved.clear()
            arena.close()
        }

        private fun callInt(
            library: String,
            name: String,
            descriptor: FunctionDescriptor,
            vararg args: Any?,
        ): Int = downcall(library, name, descriptor).callInt(args)

        private fun callVoid(
            library: String,
            name: String,
            descriptor: FunctionDescriptor,
            vararg args: Any?,
        ) = downcall(library, name, descriptor).callVoid(args)

        private fun callAddress(
            library: String,
            name: String,
            descriptor: FunctionDescriptor,
            vararg args: Any?,
        ): MemorySegment = downcall(library, name, descriptor).callAddress(args)

        companion object {
            /** One C string's ceiling when reading it back; titles are short. */
            private const val MAX_C_STRING = 4096L

            /**
             * Dependency order: each library loads only after the libraries its
             * NEEDED entries name. The dynamic loader satisfies a NEEDED soname
             * from an already-loaded object before it searches RUNPATH, so this
             * order is what keeps a load working even when a library's own
             * RUNPATH does not name its siblings' directory.
             */
            private val LOAD_ORDER = listOf("avutil", "swresample", "avcodec", "avformat")

            /**
             * Loads the four libraries of [directory] and checks them against the
             * layout beside them. Throws when the build is missing, incomplete or
             * of a different major.
             */
            fun load(directory: Path): FfmpegLibrary {
                require(Files.isDirectory(directory)) { "no FFmpeg build in $directory: run buildFfmpeg" }
                val layout =
                    FfmpegLayout.readOrNull(directory)
                        ?: error("no ${FfmpegLayout.FILE_NAME} in $directory: run buildFfmpeg")
                val os = System.getProperty("os.name")
                // FFmpeg installs shared libs under $prefix/lib, its Windows
                // DLLs under $prefix/bin (configure's shlibdir_default).
                val libDir = if (os.startsWith("Windows")) "bin" else "lib"
                val arena = Arena.ofShared()
                val lookups =
                    layout.libraries.entries
                        .sortedBy { entry ->
                            LOAD_ORDER.indexOf(entry.key).let { i -> if (i < 0) LOAD_ORDER.size else i }
                        }
                        .associate { (name, major) ->
                        name to
                            SymbolLookup.libraryLookup(
                                directory
                                    .resolve(libDir)
                                    .resolve(libraryFileName(name, major, os))
                                    .toAbsolutePath()
                                    .toString(),
                                arena,
                            )
                    }
                val library = FfmpegLibrary(layout, arena, lookups)
                library.verifyMajors()
                library.setQuietLogging()
                return library
            }

            /**
             * The file name of one FFmpeg library on [osName], keeping FFmpeg's
             * upstream naming so the four stay separately replaceable.
             */
            fun libraryFileName(
                library: String,
                major: Int,
                osName: String = System.getProperty("os.name"),
            ): String =
                when {
                    osName.startsWith("Windows") -> "$library-$major.dll"
                    osName.startsWith("Mac") -> "lib$library.$major.dylib"
                    else -> "lib$library.so.$major"
                }

            /** Every file name this target's build must install, for the licence gate. */
            fun libraryNames(
                layout: FfmpegLayout,
                osName: String = System.getProperty("os.name"),
            ): List<String> = layout.libraries.map { (library, major) -> libraryFileName(library, major, osName) }

            private val VERSION = FunctionDescriptor.of(ValueLayout.JAVA_INT)
            private val LICENSE = FunctionDescriptor.of(ValueLayout.ADDRESS)
            private val STRERROR =
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG,
                )
            private val MALLOC = FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
            private val LOG_SET_LEVEL = FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT)
            private val OPT_SET_INT =
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT,
                )
            private val ALLOC_CONTEXT = FunctionDescriptor.of(ValueLayout.ADDRESS)
            private val OPEN_INPUT =
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                )
            private val FIND_STREAM_INFO =
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            private val CLOSE_INPUT = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
            private val READ_FRAME =
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            private val SEEK_FILE =
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT,
                )
            private val FIND_BEST_STREAM =
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                )
            private val ALLOC_PACKET = FunctionDescriptor.of(ValueLayout.ADDRESS)
            private val REF_PACKET =
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            private val FREE_PACKET = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
            private val UNREF_PACKET = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
            private val ALLOC_FRAME = FunctionDescriptor.of(ValueLayout.ADDRESS)
            private val FREE_FRAME = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
            private val UNREF_FRAME = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
            private val FIND_DECODER = FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
            private val ALLOC_CODEC_CONTEXT = FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            private val PARAMETERS_TO_CONTEXT =
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            private val OPEN_CODEC =
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                )
            private val FREE_CODEC_CONTEXT = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
            private val SEND_PACKET =
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            private val RECEIVE_FRAME =
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            private val FLUSH_BUFFERS = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
            private val SWR_ALLOC = FunctionDescriptor.of(ValueLayout.ADDRESS)
            private val OPT_SET =
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                )
            private val SWR_INIT = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
            private val SWR_CONVERT =
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                )
            private val SWR_FREE = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
            private val DICT_GET =
                FunctionDescriptor.of(
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                )

            /**
             * The loaded libraries must report the majors the layout was generated
             * from (11 FFM bindings): a same-major replacement binds, a different
             * major is refused before any struct field is read.
             */
            private fun FfmpegLibrary.verifyMajors() {
                val actual =
                    mapOf(
                        "avcodec" to avcodecVersion.major(),
                        "avformat" to avformatVersion.major(),
                        "avutil" to avutilVersion.major(),
                        "swresample" to swresampleVersion.major(),
                    )
                val expected = layout.libraries
                val mismatch = actual.filter { (library, major) -> expected[library] != major }
                require(mismatch.isEmpty()) {
                    "the loaded FFmpeg libraries are $actual, the layout expects $expected"
                }
            }

            /** The library major out of FFmpeg's packed `LIB*_VERSION_INT`. */
            private fun Int.major(): Int = (this ushr 16) and 0xFF
        }
    }
