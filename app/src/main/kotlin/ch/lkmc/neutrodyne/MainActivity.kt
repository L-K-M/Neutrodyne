// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.rememberAndroidPlatformActions
import ch.lkmc.neutrodyne.core.ui.root.NeutrodyneRoot
import ch.lkmc.neutrodyne.core.ui.root.RootActions
import ch.lkmc.neutrodyne.core.ui.root.RootSlots
import ch.lkmc.neutrodyne.core.ui.root.RootUiState
import kotlinx.coroutines.flow.emptyFlow

/**
 * The one launcher activity (01 Application element and components): AppCompat for per-app language, the system
 * splash, edge-to-edge, then the shared [NeutrodyneRoot]. Before M1 there is no database, so the start-up gate is
 * ready at once (01 Splash and start-up gate, step 5); playback, notices and sync wire their state in later
 * milestones.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val installers = (application as NeutrodyneApplication).graph.entryInstallers
        setContent {
            val systemUi = SystemUiState.DEFAULT.copy(dark = isSystemInDarkTheme())
            CompositionLocalProvider(LocalPlatformActions provides rememberAndroidPlatformActions()) {
                NeutrodyneRoot(
                    state = RootUiState.READY,
                    actions = M0_ROOT_ACTIONS,
                    slots = M0_ROOT_SLOTS,
                    installers = installers,
                    systemUi = systemUi,
                    platform = BuildInfo.Platform.ANDROID,
                )
            }
        }
    }

    private companion object {
        /** Nothing to retry, dismiss or play before M1/M4: the root's callbacks are inert. */
        val M0_ROOT_ACTIONS = RootActions(
            retryStartup = {},
            dismissNotice = {},
            continueHere = {},
            dismissRemoteSession = {},
            playbackKey = { false },
        )

        /** No player (M4) and no user messages yet. */
        val M0_ROOT_SLOTS = RootSlots(player = {}, userMessages = emptyFlow())
    }
}
