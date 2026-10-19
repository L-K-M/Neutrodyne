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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.MutableCreationExtras
import ch.lkmc.neutrodyne.core.common.GraphHolder
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.database.DatabaseOpenException
import ch.lkmc.neutrodyne.core.database.DatabaseOpenState
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.ui.AndroidDrawnReporter
import ch.lkmc.neutrodyne.core.ui.LocalDrawnReporter
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
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
import ch.lkmc.neutrodyne.di.AndroidAppGraph
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
    /** `appearance.*` once the settings file has emitted; null until then (08 App scheme). */
    private val appearance = mutableStateOf<AppearancePrefs?>(null)

    internal val routing: MainIntentViewModel by lazy {
        // Intent extras are untrusted; only restored routing state may seed the handle.
        val extras =
            MutableCreationExtras(defaultViewModelCreationExtras).apply {
                this[DEFAULT_ARGS_KEY] = Bundle.EMPTY
            }
        ViewModelProvider(viewModelStore, defaultViewModelProviderFactory, extras)[MainIntentViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val graph = (application as GraphHolder).graph as AndroidAppGraph
        if (savedInstanceState == null) routing.receive(intent, packageName)
        holdSplashForAppearance(splash, graph.settingsRepository)

        setContent {
            val opener = graph.databaseOpener
            val openState by opener.openState.collectAsStateWithLifecycle()

            val systemUi = SystemUiState.DEFAULT.copy(dark = isSystemInDarkTheme())
            CompositionLocalProvider(
                LocalPlatformActions provides rememberAndroidPlatformActions(),
                // 07 Storage rows pick per-platform wording from this local.
                LocalPlatformKind provides PlatformKind.ANDROID,
                // 08 Feeds / 09 ColdStartToFeeds: ReportDrawnWhen once the feed settles.
                LocalDrawnReporter provides AndroidDrawnReporter,
                // 01 Feature entry installers: metroViewModel()/assistedMetroViewModel() resolve here.
                LocalMetroViewModelFactory provides graph.metroViewModelFactory,
            ) {
                NeutrodyneRoot(
                    state = ROOT_STATE_BASE.copy(startup = openState.toGateState()),
                    actions =
                        RootActions(
                            selectFeedSource = { uuid ->
                                suspendRunCatching {
                                    graph.settingsRepository.set(
                                        AppearanceSettingKeys.FEEDS_SELECTED_SOURCE,
                                        uuid?.let { "group:$it" } ?: "all",
                                    )
                                }.getOrNull() is Outcome.Success
                            },
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
                    routes = routing.routes,
                    installers = graph.entryInstallers,
                    prefs = appearance.value ?: AppearancePrefs(),
                    systemUi = systemUi,
                    platform = BuildInfo.Platform.ANDROID,
                )
                val importError by routing.error.collectAsStateWithLifecycle()
                importError?.let { error ->
                    NeutrodyneTheme(prefs = appearance.value ?: AppearancePrefs(), system = systemUi) {
                        NdDialog(
                            onDismissRequest = { routing.dismissError() },
                            title = getString(R.string.import_into_neutrodyne),
                            text = getString(error.message),
                            confirm = NdDialogAction(getString(android.R.string.ok)) { routing.dismissError() },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routing.receive(intent, packageName)
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
