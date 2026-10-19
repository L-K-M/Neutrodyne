// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.di

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import ch.lkmc.neutrodyne.SnapshotNowReceiver
import ch.lkmc.neutrodyne.core.artwork.ArtworkProviderGraph
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.database.DatabaseOpenInitializer
import ch.lkmc.neutrodyne.core.database.DatabaseOpenState
import ch.lkmc.neutrodyne.core.model.ExportRequest
import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.SleepTimerState
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.BackupKey
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.EpisodeKey
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.ImportFileKey
import ch.lkmc.neutrodyne.core.navigation.ImportKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.LicencesKey
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.PodcastSettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsHomeKey
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.navigation.SpeedKey
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import ch.lkmc.neutrodyne.feature.player.PlayerViewModel
import ch.lkmc.neutrodyne.notifications.AndroidGroupChannelSync
import ch.lkmc.neutrodyne.notifications.AndroidImportReportPoster
import ch.lkmc.neutrodyne.notifications.AndroidNewEpisodePoster
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.youtube.api.AudioPref
import ch.lkmc.neutrodyne.youtube.api.ResolveResult
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The Android graph test (01 Dependency injection rule 10): the real graph builds, every key that exists in M0a has
 * an entry, and every initializer's order lies in a start-up band. A plain `Application` keeps
 * `NeutrodyneApplication`'s start-up out of the test.
 */
@RunWith(RobolectricTestRunner::class)
// Share the startup tests' sandbox so SQLite JNI is loaded by one classloader.
@Config(application = Application::class, sdk = [34])
class AndroidAppGraphTest {
    private val graph = createGraphFactory<AndroidAppGraph.Factory>().create(RuntimeEnvironment.getApplication())

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
                    )
                for ((binding, expectedName) in bindings) {
                    assertThat(binding::class.simpleName).isEqualTo(expectedName)
                }
                assertThat(graph.queueRepository()).isSameInstanceAs(graph.queueRepository())
                assertThat(graph.playContextResolver()).isSameInstanceAs(graph.playContextResolver())
                assertThat(graph.playSessionStore()).isSameInstanceAs(graph.playSessionStore())
                assertThat(graph.positionStore()).isSameInstanceAs(graph.positionStore())
                assertThat(graph.playbackMediaLookup()).isSameInstanceAs(graph.playbackMediaLookup())
            } finally {
                graph.databaseOpener.requireDatabase().close()
            }
        }

    @Test
    fun playerSpeedEntryAndWindowViewModelResolveWithoutOpeningDatabase() {
        val provider = entryProvider<NavKey> { graph.entryInstallers.forEach { it() } }
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
            assertThat(graph.playbackStateSource).isSameInstanceAs(graph.playbackStateSource)
            assertThat(graph.playbackMaintenance).isSameInstanceAs(graph.playbackMaintenance)
            assertThat(graph.playbackController::class.simpleName).isEqualTo("PlaybackControllerImpl")
            assertThat(graph.playbackStateSource::class.simpleName).isEqualTo("PlaybackStateHub")
            assertThat(graph.playbackMaintenance::class.simpleName).isEqualTo("StreamingCache")
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
            // Dismiss is a durable command; it is deliberately not invoked by a graph-construction test.
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
            assertThat(graph.databaseOpener.openState.value is DatabaseOpenState.Opened).isFalse()
        }

    @Test
    fun serviceInjectionDefersDatabaseConsumersWhileOpenIsPending() {
        val service =
            ch.lkmc.neutrodyne.playback.impl
                .NeutrodynePlaybackService()
        (graph as ch.lkmc.neutrodyne.playback.impl.PlaybackServiceInjector).inject(service)
        assertThat(service.access).isNotNull()
        assertThat(graph.databaseOpener.openState.value).isEqualTo(DatabaseOpenState.Pending)
    }

    @Test
    fun artworkProviderContributionDefersDatabaseAccess() {
        val artwork = graph as ArtworkProviderGraph
        assertThat(artwork.artworkProviderAccess()).isNotNull()
        assertThat(graph.workerFactory).isNotNull()
        assertThat(graph.databaseOpener.openState.value is DatabaseOpenState.Opened).isFalse()
    }

    @Test
    fun everyM0aKeyHasAnEntry() {
        val provider = entryProvider<NavKey> { graph.entryInstallers.forEach { install -> install() } }

        for (key in M0A_KEYS) {
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

    @Test
    fun initializerOrdersLieInStartupBands() {
        for (initializer in graph.initializers) {
            val band = STARTUP_BANDS.getValue(checkNotNull(initializer::class.simpleName))
            assertThat(initializer.order).isAtLeast(band.first)
            assertThat(initializer.order).isAtMost(band.last)
        }
    }

    @Test
    fun refreshInitializersFollowTheDatabaseBand() {
        val orders = graph.initializers.associate { (it::class.simpleName ?: it::class.toString()) to it.order }

        // 01 Application start-up: band 100 opens the database, M1b's `credential-load` waits for
        // the store at 120, and the M1a refresh initializers run in band 200 (periodic tick) and
        // 220 (foreground observer) — after it and in that order (03 Start-up).
        val dbOrder =
            graph.initializers
                .filterIsInstance<DatabaseOpenInitializer>()
                .single()
                .order
        assertThat(orders.getValue("NewEpisodeChannelsInitializer")).isLessThan(dbOrder)
        assertThat(orders.getValue("ImportBackupChannelsInitializer")).isLessThan(dbOrder)
        assertThat(dbOrder).isLessThan(orders.getValue("GroupChannelsInitializer"))
        assertThat(
            orders.getValue("GroupChannelsInitializer"),
        ).isLessThan(orders.getValue("PeriodicRefreshInitializer"))
        assertThat(dbOrder).isLessThan(orders.getValue("CredentialLoadInitializer"))
        assertThat(orders.getValue("CredentialLoadInitializer"))
            .isLessThan(orders.getValue("PeriodicRefreshInitializer"))
        assertThat(orders.getValue("PeriodicRefreshInitializer"))
            .isLessThan(orders.getValue("RefreshForegroundObserverInitializer"))
        // 05's work table: the auto-backup arm and durable-work reconciliation run in band
        // 200 too — after the database open, in the scheduling band.
        assertThat(dbOrder).isLessThan(orders.getValue("AutoBackupInitializer"))
        // 05 First-launch restore: the adopted snapshot's restore request runs after the
        // database open and before the auto-backup lane's durable-work reconciliation.
        assertThat(dbOrder).isLessThan(orders.getValue("FirstLaunchRestoreInitializer"))
        assertThat(orders.getValue("FirstLaunchRestoreInitializer"))
            .isLessThan(orders.getValue("AutoBackupInitializer"))
    }

    /**
     * M1b wiring: `AuthInterceptor` reads `CredentialLookup`, which must be the Keystore-backed
     * `SecretStore` singleton — never a leftover `None` (03 Basic auth). Resolving either
     * accessor touches the database, so the open runs first.
     */
    @Test
    fun theCredentialLookupIsTheSecretStore() {
        runBlocking { graph.databaseOpener.awaitOpen() }
        try {
            assertThat(graph.credentialLookup).isSameInstanceAs(graph.secretStore)
        } finally {
            graph.databaseOpener.requireDatabase().close()
        }
    }

    /**
     * M3 C1: `ExportRepository` binds through the Android seam — `prepare` mints the cache
     * file AND a `content://{applicationId}.fileprovider/export/…` URI resolved by the
     * manifest's FileProvider, proving the authority and the `export/` root end to end.
     */
    @Test
    fun exportRepositoryPreparesThroughTheFileProviderSeam() =
        runBlocking {
            graph.databaseOpener.awaitOpen()
            try {
                val prepared =
                    (graph.exportRepository.prepare(ExportRequest()) as Outcome.Success).value

                val authority = "${RuntimeEnvironment.getApplication().packageName}.fileprovider"
                assertThat(prepared.shareUri)
                    .isEqualTo("content://$authority/export/${prepared.fileName}")
                assertThat(prepared.mimeType).isEqualTo("text/x-opml")
                assertThat(
                    File(
                        RuntimeEnvironment.getApplication().cacheDir,
                        "export/${prepared.fileName}",
                    ).isFile,
                ).isTrue()
            } finally {
                graph.databaseOpener.requireDatabase().close()
            }
        }

    @Test
    fun newEpisodePosterUsesTheAndroidAdapter() =
        runBlocking {
            graph.databaseOpener.awaitOpen()
            try {
                assertThat(graph.groupChannelSync).isInstanceOf(AndroidGroupChannelSync::class.java)
                assertThat(graph.groupChannelSync).isSameInstanceAs(graph.groupChannelSync)
                assertThat(graph.newEpisodePoster).isInstanceOf(AndroidNewEpisodePoster::class.java)
                assertThat(graph.newEpisodePoster).isSameInstanceAs(graph.newEpisodePoster)
            } finally {
                graph.databaseOpener.requireDatabase().close()
            }
        }

    /**
     * M3 C2: the import/backup lanes bind the real services — no stub can reach the graph —
     * and the `import_backup` poster resolves to the Android adapter.
     */
    @Test
    fun theImportLanesBindTheRealServices() =
        runBlocking {
            graph.databaseOpener.awaitOpen()
            try {
                assertThat(graph.importRepository).isSameInstanceAs(graph.importRepository)
                assertThat(graph.backupRepository).isSameInstanceAs(graph.backupRepository)
                assertThat(graph.importReportPoster).isInstanceOf(AndroidImportReportPoster::class.java)
            } finally {
                graph.databaseOpener.requireDatabase().close()
            }
        }

    @Test
    fun snapshotReceiverInjectionKeepsTheRepositoryLazyBeforeDatabaseOpen() {
        val receiver = SnapshotNowReceiver()

        graph.inject(receiver)

        assertThat(receiver.backupRepository.isInitialized()).isFalse()
        assertThat(graph.databaseOpener.openState.value is DatabaseOpenState.Opened).isFalse()
    }

    @Test
    fun everyImportNotificationTargetHasAnEntry() {
        val provider = entryProvider<NavKey> { graph.entryInstallers.forEach { install -> install() } }
        for (key in listOf(ImportKey("1"), BackupKey)) {
            assertThat(provider(key).contentKey).isNotNull()
        }
    }

    private companion object {
        val STARTUP_BANDS =
            mapOf(
                "DebugToolsInitializer" to 0..99,
                "NewEpisodeChannelsInitializer" to 10..10,
                "ImportBackupChannelsInitializer" to 10..10,
                "GroupChannelsInitializer" to 140..140,
                "DatabaseOpenInitializer" to 100..119,
                // 05 First-launch restore: order 110, inside the database band.
                "FirstLaunchRestoreInitializer" to 100..119,
                // M1b's `credential-load` sits at 120: after the database, before refresh work.
                "CredentialLoadInitializer" to 120..199,
                "PeriodicRefreshInitializer" to 200..299,
                "RefreshForegroundObserverInitializer" to 200..299,
                // 05's work table: periodic arm, watcher and durable-work reconciliation.
                "AutoBackupInitializer" to 200..299,
                // 05 Destinations: expired private exports are swept in the cleanup band.
                "ExportFilesCleaner" to 300..399,
                "ArtworkInitializer" to 300..300,
                "PlaybackPrefsInitializer" to 300..300,
                "PlayerConnectionInitializer" to 300..300,
                "PlaybackChannels" to 10..10,
            )

        /** The five destinations and the Settings screens M0a renders (01 M0 checklist step 16). */
        val M0A_KEYS: List<NavKey> =
            listOf(
                FeedsKey,
                LibraryKey,
                UpNextKey,
                SpeedKey,
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
