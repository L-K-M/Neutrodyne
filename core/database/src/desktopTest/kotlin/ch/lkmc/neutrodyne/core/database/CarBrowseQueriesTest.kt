// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.database

import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.PlayContextSpec
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.core.testing.database.queueEntryEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CarBrowseQueriesTest {
    @Test fun browseIncludesQueuedMembersButTailExcludesThemAndFiltersStillApply() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val p = db.podcastDao().insertPodcast(podcastEntity())
                val other = db.podcastDao().insertPodcast(podcastEntity())
                val group = db.groupDao().insertGroupOrIgnore(groupEntity(orderKey = "a0"))
                db.groupDao().insertMember(PodcastGroupMemberEntity(group, p, "a0", 0))

                suspend fun episode(
                    date: Long,
                    change: EpisodeEntity.() -> EpisodeEntity = { this },
                ) = db.episodeDao().insertStub(episodeEntity(podcastId = p, sortDate = date).change())
                val queued = episode(400)
                val tie = episode(400)
                val played = episode(500)
                db.episodeStateDao().upsert(episodeStateEntity(played, playedAt = 1))
                episode(600) { copy(availability = Availability.UNAVAILABLE) }
                episode(700) { copy(availability = Availability.LIVE) }
                episode(800) { copy(isShort = true) }
                episode(1)
                db.episodeDao().insertStub(episodeEntity(podcastId = other, sortDate = 900))
                db.queueDao().insert(queueEntryEntity(queued, "a0"))
                val spec =
                    PlayContextSpec(ContextType.GROUP, group, FeedOrder.NEWEST_FIRST, 0, MediaFilter.ALL, 100, false)

                suspend fun browse(
                    s: PlayContextSpec = spec,
                    id: Long? = null,
                ): List<Long> {
                    val query = FeedQueryBuilder.contextList(s, 100, false, id)
                    return db.feedDao().contextList(query).map { it.episodeId }
                }
                assertEquals(listOf(tie, queued), browse())
                assertEquals(listOf(queued, tie), browse(spec.copy(order = FeedOrder.OLDEST_FIRST)))
                assertEquals(listOf(queued), browse(id = queued))
                assertTrue(browse(id = played).isEmpty())
                val tail = FeedQueryBuilder.contextTail(spec, null, null, 100, false)
                assertEquals(listOf(tie), db.feedDao().contextTail(tail).map { it.episodeId })
                db.groupDao().deleteGroup(group)
                assertTrue(browse().isEmpty())
            } finally {
                db.close()
            }
        }

    @Test fun podcastsUseCustomTitlesStableTiesAndExternalCapability() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val z =
                    db.podcastDao().insertPodcast(podcastEntity(title = "Original") { copy(customTitle = "Zulu") })
                val a = db.podcastDao().insertPodcast(podcastEntity(title = "Alpha"))
                val tie = db.podcastDao().insertPodcast(podcastEntity(title = "alpha"))
                val yt =
                    db.podcastDao().insertPodcast(
                        podcastEntity(title = "Beta") { copy(sourceType = SourceType.YOUTUBE_CHANNEL) },
                    )
                assertEquals(listOf(a, tie, z), db.podcastDao().listForBrowse(false).map { it.id })
                assertEquals(listOf(a, tie, yt, z), db.podcastDao().listForBrowse(true).map { it.id })
            } finally {
                db.close()
            }
        }

    @Test fun searchBindsLiteralWildcardsUnicodeAndUsesNewestIdTieBreak() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val p = db.podcastDao().insertPodcast(podcastEntity())

                suspend fun add(
                    title: String,
                    date: Long = 10,
                ) = db.episodeDao().insertStub(episodeEntity(podcastId = p, title = title, sortDate = date))
                val percent = add("100% ready")
                val underscore = add("a_b")
                val slash = add("a\\b")
                val unicode = add("日本語 Ångström")
                add("unrelated")

                suspend fun find(
                    q: String,
                    limit: Int = 20,
                ): List<Long> {
                    val escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                    return db.episodeDao().searchTitles("%$escaped%", limit, false)
                }
                assertEquals(listOf(percent), find("%"))
                assertEquals(listOf(underscore), find("_"))
                assertEquals(listOf(slash), find("\\"))
                assertEquals(listOf(unicode), find("日本語"))
                assertTrue(find("' OR 1=1 --").isEmpty())
                val first = add("tie", 30)
                val second = add("tie", 30)
                assertEquals(listOf(second, first), find("tie"))
                assertEquals(listOf(second), find("tie", 1))
            } finally {
                db.close()
            }
        }
}
