// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import ch.lkmc.neutrodyne.core.common.DesktopNotification
import ch.lkmc.neutrodyne.core.model.settings.DesktopSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The PR #314 review guard for 11 No tray available's one-time hint: the write marking the
 * hint shown happens only after the banner was posted, and a failing post is captured — a
 * lost hint stays showable instead of being suppressed forever. String resolution stays in
 * the window wrapper; this test drives [showNoTrayHintOnce] with resolved copy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MaybeShowNoTrayHintTest {
    @Test
    fun `the flag is written only after the banner was posted`() =
        runTest {
            val settings = FakeSettingsRepository()
            val enteredPost = CompletableDeferred<Unit>()
            val releasePost = CompletableDeferred<Unit>()

            // Unconfined so the launch runs to the post's suspension point at once, exactly
            // where the ordering under test is observable (AppBusySignalTest's pattern).
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                showNoTrayHintOnce(settings, post = {
                    enteredPost.complete(Unit)
                    releasePost.await()
                }, title = "No tray available", body = "The window was minimised instead.")
            }

            // The post has been entered but not returned: the one-time flag must still be
            // clear, or a failed post would have suppressed the hint forever.
            assertThat(enteredPost.isCompleted).isTrue()
            assertThat(settings.get(DesktopSettingKeys.TRAY_HINT_SHOWN)).isFalse()

            releasePost.complete(Unit)
            assertThat(settings.get(DesktopSettingKeys.TRAY_HINT_SHOWN)).isTrue()
        }

    @Test
    fun `a failing post is captured and leaves the hint showable again`() =
        runTest {
            val settings = FakeSettingsRepository()
            var attempts = 0

            showNoTrayHintOnce(
                settings,
                post = {
                    attempts++
                    throw IllegalStateException("banner sink is down")
                },
                title = "No tray available",
                body = "The window was minimised instead.",
            )
            assertThat(attempts).isEqualTo(1)
            assertThat(settings.get(DesktopSettingKeys.TRAY_HINT_SHOWN)).isFalse()

            // The failure did not mark the hint shown, so a later close can show it.
            showNoTrayHintOnce(settings, post = { attempts++ }, title = "t", body = "b")
            assertThat(attempts).isEqualTo(2)
            assertThat(settings.get(DesktopSettingKeys.TRAY_HINT_SHOWN)).isTrue()
        }

    @Test
    fun `an already shown hint posts nothing`() =
        runTest {
            val settings = FakeSettingsRepository()
            settings.set(DesktopSettingKeys.TRAY_HINT_SHOWN, true)
            var posts = 0

            showNoTrayHintOnce(settings, post = { posts++ }, title = "t", body = "b")

            assertThat(posts).isEqualTo(0)
        }
}
