// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.testing.testNowPlaying
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.runBlocking
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * MD0 AC4 on Linux: a private `dbus-daemon --session` bus proves the exported
 * MPRIS object answers `org.freedesktop.DBus.Properties` queries and that
 * media-key method calls arrive as [RemoteCommand]s — the same proof desktop
 * environments get, without needing a desktop. Skips where `dbus-daemon` is
 * not installed (the Linux desktop-matrix runner installs `dbus-x11`).
 */
class LinuxMprisSessionTest {
    @Test(timeout = TEST_TIMEOUT_MS)
    fun privateBusAnswersPropertiesAndDeliversCommands() {
        val daemon = findDbusDaemon() ?: run {
            assumeTrue("dbus-daemon is not installed on this host", false)
            return
        }
        val bus = ProcessBuilder(daemon, "--session", "--nofork", "--print-address=1")
            .redirectErrorStream(true)
            .start()
        try {
            val address = bus.inputStream.bufferedReader().readLine()
            assumeTrue("private session bus failed to start", address?.startsWith("unix:") == true)

            LinuxMprisSession.open(address).use { session ->
                val received = ConcurrentLinkedQueue<RemoteCommand>()
                Thread({
                        runBlocking { session.commands.collect { received += it } }
                    }, "mpris-collector")
                    .apply { isDaemon = true; start() }

                session.publish(testNowPlaying(), SKIP_MS, SKIP_MS, SPEED_PRESETS)

                DBusConnectionBuilder.forAddress(address).build().use { client ->
                    val props = client.properties()

                    assertEquals(
                        "Playing",
                        props.Get<Variant<*>>(MprisNames.PLAYER, "PlaybackStatus").value,
                    )
                    assertEquals(
                        "Neutrodyne",
                        props.Get<Variant<*>>(MprisNames.ROOT, "Identity").value,
                    )
                    assertEquals(
                        testNowPlaying().position.positionMs * US_PER_MS,
                        props.Get<Variant<*>>(MprisNames.PLAYER, "Position").value as Long,
                    )

                    @Suppress("UNCHECKED_CAST")
                    val metadata =
                        props.Get<Variant<*>>(MprisNames.PLAYER, "Metadata").value
                            as Map<String, Variant<*>>
                    assertEquals(testNowPlaying().title, metadata["xesam:title"]?.value)

                    val player = client.player()
                    player.PlayPause()
                    player.Seek(SEEK_US)
                    player.Next()
                    client.root().Raise()
                    props.Set(
                        MprisNames.PLAYER,
                        "Rate",
                        Variant(SET_RATE),
                    )

                    await("Toggle") { received.contains(RemoteCommand.Toggle) }
                    await("SeekTo") { received.any { it is RemoteCommand.SeekTo } }
                    await("Next") { received.contains(RemoteCommand.Next) }
                    await("SetRate") { received.contains(RemoteCommand.SetRate(SET_RATE.toFloat())) }

                    val seek = received.filterIsInstance<RemoteCommand.SeekTo>().last()
                    assertEquals(testNowPlaying().position.positionMs + SEEK_US / US_PER_MS, seek.positionMs)
                    assertEquals(
                        SET_RATE,
                        props.Get<Variant<*>>(MprisNames.PLAYER, "Rate").value as Double,
                        RATE_EPSILON,
                    )
                }
            }
        } finally {
            bus.destroyForcibly()
        }
    }

    private fun DBusConnection.properties(): Properties =
        getRemoteObject(MprisNames.BUS, MprisNames.PATH, Properties::class.java)

    private fun DBusConnection.player(): MprisPlayerClient =
        getRemoteObject(MprisNames.BUS, MprisNames.PATH, MprisPlayerClient::class.java)

    private fun DBusConnection.root(): MprisRootClient =
        getRemoteObject(MprisNames.BUS, MprisNames.PATH, MprisRootClient::class.java)

    private fun findDbusDaemon(): String? =
        listOfNotNull(
                System.getenv("DBUS_DAEMON"),
                "dbus-daemon",
                "/usr/bin/dbus-daemon",
                "/usr/local/bin/dbus-daemon",
            )
            .firstOrNull { candidate ->
                runCatching {
                        ProcessBuilder(candidate, "--version")
                            .redirectErrorStream(true)
                            .start()
                            .waitFor()
                    }
                    .isSuccess
            }

    private fun await(what: String, until: () -> Boolean) {
        val deadline = System.nanoTime() + AWAIT_MS * 1_000_000
        while (!until() && System.nanoTime() < deadline) Thread.sleep(AWAIT_POLL_MS)
        assertTrue("$what never arrived on the commands flow", until())
    }

    private companion object {
        const val US_PER_MS = 1_000L
        const val SKIP_MS = 30_000L
        const val SEEK_US = 15_000_000L
        const val SET_RATE = 2.0
        const val RATE_EPSILON = 0.001
        const val AWAIT_MS = 5_000L
        const val AWAIT_POLL_MS = 10L
        const val TEST_TIMEOUT_MS = 60_000L
        val SPEED_PRESETS = listOf(0.5f, 1f, 2f, 3f)
    }
}

// Client-side proxies need the bus interface names; dbus-java derives them
// from @DBusInterfaceName, not from the Kotlin type name.
@DBusInterfaceName(MprisNames.PLAYER)
internal interface MprisPlayerClient : DBusInterface {
    fun PlayPause()

    fun Seek(offset: Long)

    fun Next()
}

@DBusInterfaceName(MprisNames.ROOT)
internal interface MprisRootClient : DBusInterface {
    fun Raise()
}
