// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.common.DesktopNotification
import ch.lkmc.neutrodyne.core.common.DesktopNotifier
import ch.lkmc.neutrodyne.core.common.NotificationKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The composite's routing rules (11 Notifications): the OS channel delivers or the banner
 * fallback shows the same event; [OsDesktopNotifier.cancel] retracts on both sides; OS
 * clicks pass straight through. The channels themselves have their own tests — the Linux
 * back end against a private bus, the AWT one under headless AWT.
 */
class OsDesktopNotifierTest {
    @Test
    fun aDeliveredPostStaysOffTheBanners() =
        runTest {
            val channel = FakeChannel()
            val banners = RecordingNotifier()
            val notifier = OsDesktopNotifier(channel, banners)

            notifier.post(notification("download-failed"))

            assertThat(channel.posts.map { it.id }).containsExactly("download-failed")
            assertThat(banners.posts).isEmpty()
        }

    @Test
    fun anUndeliveredPostFallsBackToTheBanners() =
        runTest {
            val channel = FakeChannel(deliver = false)
            val banners = RecordingNotifier()
            val notifier = OsDesktopNotifier(channel, banners)

            notifier.post(notification("download-failed"))

            assertThat(banners.posts.map { it.id }).containsExactly("download-failed")
        }

    @Test
    fun cancelRetractsOnBothSides() =
        runTest {
            val channel = FakeChannel()
            val banners = RecordingNotifier()
            val notifier = OsDesktopNotifier(channel, banners)

            notifier.cancel("app-update")

            assertThat(channel.cancels).containsExactly("app-update")
            assertThat(banners.cancels).containsExactly("app-update")
        }

    @Test
    fun clicksCarryTheWholeNotification() =
        runTest {
            val channel = FakeChannel()
            val notifier = OsDesktopNotifier(channel, RecordingNotifier())
            val received = mutableListOf<DesktopNotification>()
            // Unconfined inside the background scope: subscribes before the click, and the
            // endless collector is cancelled when the test body finishes.
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { notifier.clicks.toList(received) }

            channel.click(notification("app-update", route = "neutrodyne://open/settings/updates"))

            assertThat(received.single().route).isEqualTo("neutrodyne://open/settings/updates")
        }

    @Test
    fun closeClosesTheChannel() {
        val channel = FakeChannel()
        val notifier = OsDesktopNotifier(channel, RecordingNotifier())

        notifier.close()

        assertThat(channel.closed).isTrue()
    }

    @Test
    fun theFactoryBindsTheInAppBannerChannelOnMacos() {
        assertThat(OsDesktopNotifier.create(null, RecordingNotifier()).description).isEqualTo("in-app banners")
    }

    private fun notification(
        id: String,
        route: String? = "neutrodyne://open/downloads",
    ) = DesktopNotification(
        id = id,
        kind = NotificationKind.DOWNLOAD_FAILED,
        title = "title",
        body = "body",
        route = route,
    )

    private class FakeChannel(deliver: Boolean = true) : OsNotificationChannel {
        val posts = mutableListOf<DesktopNotification>()
        val cancels = mutableListOf<String>()
        var closed = false
        private val clickFlow = MutableSharedFlow<DesktopNotification>(replay = 1)

        override val description = "fake"

        override val clicks: Flow<DesktopNotification> = clickFlow

        private val delivers = deliver

        override suspend fun post(n: DesktopNotification): Boolean {
            posts += n
            return delivers
        }

        override fun cancel(id: String) {
            cancels += id
        }

        fun click(n: DesktopNotification) {
            clickFlow.tryEmit(n)
        }

        override fun close() {
            closed = true
        }
    }

    private class RecordingNotifier : DesktopNotifier {
        val posts = mutableListOf<DesktopNotification>()
        val cancels = mutableListOf<String>()

        override suspend fun post(n: DesktopNotification) {
            posts += n
        }

        override fun cancel(id: String) {
            cancels += id
        }
    }
}
