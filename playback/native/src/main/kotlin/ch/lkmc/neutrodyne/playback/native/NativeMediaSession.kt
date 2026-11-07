// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.native

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.file.Path

/** MD0 OS-session prototype. Native callbacks enqueue; only this poll enters the JVM. */
class NativeMediaSession private constructor(
    private val library: NdmediaLibrary,
) : AutoCloseable {
    private val publishCall = library.downcall("nd_session_publish", PUBLISH)
    private val pollCall = library.downcall("nd_session_poll", POLL)
    private val closeCall = library.downcall("nd_session_close", FunctionDescriptor.ofVoid())
    private var closed = false

    @Synchronized
    fun publish(info: NativeMediaInfo) {
        check(!closed) { "media session is closed" }
        Arena.ofConfined().use { arena ->
            val struct = arena.allocate(NdmediaAbi.MEDIA_INFO_SIZE)
            struct.set(
                NdmediaAbi.ADDRESS,
                NdmediaAbi.MEDIA_INFO_TITLE_OFFSET,
                info.title?.let(arena::allocateFrom) ?: MemorySegment.NULL,
            )
            struct.set(
                NdmediaAbi.ADDRESS,
                NdmediaAbi.MEDIA_INFO_ARTIST_OFFSET,
                info.artist?.let(arena::allocateFrom) ?: MemorySegment.NULL,
            )
            struct.set(
                NdmediaAbi.ADDRESS,
                NdmediaAbi.MEDIA_INFO_ALBUM_OFFSET,
                info.album?.let(arena::allocateFrom) ?: MemorySegment.NULL,
            )
            struct.set(
                NdmediaAbi.ADDRESS,
                NdmediaAbi.MEDIA_INFO_ART_PATH_OFFSET,
                info.artPath?.let(arena::allocateFrom) ?: MemorySegment.NULL,
            )
            struct.set(ValueLayout.JAVA_LONG, NdmediaAbi.MEDIA_INFO_DURATION_OFFSET, info.durationMs)
            struct.set(ValueLayout.JAVA_LONG, NdmediaAbi.MEDIA_INFO_POSITION_OFFSET, info.positionMs)
            struct.set(ValueLayout.JAVA_FLOAT, NdmediaAbi.MEDIA_INFO_RATE_OFFSET, info.rate)
            struct.set(ValueLayout.JAVA_INT, NdmediaAbi.MEDIA_INFO_PLAYING_OFFSET, if (info.playing) 1 else 0)
            val result = publishCall.invokeExact(struct) as Int
            check(result >= 0) { "native media publish failed: $result" }
        }
    }

    @Synchronized
    fun poll(): NativeSessionEvent? {
        check(!closed) { "media session is closed" }
        Arena.ofConfined().use { arena ->
            val value = arena.allocate(ValueLayout.JAVA_LONG)
            val result = pollCall.invokeExact(value) as Int
            check(result >= 0) { "native media session failed: $result" }
            if (result == 0) return null
            val kind = NativeSessionEventKind.entries.single { it.code == result }
            return NativeSessionEvent(kind, value.get(ValueLayout.JAVA_LONG, 0))
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            closeCall.invokeExact()
        } finally {
            library.close()
        }
    }

    companion object {
        private val PUBLISH = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
        private val POLL = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)

        fun open(directory: Path): NativeMediaSession {
            val library = NdmediaLibrary.load(directory)
            try {
                val open = library.downcall("nd_session_open", FunctionDescriptor.of(ValueLayout.JAVA_INT))
                val result = open.invokeExact() as Int
                check(result >= 0) { "native media session open failed: $result" }
                return NativeMediaSession(library)
            } catch (failure: Throwable) {
                library.close()
                throw failure
            }
        }
    }
}

/** One publish: the media-session metadata of 11's SMTC and Now Playing rows. */
data class NativeMediaInfo(
    val title: String?,
    val artist: String?,
    val album: String?,
    val artPath: String?,
    val durationMs: Long,
    val positionMs: Long,
    val rate: Float,
    val playing: Boolean,
)

enum class NativeSessionEventKind(
    val code: Int,
) {
    PLAY(1),
    PAUSE(2),
    TOGGLE(3),
    NEXT(4),
    PREVIOUS(5),
    SEEK(6),
    SUSPEND(7),
    RESUME(8),
    REMOVED(9),
    DEFAULT_CHANGED(10),
}

data class NativeSessionEvent(
    val kind: NativeSessionEventKind,
    val value: Long,
)
