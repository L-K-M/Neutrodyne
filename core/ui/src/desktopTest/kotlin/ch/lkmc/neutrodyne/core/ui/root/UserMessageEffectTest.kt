// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.root

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/**
 * `ShowUserMessages` drains pending `UserMessage`s serially (01's acknowledgement rule): an
 * appended message must not restart the visible snackbar, and the next head shows once the
 * current one is acknowledged.
 */
@OptIn(ExperimentalTestApi::class)
class UserMessageEffectTest {
    @Test
    fun appendingDoesNotRestartTheVisibleSnackbar() =
        runDesktopComposeUiTest(width = 400, height = 300) {
            val host = SnackbarHostState()
            var messages by
                mutableStateOf<ImmutableList<UserMessage>>(
                    persistentListOf(UserMessage(0, UiText.Raw("first"))),
                )
            val acknowledged = mutableListOf<Long>()
            setContent {
                CompositionLocalProvider(LocalSnackbarHost provides host) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        ShowUserMessages(
                            messages = messages,
                            onAcknowledgeMessage = { id ->
                                acknowledged += id
                                messages = messages.filterNot { it.id == id }.toImmutableList()
                            },
                        )
                        SnackbarHost(host)
                    }
                }
            }

            waitUntil(timeoutMillis = 5_000) { host.currentSnackbarData != null }
            val visible = host.currentSnackbarData ?: error("snackbar never showed")

            messages = messages + UserMessage(1, UiText.Raw("second"))
            // A restart would swap currentSnackbarData for a new instance; give it a frame.
            mainClock.advanceTimeBy(1_000)
            waitForIdle()
            assertEquals(
                visible,
                host.currentSnackbarData,
                "Appending a pending message restarted the visible snackbar",
            )

            // Dismissing the first acknowledges it; the queued head then shows once.
            visible.dismiss()
            waitUntil(timeoutMillis = 5_000) {
                host.currentSnackbarData != null && host.currentSnackbarData !== visible
            }
            assertEquals(listOf(0L), acknowledged)
        }
}
