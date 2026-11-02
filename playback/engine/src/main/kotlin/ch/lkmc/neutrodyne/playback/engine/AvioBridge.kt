// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A custom AVIO over a JVM [ByteSource] (11 Demux and decode step 2).
 *
 * FFmpeg reads the whole item through this context, so no FFmpeg file or network
 * protocol is ever compiled in or used and the build's protocol inventory stays
 * empty (MD0 AC2). The read and seek upcalls run on whichever thread called into
 * FFmpeg — `nd-prepare` or `nd-engine` — never on the audio thread, and their
 * bodies catch everything and return a defined FFmpeg error so no throwable
 * crosses the native boundary.
 *
 * The AVIO buffer comes from FFmpeg's own allocator because the format context
 * releases it when it is closed; nothing the context owns lives in [arena],
 * which holds the upcall stubs and the caller's own scratch memory.
 */
class AvioBridge
    private constructor(
        private val library: FfmpegLibrary,
        private val arena: Arena,
        private val source: ByteSource,
    ) : AutoCloseable {
        private val readStub: MemorySegment = upcall("onRead", READ_DESCRIPTOR)

        private val seekStub: MemorySegment = upcall("onSeek", SEEK_DESCRIPTOR)

        /** Reads only: FFmpeg never writes through this context. */
        private val writeStub: MemorySegment = upcall("onWrite", WRITE_DESCRIPTOR)

        private val buffer: MemorySegment = library.malloc(AVIO_BUFFER_BYTES.toLong())

        /** The `AVIOContext*` FFmpeg reads through; the format context owns it. */
        val context: MemorySegment =
            library
                .downcall("avformat", "avio_alloc_context", AVIO_ALLOC)
                .callAddress(arrayOf(buffer, AVIO_BUFFER_BYTES, 0, MemorySegment.NULL, readStub, writeStub, seekStub))

        private var position = 0L
        private val aborted = AtomicBoolean(false)

        /** Any thread; makes a blocked [ByteSource.read] return so a seek can interrupt it. */
        fun abort() {
            aborted.set(true)
            source.abort()
        }

        /**
         * Clears the abort flag, so a seek that has finished does not abort every
         * read after it. Called by the demuxer once its seek has repositioned the
         * source.
         */
        fun resumeReads() {
            aborted.set(false)
        }

        /**
         * The AVIO read upcall. FFmpeg treats a negative return as an error
         * except `AVERROR_EOF`, which is how end of file is reported.
         */
        private fun onRead(
            @Suppress("UNUSED_PARAMETER") opaque: MemorySegment,
            buffer: MemorySegment,
            size: Int,
        ): Int =
            try {
                if (aborted.get()) {
                    return library.layout.intConstant("AVERROR_EXIT")
                }
                when (val read = source.read(position, buffer, size)) {
                    ABORTED -> library.layout.intConstant("AVERROR_EXIT")
                    END_OF_FILE -> library.layout.intConstant("AVERROR_EOF")
                    else -> {
                        position += read
                        read
                    }
                }
            } catch (failure: Throwable) {
                // An upcall must never let a throwable cross into FFmpeg.
                library.layout.intConstant("AVERROR_EIO")
            }

        /**
         * The AVIO seek upcall. `AVSEEK_SIZE` answers a length query without
         * moving; a source of unknown length reports failure, which FFmpeg reads
         * as "cannot seek" and falls back to reading from where it is.
         */
        private fun onSeek(
            @Suppress("UNUSED_PARAMETER") opaque: MemorySegment,
            offset: Long,
            whence: Int,
        ): Long =
            try {
                val layout = library.layout
                val sizeFlag = layout.intConstant("AVSEEK_SIZE")
                if (whence and sizeFlag != 0) {
                    return source.length ?: -1L
                }
                val flags = sizeFlag or layout.intConstant("AVSEEK_FORCE")
                val target =
                    when (whence and flags.inv()) {
                        layout.intConstant("SEEK_SET") -> offset
                        layout.intConstant("SEEK_CUR") -> position + offset
                        layout.intConstant("SEEK_END") -> (source.length ?: return -1L) + offset
                        else -> return -1L
                    }
                if (target < 0) return -1L
                position = target
                target
            } catch (failure: Throwable) {
                -1L
            }

        private fun onWrite(
            @Suppress("UNUSED_PARAMETER") opaque: MemorySegment,
            @Suppress("UNUSED_PARAMETER") buffer: MemorySegment,
            @Suppress("UNUSED_PARAMETER") size: Int,
        ): Int = library.layout.intConstant("AVERROR_EIO")

        override fun close() {
            // The format context owns the AVIOContext and its buffer and releases
            // both when it is closed, so only the upcall stubs are released here.
            // The bridge owns its own arena, so closing it twice is impossible.
            arena.close()
        }

        /** Binds one upcall of this bridge into the bridge's arena. */
        private fun upcall(
            name: String,
            descriptor: FunctionDescriptor,
        ): MemorySegment {
            val lookup = MethodHandles.lookup()
            // The List overload of methodType: spreading an array into the Java
            // vararg does not resolve from Kotlin.
            val returnType = carrier(descriptor.returnLayout().get())
            val parameters = descriptor.argumentLayouts().map(::carrier)
            val target: MethodHandle = lookup.bind(this, name, MethodType.methodType(returnType, parameters))
            return LINKER.upcallStub(target, descriptor, arena)
        }

        companion object {
            /** 64 KiB, the size 11 Demux and decode step 2 specifies. */
            const val AVIO_BUFFER_BYTES = 64 * 1024

            /** [ByteSource.read]'s return values the upcall translates. */
            private const val ABORTED = -1
            private const val END_OF_FILE = 0

            private val LINKER = Linker.nativeLinker()

            private val READ_DESCRIPTOR =
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                )

            private val SEEK_DESCRIPTOR =
                FunctionDescriptor.of(
                    ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT,
                )

            private val WRITE_DESCRIPTOR =
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                )

            private val AVIO_ALLOC =
                FunctionDescriptor.of(
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                )

            /** The JVM carrier type of one foreign layout, for an upcall's signature. */
            private fun carrier(layout: java.lang.foreign.MemoryLayout): Class<*> =
                when (layout) {
                    ValueLayout.JAVA_INT -> Int::class.javaPrimitiveType!!
                    ValueLayout.JAVA_LONG -> Long::class.javaPrimitiveType!!
                    else -> MemorySegment::class.java
                }
        }
    }
