// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.notifications

import ch.lkmc.neutrodyne.core.common.DesktopNotification
import ch.lkmc.neutrodyne.core.common.NotificationKind
import ch.lkmc.neutrodyne.core.navigation.ImportKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.Route
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessage
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_open
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The in-app banner fallback (`DesktopNotifier` before MD2's `OsDesktopNotifier`, 11
 * Notifications): posts become `RootSlots.userMessages` — serial, bounded, `replaces_id` on a
 * repost, retractable while pending — and an "Open" tap maps through `IntentRouter` to the
 * shell's route dispatch. No OS-centre delivery is claimed here.
 */
class DesktopBannerNotifierTest {
    private val notifier = DesktopBannerNotifier()

    @Test
    fun aPostShowsItsTitleBodyAndOpenAction() =
        runTest {
            notifier.post(notification(route = "neutrodyne://open/import/7"))

            val message = notifier.userMessages.first()
            assertThat(message.text).isEqualTo(UiText.Raw("Title — Body"))
            assertThat(message.action).isEqualTo(UiText.Res(Res.string.action_open))
        }

    @Test
    fun aRoutelessPostShowsNoAction() =
        runTest {
            notifier.post(notification(route = null))

            assertThat(notifier.userMessages.first().action).isNull()
        }

    @Test
    fun aBlankBodyPostsTheTitleAlone() =
        runTest {
            notifier.post(notification(body = " "))

            assertThat(notifier.userMessages.first().text).isEqualTo(UiText.Raw("Title"))
        }

    @Test
    fun aRepostUnderTheSameIdReplacesThePendingEntry() =
        runTest {
            notifier.post(notification(id = "a", title = "first", body = ""))
            notifier.post(notification(id = "b", title = "other", body = ""))
            notifier.post(notification(id = "a", title = "second", body = ""))

            val shown =
                notifier.userMessages
                    .take(2)
                    .toList()
                    .map { (it.text as UiText.Raw).value }
            assertThat(shown).containsExactly("other", "second").inOrder()
        }

    @Test
    fun thePendingQueueIsBounded() =
        runTest {
            repeat(10) { i -> notifier.post(notification(id = "n$i", title = "post-$i", body = "")) }

            // Eight pending survive; the oldest two were dropped.
            assertThat((notifier.userMessages.first().text as UiText.Raw).value).isEqualTo("post-2")
        }

    @Test
    fun cancelRetractsANotYetShownNotification() =
        runTest {
            notifier.post(notification(id = "a", title = "gone", body = ""))
            notifier.cancel("a")
            notifier.post(notification(id = "b", title = "kept", body = ""))

            assertThat((notifier.userMessages.first().text as UiText.Raw).value).isEqualTo("kept")
        }

    @Test
    fun pendingQueuePressureKeepsTheVisibleBannersOpenAction() =
        assertShownActionSurvives {
            repeat(10) { notifier.post(notification(id = "new-$it")) }
        }

    @Test
    fun cancellingAPendingIdDoesNotBreakItsAlreadyVisibleOpenAction() =
        assertShownActionSurvives { notifier.cancel("n-1") }

    @Test
    fun replacingAnIdKeepsTheVisibleBannersOriginalRoute() =
        assertShownActionSurvives {
            notifier.post(notification(route = "neutrodyne://open/import/8"))
        }

    @Test
    fun anOpenTapRoutesThroughIntentRouterToTheShellDispatch() =
        runTest {
            notifier.post(notification(route = "neutrodyne://open/import/7"))
            val message = notifier.userMessages.first()
            val dispatched = mutableListOf<Route>()
            notifier.routeDispatch = {
                dispatched += it
                true
            }

            notifier.messageAction(message.id)

            assertThat(dispatched)
                .containsExactly(Route.Navigate(LibraryKey, listOf(ImportKey("7"))))
        }

    @Test
    fun aTapWithoutAnAttachedDispatchIsDroppedQuietly() =
        runTest {
            notifier.post(notification(route = "neutrodyne://open/import/7"))
            val message = notifier.userMessages.first()

            // Default dispatch answers "unhandled" — the tap logs and moves on.
            notifier.messageAction(message.id)
        }

    private fun notification(
        id: String = "n-1",
        title: String = "Title",
        body: String = "Body",
        route: String? = null,
    ) = DesktopNotification(
        id = id,
        kind = NotificationKind.IMPORT_BACKUP,
        title = title,
        body = body,
        route = route,
    )

    private fun assertShownActionSurvives(mutatePending: suspend () -> Unit) =
        runTest {
            notifier.post(notification(route = "neutrodyne://open/import/7"))
            val shown = CompletableDeferred<UserMessage>()
            val collector =
                launch {
                    notifier.userMessages.collect {
                        shown.complete(it)
                        awaitCancellation()
                    }
                }
            try {
                val message = shown.await()
                val dispatched = mutableListOf<Route>()
                notifier.routeDispatch = {
                    dispatched += it
                    true
                }

                mutatePending()
                notifier.messageAction(message.id)

                assertThat(dispatched).containsExactly(Route.Navigate(LibraryKey, listOf(ImportKey("7"))))
            } finally {
                collector.cancelAndJoin()
            }
        }
}
