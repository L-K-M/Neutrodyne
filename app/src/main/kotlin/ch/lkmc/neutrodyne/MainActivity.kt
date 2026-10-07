// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
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
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * The one launcher activity (01 Application element and components): AppCompat for per-app language, the system
 * splash, edge-to-edge, then the shared [NeutrodyneRoot]. Before M1 there is no database, so the start-up gate is
 * ready at once (01 Splash and start-up gate, step 5); playback, notices and sync wire their state in later
 * milestones.
 */
class MainActivity : AppCompatActivity() {
    /** `appearance.*` once the settings file has emitted; null until then (08 App scheme). */
    private val appearance = mutableStateOf<AppearancePrefs?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        mirrorActivityLocalesToDefault()

        val graph = (application as NeutrodyneApplication).graph
        holdSplashForAppearance(splash, graph.settingsRepository)

        setContent {
            val systemUi = SystemUiState.DEFAULT.copy(dark = isSystemInDarkTheme())
            CompositionLocalProvider(LocalPlatformActions provides rememberAndroidPlatformActions()) {
                NeutrodyneRoot(
                    state = RootUiState.READY,
                    actions = M0_ROOT_ACTIONS,
                    slots = M0_ROOT_SLOTS,
                    installers = graph.entryInstallers,
                    prefs = appearance.value ?: AppearancePrefs(),
                    systemUi = systemUi,
                    platform = BuildInfo.Platform.ANDROID,
                )
            }
        }
    }

    /**
     * S11 (2026-10-07): below API 33 AppCompat applies the per-app language to this activity's
     * configuration only, while Compose resources resolve their locale from the process default
     * (`Locale.current`, i.e. `LocaleList.getDefault()`). The default takes the user's requested
     * app locales, not the activity configuration: the framework reorders that list so a locale the
     * APK's Android resources ship comes first (`en-US,de` for a requested `de` on an en-only APK),
     * while the Compose resources carry every language. Without a request it follows the
     * configuration (the system locales). From API 33 `LocaleManager` sets the default itself.
     */
    private fun mirrorActivityLocalesToDefault() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        val requested = AppCompatDelegate.getApplicationLocales()
        val locales =
            if (requested.isEmpty) {
                resources.configuration.locales
            } else {
                LocaleList.forLanguageTags(
                    requested.toLanguageTags(),
                )
            }
        LocaleList.setDefault(locales)
        Locale.setDefault(locales[0])
    }

    /**
     * 08 App scheme: the splash stays until the settings file's first emission (1 s cap, then the
     * key defaults), so a stored dark theme never flashes light. Collected on the activity's
     * lifecycle scope, outside composition: a kept splash blocks every frame, so a gate that waited
     * on a composition effect would wait on frames it blocks itself (and on the test clock under
     * Compose tests, which only advances once the UI is idle).
     */
    private fun holdSplashForAppearance(
        splash: SplashScreen,
        settings: SettingsRepository,
    ) {
        var gateExpired = false
        splash.setKeepOnScreenCondition { appearance.value == null && !gateExpired }

        lifecycleScope.launch {
            delay(PREFS_GATE_TIMEOUT)
            gateExpired = true
        }
        lifecycleScope.launch {
            combine(
                settings.observe(AppearanceSettingKeys.THEME),
                settings.observe(AppearanceSettingKeys.DYNAMIC_COLOR),
            ) { theme, dynamicColor -> AppearancePrefs(theme = theme, dynamicColor = dynamicColor) }
                .flowWithLifecycle(lifecycle)
                .collect { appearance.value = it }
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
