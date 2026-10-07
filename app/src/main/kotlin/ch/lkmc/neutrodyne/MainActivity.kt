// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
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
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.database.DatabaseOpenException
import ch.lkmc.neutrodyne.core.database.DatabaseOpenState
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.ui.AndroidDrawnReporter
import ch.lkmc.neutrodyne.core.ui.LocalDrawnReporter
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.rememberAndroidPlatformActions
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.startup_recovered
import ch.lkmc.neutrodyne.core.ui.root.NeutrodyneRoot
import ch.lkmc.neutrodyne.core.ui.root.RootActions
import ch.lkmc.neutrodyne.core.ui.root.RootSlots
import ch.lkmc.neutrodyne.core.ui.root.RootUiState
import ch.lkmc.neutrodyne.core.ui.root.StartupFailure
import ch.lkmc.neutrodyne.core.ui.root.StartupGateState
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * The one launcher activity (01 Application element and components): AppCompat for per-app language, the system
 * splash, edge-to-edge, then the shared [NeutrodyneRoot]. The start-up gate renders
 * `DatabaseOpener.openState` (01 Splash and start-up gate): "Try again" re-runs `awaitOpen()` (a
 * failed result is never cached), "Manage storage" opens `ACTION_INTERNAL_STORAGE_SETTINGS`; playback,
 * notices and sync wire their state in later milestones.
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

            val opener = graph.databaseOpener
            val openState by opener.openState.collectAsStateWithLifecycle()

            val systemUi = SystemUiState.DEFAULT.copy(dark = isSystemInDarkTheme())
            CompositionLocalProvider(
                LocalPlatformActions provides rememberAndroidPlatformActions(),
                // 08 Feeds / 09 ColdStartToFeeds: ReportDrawnWhen once the feed settles.
                LocalDrawnReporter provides AndroidDrawnReporter,
                // 01 Feature entry installers: metroViewModel()/assistedMetroViewModel() resolve here.
                LocalMetroViewModelFactory provides graph.metroViewModelFactory,
            ) {
                NeutrodyneRoot(
                    state = ROOT_STATE_BASE.copy(startup = openState.toGateState()),
                    actions =
                        RootActions(
                            retryStartup = { graph.appScope.launch { suspendRunCatching { opener.awaitOpen() } } },
                            dismissNotice = {},
                            continueHere = {},
                            dismissRemoteSession = {},
                            playbackKey = { false },
                            manageStorage = {
                                // 01 deviation: the SDK's storage-settings action is
                                // INTERNAL_STORAGE_SETTINGS (docs say ACTION_MANAGE_STORAGE).
                                startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
                            },
                        ),
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

        /** Everything below `startup` is still the M0 default (playback, notices, sync later). */
        val ROOT_STATE_BASE = RootUiState.READY

        /** No player (M4) and no user messages yet. */
        val M0_ROOT_SLOTS = RootSlots(player = {}, userMessages = emptyFlow())
    }
}

/** The shell's `DatabaseOpener → StartupGateState` mapping (01 Application start-up). */
internal fun DatabaseOpenState.toGateState(): StartupGateState =
    when (this) {
        DatabaseOpenState.Pending -> {
            StartupGateState.Pending
        }

        is DatabaseOpenState.Opened -> {
            if (result.recovered != null) {
                StartupGateState.Recovered(UiText.Res(Res.string.startup_recovered))
            } else {
                StartupGateState.Ready
            }
        }

        is DatabaseOpenState.Failed -> {
            StartupGateState.Failed(
                when (exception.reason) {
                    DatabaseOpenException.Reason.DISK_FULL -> StartupFailure.DISK_FULL
                    DatabaseOpenException.Reason.IO -> StartupFailure.IO
                    DatabaseOpenException.Reason.UNKNOWN -> StartupFailure.UNKNOWN
                },
            )
        }
    }
