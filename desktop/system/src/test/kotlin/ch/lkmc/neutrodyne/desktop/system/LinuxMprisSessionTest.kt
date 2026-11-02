// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.testing.testNowPlaying
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
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * MD0 AC4 on Linux: `dbus-run-session` hands the test JVM a private session
 * bus through `DBUS_SESSION_BUS_ADDRESS`, and the exported MPRIS object proves
 * it answers `org.freedesktop.DBus.Properties` queries and delivers media-key
 * method calls as [RemoteCommand]s — the same proof desktop environments get,
 * without needing a desktop. The test skips when no session bus address is
 * set: CI wraps the Gradle invocation in `dbus-run-session` on Linux, where
 * MPRIS is the media-key contract. Starting the daemon here would trip the
 * process-spawn rule (01 banned APIs), so the runner owns it.
 */
class LinuxMprisSessionTest {
    @Test(timeout = TEST_TIMEOUT_MS)
    fun privateBusAnswersPropertiesAndDeliversCommands() {
        val address = System.getenv("DBUS_SESSION_BUS_ADDRESS")
        assumeTrue(
            "no session bus: run under dbus-run-session (CI wraps the Linux Gradle steps)",
            address?.startsWith("unix:") == true,
        )

        LinuxMprisSession.open(address).use { session ->
            val received = ConcurrentLinkedQueue<RemoteCommand>()
            Thread({
                runBlocking { session.commands.collect { received += it } }
            }, "mpris-collector")
                .apply {
                    isDaemon = true
                    start()
                }

            session.publish(testNowPlaying(), SKIP_MS, SKIP_MS, SPEED_PRESETS)

            DBusConnectionBuilder.forAddress(address).build().use { client ->
                val props = client.properties()

                // Client-side Get unwraps Variants recursively, so the
                // a{sv} map's values come back as their raw types.
                assertEquals(
                    "Playing",
                    props.Get<Any?>(MprisNames.PLAYER, "PlaybackStatus"),
                )
                assertEquals(
                    "Neutrodyne",
                    props.Get<Any?>(MprisNames.ROOT, "Identity"),
                )
                assertEquals(
                    testNowPlaying().position.positionMs * US_PER_MS,
                    props.Get<Any?>(MprisNames.PLAYER, "Position"),
                )

                @Suppress("UNCHECKED_CAST")
                val metadata =
                    props.Get<Any?>(MprisNames.PLAYER, "Metadata")
                        as Map<String, Any?>
                assertEquals(testNowPlaying().title, metadata["xesam:title"])

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
                    props.Get<Any?>(MprisNames.PLAYER, "Rate") as Double,
                    RATE_EPSILON,
                )
            }
        }
    }

    private fun DBusConnection.properties(): Properties =
        getRemoteObject(MprisNames.BUS, MprisNames.PATH, Properties::class.java)

    private fun DBusConnection.player(): MprisPlayerClient =
        getRemoteObject(MprisNames.BUS, MprisNames.PATH, MprisPlayerClient::class.java)

    private fun DBusConnection.root(): MprisRootClient =
        getRemoteObject(MprisNames.BUS, MprisNames.PATH, MprisRootClient::class.java)

    private fun await(
        what: String,
        until: () -> Boolean,
    ) {
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
@Suppress("ktlint:standard:function-naming", "FunctionName")
@DBusInterfaceName(MprisNames.PLAYER)
internal interface MprisPlayerClient : DBusInterface {
    fun PlayPause()

    fun Seek(offset: Long)

    fun Next()
}

@Suppress("ktlint:standard:function-naming", "FunctionName")
@DBusInterfaceName(MprisNames.ROOT)
internal interface MprisRootClient : DBusInterface {
    fun Raise()
}
