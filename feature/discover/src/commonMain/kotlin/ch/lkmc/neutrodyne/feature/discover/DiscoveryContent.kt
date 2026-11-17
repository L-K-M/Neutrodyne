// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.discover

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.CoverArt
import ch.lkmc.neutrodyne.core.designsystem.components.CoverNetworkPolicy
import ch.lkmc.neutrodyne.core.designsystem.radio.Brass
import ch.lkmc.neutrodyne.core.designsystem.radio.KeyBank
import ch.lkmc.neutrodyne.core.designsystem.radio.MomentaryKey
import ch.lkmc.neutrodyne.core.designsystem.radio.recess
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.ChartGenre
import ch.lkmc.neutrodyne.core.model.DirectoryHit
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.model.ProviderId
import ch.lkmc.neutrodyne.core.model.ProviderInfo
import ch.lkmc.neutrodyne.core.model.ProviderStatus
import ch.lkmc.neutrodyne.core.model.SearchResults
import ch.lkmc.neutrodyne.core.navigation.AppNavigator
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.PodcastPreviewKey
import ch.lkmc.neutrodyne.core.ui.FeedDates
import ch.lkmc.neutrodyne.core.ui.SubscribedLamp
import ch.lkmc.neutrodyne.core.ui.rememberMonogram
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_retry
import ch.lkmc.neutrodyne.core.ui.resources.add_episodes_count
import ch.lkmc.neutrodyne.core.ui.resources.add_last_episode
import ch.lkmc.neutrodyne.core.ui.resources.discovery_busy
import ch.lkmc.neutrodyne.core.ui.resources.discovery_charts_off
import ch.lkmc.neutrodyne.core.ui.resources.discovery_disabled
import ch.lkmc.neutrodyne.core.ui.resources.discovery_disclosure
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
import ch.lkmc.neutrodyne.core.ui.resources.discovery_providers_loading
import ch.lkmc.neutrodyne.core.ui.resources.discovery_unavailable
import ch.lkmc.neutrodyne.core.ui.resources.net_offline
import ch.lkmc.neutrodyne.core.ui.resources.settings
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

internal fun AppNavigator.openHit(hit: DirectoryHit) =
    pushDetail(
        hit.subscribedPodcastId?.let(::PodcastKey) ?: PodcastPreviewKey(hit.feedUrl),
    )

/**
 * The recipient list under every action that sends the query to the directory providers
 * (03 privacy disclosure): Discover's search field and the add sheet's "Search for …" action
 * (M7) share it, so the effective providers are visible before the tap transmits anything.
 */
@Composable
internal fun ProviderDisclosure(
    providers: List<ProviderInfo>?,
    modifier: Modifier = Modifier,
    onVisible: (List<ProviderInfo>) -> Unit = {},
) {
    if (providers == null) {
        Text(stringResource(Res.string.discovery_providers_loading), modifier)
        return
    }
    val currentOnVisible by rememberUpdatedState(onVisible)
    LaunchedEffect(providers.configuration()) {
        withFrameNanos { }
        currentOnVisible(providers)
    }
    val recipients = providers.filter { it.effective }.joinToString(", ") { it.id.displayName() }
    Text(
        if (recipients.isEmpty()) {
            stringResource(Res.string.discovery_disabled)
        } else {
            stringResource(Res.string.discovery_disclosure, recipients)
        },
        modifier = modifier,
    )
}

/**
 * One directory hit as a paper slip in the tray (PO-55): cover in a recess, the title in Courier,
 * the author, then episode count, latest episode and the directories that returned it, and a lit
 * lamp for podcasts already in the library. The click target deliberately precedes the slip's
 * shape: the whole list line takes the click, while the slip draws the press, hover and focus.
 */
@Suppress("ktlint:compose:modifier-clickable-order")
@Composable
internal fun DirectoryRow(
    hit: DirectoryHit,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val details =
        listOfNotNull(
            hit.episodeCount?.let { pluralStringResource(Res.plurals.add_episodes_count, it, it) },
            hit.lastEpisodeAt?.let {
                val (day, month) = FeedDates.dayMonth(it)
                stringResource(Res.string.add_last_episode, "$day $month")
            },
            hit.providers.joinToString(", ") { it.displayName() }.takeIf { it.isNotEmpty() },
        ).joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .shadow(1.5.dp, SlipShape)
            .clip(SlipShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .indication(interaction, LocalIndication.current)
            .border(0.5.dp, Brass.copy(alpha = 0.7f), SlipShape)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DirectoryCover(hit, Modifier.size(56.dp).recess(RoundedCornerShape(3.dp)))
        Column(Modifier.weight(1f)) {
            Text(
                hit.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            hit.author?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (details.isNotEmpty()) {
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (hit.subscribedPodcastId != null) SubscribedLamp(Modifier.padding(top = 4.dp))
        }
    }
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
        // Directory art is provider-supplied, pre-confirmation (03 note 2026-11-02).
        networkPolicy = CoverNetworkPolicy.UNCONFIRMED,
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
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val active = results.statuses.filterValues { it != ProviderStatus.Disabled }
        val failed = active.filterValues { it is ProviderStatus.Failed || it == ProviderStatus.RateLimited }
        when {
            !results.complete -> {
                LinearProgressIndicator()
            }

            active.isEmpty() -> {
                Text(stringResource(Res.string.discovery_disabled))
                KeyBank { MomentaryKey(stringResource(Res.string.settings), settings) }
            }

            failed.size == active.size -> {
                val offline = failed.values.any { (it as? ProviderStatus.Failed)?.error == NetError.Offline }
                Text(stringResource(if (offline) Res.string.net_offline else Res.string.discovery_unavailable))
                KeyBank { MomentaryKey(stringResource(Res.string.action_retry), retry) }
            }

            results.hits.isEmpty() -> {
                Text(stringResource(Res.string.discovery_empty, results.query))
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

/** In place of the charts while Apple, their only source, is switched off: why, and the way back. */
@Composable
internal fun ChartsOff(settings: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(Res.string.discovery_charts_off))
        KeyBank { MomentaryKey(stringResource(Res.string.settings), settings) }
    }
}
