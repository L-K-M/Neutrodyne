// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.acceptance

import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.ContextEligibility
import ch.lkmc.neutrodyne.core.domain.PlayContextResolver
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedUrls
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal suspend fun assertInitialExternalChannel(
    db: NeutrodyneDatabase,
    id: Long,
    group: Long,
) {
    val channel = assertNotNull(db.podcastDao().byId(id))
    assertEquals(SourceType.YOUTUBE_CHANNEL, channel.sourceType)
    assertEquals(YouTubeFeedUrls.canonical(checkNotNull(channel.youtubeChannelId)), channel.feedUrl)
    assertFalse(channel.initialFetch)
    assertTrue(channel.syncId.isNotBlank())
    assertTrue(db.groupDao().membershipPairs().any { it.podcastId == id && it.groupId == group })
    val episode = assertNotNull(db.episodeDao().byIdentityKey(id, "g:yt:video:aaaaaaaaaaa"))
    assertEquals("yt:video:aaaaaaaaaaa", episode.guid)
    assertFalse(episode.isNew)
    assertNull(episode.enclosureUrl)
    assertEquals("aaaaaaaaaaa", episode.externalMediaId)
    assertNull(db.downloadDao().byEpisode(episode.id))
    assertEquals(emptyList(), db.queueDao().entries())
}

internal suspend fun assertMixedGroupSkipsExternal(
    db: NeutrodyneDatabase,
    resolver: PlayContextResolver,
    group: Long,
) {
    val spec =
        resolver.spec(
            FeedSource
                .Group(group),
            FeedFilters(),
            FeedOrder.NEWEST_FIRST,
        )
    assertNull(resolver.startItem(spec))
    assertNotNull(resolver.startItem(spec, ContextEligibility.INCLUDE_EXTERNAL_YOUTUBE))
    val rss =
        db.podcastDao().insertPodcast(
            podcastEntity(),
        )
    db.groupDao().insertMember(
        memberEntity(group, rss, "b0"),
    )
    val episode =
        db.episodeDao().insertStub(
            episodeEntity(podcastId = rss) {
                copy(enclosureUrl = "https://fixtures.neutrodyne.invalid/audio.mp3", enclosureType = "audio/mpeg")
            },
        )
    assertEquals(episode, resolver.startItem(spec))
}
