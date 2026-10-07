// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.lkmc.neutrodyne.core.designsystem.components.NdButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdEmptyState
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.ExportKey
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.SettingsHomeKey
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.rememberAndroidPlatformActions
import ch.lkmc.neutrodyne.core.ui.root.NeutrodyneRoot
import ch.lkmc.neutrodyne.core.ui.root.RootActions
import ch.lkmc.neutrodyne.core.ui.root.RootSlots
import ch.lkmc.neutrodyne.core.ui.root.RootUiState
import ch.lkmc.neutrodyne.core.ui.root.SettingsGearButton
import kotlinx.coroutines.flow.emptyFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Debug-only (01 S5): the activity S5's instrumented tests host. It composes the shared
 * [NeutrodyneRoot] with the stubs below, mirroring `MainActivity`'s shell composition (test installers stand in for the graph's
 * `entryInstallers`). `onCreate` owns the `setContent` call so `ActivityScenario.recreate()`
 * re-runs it against the restored saved state — the "Don't keep activities" / process-death leg.
 */
class SpikeRootActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SpikeRoot() }
    }
}

@Composable
internal fun SpikeRoot() {
    CompositionLocalProvider(LocalPlatformActions provides rememberAndroidPlatformActions()) {
        NeutrodyneRoot(
            state = RootUiState.READY,
            actions = SpikeActions,
            slots = SpikeSlots,
            installers = SpikeInstallers,
            modifier = Modifier.testTag(SpikeRootTags.ROOT),
        )
    }
}

internal object SpikeRootTags {
    const val ROOT = "root-box"
}

private val SpikeActions =
    RootActions(
        retryStartup = {},
        dismissNotice = {},
        continueHere = {},
        dismissRemoteSession = {},
        playbackKey = { false },
    )

private val SpikeSlots = RootSlots(player = {}, userMessages = emptyFlow())

/**
 * The test-only `EntryProviderInstaller`s S5 asks for — the same shape as the desktop test's
 * stubs (01 S9): the five labelled destinations, a detail, a sheet and a dialog. The `PodcastKey`
 * entry additionally probes the per-entry ViewModel scope the host's
 * `rememberViewModelStoreNavEntryDecorator()` provides.
 */
internal val SpikeInstallers: Set<EntryProviderInstaller> =
    buildSet {
        add { entry<FeedsKey>(metadata = NdSceneMetadata.paneList()) { FeedsStub() } }
        add { entry<LibraryKey>(metadata = NdSceneMetadata.paneList()) { LibraryStub() } }
        add {
            entry<UpNextKey>(metadata = NdSceneMetadata.paneList()) {
                TabStub("up-next-content", NdIcons.QueueMusic)
            }
        }
        add {
            entry<DownloadsKey>(metadata = NdSceneMetadata.paneList()) {
                TabStub("downloads-content", NdIcons.Download)
            }
        }
        add {
            entry<DiscoverKey>(metadata = NdSceneMetadata.paneList()) {
                TabStub("discover-content", NdIcons.Explore)
            }
        }
        add { entry<SettingsHomeKey>(metadata = NdSceneMetadata.paneList()) { Text("settings-home") } }
        add {
            entry<PodcastKey>(metadata = NdSceneMetadata.paneDetail()) { key -> PodcastStub(key) }
        }
        add { entry<AddPodcastKey>(metadata = NdSceneMetadata.bottomSheet()) { Text("add-podcast-sheet") } }
        add { entry<ExportKey>(metadata = NdSceneMetadata.dialog()) { Text("export-dialog") } }
    }

/** A `ViewModel` the `PodcastKey` stub resolves through `viewModel()` inside the entry. */
internal class ProbeViewModel : ViewModel()

/**
 * Records which `ViewModel` instance each `PodcastKey` entry got, so the tests prove two entries
 * hold distinct instances and an instance survives while its tab is hidden (the decorator clears
 * the store on pop, not on hide).
 */
internal object PodcastVmProbe {
    private val instances = ConcurrentHashMap<PodcastKey, LinkedHashSet<ProbeViewModel>>()

    fun register(
        key: PodcastKey,
        vm: ProbeViewModel,
    ) {
        instances.getOrPut(key) { LinkedHashSet() }.add(vm)
    }

    fun instancesOf(key: PodcastKey): Set<ProbeViewModel> = instances[key].orEmpty()

    fun reset() = instances.clear()
}

@Composable
private fun FeedsStub() {
    val navigator = LocalAppNavigator.current
    var count by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        StubTopBar("Feeds")
        Text("feeds-content")
        Text("feeds-count:$count")
        NdButton(label = "feeds-inc", onClick = { count++ })
        NdButton(label = "open-sheet", onClick = { navigator.push(AddPodcastKey(null)) })
        NdButton(label = "open-dialog", onClick = { navigator.push(ExportKey(null)) })
        NdButton(label = "open-podcast", onClick = { navigator.push(PodcastKey(FEEDS_PODCAST)) })
        NdButton(label = "open-podcast-2", onClick = { navigator.push(PodcastKey(FEEDS_PODCAST_2)) })
    }
}

@Composable
private fun LibraryStub() {
    val navigator = LocalAppNavigator.current
    var count by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        StubTopBar("Library")
        Text("library-content")
        Text("library-count:$count")
        NdButton(label = "library-inc", onClick = { count++ })
        NdButton(label = "open-podcast", onClick = { navigator.push(PodcastKey(LIBRARY_PODCAST)) })
    }
}

@Composable
private fun PodcastStub(key: PodcastKey) {
    // The host's rememberViewModelStoreNavEntryDecorator() scopes this to the nav entry, not the
    // activity: two PodcastKey entries must not share the instance.
    val vm = viewModel { ProbeViewModel() }
    SideEffect { PodcastVmProbe.register(key, vm) }
    Column(Modifier.fillMaxSize()) { Text("podcast-${key.podcastId}") }
}

@Composable
private fun TabStub(
    text: String,
    icon: ImageVector,
) {
    Column(Modifier.fillMaxSize()) {
        NdEmptyState(icon = icon, title = text, body = text)
    }
}

@Composable
private fun StubTopBar(title: String) {
    NdTopAppBar(title = title, actions = { SettingsGearButton() })
}

internal const val FEEDS_PODCAST = 3L
internal const val FEEDS_PODCAST_2 = 5L
internal const val LIBRARY_PODCAST = 9L
