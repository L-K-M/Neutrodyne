// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.rememberAndroidPlatformActions
import ch.lkmc.neutrodyne.core.ui.root.NeutrodyneRoot
import ch.lkmc.neutrodyne.core.ui.root.RootActions
import ch.lkmc.neutrodyne.core.ui.root.RootSlots
import ch.lkmc.neutrodyne.core.ui.root.RootUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlin.time.Duration.Companion.seconds

/**
 * The one launcher activity (01 Application element and components): AppCompat for per-app language, the system
 * splash, edge-to-edge, then the shared [NeutrodyneRoot]. Before M1 there is no database, so the start-up gate is
 * ready at once (01 Splash and start-up gate, step 5); playback, notices and sync wire their state in later
 * milestones.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val graph = (application as NeutrodyneApplication).graph
        setContent {
            // 08 App scheme: the first frame waits for the settings file's first emission (1 s cap,
            // then the key defaults) so a stored dark theme never flashes light.
            val settings = graph.settingsRepository
            val prefs by
                remember(settings) {
                    combine(
                        settings.observe(AppearanceSettingKeys.THEME),
                        settings.observe(AppearanceSettingKeys.DYNAMIC_COLOR),
                    ) { theme, dynamicColor ->
                        AppearancePrefs(theme = theme, dynamicColor = dynamicColor)
                    }
                }.collectAsStateWithLifecycle(initialValue = null)
            var gateExpired by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(PREFS_GATE_TIMEOUT)
                gateExpired = true
            }
            val gateReady = prefs != null || gateExpired
            splash.setKeepOnScreenCondition { !gateReady }
            if (!gateReady) return@setContent

            val systemUi = SystemUiState.DEFAULT.copy(dark = isSystemInDarkTheme())
            CompositionLocalProvider(LocalPlatformActions provides rememberAndroidPlatformActions()) {
                NeutrodyneRoot(
                    state = RootUiState.READY,
                    actions = M0_ROOT_ACTIONS,
                    slots = M0_ROOT_SLOTS,
                    installers = graph.entryInstallers,
                    prefs = prefs ?: AppearancePrefs(),
                    systemUi = systemUi,
                    platform = BuildInfo.Platform.ANDROID,
                )
            }
        }
    }

    private companion object {
        /** 01 Application start-up: the settings wait never blocks the splash past 1 s. */
        val PREFS_GATE_TIMEOUT = 1.seconds

        /** Nothing to retry, dismiss or play before M1/M4: the root's callbacks are inert. */
        val M0_ROOT_ACTIONS =
            RootActions(
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
