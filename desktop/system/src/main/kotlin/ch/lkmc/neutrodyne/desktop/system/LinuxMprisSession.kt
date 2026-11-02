// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.playback.api.NowPlaying
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.types.Variant

/**
 * The MD0 MPRIS prototype (11 Media session integration, AC4): exports
 * `org.mpris.MediaPlayer2.neutrodyne` at `/org/mpris/MediaPlayer2` through
 * dbus-java so Linux media keys and shell applets reach the engine as
 * [RemoteCommand]s.
 *
 * dbus-java dispatches `org.freedesktop.DBus.Properties` Get/Set/GetAll to the
 * `@DBusBoundProperty` methods on [MprisRoot]/[MprisPlayer]; the state they
 * read is volatile because property queries run on the bus worker thread
 * while [publish] runs on the app's thread. Commands are posted to a buffered
 * flow — never executed on the bus thread (11: command handling belongs to
 * the playback thread).
 *
 * Spike scope: Raise/Quit/OpenUri/Fullscreen are no-ops (there is no window
 * or app lifecycle yet) and `Stop` maps to [RemoteCommand.Pause]; position
 * answers the last published value without interpolation.
 */
class LinuxMprisSession private constructor(
    private val connection: DBusConnection,
) : SystemMediaSession,
    MprisRoot,
    MprisPlayer {
    // A Channel, not a SharedFlow: queued commands must survive until a
    // collector attaches, while replay=0 drops every emit that lands before
    // subscription.
    private val commandChannel = Channel<RemoteCommand>(COMMAND_BUFFER)

    override val commands: Flow<RemoteCommand> = commandChannel.receiveAsFlow()

    /** The path [open] exports this object under. */
    override fun getObjectPath() = MprisNames.PATH

    @Volatile private var current: NowPlaying? = null

    @Volatile private var rate = 1.0

    @Volatile private var positionMs = 0L

    @Volatile private var durationMs: Long? = null

    @Volatile private var volume = 1.0

    @Volatile private var closed = false

    override fun publish(
        nowPlaying: NowPlaying?,
        skipBackMs: Long,
        skipForwardMs: Long,
        speedPresets: List<Float>,
    ) {
        current = nowPlaying
        rate = (nowPlaying?.position?.speed ?: 1f).toDouble()
        positionMs = nowPlaying?.position?.positionMs ?: 0
        durationMs = nowPlaying?.position?.durationMs
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { connection.unExportObject(MprisNames.PATH) }
        runCatching { connection.releaseBusName(MprisNames.BUS) }
        connection.close()
    }

    // -- org.mpris.MediaPlayer2 ------------------------------------------------

    override fun Raise() = Unit

    override fun Quit() = Unit

    override fun isFullscreen() = false

    override fun setFullscreen(fullscreen: Boolean) = Unit

    override fun getCanSetFullscreen() = false

    override fun getCanRaise() = false

    override fun getCanQuit() = false

    override fun getHasTrackList() = false

    override fun getIdentity() = "Neutrodyne"

    override fun getDesktopEntry() = DESKTOP_ENTRY

    override fun getSupportedUriSchemes() = URI_SCHEMES

    override fun getSupportedMimeTypes() = MIME_TYPES

    // -- org.mpris.MediaPlayer2.Player ------------------------------------------

    override fun Next() = emit(RemoteCommand.Next)

    override fun Previous() = emit(RemoteCommand.Previous)

    override fun Pause() = emit(RemoteCommand.Pause)

    override fun PlayPause() = emit(RemoteCommand.Toggle)

    // MPRIS Stop is not in RemoteCommand; pausing is the closest match.
    override fun Stop() = emit(RemoteCommand.Pause)

    override fun Play() = emit(RemoteCommand.Play)

    override fun Seek(offset: Long) {
        val target = positionMs + offset / US_PER_MS
        emit(RemoteCommand.SeekTo(target))
        emitSeeked(target)
    }

    override fun SetPosition(
        trackId: DBusPath,
        position: Long,
    ) {
        val item = current ?: return
        if (trackId.path != "$TRACK_PATH/${item.episodeId}" || position < 0) return
        val target = position / US_PER_MS
        emit(RemoteCommand.SeekTo(target))
        emitSeeked(target)
    }

    // The spike cannot hand a URI to the engine; MD1 decides policy.
    override fun OpenUri(uri: String) = Unit

    override fun getPlaybackStatus() =
        when {
            current == null -> "Stopped"
            current?.isPlaying == true -> "Playing"
            else -> "Paused"
        }

    override fun getLoopStatus() = "None"

    override fun getRate() = rate

    override fun setRate(rate: Double) {
        val clamped = rate.coerceIn(MIN_RATE, MAX_RATE)
        this.rate = clamped
        emit(RemoteCommand.SetRate(clamped.toFloat()))
    }

    override fun getShuffle() = false

    override fun setShuffle(shuffle: Boolean) = Unit

    override fun getMetadata(): Map<String, Variant<*>> {
        val item = current ?: return emptyMap()
        return buildMap {
            put("mpris:trackid", Variant(DBusPath("$TRACK_PATH/${item.episodeId}")))
            put("xesam:title", Variant(item.title))
            // Collections inside a Variant need the explicit signature; the
            // marshaller cannot infer the element type of listOf(x).
            put("xesam:artist", Variant(listOf(item.podcastTitle), "as"))
            put("xesam:album", Variant(item.podcastTitle))
            item.artwork.url?.let { put("mpris:artUrl", Variant(it)) }
            durationMs?.let { put("mpris:length", Variant(it * US_PER_MS)) }
        }
    }

    override fun getVolume() = volume

    override fun setVolume(volume: Double) {
        this.volume = volume.coerceIn(0.0, 1.0)
    }

    override fun getPosition() = positionMs * US_PER_MS

    override fun getMinimumRate() = MIN_RATE

    override fun getMaximumRate() = MAX_RATE

    override fun getCanGoNext() = current?.hasNext == true

    override fun getCanGoPrevious() = false

    override fun getCanPlay() = current != null

    override fun getCanPause() = current != null

    override fun getCanSeek() = durationMs != null

    override fun getCanControl() = true

    private fun emit(command: RemoteCommand) {
        commandChannel.trySend(command)
    }

    private fun emitSeeked(positionMs: Long) {
        runCatching { connection.sendMessage(SeekedSignal(MprisNames.PATH, positionMs * US_PER_MS)) }
    }

    companion object {
        private const val US_PER_MS = 1_000L
        private const val MIN_RATE = 0.5
        private const val MAX_RATE = 3.0
        private const val COMMAND_BUFFER = 64
        private const val TRACK_PATH = "/ch/lkmc/neutrodyne/episode"
        private const val DESKTOP_ENTRY = "neutrodyne"
        private val URI_SCHEMES = listOf("file", "http", "https")
        private val MIME_TYPES =
            listOf(
                "audio/mpeg",
                "audio/mp4",
                "audio/aac",
                "audio/ogg",
                "audio/vorbis",
                "audio/opus",
                "audio/flac",
                "audio/x-wav",
                "audio/webm",
                "video/webm",
            )

        /**
         * Exports the session on the session bus, or on the bus at
         * [busAddress] (the private-bus test passes `dbus-daemon
         * --print-address` output here).
         */
        fun open(busAddress: String? = null): LinuxMprisSession {
            val connection =
                when (busAddress) {
                    null -> DBusConnectionBuilder.forSessionBus().build()
                    else -> DBusConnectionBuilder.forAddress(busAddress).build()
                }
            return try {
                val session = LinuxMprisSession(connection)
                connection.exportObject(MprisNames.PATH, session)
                connection.requestBusName(MprisNames.BUS)
                session
            } catch (e: Exception) {
                runCatching { connection.close() }
                throw e
            }
        }
    }
}
