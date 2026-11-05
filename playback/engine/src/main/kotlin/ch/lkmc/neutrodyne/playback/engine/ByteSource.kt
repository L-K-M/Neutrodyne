// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import java.lang.foreign.MemorySegment
import java.nio.file.Path

/**
 * A readable byte range of one episode (11 Engine contracts, Sources and SpanCache).
 * Production sources are `FileByteSource` (a download) and an HTTP source over the
 * span cache; the spike uses the file source and an in-memory one.
 *
 * Reads are positional and blocking, and run on the engine or prepare thread —
 * never on the audio thread. [abort] makes a blocked read return so a seek or a
 * window change can interrupt it.
 */
interface ByteSource : AutoCloseable {
    /** Total size in bytes, or null while it is unknown (no `Content-Range` total yet). */
    val length: Long?

    /**
     * Read up to [maxBytes] into [dst] at [position]. Returns the number of bytes
     * read, 0 at end of file, or -1 when the read was aborted.
     */
    fun read(
        position: Long,
        dst: MemorySegment,
        maxBytes: Int,
    ): Int

    /** Any thread; makes a blocked [read] return -1. */
    fun abort()

    override fun close()
}

/** A file source over a positional `FileChannel` (11 Sources and SpanCache). */
interface FileByteSourceFactory {
    fun open(path: Path): ByteSource
}

/** What the demuxer may assume about the container before it probes (11 Demux and decode). */
enum class SourceHint {
    /** Probe the container from the bytes (the default). */
    UNKNOWN,

    /** A local file with a known extension. */
    FILE,

    /** A YouTube audio stream (`itag` structure, no edit list). */
    YOUTUBE,
}
