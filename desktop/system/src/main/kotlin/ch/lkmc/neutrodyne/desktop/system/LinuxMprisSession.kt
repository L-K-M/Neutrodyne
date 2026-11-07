// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.playback.api.NowPlaying
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.errors.NotSupported
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant

/**
 * The Linux [SystemMediaSession] (11 Linux MPRIS): exports
 * `org.mpris.MediaPlayer2.neutrodyne` at `/org/mpris/MediaPlayer2` through dbus-java so
 * media keys and shell applets reach the shared controller as [RemoteCommand]s.
 *
 * dbus-java dispatches `org.freedesktop.DBus.Properties` Get/Set/GetAll to the
 * `@DBusBoundProperty` methods on [MprisRoot]/[MprisPlayer]; the state they read is volatile
 * because property queries run on the bus worker thread while [publish] runs on the app's
 * thread. Commands are posted to a buffered flow — never executed on the bus thread (11:
 * command handling belongs to the playback thread). `PropertiesChanged` is signalled for the
 * properties that actually changed between publishes; the spec never signals `Position`.
 *
 * [shell] receives `Raise()`/`Quit()` on the bus thread; the wiring hops to the application
 * scope before touching the window or the shutdown path. `OpenUri` answers
 * `org.freedesktop.DBus.Error.NotSupported` (11 Remote commands' table).
 */
class LinuxMprisSession private constructor(
    private val connection: DBusConnection,
    private val shell: MprisShellActions,
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

    // Written by publish on the app side, read by D-Bus method threads.
    @Volatile private var published: NowPlaying? = null

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
        artworkPath: String?,
    ) {
        val changed = mutableMapOf<String, Variant<*>>()
        val metadata = metadataOf(nowPlaying)
        if (metadata != metadataOf(published)) {
            // Maps need the explicit a{sv} signature: dbus-java cannot infer element types
            // from a Variant's runtime value (the artist array's "as" is the same rule).
            changed["Metadata"] = Variant(metadata, METADATA_SIGNATURE)
        }
        val status = playbackStatus(nowPlaying)
        if (status != playbackStatus(published)) changed["PlaybackStatus"] = Variant(status)

        // The advertised Rate window is the contract; a non-finite or out-of-range speed is
        // clamped onto it (the MD0 review's unclamped-publish finding).
        val nextRate = rateOf(nowPlaying).coerceIn(MIN_RATE, MAX_RATE)
        if (nextRate != rate) {
            rate = nextRate
            changed["Rate"] = Variant(rate)
        }

        published = nowPlaying
        positionMs = nowPlaying?.position?.positionMs ?: 0
        durationMs = nowPlaying?.position?.durationMs
        if (changed.isNotEmpty()) propertiesChanged(changed)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { connection.unExportObject(MprisNames.PATH) }
        runCatching { connection.releaseBusName(MprisNames.BUS) }
        connection.close()
        commandChannel.close()
    }

    // -- org.mpris.MediaPlayer2 ------------------------------------------------

    override fun Raise() = shell.raise()

    override fun Quit() = shell.quit()

    override fun isFullscreen() = false

    override fun setFullscreen(fullscreen: Boolean) = Unit

    override fun getCanSetFullscreen() = false

    override fun getCanRaise() = true

    override fun getCanQuit() = true

    override fun getHasTrackList() = false

    override fun getIdentity() = "Neutrodyne"

    override fun getDesktopEntry() = DESKTOP_ENTRY

    override fun getSupportedUriSchemes() = emptyList<String>()

    override fun getSupportedMimeTypes() = emptyList<String>()

    // -- org.mpris.MediaPlayer2.Player ------------------------------------------

    override fun Next() = emit(RemoteCommand.Next)

    override fun Previous() = emit(RemoteCommand.Previous)

    override fun Pause() = emit(RemoteCommand.Pause)

    override fun PlayPause() = emit(RemoteCommand.Toggle)

    // MPRIS Stop is not in RemoteCommand; pausing keeps the session, like Android (11).
    override fun Stop() = emit(RemoteCommand.Pause)

    override fun Play() = emit(RemoteCommand.Play)

    override fun Seek(offset: Long) {
        val target = (positionMs + offset / US_PER_MS).coerceAtLeast(0)
        emit(RemoteCommand.SeekTo(target))
        emitSeeked(target)
    }

    override fun SetPosition(
        trackId: DBusPath,
        position: Long,
    ) {
        val item = published ?: return
        if (trackId.path != "$TRACK_PATH/${item.episodeId}" || position < 0) return
        val target = position / US_PER_MS
        emit(RemoteCommand.SeekTo(target))
        emitSeeked(target)
    }

    override fun OpenUri(uri: String) {
        // The player takes items only through its own queue, never a URI from the bus.
        throw NotSupported("Neutrodyne does not open URIs from MPRIS")
    }

    override fun getPlaybackStatus() = playbackStatus(published)

    override fun getLoopStatus() = "None"

    override fun getRate() = rate

    override fun setRate(rate: Double) {
        require(rate.isFinite()) { "playback rate must be finite" }
        val clamped = rate.coerceIn(MIN_RATE, MAX_RATE)
        this.rate = clamped
        propertiesChanged(mapOf("Rate" to Variant(clamped)))
        emit(RemoteCommand.SetRate(clamped.toFloat()))
    }

    override fun getShuffle() = false

    override fun setShuffle(shuffle: Boolean) = Unit

    override fun getMetadata(): Map<String, Variant<*>> = metadataOf(published)

    override fun getVolume() = volume

    override fun setVolume(volume: Double) {
        this.volume = volume.coerceIn(0.0, 1.0)
    }

    override fun getPosition() = positionMs * US_PER_MS

    override fun getMinimumRate() = MIN_RATE

    override fun getMaximumRate() = MAX_RATE

    override fun getCanGoNext() = published?.hasNext == true

    override fun getCanGoPrevious() = true

    override fun getCanPlay() = true

    override fun getCanPause() = true

    override fun getCanSeek() = durationMs != null

    override fun getCanControl() = true

    private fun playbackStatus(item: NowPlaying?): String =
        when {
            item == null -> "Stopped"
            item.isPlaying -> "Playing"
            else -> "Paused"
        }

    private fun metadataOf(item: NowPlaying?): Map<String, Variant<*>> =
        if (item == null) {
            emptyMap()
        } else {
            buildMap {
                put("mpris:trackid", Variant(DBusPath("$TRACK_PATH/${item.episodeId}")))
                put("xesam:title", Variant(item.title))
                // Collections inside a Variant need the explicit signature; the
                // marshaller cannot infer the element type of listOf(x).
                put("xesam:artist", Variant(listOf(item.podcastTitle), "as"))
                put("xesam:album", Variant(item.podcastTitle))
                item.artwork.url?.let { put("mpris:artUrl", Variant(it)) }
                lengthOf(item)?.let { put("mpris:length", Variant(it * US_PER_MS)) }
            }
        }

    private fun lengthOf(item: NowPlaying?): Long? = item?.position?.durationMs

    private fun rateOf(item: NowPlaying?): Double {
        val speed = item?.position?.speed ?: return DEFAULT_RATE
        return if (speed.isFinite()) speed.toDouble() else DEFAULT_RATE
    }

    private fun emit(command: RemoteCommand) {
        commandChannel.trySend(command)
    }

    private fun emitSeeked(positionMs: Long) {
        runCatching { connection.sendMessage(SeekedSignal(MprisNames.PATH, positionMs * US_PER_MS)) }
    }

    private fun propertiesChanged(changed: Map<String, Variant<*>>) {
        runCatching {
            connection.sendMessage(
                Properties.PropertiesChanged(MprisNames.PATH, MprisNames.PLAYER, changed, emptyList()),
            )
        }
    }

    companion object {
        private const val US_PER_MS = 1_000L
        private const val MIN_RATE = 0.5
        private const val MAX_RATE = 3.0
        private const val DEFAULT_RATE = 1.0
        private const val COMMAND_BUFFER = 64
        private const val TRACK_PATH = "/ch/lkmc/neutrodyne/episode"
        private const val METADATA_SIGNATURE = "a{sv}"

        /** The desktop-entry id 11's table pins; the entry file carries `.desktop`. */
        private const val DESKTOP_ENTRY = "ch.lkmc.neutrodyne"

        /**
         * Exports the session on the session bus, or on the bus at
         * [busAddress] (the private-bus test passes `dbus-run-session`
         * output here). [shell] receives `Raise`/`Quit` on the bus thread.
         */
        fun open(
            busAddress: String? = null,
            shell: MprisShellActions = MprisShellActions.NONE,
        ): LinuxMprisSession {
            val connection =
                when (busAddress) {
                    null -> DBusConnectionBuilder.forSessionBus().build()
                    else -> DBusConnectionBuilder.forAddress(busAddress).build()
                }
            return try {
                val session = LinuxMprisSession(connection, shell)
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

/** `Raise()`/`Quit()` from the bus thread; implementations hop to their own thread. */
interface MprisShellActions {
    fun raise()

    fun quit()

    companion object {
        val NONE =
            object : MprisShellActions {
                override fun raise() = Unit

                override fun quit() = Unit
            }
    }
}
