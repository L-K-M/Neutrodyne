// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.root

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessage
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * `ShowUserMessages` against a real `SnackbarHostState` (08's serial acknowledge model): each
 * pending message shows once, in order, and appending to the pending list must not cancel and
 * replay the visible snackbar.
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

            // A new pending message must not dismiss and re-show the visible one.
            messages = (messages + UserMessage(1, UiText.Raw("second"))).toImmutableList()
            waitForIdle()
            assertSame(
                visible,
                host.currentSnackbarData,
                "Appending a pending message restarted the visible snackbar",
            )

            // Dismissing the head acknowledges it and shows the queued message next.
            visible.dismiss()
            waitUntil(timeoutMillis = 5_000) {
                host.currentSnackbarData != null && host.currentSnackbarData !== visible
            }
            assertEquals(listOf(0L), acknowledged)
        }

    @Test
    fun actionTapReportsBackAndAcknowledges() =
        runDesktopComposeUiTest(width = 400, height = 300) {
            // 08's Undo catalogue needs the tap back: SnackbarResult.ActionPerformed is reported
            // through onMessageAction by message id before the acknowledgement (01's contract).
            val host = SnackbarHostState()
            var messages by
                mutableStateOf<ImmutableList<UserMessage>>(
                    persistentListOf(UserMessage(0, UiText.Raw("Removed"), UiText.Raw("Undo"))),
                )
            val acknowledged = mutableListOf<Long>()
            val actioned = mutableListOf<Long>()
            setContent {
                CompositionLocalProvider(LocalSnackbarHost provides host) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        ShowUserMessages(
                            messages = messages,
                            onAcknowledgeMessage = { id ->
                                acknowledged += id
                                messages = messages.filterNot { it.id == id }.toImmutableList()
                            },
                            onMessageAction = { actioned += it },
                        )
                        SnackbarHost(host)
                    }
                }
            }

            onNodeWithText("Undo").performClick()
            assertEquals(listOf(0L), actioned)
            assertEquals(listOf(0L), acknowledged)
        }
}
