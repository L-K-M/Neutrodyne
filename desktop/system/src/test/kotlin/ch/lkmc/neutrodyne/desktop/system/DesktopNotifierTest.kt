// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.common.DesktopNotification
import ch.lkmc.neutrodyne.core.common.DesktopNotifier
import ch.lkmc.neutrodyne.core.common.NotificationKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 11 Notifications' Linux back end against a fake notification server on the private
 * session bus of `dbus-run-session` (CI wraps the Linux Gradle steps; the daemon belongs
 * to the runner, 01's process rule). Proves 11's table row: one `Notify` per event with
 * app name, icon and the `desktop-entry` hint, `replaces_id` reuse per notification id,
 * the `default` action only when the server advertises `actions`, `ActionInvoked`
 * delivering the click, `CloseNotification` on cancel — and, with no server on the bus,
 * the post reporting undelivered so [OsDesktopNotifier] shows the banner fallback
 * (11's OS-integration failure modes table).
 */
class DesktopNotifierTest {
    @Test(timeout = TEST_TIMEOUT_MS)
    fun oneNotificationPerEventWithThePinnedIdentity() {
        val address = busAddress()
        runBlocking {
            FakeServer(address, capabilities = listOf("actions", "body")).use { server ->
                LinuxDbusNotifications(address).use { channel ->
                    assertThat(
                        channel.post(notification(id = "app-update", route = "neutrodyne://open/settings/updates")),
                    ).isTrue()
                    assertThat(channel.post(notification(id = "download-failed", route = null))).isTrue()

                    await("two Notify calls") { server.calls.size >= 2 }
                    server.calls.forEach { call ->
                        assertThat(call.appName).isEqualTo("Neutrodyne")
                        assertThat(call.appIcon).isEqualTo("neutrodyne")
                        assertThat(call.hints.getValue("desktop-entry").value).isEqualTo("ch.lkmc.neutrodyne")
                        assertThat(call.actions).containsExactly("default", "Activate").inOrder()
                        assertThat(call.expireTimeout).isEqualTo(-1)
                        assertThat(call.replacesId).isEqualTo(UInt32(0L))
                    }
                    assertThat(server.calls.map { it.summary }).containsExactly("Update", "Download failed").inOrder()
                }
            }
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun aSecondPostUnderTheSameIdReplacesTheServerNotification() {
        val address = busAddress()
        runBlocking {
            FakeServer(address).use { server ->
                LinuxDbusNotifications(address).use { channel ->
                    channel.post(notification(id = "new-episodes", body = "first"))
                    await("first Notify") { server.calls.size == 1 }

                    channel.post(notification(id = "new-episodes", body = "second"))

                    await("second Notify") { server.calls.size == 2 }
                    assertThat(server.calls.toList()[1].replacesId).isEqualTo(server.calls.toList()[0].returnedId)
                }
            }
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun aServerWithoutActionsCapabilityGetsNoActionList() {
        val address = busAddress()
        runBlocking {
            FakeServer(address, capabilities = listOf("body")).use { server ->
                LinuxDbusNotifications(address).use { channel ->
                    channel.post(notification(id = "app-update"))

                    await("Notify") { server.calls.size == 1 }
                    assertThat(server.calls.single().actions).isEmpty()
                }
            }
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun aDefaultActionClickEmitsTheNotificationWithItsRoute() {
        val address = busAddress()
        runBlocking {
            FakeServer(address).use { server ->
                LinuxDbusNotifications(address).use { channel ->
                    val clicked = arrayOfNulls<DesktopNotification>(1)
                    val collector =
                        Thread(
                            {
                                runBlocking { clicked[0] = channel.clicks.first() }
                            },
                            "notify-click-collector",
                        ).apply {
                            isDaemon = true
                            start()
                        }

                    channel.post(notification(id = "new-episodes", route = "neutrodyne://open/group/uuid-1"))
                    await("Notify") { server.calls.size == 1 }
                    server.invokeDefault(server.calls.single().returnedId)

                    collector.join(COLLECTOR_JOIN_MS)
                    assertThat(clicked[0]).isNotNull()
                    assertThat(clicked[0]!!.route).isEqualTo("neutrodyne://open/group/uuid-1")
                }
            }
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun cancelClosesTheServerNotificationAndDropsTheMapping() {
        val address = busAddress()
        runBlocking {
            FakeServer(address).use { server ->
                LinuxDbusNotifications(address).use { channel ->
                    channel.post(notification(id = "app-update"))
                    await("Notify") { server.calls.size == 1 }

                    channel.cancel("app-update")

                    await("CloseNotification") { server.closed.isNotEmpty() }
                    assertThat(server.closed).containsExactly(server.calls.single().returnedId)

                    // The mapping is gone: a repost of the id replaces nothing.
                    channel.post(notification(id = "app-update"))
                    await("repost") { server.calls.size == 2 }
                    assertThat(server.calls.toList()[1].replacesId).isEqualTo(UInt32(0L))
                }
            }
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun aClosedNotificationIsNotReplacedAfterwards() {
        val address = busAddress()
        runBlocking {
            FakeServer(address).use { server ->
                LinuxDbusNotifications(address).use { channel ->
                    val clicks = ClickProbe(channel)
                    channel.post(notification(id = "new-episodes"))
                    await("Notify") { server.calls.size == 1 }
                    val id = server.calls.single().returnedId

                    // The click is delivered while the notification is live; it is the
                    // observable proof that the id is still mapped.
                    clicks.expectClick(server, id)
                    server.close(id)
                    // `NotificationClosed` is asynchronous: poll until the click stops
                    // arriving, which proves the handler dropped the mapping. A broken
                    // handler keeps the notification clickable forever and times out.
                    clicks.awaitNotClickable(server, id)

                    channel.post(notification(id = "new-episodes"))
                    await("repost") { server.calls.size == 2 }
                    assertThat(server.calls.toList()[1].replacesId).isEqualTo(UInt32(0L))
                }
            }
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun aBusWithoutAServerFallsBackToTheBanners() {
        val address = busAddress()
        runBlocking {
            // No fake server exported: the notification service does not exist on this bus.
            LinuxDbusNotifications(address).use { channel ->
                val banners = RecordingNotifier()
                val notifier = OsDesktopNotifier(channel, banners)

                notifier.post(notification(id = "app-update"))

                assertThat(banners.posts.map { it.id }).containsExactly("app-update")
            }
        }
    }

    private fun busAddress(): String {
        val address = System.getenv("DBUS_SESSION_BUS_ADDRESS")
        assumeTrue(
            "no session bus: run under dbus-run-session (CI wraps the Linux Gradle steps)",
            address?.startsWith("unix:") == true,
        )
        return address
    }

    private fun notification(
        id: String,
        body: String = "body",
        route: String? = null,
    ) = DesktopNotification(
        id = id,
        kind = NotificationKind.APP_UPDATE,
        title = when (id) {
            "download-failed" -> "Download failed"
            "new-episodes" -> "New episodes"
            else -> "Update"
        },
        body = body,
        route = route,
    )

    private fun await(
        what: String,
        until: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + AWAIT_MS * 1_000_000
        while (!until() && System.nanoTime() < deadline) Thread.sleep(AWAIT_POLL_MS)
        assertThat(until()).isTrue()
    }

    /**
     * Collects the channel's clicks so `ActionInvoked` becomes the observable state of the
     * id mapping: clickable = still mapped, not clickable = `NotificationClosed` handled.
     */
    private class ClickProbe(
        channel: LinuxDbusNotifications,
    ) {
        private val clicks = ConcurrentLinkedQueue<DesktopNotification>()

        init {
            Thread(
                {
                    runBlocking {
                        channel.clicks.collect { clicks += it }
                    }
                },
                "notify-click-probe",
            ).apply {
                isDaemon = true
                start()
            }
        }

        fun expectClick(
            server: FakeServer,
            id: UInt32,
        ) {
            server.invokeDefault(id)
            val deadline = System.nanoTime() + AWAIT_MS * 1_000_000
            while (clicks.isEmpty() && System.nanoTime() < deadline) Thread.sleep(AWAIT_POLL_MS)
            assertThat(clicks.isNotEmpty()).isTrue()
        }

        fun awaitNotClickable(
            server: FakeServer,
            id: UInt32,
        ) {
            val deadline = System.nanoTime() + AWAIT_MS * 1_000_000
            while (System.nanoTime() < deadline) {
                val before = clicks.size
                server.invokeDefault(id)
                // Far above the bus latency: a click that does not arrive in this window
                // means the handler has dropped the mapping.
                Thread.sleep(NOT_CLICKABLE_SETTLE_MS)
                if (clicks.size == before) return
            }
            assertThat(false).isTrue() // the mapping never cleared within the budget
        }
    }

    private class NotifyCall(
        val appName: String,
        val replacesId: UInt32,
        val appIcon: String,
        val summary: String,
        val body: String,
        val actions: List<String>,
        val hints: Map<String, Variant<*>>,
        val expireTimeout: Int,
        val returnedId: UInt32,
    )

    /**
     * The fake server owns `org.freedesktop.Notifications`; `invokeDefault` plays the
     * server's `ActionInvoked` for a body click, `close` its `NotificationClosed`.
     */
    private class FakeServer(
        private val address: String,
        private val capabilities: List<String> = listOf("actions", "body", "body-markup"),
    ) : AutoCloseable {
        private val bus: DBusConnection = DBusConnectionBuilder.forAddress(address).build()
        private val nextId = java.util.concurrent.atomic.AtomicInteger(1)

        val calls = ConcurrentLinkedQueue<NotifyCall>()
        val closed = ConcurrentLinkedQueue<UInt32>()

        private val api =
            object : NotificationsApi {
                override fun getObjectPath() = NotificationsNames.PATH

                override fun Notify(
                    appName: String,
                    replacesId: UInt32,
                    appIcon: String,
                    summary: String,
                    body: String,
                    actions: List<String>,
                    hints: Map<String, Variant<*>>,
                    expireTimeout: Int,
                ): UInt32 {
                    val id = UInt32(nextId.getAndIncrement().toLong())
                    calls +=
                        NotifyCall(
                            appName,
                            replacesId,
                            appIcon,
                            summary,
                            body,
                            actions,
                            hints,
                            expireTimeout,
                            id,
                        )
                    return id
                }

                override fun CloseNotification(id: UInt32) {
                    closed += id
                }

                override fun GetCapabilities(): List<String> = capabilities
            }

        init {
            bus.exportObject(NotificationsNames.PATH, api)
            bus.requestBusName(NotificationsNames.BUS)
        }

        fun invokeDefault(id: UInt32) {
            runCatching { bus.sendMessage(NotificationsApi.ActionInvoked(NotificationsNames.PATH, id, "default")) }
        }

        fun close(id: UInt32) {
            runCatching { bus.sendMessage(NotificationsApi.NotificationClosed(NotificationsNames.PATH, id, UInt32(1L))) }
        }

        override fun close() = bus.close()
    }

    private class RecordingNotifier : DesktopNotifier {
        val posts = mutableListOf<DesktopNotification>()

        override suspend fun post(n: DesktopNotification) {
            posts += n
        }

        override fun cancel(id: String) = Unit
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 60_000L
        const val AWAIT_MS = 5_000L
        const val AWAIT_POLL_MS = 10L
        const val COLLECTOR_JOIN_MS = 10_000L
        const val NOT_CLICKABLE_SETTLE_MS = 300L
    }
}
