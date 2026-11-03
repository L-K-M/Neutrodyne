// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusBoundProperty
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusProperty
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant

internal object MprisNames {
    const val BUS = "org.mpris.MediaPlayer2.neutrodyne"
    const val PATH = "/org/mpris/MediaPlayer2"
    const val ROOT = "org.mpris.MediaPlayer2"
    const val PLAYER = "org.mpris.MediaPlayer2.Player"
}

/**
 * `org.mpris.MediaPlayer2` as dbus-java sees it. Method and member names are
 * the bus names verbatim — D-Bus members are capitalized, so these Java
 * methods intentionally are too.
 */
@Suppress("ktlint:standard:function-naming", "FunctionName")
@DBusInterfaceName(MprisNames.ROOT)
internal interface MprisRoot : DBusInterface {
    fun Raise()

    fun Quit()

    @DBusBoundProperty
    fun isFullscreen(): Boolean

    @DBusBoundProperty(access = DBusProperty.Access.WRITE)
    fun setFullscreen(fullscreen: Boolean)

    @DBusBoundProperty
    fun getCanSetFullscreen(): Boolean

    @DBusBoundProperty
    fun getCanRaise(): Boolean

    @DBusBoundProperty
    fun getCanQuit(): Boolean

    @DBusBoundProperty
    fun getHasTrackList(): Boolean

    @DBusBoundProperty
    fun getIdentity(): String

    @DBusBoundProperty
    fun getDesktopEntry(): String

    @DBusBoundProperty
    fun getSupportedUriSchemes(): List<String>

    @DBusBoundProperty
    fun getSupportedMimeTypes(): List<String>
}

/** `org.mpris.MediaPlayer2.Player` — the transport interface media keys hit. */
@Suppress("ktlint:standard:function-naming", "FunctionName")
@DBusInterfaceName(MprisNames.PLAYER)
internal interface MprisPlayer : DBusInterface {
    fun Next()

    fun Previous()

    fun Pause()

    fun PlayPause()

    fun Stop()

    fun Play()

    fun Seek(offset: Long)

    fun SetPosition(
        trackId: DBusPath,
        position: Long,
    )

    fun OpenUri(uri: String)

    @DBusBoundProperty
    fun getPlaybackStatus(): String

    @DBusBoundProperty
    fun getLoopStatus(): String

    @DBusBoundProperty
    fun getRate(): Double

    @DBusBoundProperty(access = DBusProperty.Access.WRITE)
    fun setRate(rate: Double)

    @DBusBoundProperty
    fun getShuffle(): Boolean

    @DBusBoundProperty(access = DBusProperty.Access.WRITE)
    fun setShuffle(shuffle: Boolean)

    @DBusBoundProperty
    fun getMetadata(): Map<String, Variant<*>>

    @DBusBoundProperty
    fun getVolume(): Double

    @DBusBoundProperty(access = DBusProperty.Access.WRITE)
    fun setVolume(volume: Double)

    @DBusBoundProperty
    fun getPosition(): Long

    @DBusBoundProperty
    fun getMinimumRate(): Double

    @DBusBoundProperty
    fun getMaximumRate(): Double

    @DBusBoundProperty
    fun getCanGoNext(): Boolean

    @DBusBoundProperty
    fun getCanGoPrevious(): Boolean

    @DBusBoundProperty
    fun getCanPlay(): Boolean

    @DBusBoundProperty
    fun getCanPause(): Boolean

    @DBusBoundProperty
    fun getCanSeek(): Boolean

    @DBusBoundProperty
    fun getCanControl(): Boolean
}

/** MPRIS `Seeked` signal; spec requires it after every Seek/SetPosition. */
internal class SeekedSignal(
    path: String,
    positionUs: Long,
) : DBusSignal(path, MprisNames.PLAYER, "Seeked", positionUs)
