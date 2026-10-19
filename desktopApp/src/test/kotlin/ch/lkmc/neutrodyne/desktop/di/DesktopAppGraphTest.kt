// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.di

import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import ch.lkmc.neutrodyne.core.artwork.ArtworkKeys
import ch.lkmc.neutrodyne.core.artwork.ArtworkRefMapper
import ch.lkmc.neutrodyne.core.database.DatabaseOpenInitializer
import ch.lkmc.neutrodyne.core.database.DatabaseOpenState
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.SleepTimerState
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.EpisodeKey
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.ImportFileKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.LicencesKey
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.PodcastSettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsHomeKey
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.navigation.SleepTimerKey
import ch.lkmc.neutrodyne.core.navigation.SpeedKey
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import ch.lkmc.neutrodyne.desktop.buildinfo.BuildInfoLoader
import ch.lkmc.neutrodyne.desktop.crash.DesktopCrashReporter
import ch.lkmc.neutrodyne.desktop.log.RecentLogBuffer
import ch.lkmc.neutrodyne.desktop.platform.DesktopClock
import ch.lkmc.neutrodyne.desktop.shell.tempAppDirs
import ch.lkmc.neutrodyne.feature.library.LibraryViewModel
import ch.lkmc.neutrodyne.feature.player.PlayerViewModel
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.youtube.api.AudioPref
import ch.lkmc.neutrodyne.youtube.api.ResolveResult
import coil3.PlatformContext
import coil3.map.Mapper
import coil3.request.Options
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * The desktop graph test (01 Testing, Graphs row; 11 DesktopAppGraph): Metro already fails
 * compilation on a missing or duplicate binding, so the runtime part proves the graph is
 * constructible with a temporary `AppDirs` and resolves the shell's shared bindings — including
 * the same M0 key set `AndroidAppGraphTest` checks, through the feature modules' contributions —
 * and M1a's `DatabaseOpener` with its band-100 initializer.
 */
class DesktopAppGraphTest {
    private val dirs = tempAppDirs().also { it.ensureCreated() }
    private val buildInfo = BuildInfoLoader.load()
    private val reporter = DesktopCrashReporter(dirs, buildInfo, DesktopClock, RecentLogBuffer())
    private val graph = createDesktopGraph(dirs, buildInfo, reporter)

    /** JUnit builds a fresh instance per test, so every test's scope is cancelled here. */
    @After
    fun tearDown() {
        graph.appScope.cancel()
    }

    @Test
    fun durablePlaybackPortsResolveToSingleProductionBindings() =
        runBlocking {
            graph.databaseOpener.awaitOpen()
            try {
                val bindings =
                    listOf(
                        graph.queueRepository() to "QueueRepositoryImpl",
                        graph.playContextResolver() to "PlayContextResolverImpl",
                        graph.playSessionStore() to "SessionWriter",
                        graph.positionStore() to "PositionWriter",
                        graph.playbackMediaLookup() to "PlaybackMediaLookupImpl",
                        graph.chapterRepository() to "ChapterRepositoryImpl",
                    )
                for ((binding, expectedName) in bindings) {
                    assertThat(binding::class.simpleName).isEqualTo(expectedName)
                }
                assertThat(graph.queueRepository()).isSameInstanceAs(graph.queueRepository())
                assertThat(graph.playContextResolver()).isSameInstanceAs(graph.playContextResolver())
                assertThat(graph.playSessionStore()).isSameInstanceAs(graph.playSessionStore())
                assertThat(graph.positionStore()).isSameInstanceAs(graph.positionStore())
                assertThat(graph.playbackMediaLookup()).isSameInstanceAs(graph.playbackMediaLookup())
                assertThat(graph.chapterRepository()).isSameInstanceAs(graph.chapterRepository())
            } finally {
                graph.databaseOpener.requireDatabase().close()
            }
        }

    @Test
    fun playerSpeedEntryAndWindowViewModelResolveWithoutOpeningDatabase() {
        val provider = entryProvider<NavKey> { graph.entryInstallers.forEach { it() } }
        assertThat(
            provider(SleepTimerKey).metadata[NdSceneMetadata.KEY_OVERLAY],
        ).isEqualTo(NdSceneMetadata.OVERLAY_SHEET)
        assertThat(provider(SpeedKey).metadata[NdSceneMetadata.KEY_OVERLAY]).isEqualTo(NdSceneMetadata.OVERLAY_SHEET)
        val player = graph.metroViewModelFactory.create(PlayerViewModel::class, CreationExtras.Empty)
        assertThat(player.uiState.value.now).isNull()
        player.viewModelScope.cancel()
        assertThat(graph.databaseOpener.openState.value is DatabaseOpenState.Opened).isFalse()
    }

    @Test
    fun playbackBindingsAreUniqueTruthfulAndDoNotOpenTheDatabase() =
        runBlocking {
            assertThat(graph.playbackController).isSameInstanceAs(graph.playbackController)
            assertThat(graph.playbackStateSource).isSameInstanceAs(graph.playbackController)
            assertThat(graph.playbackMaintenance).isSameInstanceAs(graph.playbackController)
            assertThat(graph.playbackStateSource.nowPlaying.value).isNull()
            assertThat(graph.playbackStateSource.effectivePlayback.value).isNull()
            assertThat(
                graph.playbackStateSource.sleepTimer.value,
            ).isEqualTo(SleepTimerState.Off)
            assertThat(graph.playbackStateSource.currentChapter.value).isNull()
            assertThat(graph.playbackController.playEpisode(1)).isEqualTo(PlayResult.ServiceUnavailable)
            assertThat(graph.playbackController.playEpisodeAt(1, 20)).isEqualTo(PlayResult.ServiceUnavailable)
            assertThat(
                graph.playbackController.playFeed(FeedSource.All, FeedFilters(), FeedOrder.NEWEST_FIRST),
            ).isEqualTo(PlayResult.ServiceUnavailable)
            assertThat(graph.playbackController.play()).isEqualTo(PlayResult.ServiceUnavailable)
            graph.playbackController.pause()
            graph.playbackController.dismiss()
            assertThat(graph.playbackMaintenance.streamingCacheBytes()).isEqualTo(0)
            graph.playbackMaintenance.clearStreamingCache()
            assertThat(graph.localMediaIndex.localUriOrNull(1)).isNull()
            assertThat(graph.youtubeCapabilities).isSameInstanceAs(graph.youtubeCapabilities)
            assertThat(graph.youtubeCapabilities.capabilities.value.inAppPlayback).isFalse()
            assertThat(
                graph.youtubeCapabilities.capabilities.value.externalReason,
            ).isEqualTo(ExternalReason.NOT_YET_AVAILABLE)
            assertThat(graph.youtubeStreamResolver.resolveAudio("id", AudioPref())).isEqualTo(ResolveResult.Unsupported)
            graph.youtubeStreamResolver.invalidate("id")
            graph.youtubeStreamResolver.invalidateAll()
            assertThat(graph.initializers).isNotEmpty()
            // Taking providers must not resolve their database-backed dependencies.
            assertThat(graph.queueRepository).isNotNull()
            assertThat(graph.playContextResolver).isNotNull()
            assertThat(graph.playSessionStore).isNotNull()
            assertThat(graph.positionStore).isNotNull()
            assertThat(graph.playbackMediaLookup).isNotNull()
            assertThat(graph.chapterRepository).isNotNull()
            assertThat(graph.databaseOpener.openState.value is DatabaseOpenState.Opened).isFalse()
        }

    @Test
    fun theGraphBuildsWithTemporaryAppDirsAndResolvesTheSharedBindings() {
        assertThat(graph.dirs).isSameInstanceAs(dirs)
        assertThat(graph.buildInfo).isSameInstanceAs(buildInfo)
        assertThat(graph.settingsRepository).isNotNull()
        assertThat(graph.networkMonitor).isInstanceOf(
            ch.lkmc.neutrodyne.core.network.DesktopNetworkMonitor::class.java,
        )
        // The shell's pre-graph instance is the graph's crash reporter, not a second one.
        assertThat(graph.crashReporter).isSameInstanceAs(reporter)
        assertThat(graph.initializers).isNotNull()
        assertThat(graph.appScope).isNotNull()
    }

    /** M1a: the graph binds the opener the window's start-up gate maps (01 Splash and start-up gate). */
    @Test
    fun theDatabaseOpenerAndItsInitializerAreBound() {
        assertThat(graph.databaseOpener).isNotNull()
        assertThat(graph.initializers.filterIsInstance<DatabaseOpenInitializer>()).hasSize(1)
    }

    /** The same keys as `app/src/test/.../AndroidAppGraphTest` (01 Graph tests rule 11). */
    @Test
    fun everyM0bKeyHasAnEntry() {
        val provider = entryProvider<NavKey> { graph.entryInstallers.forEach { install -> install() } }

        for (key in M0B_KEYS) {
            assertThat(provider(key).contentKey).isNotNull()
        }
    }

    /**
     * Every key `LibraryRoute` pushes must resolve through the production installers: nav3's
     * provider throws `IllegalStateException("Unknown screen")` for a key no feature module
     * contributes, and the host resolves entries eagerly at the next composition — a missing
     * contribution crashes the app (94-1).
     */
    @Test
    fun everyKeyTheLibraryPushesHasAnEntry() {
        val provider = entryProvider<NavKey> { graph.entryInstallers.forEach { install -> install() } }

        for (key in LIBRARY_PUSHED_KEYS) {
            assertThat(provider(key).contentKey).isNotNull()
        }
    }

    /**
     * Every key `FeedsRoute` pushes must resolve too — the row tap's `EpisodeKey`, the row
     * menu's `PodcastKey` and the empty state's `AddPodcastKey` hit the same 94-1 crash.
     */
    @Test
    fun everyKeyTheFeedsRoutePushesHasAnEntry() {
        val provider = entryProvider<NavKey> { graph.entryInstallers.forEach { install -> install() } }

        for (key in FEEDS_PUSHED_KEYS) {
            assertThat(provider(key).contentKey).isNotNull()
        }
    }

    /**
     * 01 Feature entry installers: the window root hands this to `LocalMetroViewModelFactory`, so
     * `metroViewModel()` inside the Library route can only work if the factory resolves the VM.
     * Graph compilation alone does not prove that — the VM is created through the public factory.
     */
    @Test
    fun theMetroViewModelFactoryCarriesTheLibraryViewModel() {
        runBlocking { graph.databaseOpener.awaitOpen() }

        val viewModel =
            graph.metroViewModelFactory.create(LibraryViewModel::class, CreationExtras.Empty)
        // ViewModel.clear is internal; cancelling its scope is the public way to stop the
        // repository collectors a dropped VM would otherwise leave running.
        try {
            assertThat(viewModel).isInstanceOf(LibraryViewModel::class.java)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    /**
     * 08 Coil ImageLoader: the singleton the hosts install is built here, and the `ArtworkRef`
     * model of a `u-` key resolves through `ArtworkRefMapper` on it — the default loader has no
     * such mapper, so a provisioning gap would surface as an unresolvable image request.
     */
    @Test
    fun theCoilLoaderFactoryIsProvisionedAndMapsArtworkRefs() {
        // `store()` inside the factory resolves `ArtworkStore`, which opens the database.
        runBlocking { graph.databaseOpener.awaitOpen() }

        val factory = graph.imageLoaderFactory

        val loader = factory.newImageLoader(PlatformContext.INSTANCE)
        try {
            assertThat(loader.memoryCache).isNotNull()
            assertThat(loader.diskCache).isNotNull()
            assertThat(
                loader.components.interceptors.any {
                    it is ch.lkmc.neutrodyne.core.artwork.YouTubeThumbnailInterceptor
                },
            ).isTrue()

            val ref = ArtworkRef(key = ArtworkKeys.forUrl(ARTWORK_URL), url = ARTWORK_URL, version = 0)
            val mapper =
                loader.components.mappers
                    .map { it.first }
                    .filterIsInstance<ArtworkRefMapper>()
                    .single()
            val resolved = (mapper as Mapper<ArtworkRef, Any>).map(ref, Options(PlatformContext.INSTANCE))
            assertThat(resolved).isEqualTo(ARTWORK_URL)
        } finally {
            loader.shutdown()
        }
    }

    @Test
    fun initializerOrdersLieInStartupBands() {
        for (initializer in graph.initializers) {
            assertThat(initializer.order).isAtLeast(FIRST_BAND)
        }

        // 03 Start-up: `credential-load` at 120 sits after the database open and before the
        // refresh runner — `FeedFetcher` awaits the store, so an early request never reads an
        // unloaded map.
        val orders =
            graph.initializers.associate { (it::class.simpleName ?: it::class.toString()) to it.order }
        val dbOrder =
            graph.initializers
                .filterIsInstance<DatabaseOpenInitializer>()
                .single()
                .order
        assertThat(dbOrder).isLessThan(orders.getValue("CredentialLoadInitializer"))
        assertThat(orders.getValue("CredentialLoadInitializer"))
            .isLessThan(orders.getValue("DesktopRunnerInitializer"))
    }

    /**
     * M1b wiring: `AuthInterceptor` reads `CredentialLookup`, which must be the secrets-file
     * `SecretStore` singleton — never a leftover `None` (03 Basic auth). Resolving either
     * accessor touches the database, so the open runs first.
     */
    @Test
    fun theCredentialLookupIsTheSecretStore() {
        runBlocking { graph.databaseOpener.awaitOpen() }

        assertThat(graph.credentialLookup).isSameInstanceAs(graph.secretStore)
    }

    /**
     * M3 C3a: the export repository resolves to the real `ExportRepositoryImpl` — resolution
     * also proves the `FileExportDestination` binding and the `OpmlExporter` chain — and the
     * snapshot scheduler is D34's documented no-op, not a silent fallback. The impls are
     * `internal` to `:core:data`, so the assertion goes through the resolved class names.
     * Resolving the repository pulls the database, so the open runs first.
     */
    @Test
    fun theExportRepositoryAndSnapshotSchedulerAreTheRealDesktopBindings() {
        runBlocking { graph.databaseOpener.awaitOpen() }

        assertThat(graph.exportRepository::class.simpleName).isEqualTo("ExportRepositoryImpl")
        assertThat(graph.snapshotScheduler::class.simpleName).isEqualTo("NoSnapshotScheduler")
    }

    /**
     * M3 C3b: the session host resolves to the real `ImportRepositoryImpl`/`BackupRepositoryImpl`,
     * which proves the `ImportWorkScheduler` provider chain (`LaneImportWorkScheduler` over a lazy
     * `JobLanePoker`, so the runner→lane→runner edge never cycles) — and resolving `jobRunner`
     * lists the `import-backup` lane in its status, so the lane really joined the runner's set.
     */
    @Test
    fun theImportBackupHostAndItsLaneAreTheRealDesktopBindings() {
        runBlocking { graph.databaseOpener.awaitOpen() }

        assertThat(graph.importRepository::class.simpleName).isEqualTo("ImportRepositoryImpl")
        assertThat(graph.backupRepository::class.simpleName).isEqualTo("BackupRepositoryImpl")
        assertThat(graph.bannerNotifier::class.simpleName).isEqualTo("DesktopBannerNotifier")
        assertThat(graph.jobRunner.status.value.keys).contains("import-backup")
    }

    private companion object {
        const val FIRST_BAND = 0

        const val ARTWORK_URL = "https://img.example/cover.png"

        /** The five destinations and the Settings screens M0 renders (01 M0 checklist step 29). */
        val M0B_KEYS: List<NavKey> =
            listOf(
                FeedsKey,
                LibraryKey,
                UpNextKey,
                SpeedKey,
                SleepTimerKey,
                DownloadsKey,
                DiscoverKey,
                SettingsHomeKey,
                SettingsKey(SettingsPage.ABOUT),
                LicencesKey,
            )

        /** The keys `LibraryRoute` pushes: tile tap, tile-menu settings and the empty-state actions. */
        val LIBRARY_PUSHED_KEYS: List<NavKey> =
            listOf(
                PodcastKey(7),
                PodcastSettingsKey(7),
                AddPodcastKey(null),
                ImportFileKey,
            )

        /** The keys `FeedsRoute` pushes: row tap, the row menu's podcast jump and the empty state. */
        val FEEDS_PUSHED_KEYS: List<NavKey> =
            listOf(
                EpisodeKey(7),
                PodcastKey(7),
                AddPodcastKey(null),
                ImportFileKey,
            )
    }
}
