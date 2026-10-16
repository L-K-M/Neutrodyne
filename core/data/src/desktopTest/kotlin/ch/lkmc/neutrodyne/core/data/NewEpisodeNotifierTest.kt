// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.withWriteTransaction
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import ch.lkmc.neutrodyne.core.data.notifications.NewEpisodeNotifier
import ch.lkmc.neutrodyne.core.data.settings.EffectiveSettingsResolverImpl
import ch.lkmc.neutrodyne.core.database.PodcastGroupSettingsEntity
import ch.lkmc.neutrodyne.core.database.PodcastSettingsEntity
import ch.lkmc.neutrodyne.core.database.ScopeOverrides
import ch.lkmc.neutrodyne.core.database.SqliteDriverBindings
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.NewEpisodes
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeNewEpisodePoster
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewEpisodeNotifierTest {
    private val db = newDb()
    private val settings = FakeSettingsRepository()
    private val poster = FakeNewEpisodePoster()
    private val notifier = NewEpisodeNotifier(db, EffectiveSettingsResolverImpl(db, settings), poster)

    @AfterTest fun close() = db.close()

    @Test fun twoNotifyingGroupsProduceTwoNoticesInGroupOrder() =
        runTest {
            val later = group("Later", "b", true)
            val earlier = group("Earlier", "a", true)
            val a = podcast(later)
            val b = podcast(earlier)
            val aIds = episodes(a, 2)
            val bIds = episodes(b, 1)

            notifier.post(listOf(NewEpisodes(a, aIds, false), NewEpisodes(b, bIds, false)))

            val notices = poster.posts.single()
            assertEquals(listOf("Earlier", "Later"), notices.map { it.groupName })
            assertEquals(listOf(bIds, aIds.reversed()), notices.map { n -> n.episodes.map { it.episodeId } })
            assertEquals(listOf(b, a, a), notices.flatMap { n -> n.episodes.map { it.podcastId } })
        }

    @Test fun firstNotifyingGroupWinsRegardlessOfMembershipInsertionOrder() =
        runTest {
            val later = group("Later", "z", true)
            val first = group("First", "a", true)
            val p = podcast(later, first)
            val ids = episodes(p, 1)

            notifier.post(listOf(NewEpisodes(p, ids, false)))

            val notice = poster.posts.single().single()
            assertEquals(db.groupDao().groupById(first)!!.uuid, notice.groupUuid)
            assertEquals(ids, notice.episodes.map { it.episodeId })
        }

    @Test fun podcastOffOverridesNotifyingGroupsAndGlobalOn() =
        runTest {
            settings.set(FeedsSettingKeys.NOTIFY_NEW_EPISODES, true)
            val p = podcast(group("On", "a", true))
            db.scopeSettingsDao().upsertPodcast(PodcastSettingsEntity(p, ScopeOverrides(notifyNewEpisodes = false)))

            notifier.post(listOf(NewEpisodes(p, episodes(p, 1), false)))

            assertTrue(poster.posts.isEmpty())
        }

    @Test fun groupOffOverridesGlobalOnAndGlobalDefaultsOff() =
        runTest {
            val ungrouped = podcast()
            val off = podcast(group("Off", "a", false))
            notifier.post(listOf(NewEpisodes(ungrouped, episodes(ungrouped, 1), false)))
            settings.set(FeedsSettingKeys.NOTIFY_NEW_EPISODES, true)
            notifier.post(listOf(NewEpisodes(off, episodes(off, 1), false)))
            assertTrue(poster.posts.isEmpty())
        }

    @Test fun playedAndHiddenEpisodesAreDroppedUsingFeedVisibility() =
        runTest {
            settings.set(FeedsSettingKeys.NOTIFY_NEW_EPISODES, true)
            val p = podcast()
            val visible = episodes(p, 1).single()
            val played = episodes(p, 1).single()
            db.episodeStateDao().ensure(episodeStateEntity(played, playedAt = 123))
            val short = db.episodeDao().insertStub(episodeEntity(podcastId = p) { copy(isShort = true) })
            val hidden =
                listOf(Availability.UPCOMING, Availability.LIVE, Availability.MEMBERS_ONLY).map {
                    db.episodeDao().insertStub(episodeEntity(podcastId = p, availability = it))
                }
            val unavailable =
                db.episodeDao().insertStub(
                    episodeEntity(podcastId = p, availability = Availability.UNAVAILABLE),
                )

            notifier.post(listOf(NewEpisodes(p, listOf(visible, played, short, unavailable) + hidden, false)))

            // UNAVAILABLE remains visible in the feed; it must not be silently excluded here.
            assertEquals(
                listOf(unavailable, visible),
                poster.posts
                    .single()
                    .single()
                    .episodes
                    .map { it.episodeId },
            )
        }

    @Test fun initialFetchAndEmptyBatchesNeverPost() =
        runTest {
            settings.set(FeedsSettingKeys.NOTIFY_NEW_EPISODES, true)
            val p = podcast()
            notifier.post(listOf(NewEpisodes(p, episodes(p, 2), true)))
            notifier.post(listOf(NewEpisodes(p, emptyList(), false)))
            notifier.post(emptyList())
            assertTrue(poster.posts.isEmpty())
        }

    @Test fun globalOnUsesDefaultWhenNoGroupHasItsOwnOnSetting() =
        runTest {
            settings.set(FeedsSettingKeys.NOTIFY_NEW_EPISODES, true)
            val p = podcast(group("Inherit", "a", null))
            notifier.post(listOf(NewEpisodes(p, episodes(p, 1), false)))
            val notice = poster.posts.single().single()
            assertEquals(null, notice.groupUuid)
            assertEquals(null, notice.groupName)
        }

    @Test fun podcastOnUsesDefaultEvenWhenGroupIsOffAndGlobalIsOff() =
        runTest {
            val p = podcast(group("Off", "a", false))
            db.scopeSettingsDao().upsertPodcast(PodcastSettingsEntity(p, ScopeOverrides(notifyNewEpisodes = true)))
            notifier.post(listOf(NewEpisodes(p, episodes(p, 1), false)))
            assertEquals(
                null,
                poster.posts
                    .single()
                    .single()
                    .groupUuid,
            )
        }

    @Test fun duplicatesAreCollapsedAndDefaultIsLastWithNewestLinesAndDisplayTitles() =
        runTest {
            settings.set(FeedsSettingKeys.NOTIFY_NEW_EPISODES, true)
            val g = group("Group", "a", true)
            val grouped = podcast(g)
            val default =
                db.podcastDao().insertPodcast(
                    podcastEntity(title = "Original", artworkKey = "cover") {
                        copy(customTitle = "Custom")
                    },
                )
            val older = db.episodeDao().insertStub(episodeEntity(podcastId = default, title = "Old", sortDate = 1))
            val newer =
                db.episodeDao().insertStub(
                    episodeEntity(podcastId = default, title = "New", sortDate = 2) {
                        copy(artworkKey = "episode-cover")
                    },
                )
            val groupedIds = episodes(grouped, 1)
            notifier.post(
                listOf(
                    NewEpisodes(default, listOf(older, newer, older), false),
                    NewEpisodes(grouped, groupedIds, false),
                    NewEpisodes(default, listOf(newer), false),
                ),
            )
            val notices = poster.posts.single()
            assertEquals(listOf("Group", null), notices.map { it.groupName })
            assertEquals(listOf(newer, older), notices.last().episodes.map { it.episodeId })
            assertEquals(listOf("Custom", "Custom"), notices.last().episodes.map { it.podcastTitle })
            assertEquals(listOf("New", "Old"), notices.last().episodes.map { it.episodeTitle })
            assertEquals(listOf("episode-cover", "cover"), notices.last().episodes.map { it.artworkKey })
        }

    @Test fun throwingPosterDoesNotEscapeOrPreventLaterRuns() =
        runTest {
            settings.set(FeedsSettingKeys.NOTIFY_NEW_EPISODES, true)
            val p = podcast()
            val batch = listOf(NewEpisodes(p, episodes(p, 1), false))
            poster.onPost = { error("poster unavailable") }
            notifier.post(batch)
            poster.onPost = {}
            notifier.post(batch)
            assertEquals(2, poster.posts.size)
        }

    @Test fun largeRunUsesBoundedReadsAndKeepsGlobalNewestFirstOrder() =
        runTest {
            val reads = AtomicInteger()
            val delegate = SqliteDriverBindings.sqliteDriver()
            val driver =
                object : SQLiteDriver by delegate {
                    override fun open(fileName: String): SQLiteConnection {
                        val connection = delegate.open(fileName)
                        return object : SQLiteConnection by connection {
                            override fun prepare(sql: String): SQLiteStatement {
                                val measured =
                                    sql.startsWith("SELECT e.id AS episodeId") ||
                                        sql.trimStart().startsWith("SELECT p.id AS podcastId")
                                if (measured) {
                                    // Model the baseline driver's variable limit even on bundled SQLite.
                                    require(sql.count { it == '?' } <= 500) { "notification bind limit exceeded" }
                                }
                                val statement = connection.prepare(sql)
                                if (!measured) return statement

                                return object : SQLiteStatement by statement {
                                    private var started = false

                                    override fun step(): Boolean {
                                        if (!started) {
                                            reads.incrementAndGet()
                                            started = true
                                        }
                                        return statement.step()
                                    }

                                    override fun reset() {
                                        statement.reset()
                                        started = false
                                    }
                                }
                            }
                        }
                    }
                }
            val localDb = TestDb.inMemory(driver = driver)
            try {
                settings.set(FeedsSettingKeys.NOTIFY_NEW_EPISODES, true)
                val batch =
                    localDb.withWriteTransaction {
                        (0..1000).map {
                            val p = localDb.podcastDao().insertPodcast(podcastEntity())
                            val episodeId =
                                localDb.episodeDao().insertStub(
                                    episodeEntity(podcastId = p, sortDate = it.toLong()),
                                )
                            NewEpisodes(p, listOf(episodeId), false)
                        }
                    }
                NewEpisodeNotifier(localDb, EffectiveSettingsResolverImpl(localDb, settings), poster).post(batch)
                val ids = batch.flatMap { it.episodeIds }

                assertEquals(
                    ids.reversed(),
                    poster.posts
                        .single()
                        .single()
                        .episodes
                        .map { it.episodeId },
                )
                assertEquals(6, reads.get())
            } finally {
                localDb.close()
            }
        }

    private suspend fun group(
        name: String,
        orderKey: String,
        notify: Boolean?,
    ): Long {
        val id = db.groupDao().insert(groupEntity(name = name, orderKey = orderKey))
        if (notify !=
            null
        ) {
            db.scopeSettingsDao().upsertGroup(
                PodcastGroupSettingsEntity(id, ScopeOverrides(notifyNewEpisodes = notify)),
            )
        }
        return id
    }

    private suspend fun podcast(vararg groups: Long): Long {
        val id = db.podcastDao().insertPodcast(podcastEntity())
        for (group in groups) db.groupDao().insertMember(memberEntity(group, id, "a"))
        return id
    }

    private suspend fun episodes(
        podcastId: Long,
        count: Int,
    ): List<Long> =
        (0 until count).map {
            db.episodeDao().insertStub(episodeEntity(podcastId = podcastId, sortDate = 1_700_000_000_000L + it))
        }
}
