// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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

internal enum class DiscoveryShot { LOADING, ERROR, CONTENT }

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
    CompositionLocalProvider(LocalAppNavigator provides RecordingAppNavigator()) {
        DiscoverScreen(
            DiscoverState(input = "news", results = results, providers = listOf(ProviderInfo(ProviderId.APPLE, true))),
            {},
            {},
            RecordingAppNavigator(),
        )
    }
}
