// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.testing.testNowPlaying
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * MD2 AC1 on Linux: `dbus-run-session` hands the test JVM a private session bus through
 * `DBUS_SESSION_BUS_ADDRESS`, and the exported MPRIS object proves it answers
 * `org.freedesktop.DBus.Properties` queries, signals `PropertiesChanged` for what a publish
 * changed, clamps the published rate to the advertised window, and delivers media-key method
 * calls as [RemoteCommand]s — the same proof desktop environments get, without needing a
 * desktop. The test skips when no session bus address is set: CI wraps the Gradle invocation
 * in `dbus-run-session` on Linux, where MPRIS is the media-key contract. Starting the daemon
 * here would trip the process-spawn rule (01 banned APIs), so the runner owns it.
 */
class LinuxMprisSessionTest {
    @Test(timeout = TEST_TIMEOUT_MS)
    fun privateBusAnswersPropertiesAndDeliversCommands() {
        val address = System.getenv("DBUS_SESSION_BUS_ADDRESS")
        assumeTrue(
            "no session bus: run under dbus-run-session (CI wraps the Linux Gradle steps)",
            address?.startsWith("unix:") == true,
        )
        val shell = RecordingShell()

        LinuxMprisSession.open(address, shell).use { session ->
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
                assertEquals(
                    true,
                    props.Get<Any?>(MprisNames.PLAYER, "CanRaise"),
                )
                assertEquals(
                    true,
                    props.Get<Any?>(MprisNames.PLAYER, "CanQuit"),
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
                client.root().Quit()
                props.Set(
                    MprisNames.PLAYER,
                    "Rate",
                    Variant(SET_RATE),
                )

                await("Toggle") { received.contains(RemoteCommand.Toggle) }
                await("SeekTo") { received.any { it is RemoteCommand.SeekTo } }
                await("Next") { received.contains(RemoteCommand.Next) }
                await("SetRate") { received.contains(RemoteCommand.SetRate(SET_RATE.toFloat())) }
                await("Raise") { shell.raises > 0 }
                await("Quit") { shell.quits > 0 }

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

    @Test(timeout = TEST_TIMEOUT_MS)
    fun aPublishClampsTheRateOntoTheAdvertisedWindow() {
        val address = System.getenv("DBUS_SESSION_BUS_ADDRESS")
        assumeTrue("private session bus required", address?.startsWith("unix:") == true)

        LinuxMprisSession.open(address).use { session ->
            session.publish(
                testNowPlaying().copy(position = testNowPlaying().position.copy(speed = ABOVE_MAX_RATE)),
                SKIP_MS,
                SKIP_MS,
                SPEED_PRESETS,
            )

            DBusConnectionBuilder.forAddress(address).build().use { client ->
                val props = client.properties()
                assertEquals(
                    MAX_RATE,
                    props.Get<Any?>(MprisNames.PLAYER, "Rate") as Double,
                    RATE_EPSILON,
                )
                assertEquals(
                    MIN_RATE,
                    props.Get<Any?>(MprisNames.PLAYER, "MinimumRate") as Double,
                    RATE_EPSILON,
                )
                assertEquals(
                    MAX_RATE,
                    props.Get<Any?>(MprisNames.PLAYER, "MaximumRate") as Double,
                    RATE_EPSILON,
                )
            }
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun aChangedPublishSignalsPropertiesChangedForWhatChanged() {
        val address = System.getenv("DBUS_SESSION_BUS_ADDRESS")
        assumeTrue("private session bus required", address?.startsWith("unix:") == true)

        LinuxMprisSession.open(address).use { session ->
            session.publish(testNowPlaying(), SKIP_MS, SKIP_MS, SPEED_PRESETS)
            DBusConnectionBuilder.forAddress(address).build().use { client ->
                val received = ConcurrentLinkedQueue<Properties.PropertiesChanged>()
                client.addSigHandler(
                    Properties.PropertiesChanged::class.java,
                    DBusSigHandler { received += it },
                )

                // The AddMatch rule must reach the bus before the first signal is due.
                Thread.sleep(MATCH_RULE_SETTLE_MS)
                val paused = testNowPlaying().copy(isPlaying = false)
                session.publish(paused, SKIP_MS, SKIP_MS, SPEED_PRESETS)
                session.publish(paused, SKIP_MS, SKIP_MS, SPEED_PRESETS)

                await("PropertiesChanged") { received.isNotEmpty() }
                // The unchanged second publish adds no further signal.
                val afterUnchanged = received.size
                Thread.sleep(UNCHANGED_WAIT_MS)
                assertEquals(afterUnchanged, received.size)
                val changed = received.single().propertiesChanged
                assertEquals("Paused", changed.getValue("PlaybackStatus").value)
            }
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun aDroppedSeekBroadcastsNoSeekedAndADispatchedOneDoes() {
        val address = System.getenv("DBUS_SESSION_BUS_ADDRESS")
        assumeTrue("private session bus required", address?.startsWith("unix:") == true)

        LinuxMprisSession.open(address).use { session ->
            DBusConnectionBuilder.forAddress(address).build().use { client ->
                // The export scan registered MprisPlayer.Seeked for this member, so the
                // signal deserializes to that class — subscribe to it, not a test twin.
                val seeked = ConcurrentLinkedQueue<MprisPlayer.Seeked>()
                client.addSigHandler(MprisPlayer.Seeked::class.java, DBusSigHandler { seeked += it })
                // The AddMatch rule must reach the bus before the first signal is due.
                Thread.sleep(MATCH_RULE_SETTLE_MS)

                // Nothing is playing: the seek is dropped, so nothing may signal.
                client.player().Seek(SEEK_US)
                Thread.sleep(UNCHANGED_WAIT_MS)
                assertTrue("a dropped seek broadcast Seeked", seeked.isEmpty())

                // A stale track id or a negative position drops SetPosition the same way.
                session.publish(testNowPlaying(), SKIP_MS, SKIP_MS, SPEED_PRESETS)
                session.SetPosition(DBusPath("/not/the/current/track"), 1_000_000)
                session.SetPosition(currentTrack(), -1)
                Thread.sleep(UNCHANGED_WAIT_MS)
                assertTrue("a dropped SetPosition broadcast Seeked", seeked.isEmpty())

                // A seek that reaches the command stream does signal Seeked.
                client.player().Seek(SEEK_US)
                await("Seeked") { seeked.isNotEmpty() }
            }
        }
    }

    @Test
    fun wrongTrackCannotSeekTheCurrentEpisode() =
        runBlocking {
            assumeTrue("private session bus required", System.getenv("DBUS_SESSION_BUS_ADDRESS") != null)
            LinuxMprisSession.open(System.getenv("DBUS_SESSION_BUS_ADDRESS")).use { session ->
                session.publish(testNowPlaying(), SKIP_MS, SKIP_MS, SPEED_PRESETS)
                session.SetPosition(DBusPath("/not/the/current/track"), 1_000_000)
                kotlin.test.assertNull(withTimeoutOrNull(100) { session.commands.first() })
            }
        }

    @Test
    fun aNonFiniteRateCannotEnterTheCommandStream() {
        assumeTrue("private session bus required", System.getenv("DBUS_SESSION_BUS_ADDRESS") != null)
        LinuxMprisSession.open(System.getenv("DBUS_SESSION_BUS_ADDRESS")).use { session ->
            kotlin.test.assertFailsWith<IllegalArgumentException> { session.setRate(Double.NaN) }
            assertTrue(session.getRate().isFinite())
        }
    }

    @Test
    fun openUriAnswersNotSupported() {
        assumeTrue("private session bus required", System.getenv("DBUS_SESSION_BUS_ADDRESS") != null)
        LinuxMprisSession.open(System.getenv("DBUS_SESSION_BUS_ADDRESS")).use { session ->
            session.publish(testNowPlaying(), SKIP_MS, SKIP_MS, SPEED_PRESETS)
            DBusConnectionBuilder
                .forAddress(System.getenv("DBUS_SESSION_BUS_ADDRESS"))
                .build()
                .use { client ->
                    val failure =
                        runCatching { client.player().OpenUri("https://example.test/x.mp3") }.exceptionOrNull()
                    // dbus-java maps the error name onto its errors.NotSupported class.
                    assertTrue(
                        "expected org.freedesktop.DBus.Error.NotSupported, got $failure",
                        failure is org.freedesktop.dbus.errors.NotSupported,
                    )
                }
        }
    }

    private fun currentTrack(): DBusPath = DBusPath("/ch/lkmc/neutrodyne/episode/${testNowPlaying().episodeId}")

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

    private class RecordingShell : MprisShellActions {
        var raises = 0
            private set

        var quits = 0
            private set

        override fun raise() {
            raises++
        }

        override fun quit() {
            quits++
        }
    }

    private companion object {
        const val US_PER_MS = 1_000L
        const val SKIP_MS = 30_000L
        const val SEEK_US = 15_000_000L
        const val SET_RATE = 2.0
        const val MIN_RATE = 0.5
        const val MAX_RATE = 3.0
        const val ABOVE_MAX_RATE = 4.0f
        const val RATE_EPSILON = 0.001
        const val AWAIT_MS = 5_000L
        const val AWAIT_POLL_MS = 10L
        const val MATCH_RULE_SETTLE_MS = 250L
        const val UNCHANGED_WAIT_MS = 250L
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

    fun OpenUri(uri: String)
}

@Suppress("ktlint:standard:function-naming", "FunctionName")
@DBusInterfaceName(MprisNames.ROOT)
internal interface MprisRootClient : DBusInterface {
    fun Raise()

    fun Quit()
}
