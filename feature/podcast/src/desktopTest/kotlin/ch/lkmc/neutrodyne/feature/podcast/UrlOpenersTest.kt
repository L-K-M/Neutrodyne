// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.podcast

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import kotlin.test.Test
import kotlin.test.assertEquals

/** `rememberNoHandlerFallback`'s "No app can open this link" + "Copy link" snackbar. */
@OptIn(ExperimentalTestApi::class)
class UrlOpenersTest {
    @Test
    fun noHandlerSnackbarShowsLong() =
        runComposeUiTest {
            // 08's duration rule: a snackbar with an action shows `Long` (10 s). Material3
            // defaults an actionLabel snackbar to `Indefinite`, which would hold the one
            // serial host until the user dismisses it.
            val host = SnackbarHostState()
            setContent {
                CompositionLocalProvider(LocalSnackbarHost provides host) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        val fallback = rememberNoHandlerFallback()
                        LaunchedEffect(Unit) { fallback("https://example.com/episode") }
                        SnackbarHost(host)
                    }
                }
            }

            waitUntil(timeoutMillis = 5_000) { host.currentSnackbarData != null }
            assertEquals(
                SnackbarDuration.Long,
                host.currentSnackbarData?.visuals?.duration,
                "The no-handler snackbar carries the Copy link action and must show Long",
            )
        }
}
