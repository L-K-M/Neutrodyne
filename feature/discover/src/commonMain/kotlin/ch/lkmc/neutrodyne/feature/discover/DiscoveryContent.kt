// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.CoverArt
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.ChartGenre
import ch.lkmc.neutrodyne.core.model.DirectoryHit
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.model.ProviderId
import ch.lkmc.neutrodyne.core.model.ProviderStatus
import ch.lkmc.neutrodyne.core.model.SearchResults
import ch.lkmc.neutrodyne.core.navigation.AppNavigator
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.PodcastPreviewKey
import ch.lkmc.neutrodyne.core.ui.EmptyState
import ch.lkmc.neutrodyne.core.ui.FeedDates
import ch.lkmc.neutrodyne.core.ui.rememberMonogram
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_retry
import ch.lkmc.neutrodyne.core.ui.resources.add_episodes_count
import ch.lkmc.neutrodyne.core.ui.resources.add_last_episode
import ch.lkmc.neutrodyne.core.ui.resources.discover_add_url
import ch.lkmc.neutrodyne.core.ui.resources.discovery_busy
import ch.lkmc.neutrodyne.core.ui.resources.discovery_disabled
import ch.lkmc.neutrodyne.core.ui.resources.discovery_empty
import ch.lkmc.neutrodyne.core.ui.resources.discovery_genre_arts
import ch.lkmc.neutrodyne.core.ui.resources.discovery_genre_business
import ch.lkmc.neutrodyne.core.ui.resources.discovery_genre_comedy
import ch.lkmc.neutrodyne.core.ui.resources.discovery_genre_education
import ch.lkmc.neutrodyne.core.ui.resources.discovery_genre_fiction
import ch.lkmc.neutrodyne.core.ui.resources.discovery_genre_government
import ch.lkmc.neutrodyne.core.ui.resources.discovery_genre_health_fitness
import ch.lkmc.neutrodyne.core.ui.resources.discovery_genre_history
import ch.lkmc.neutrodyne.core.ui.resources.discovery_genre_kids_family
import ch.lkmc.neutrodyne.core.ui.resources.discovery_genre_leisure
import ch.lkmc.neutrodyne.core.ui.resources.discovery_genre_music
import ch.lkmc.neutrodyne.core.ui.resources.discovery_genre_news
import ch.lkmc.neutrodyne.core.ui.resources.discovery_genre_religion
import ch.lkmc.neutrodyne.core.ui.resources.discovery_genre_science
import ch.lkmc.neutrodyne.core.ui.resources.discovery_genre_society_culture
import ch.lkmc.neutrodyne.core.ui.resources.discovery_genre_sports
import ch.lkmc.neutrodyne.core.ui.resources.discovery_genre_technology
import ch.lkmc.neutrodyne.core.ui.resources.discovery_genre_true_crime
import ch.lkmc.neutrodyne.core.ui.resources.discovery_genre_tv_film
import ch.lkmc.neutrodyne.core.ui.resources.discovery_partial
import ch.lkmc.neutrodyne.core.ui.resources.discovery_subscribed
import ch.lkmc.neutrodyne.core.ui.resources.discovery_unavailable
import ch.lkmc.neutrodyne.core.ui.resources.net_offline
import ch.lkmc.neutrodyne.core.ui.resources.settings
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

internal fun AppNavigator.openHit(hit: DirectoryHit) =
    pushDetail(
        hit.subscribedPodcastId?.let(::PodcastKey) ?: PodcastPreviewKey(hit.feedUrl),
    )

@Composable
internal fun DirectoryRow(
    hit: DirectoryHit,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(hit.title) },
        supportingContent = {
            Column {
                hit.author?.let { Text(it) }
                Text(
                    listOfNotNull(
                        hit.episodeCount?.let { pluralStringResource(Res.plurals.add_episodes_count, it, it) },
                        hit.lastEpisodeAt?.let {
                            val (day, month) = FeedDates.dayMonth(it)
                            stringResource(Res.string.add_last_episode, "$day $month")
                        },
                    ).joinToString(" · "),
                )
                Text(hit.providers.joinToString(", ") { it.displayName() })
                if (hit.subscribedPodcastId != null) Text(stringResource(Res.string.discovery_subscribed))
            }
        },
        leadingContent = { DirectoryCover(hit, Modifier.size(64.dp)) },
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}

@Composable
internal fun DirectoryCover(
    hit: DirectoryHit,
    modifier: Modifier = Modifier,
) {
    CoverArt(
        ref = hit.artworkUrl?.let { ArtworkRef(key = it, url = it, version = 0) },
        monogram = rememberMonogram(hit.title),
        title = hit.title,
        modifier = modifier,
    )
}

internal fun ProviderId.displayName(): String =
    when (this) {
        ProviderId.APPLE -> "Apple"
        ProviderId.FYYD -> "fyyd"
        ProviderId.PODCAST_INDEX -> "Podcast Index"
    }

@Composable
internal fun SearchStatus(
    results: SearchResults,
    retry: () -> Unit,
    settings: () -> Unit,
    addByUrl: (() -> Unit)? = null,
) {
    Column {
        val active = results.statuses.filterValues { it != ProviderStatus.Disabled }
        val failed = active.filterValues { it is ProviderStatus.Failed || it == ProviderStatus.RateLimited }
        when {
            !results.complete -> {
                LinearProgressIndicator()
            }

            active.isEmpty() -> {
                Text(stringResource(Res.string.discovery_disabled))
                NdTextButton(stringResource(Res.string.settings), settings)
            }

            failed.size == active.size -> {
                val offline = failed.values.any { (it as? ProviderStatus.Failed)?.error == NetError.Offline }
                Text(stringResource(if (offline) Res.string.net_offline else Res.string.discovery_unavailable))
                NdTextButton(stringResource(Res.string.action_retry), retry)
            }

            results.hits.isEmpty() -> {
                EmptyState(
                    NdIcons.Search,
                    stringResource(Res.string.discovery_empty, results.query),
                    "",
                    actionLabel = if (addByUrl != null) stringResource(Res.string.discover_add_url) else null,
                    onAction = addByUrl,
                )
            }
        }
        if (failed.isNotEmpty() && failed.size < active.size) {
            Text(
                stringResource(
                    if (failed.values.any {
                            it == ProviderStatus.RateLimited
                        }
                    ) {
                        Res.string.discovery_busy
                    } else {
                        Res.string.discovery_partial
                    },
                    failed.keys.joinToString(", ") { it.displayName() },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
internal fun ChartGenre.label(): String =
    stringResource(
        when (this) {
            ChartGenre.TECHNOLOGY -> Res.string.discovery_genre_technology
            ChartGenre.NEWS -> Res.string.discovery_genre_news
            ChartGenre.FICTION -> Res.string.discovery_genre_fiction
            ChartGenre.COMEDY -> Res.string.discovery_genre_comedy
            ChartGenre.SCIENCE -> Res.string.discovery_genre_science
            ChartGenre.HISTORY -> Res.string.discovery_genre_history
            ChartGenre.SPORTS -> Res.string.discovery_genre_sports
            ChartGenre.TRUE_CRIME -> Res.string.discovery_genre_true_crime
            ChartGenre.BUSINESS -> Res.string.discovery_genre_business
            ChartGenre.ARTS -> Res.string.discovery_genre_arts
            ChartGenre.EDUCATION -> Res.string.discovery_genre_education
            ChartGenre.HEALTH_FITNESS -> Res.string.discovery_genre_health_fitness
            ChartGenre.KIDS_FAMILY -> Res.string.discovery_genre_kids_family
            ChartGenre.MUSIC -> Res.string.discovery_genre_music
            ChartGenre.SOCIETY_CULTURE -> Res.string.discovery_genre_society_culture
            ChartGenre.TV_FILM -> Res.string.discovery_genre_tv_film
            ChartGenre.RELIGION -> Res.string.discovery_genre_religion
            ChartGenre.GOVERNMENT -> Res.string.discovery_genre_government
            ChartGenre.LEISURE -> Res.string.discovery_genre_leisure
        },
    )
