// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.di

import android.app.Application
import androidx.navigation3.runtime.NavKey
import ch.lkmc.neutrodyne.core.database.DatabaseOpenInitializer
import androidx.navigation3.runtime.entryProvider
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.LicencesKey
import ch.lkmc.neutrodyne.core.navigation.SettingsHomeKey
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.createGraphFactory
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

    @Test
    fun initializerOrdersLieInStartupBands() {
        for (initializer in graph.initializers) {
            assertThat(initializer.order).isAtLeast(FIRST_BAND)
        }
    }

    @Test
    fun refreshInitializersFollowTheDatabaseBand() {
        val orders = graph.initializers.associate { (it::class.simpleName ?: it::class.toString()) to it.order }

        // 01 Application start-up: band 100 opens the database; the M1a refresh initializers run
        // in band 200 (periodic tick) and 220 (foreground observer) — after it and in that order.
        val dbOrder = graph.initializers.filterIsInstance<DatabaseOpenInitializer>().single().order
        assertThat(dbOrder).isLessThan(orders.getValue("PeriodicRefreshInitializer"))
        assertThat(orders.getValue("PeriodicRefreshInitializer"))
            .isLessThan(orders.getValue("RefreshForegroundObserverInitializer"))
    }

    private companion object {
        const val FIRST_BAND = 0

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
    }
}
