// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import ch.lkmc.neutrodyne.core.model.ChartGenre
import ch.lkmc.neutrodyne.core.model.DirectoryHit
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.model.ProviderId
import ch.lkmc.neutrodyne.core.model.ProviderInfo
import ch.lkmc.neutrodyne.core.model.ProviderStatus
import ch.lkmc.neutrodyne.core.model.SearchResults
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf

internal enum class DiscoveryShot { LOADING, ERROR, CONTENT, CHARTS }

/** Fixed input and no network, clock, artwork or platform-dependent text. */
@Composable
internal fun DiscoveryScreenshotFixture(shot: DiscoveryShot) {
    val results =
        SearchResults(
            "news",
            if (shot == DiscoveryShot.CONTENT) {
                persistentListOf(
                    DirectoryHit(
                        "https://example.invalid/news.xml",
                        "Morning news",
                        author = "Example publisher",
                        providers = persistentSetOf(ProviderId.APPLE),
                    ),
                )
            } else {
                persistentListOf()
            },
            persistentMapOf(
                ProviderId.APPLE to
                    if (shot == DiscoveryShot.ERROR) {
                        ProviderStatus.Failed(NetError.Offline)
                    } else if (shot == DiscoveryShot.LOADING) {
                        ProviderStatus.Pending
                    } else {
                        ProviderStatus.Ok
                    },
            ),
            shot != DiscoveryShot.LOADING,
        )
    val providers = listOf(ProviderInfo(ProviderId.APPLE, true))
    val state =
        if (shot == DiscoveryShot.CHARTS) {
            // Tuned to a preset away from the dial's start, so the needle and the scroll show.
            DiscoverState(
                providers = providers,
                presets = listOf(null, ChartGenre.SCIENCE),
                chart = TunedChart(ChartGenre.SCIENCE, CHART_SHOWS.map(::chartHit)),
            )
        } else {
            DiscoverState(input = "news", results = results, providers = providers)
        }
    CompositionLocalProvider(LocalAppNavigator provides RecordingAppNavigator()) {
        DiscoverScreen(state, {}, {}, RecordingAppNavigator())
    }
}

private val CHART_SHOWS = listOf("Deep Field" to "Observatory Audio", "The Lab Notebook" to "Kettle & Co.")

private fun chartHit(show: Pair<String, String>) =
    DirectoryHit(
        "https://example.invalid/${show.first.replace(' ', '-')}.xml",
        show.first,
        author = show.second,
        episodeCount = 42,
        providers = persistentSetOf(ProviderId.APPLE),
    )
