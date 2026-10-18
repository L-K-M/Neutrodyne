// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.di

import android.app.Application
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import ch.lkmc.neutrodyne.core.database.DatabaseOpenInitializer
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.EpisodeKey
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.LicencesKey
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.PodcastSettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsHomeKey
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import ch.lkmc.neutrodyne.notifications.AndroidGroupChannelSync
import ch.lkmc.neutrodyne.notifications.AndroidNewEpisodePoster
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Android graph test (01 Dependency injection rule 10): the real graph builds, every key that exists in M0a has
 * an entry, and every initializer's order lies in a start-up band. A plain `Application` keeps
 * `NeutrodyneApplication`'s start-up out of the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AndroidAppGraphTest {
    private val graph = createGraphFactory<AndroidAppGraph.Factory>().create(RuntimeEnvironment.getApplication())

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
        assertThat(dbOrder).isLessThan(orders.getValue("GroupChannelsInitializer"))
        assertThat(
            orders.getValue("GroupChannelsInitializer"),
        ).isLessThan(orders.getValue("PeriodicRefreshInitializer"))
        assertThat(dbOrder).isLessThan(orders.getValue("CredentialLoadInitializer"))
        assertThat(orders.getValue("CredentialLoadInitializer"))
            .isLessThan(orders.getValue("PeriodicRefreshInitializer"))
        assertThat(orders.getValue("PeriodicRefreshInitializer"))
            .isLessThan(orders.getValue("RefreshForegroundObserverInitializer"))
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

    private companion object {
        val STARTUP_BANDS =
            mapOf(
                "DebugToolsInitializer" to 0..99,
                "NewEpisodeChannelsInitializer" to 10..10,
                "GroupChannelsInitializer" to 140..140,
                "DatabaseOpenInitializer" to 100..119,
                // M1b's `credential-load` sits at 120: after the database, before refresh work.
                "CredentialLoadInitializer" to 120..199,
                "PeriodicRefreshInitializer" to 200..299,
                "RefreshForegroundObserverInitializer" to 200..299,
                // 05 Destinations: expired private exports are swept in the cleanup band.
                "ExportFilesCleaner" to 300..399,
            )

        /** The five destinations and the Settings screens M0a renders (01 M0 checklist step 16). */
        val M0A_KEYS: List<NavKey> =
            listOf(
                FeedsKey,
                LibraryKey,
                UpNextKey,
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
            )

        /** The keys `FeedsRoute` pushes: row tap, the row menu's podcast jump and the empty state. */
        val FEEDS_PUSHED_KEYS: List<NavKey> =
            listOf(
                EpisodeKey(7),
                PodcastKey(7),
                AddPodcastKey(null),
            )
    }
}
