// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.database.SyncOutboxEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.CredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.NoPlaybackSync
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncActive
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncEvent
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncPort
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakePlaybackSyncPort
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsSyncPort
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.sync.api.SyncNotice
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceDto
import ch.lkmc.neutrodyne.sync.protocol.dto.FieldValue
import ch.lkmc.neutrodyne.sync.protocol.dto.MatchHints
import ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 10 Pull and apply: collection writes, the mass-change guard and idempotent re-application. */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncApplierTest {
    private val node = NodeId("0000000000000001")
    private val remote = NodeId("0000000000000002")
    private val podcastSyncId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val now = 1_800_000_000_000L
    private val deviceId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"

    @Test
    fun remotePodcastIsInsertedAsPendingAndStateApplied() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val applier = applier(db)
                val result =
                    applier.applyPage(
                        listOf(
                            podcastRecord(
                                podcastSyncId,
                                "subscribed" to bool(true),
                                "feedUrl" to text("https://example.com/feed"),
                                "title" to text("Example"),
                                "subscribedAt" to int(1L),
                            ),
                        ),
                        roundId = now,
                    )
                assertEquals(1, result.applied)

                val podcast = db.podcastDao().bySyncId(podcastSyncId)
                assertNotNull(podcast)
                assertEquals(ch.lkmc.neutrodyne.core.model.PodcastStatus.PENDING_FIRST_FETCH, podcast.status)
                assertTrue(podcast.initialFetch)
                assertEquals("https://example.com/feed", podcast.feedUrl)
                assertNotNull(podcast.nextRefreshAt)

                val podcastId = podcast.id
                val episodeId =
                    db
                        .ingestDao()
                        .insertEpisodes(listOf(episodeEntity(podcastId = podcastId, identityKey = "g:one")))
                        .single()
                val episode =
                    applier.applyPage(
                        listOf(
                            episodeRecord(
                                podcastSyncId,
                                "g:one",
                                MatchHints(guid = "one", enc = "https://cdn.example.com/one.mp3"),
                                "played" to bool(true),
                                "playedAt" to int(5L),
                            ),
                        ),
                        roundId = now + 1,
                    )
                assertEquals(1, episode.applied)
                assertEquals(5L, db.episodeStateDao().byEpisode(episodeId)?.playedAt)
            } finally {
                db.close()
            }
        }

    @Test
    fun reapplyingTheSamePageIsIdempotent() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val applier = applier(db)
                val page =
                    listOf(
                        podcastRecord(
                            podcastSyncId,
                            "subscribed" to bool(true),
                            "feedUrl" to text("https://example.com/feed"),
                            "title" to text("Example"),
                        ),
                    )
                assertEquals(1, applier.applyPage(page, roundId = now).applied)
                val second = applier.applyPage(page, roundId = now + 1)
                assertEquals(0, second.applied)
                assertEquals(1, second.stale)
                assertEquals(1, db.podcastDao().bySyncId(podcastSyncId)?.let { 1 } ?: 0)
            } finally {
                db.close()
            }
        }

    @Test
    fun aSingleRemoteUnsubscribeIsAppliedBelowTheThreshold() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                seedPodcast(db, podcastSyncId)
                val applier = applier(db)
                val result =
                    applier.applyPage(
                        listOf(podcastRecord(podcastSyncId, "subscribed" to bool(false))),
                        roundId = now,
                    )
                assertEquals(1, result.held)
                val approved =
                    MassChangeGuard
                        .evaluate(db, roundId = now, localPodcasts = 1)
                        .filterIsInstance<MassChangeGuard.Verdict.Approved>()
                        .singleOrNull()
                assertNotNull(approved, "one removal of one is below the thresholds")
                db.withWriteTransaction {
                    db.syncStateDao().withApplying {
                        MassChangeGuard.apply(db, approved.batch, now)
                    }
                    db.syncHeldDao().delete(approved.rowId)
                }
                assertNull(db.podcastDao().bySyncId(podcastSyncId), "the staged removal applies")
            } finally {
                db.close()
            }
        }

    @Test
    fun theGuardThresholdsKeepTheirBoundaries() {
        // Podcasts: more than 10, or more than 20% with at least 3 removed; never `>=`.
        assertFalse(MassChangeGuard.isMassChange(podcastCount = 10, groupCount = 0, localPodcasts = 50))
        assertTrue(MassChangeGuard.isMassChange(podcastCount = 11, groupCount = 0, localPodcasts = 50))
        assertFalse(MassChangeGuard.isMassChange(podcastCount = 3, groupCount = 0, localPodcasts = 15))
        assertTrue(MassChangeGuard.isMassChange(podcastCount = 4, groupCount = 0, localPodcasts = 15))
        assertFalse(MassChangeGuard.isMassChange(podcastCount = 2, groupCount = 0, localPodcasts = 4))
        // Groups: more than 3.
        assertFalse(MassChangeGuard.isMassChange(podcastCount = 0, groupCount = 3, localPodcasts = 50))
        assertTrue(MassChangeGuard.isMassChange(podcastCount = 0, groupCount = 4, localPodcasts = 50))
    }

    @Test
    fun aNewerLocalCaptureCancelsAStagedRemoval() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                seedPodcast(db, podcastSyncId)
                val applier = applier(db)
                val result =
                    applier.applyPage(
                        listOf(podcastRecord(podcastSyncId, "subscribed" to bool(false))),
                        roundId = now,
                    )
                assertEquals(1, result.held)

                // The user re-subscribes before the guard settles; the pending capture's clock
                // beats the staged removal's (10 Mass-change guard 2).
                db.withWriteTransaction {
                    db
                        .syncOutboxDao()
                        .upsert(
                            SyncOutboxEntity(
                                coll = "podcast",
                                rid = podcastSyncId,
                                field = "subscribed",
                                hlc = clock(11).packed,
                                nodeId = node.value,
                                value = "true",
                            ),
                        )
                }

                val approved =
                    MassChangeGuard
                        .evaluate(db, roundId = now, localPodcasts = 1)
                        .filterIsInstance<MassChangeGuard.Verdict.Approved>()
                        .single()
                db.withWriteTransaction {
                    db.syncStateDao().withApplying {
                        MassChangeGuard.apply(db, approved.batch, now)
                    }
                    db.syncHeldDao().delete(approved.rowId)
                }
                assertNotNull(
                    db.podcastDao().bySyncId(podcastSyncId),
                    "the newer local capture cancels the removal",
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun aNewerRemoteRevivalCancelsAStagedRemoval() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                seedPodcast(db, podcastSyncId)
                val applier = applier(db)
                val result =
                    applier.applyPage(
                        listOf(podcastRecord(podcastSyncId, "subscribed" to bool(false))),
                        roundId = now,
                    )
                assertEquals(1, result.held)

                // A newer remote record revives the subscription before the guard settles.
                applier.applyPage(
                    listOf(
                        podcastRecord(
                            podcastSyncId,
                            "subscribed" to FieldValue(JsonPrimitive(true), clock(11).toString()),
                        ),
                    ),
                    roundId = now + 1,
                )

                val approved =
                    MassChangeGuard
                        .evaluate(db, roundId = now + 1, localPodcasts = 1)
                        .filterIsInstance<MassChangeGuard.Verdict.Approved>()
                        .single()
                db.withWriteTransaction {
                    db.syncStateDao().withApplying {
                        MassChangeGuard.apply(db, approved.batch, now)
                    }
                    db.syncHeldDao().delete(approved.rowId)
                }
                assertNotNull(
                    db.podcastDao().bySyncId(podcastSyncId),
                    "the newer remote revival cancels the removal",
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun elevenRemoteUnsubscribesAreHeldAndKeepMineRecaptures() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                repeat(50) { seedPodcast(db, "00000000-0000-4000-8000-${it.toString().padStart(12, '0')}") }
                val applier = applier(db)
                val removals =
                    (0 until 11).map { index ->
                        val rid = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
                        podcastRecord(rid, "subscribed" to bool(false))
                    }
                assertEquals(11, applier.applyPage(removals, roundId = now).held)

                val held =
                    MassChangeGuard
                        .evaluate(db, roundId = now, localPodcasts = 50)
                        .filterIsInstance<MassChangeGuard.Verdict.Held>()
                        .singleOrNull()
                assertNotNull(held)
                assertEquals(11, held.batch.podcasts.size)
                assertEquals(50, db.podcastDao().allForExport().size)

                db.withWriteTransaction {
                    db.syncStateDao().withApplying {
                        MassChangeGuard.keepMine(db, held.batch, TestClock(now))
                    }
                }
                val outbox = db.syncOutboxDao().rows()
                assertTrue(outbox.any { it.coll == "podcast" && it.field == "*" })
                assertTrue(outbox.none { it.field == "subscribed" && it.value == "false" })
            } finally {
                db.close()
            }
        }

    @Test
    fun aWinningRemotePlayedMarkOverwritesAnOlderLocalPosition() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val podcastId = seedPodcast(db, podcastSyncId)
                val episodeId =
                    db
                        .ingestDao()
                        .insertEpisodes(listOf(episodeEntity(podcastId = podcastId, identityKey = "g:one")))
                        .single()
                db
                    .episodeStateDao()
                    .ensure(
                        ch.lkmc.neutrodyne.core.database
                            .EpisodeStateEntity(episodeId, updatedAt = 1),
                    )
                db
                    .positionDao()
                    .upsert(
                        ch.lkmc.neutrodyne.core.database.EpisodePositionEntity(
                            episodeId = episodeId,
                            positionMs = 500,
                            positionSource = ch.lkmc.neutrodyne.core.model.PositionSource.STREAM,
                            updatedAt = 1,
                        ),
                    )
                db
                    .syncOutboxDao()
                    .upsert(
                        ch.lkmc.neutrodyne.core.database.SyncOutboxEntity(
                            coll = "episode",
                            rid = "$podcastSyncId" + "g:one",
                            field = "pos",
                            hlc = 1L shl 16,
                            nodeId = node.value,
                            value = "{\"ms\":500}",
                        ),
                    )

                val applier = applier(db)
                applier.applyPage(
                    listOf(
                        episodeRecord(
                            podcastSyncId,
                            "g:one",
                            MatchHints(guid = "one"),
                            "played" to bool(true),
                            "playedAt" to int(9L),
                            "pos" to pos(0, reset = true),
                        ),
                    ),
                    roundId = now,
                )
                val state = db.episodeStateDao().byEpisode(episodeId)
                assertNotNull(state)
                assertEquals(9L, state.playedAt)
                assertEquals(0L, db.positionDao().byEpisode(episodeId)?.positionMs)
            } finally {
                db.close()
            }
        }

    @Test
    fun anAbsentPlayedAtKeepsTheLocalValue() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val podcastId = seedPodcast(db, podcastSyncId)
                val episodeId =
                    db
                        .ingestDao()
                        .insertEpisodes(listOf(episodeEntity(podcastId = podcastId, identityKey = "g:one")))
                        .single()
                db
                    .episodeStateDao()
                    .ensure(
                        ch.lkmc.neutrodyne.core.database
                            .EpisodeStateEntity(episodeId, playedAt = 9L, updatedAt = 1),
                    )

                val applier = applier(db)
                applier.applyPage(
                    listOf(
                        episodeRecord(
                            podcastSyncId,
                            "g:one",
                            MatchHints(guid = "one"),
                            "fav" to bool(true),
                        ),
                    ),
                    roundId = now,
                )
                assertEquals(9L, db.episodeStateDao().byEpisode(episodeId)?.playedAt)
            } finally {
                db.close()
            }
        }

    @Test
    fun anExplicitNullPlayedAtClearsTheLocalValue() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val podcastId = seedPodcast(db, podcastSyncId)
                val episodeId =
                    db
                        .ingestDao()
                        .insertEpisodes(listOf(episodeEntity(podcastId = podcastId, identityKey = "g:one")))
                        .single()
                db
                    .episodeStateDao()
                    .ensure(
                        ch.lkmc.neutrodyne.core.database
                            .EpisodeStateEntity(episodeId, playedAt = 9L, updatedAt = 1),
                    )

                val applier = applier(db)
                applier.applyPage(
                    listOf(
                        episodeRecord(
                            podcastSyncId,
                            "g:one",
                            MatchHints(guid = "one"),
                            "played" to bool(false),
                            "playedAt" to FieldValue(kotlinx.serialization.json.JsonNull, clock(10).toString()),
                        ),
                    ),
                    roundId = now,
                )
                assertNull(db.episodeStateDao().byEpisode(episodeId)?.playedAt)
            } finally {
                db.close()
            }
        }

    @Test
    fun aPodcastOnAnExistingFeedKeyIsInvalidNotAConstraintError() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val feedUrl = "https://example.com/feed/duplicate"
                db
                    .podcastDao()
                    .insertPodcast(
                        podcastEntity(
                            syncId = "00000000-0000-4000-8000-0000000000aa",
                            feedUrl = feedUrl,
                            feedKey =
                                ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
                                    .forIdentity(feedUrl)!!,
                        ),
                    )
                val applier = applier(db)
                val result =
                    applier.applyPage(
                        listOf(
                            podcastRecord(
                                podcastSyncId,
                                "subscribed" to bool(true),
                                "feedUrl" to text(feedUrl),
                                "title" to text("Duplicate feed"),
                            ),
                        ),
                        roundId = now,
                    )
                assertEquals(1, result.invalid)
                assertNull(db.podcastDao().bySyncId(podcastSyncId))
            } finally {
                db.close()
            }
        }

    @Test
    fun aRemotePodcastInsertCarriesYoutubeVariants() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val applier = applier(db)
                val result =
                    applier.applyPage(
                        listOf(
                            podcastRecord(
                                podcastSyncId,
                                "feedUrl" to text("https://example.com/feed"),
                                "title" to text("Example"),
                                "sourceType" to text("YOUTUBE_CHANNEL"),
                                "youtubeVariants" to variants(3, counter = 10),
                            ),
                        ),
                        roundId = now,
                    )
                assertEquals(1, result.applied)
                assertEquals(3, db.podcastDao().bySyncId(podcastSyncId)!!.youtubeVariants)
            } finally {
                db.close()
            }
        }

    @Test
    fun aWinningRemoteYoutubeVariantsOverwritesTheLocalDefaultAndAStaleOneDoesNot() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                seedPodcast(db, podcastSyncId) {
                    copy(sourceType = ch.lkmc.neutrodyne.core.model.SourceType.YOUTUBE_CHANNEL)
                }
                val applier = applier(db)
                applier.applyPage(
                    listOf(podcastRecord(podcastSyncId, "youtubeVariants" to variants(3, counter = 20))),
                    roundId = now,
                )
                assertEquals(3, db.podcastDao().bySyncId(podcastSyncId)!!.youtubeVariants)

                val stale =
                    applier.applyPage(
                        listOf(podcastRecord(podcastSyncId, "youtubeVariants" to variants(2, counter = 5))),
                        roundId = now + 1,
                    )
                assertEquals(1, stale.applied)
                assertEquals(3, db.podcastDao().bySyncId(podcastSyncId)!!.youtubeVariants)
            } finally {
                db.close()
            }
        }

    @Test
    fun aWinningRemotePlayedMarkOnTheLoadedItemFreezesInsideTheTransactionAndNoticesAfterCommit() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val podcastId = seedPodcast(db, podcastSyncId)
                val episodeId =
                    db
                        .ingestDao()
                        .insertEpisodes(
                            listOf(
                                episodeEntity(podcastId = podcastId, identityKey = "g:one") {
                                    copy(title = "Episode one")
                                },
                            ),
                        ).single()
                val playback = FakePlaybackSyncPort()
                playback.setActive(episodeId, isPlaying = true)
                val notices = SyncNoticeBus()
                val coordinator = FakeCredentialCommitCoordinator()
                val received = mutableListOf<SyncNotice>()
                backgroundScope.launch(
                    start = CoroutineStart.UNDISPATCHED,
                ) { notices.notices.collect { received += it } }
                val applier =
                    applier(
                        db,
                        playback = playback,
                        notices = notices,
                        coordinator = coordinator,
                        deviceNames =
                            DeviceNames {
                                // A lookup can need credentials: it must run after the page's
                                // writer committed and its credential hold was released.
                                withTimeout(1_000) {
                                    coordinator.withCredentialCommit { Unit }
                                }
                                listOf(
                                    DeviceDto(
                                        id = deviceId,
                                        name = "Pixel",
                                        platform = "android",
                                        appVersion = null,
                                        lastSeenAt = null,
                                        current = false,
                                        stale = false,
                                    ),
                                )
                            },
                    )

                val result =
                    applier.applyPage(
                        listOf(
                            episodeRecord(
                                podcastSyncId,
                                "g:one",
                                MatchHints(guid = "one"),
                                "played" to bool(true),
                                "playedAt" to int(9L),
                            ),
                        ),
                        roundId = now,
                    )
                runCurrent()

                assertEquals(listOf(episodeId to deviceId), playback.remotePlayed)
                assertEquals(listOf(RemotePlayedMark(episodeId, deviceId)), result.remotePlayed)
                assertEquals(9L, db.episodeStateDao().byEpisode(episodeId)?.playedAt)
                assertEquals(2, coordinator.commits, "page and post-commit name lookup acquire separately")
                assertTrue(playback.remoteReverted.isEmpty(), "a committed mark is never reverted")
                val notice =
                    received
                        .filterIsInstance<SyncNotice.MarkedPlayedElsewhere>()
                        .single()
                assertEquals(episodeId, notice.episodeId)
                assertEquals("Episode one", notice.title)
                assertEquals("Pixel", notice.deviceName, "the device id resolves to its display name")
            } finally {
                db.close()
            }
        }

    @Test
    fun aPlayedMarkOnAnEpisodeThatIsNotLoadedReportsNoFreezeAndNoNotice() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val podcastId = seedPodcast(db, podcastSyncId)
                val episodeId =
                    db
                        .ingestDao()
                        .insertEpisodes(listOf(episodeEntity(podcastId = podcastId, identityKey = "g:one")))
                        .single()
                val playback = FakePlaybackSyncPort()
                playback.setActive(episodeId + 1, isPlaying = true)
                val notices = SyncNoticeBus()
                val received = mutableListOf<SyncNotice>()
                backgroundScope.launch(
                    start = CoroutineStart.UNDISPATCHED,
                ) { notices.notices.collect { received += it } }
                val applier = applier(db, playback = playback, notices = notices)

                val result =
                    applier.applyPage(
                        listOf(
                            episodeRecord(
                                podcastSyncId,
                                "g:one",
                                MatchHints(guid = "one"),
                                "played" to bool(true),
                                "playedAt" to int(9L),
                            ),
                        ),
                        roundId = now,
                    )
                runCurrent()

                assertEquals(9L, db.episodeStateDao().byEpisode(episodeId)?.playedAt, "the mark still applies")
                assertTrue(playback.remotePlayed.isEmpty(), "only the loaded item freezes")
                assertTrue(result.remotePlayed.isEmpty())
                assertTrue(received.isEmpty(), "no notice without a freeze")
            } finally {
                db.close()
            }
        }

    @Test
    fun anOlderPlayedMarkHiddenByANewerPositionNeverFreezesAndNeverNotices() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val podcastId = seedPodcast(db, podcastSyncId)
                val episodeId =
                    db
                        .ingestDao()
                        .insertEpisodes(listOf(episodeEntity(podcastId = podcastId, identityKey = "g:one")))
                        .single()
                val playback = FakePlaybackSyncPort()
                playback.setActive(episodeId, isPlaying = true)
                val notices = SyncNoticeBus()
                val received = mutableListOf<SyncNotice>()
                backgroundScope.launch(
                    start = CoroutineStart.UNDISPATCHED,
                ) { notices.notices.collect { received += it } }
                val applier = applier(db, playback = playback, notices = notices)

                // The `pos` clock (30) is newer than the `played` clock (10): the merged record
                // derives in-progress, so the hidden mark must neither freeze nor notice.
                val result =
                    applier.applyPage(
                        listOf(
                            episodeRecord(
                                podcastSyncId,
                                "g:one",
                                MatchHints(guid = "one"),
                                "played" to bool(true),
                                "playedAt" to int(9L),
                                "pos" to pos(5_000).let { it.copy(c = clock(30).toString()) },
                            ),
                        ),
                        roundId = now,
                    )
                runCurrent()

                assertTrue(playback.remotePlayed.isEmpty(), "a hidden played mark never freezes")
                assertTrue(result.remotePlayed.isEmpty())
                assertTrue(received.isEmpty())
                assertEquals(
                    5_000L,
                    db.positionDao().byEpisode(episodeId)?.positionMs,
                    "the newer listening position won the merge",
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun aRolledBackPageRevertsOnlyItsOwnFreezeAndPostsNoNotice() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val podcastId = seedPodcast(db, podcastSyncId)
                val episodeId =
                    db
                        .ingestDao()
                        .insertEpisodes(listOf(episodeEntity(podcastId = podcastId, identityKey = "g:one")))
                        .single()
                // Page A commits; page B carries a newer mark and fails after installing its freeze.
                val freezes = mutableMapOf<Long, Int>()
                var failOnMark = false
                val playback =
                    object : PlaybackSyncPort {
                        private val _active =
                            MutableStateFlow(
                                PlaybackSyncActive(true, episodeId),
                            )
                        override val active = _active
                        override val events = flowOf<PlaybackSyncEvent>()

                        override suspend fun onRemoteMarkedPlayed(
                            episodeId: Long,
                            deviceName: String,
                        ) {
                            freezes[episodeId] = (freezes[episodeId] ?: 0) + 1
                            check(!failOnMark) { "injected apply failure" }
                        }

                        override suspend fun onRemoteMarkReverted(episodeId: Long) {
                            val count = freezes[episodeId] ?: return
                            if (count <= 1) freezes.remove(episodeId) else freezes[episodeId] = count - 1
                        }
                    }
                val notices = SyncNoticeBus()
                val received = mutableListOf<SyncNotice>()
                backgroundScope.launch(
                    start = CoroutineStart.UNDISPATCHED,
                ) { notices.notices.collect { received += it } }
                val applier = applier(db, playback = playback, notices = notices)

                val committed =
                    applier.applyPage(
                        listOf(
                            episodeRecord(
                                podcastSyncId,
                                "g:one",
                                MatchHints(guid = "one"),
                                "played" to bool(true),
                                "playedAt" to int(9L),
                            ),
                        ),
                        roundId = now,
                    )
                runCurrent()
                assertEquals(1, committed.remotePlayed.size)
                assertEquals(1, received.size)

                failOnMark = true
                val failure =
                    runCatching {
                        applier.applyPage(
                            listOf(
                                episodeRecord(
                                    podcastSyncId,
                                    "g:one",
                                    MatchHints(guid = "one"),
                                    "played" to FieldValue(JsonPrimitive(true), clock(11).toString()),
                                    "playedAt" to FieldValue(JsonPrimitive(10L), clock(11).toString()),
                                ),
                            ),
                            roundId = now + 1,
                        )
                    }
                assertTrue(failure.isFailure, "the injected apply failure propagates")
                assertEquals("injected apply failure", failure.exceptionOrNull()?.message)
                assertEquals(
                    1,
                    freezes[episodeId],
                    "only the rolled-back contribution releases; the committed freeze stays",
                )
                assertEquals(1, received.size, "a failed page posts no further notice")
                assertEquals(9L, db.episodeStateDao().byEpisode(episodeId)?.playedAt, "page B rolled back")
            } finally {
                db.close()
            }
        }

    @Test
    fun aFavouriteUpdateDoesNotRepeatAnEarlierPlayedMark() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val podcastId = seedPodcast(db, podcastSyncId)
                val episodeId =
                    db
                        .ingestDao()
                        .insertEpisodes(
                            listOf(episodeEntity(podcastId = podcastId, identityKey = "g:one")),
                        ).single()
                val playback = FakePlaybackSyncPort()
                val applier = applier(db, playback = playback)
                playback.setActive(episodeId, isPlaying = true)
                applier.applyPage(
                    listOf(
                        episodeRecord(
                            podcastSyncId,
                            "g:one",
                            MatchHints(guid = "one"),
                            "played" to bool(true),
                            "playedAt" to int(9L),
                        ),
                    ),
                    roundId = now,
                )
                val result =
                    applier.applyPage(
                        listOf(
                            episodeRecord(
                                podcastSyncId,
                                "g:one",
                                MatchHints(guid = "one"),
                                "fav" to bool(true),
                            ).copy(by = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                        ),
                        roundId = now + 1,
                    )
                assertEquals(1, result.applied, "the favourite really changed")
                assertTrue(db.episodeStateDao().byEpisode(episodeId)?.isFavorite == true)
                assertEquals(
                    listOf(episodeId to deviceId),
                    playback.remotePlayed,
                    "an unrelated field update must not repeat an earlier played mark",
                )
                assertTrue(result.remotePlayed.isEmpty())
            } finally {
                db.close()
            }
        }

    @Test
    fun aNewerPlayedMarkStillReportsAWinningMark() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val podcastId = seedPodcast(db, podcastSyncId)
                val episodeId =
                    db
                        .ingestDao()
                        .insertEpisodes(
                            listOf(episodeEntity(podcastId = podcastId, identityKey = "g:one")),
                        ).single()
                val playback = FakePlaybackSyncPort()
                playback.setActive(episodeId, isPlaying = true)
                val applier = applier(db, playback = playback)
                applier.applyPage(
                    listOf(
                        episodeRecord(
                            podcastSyncId,
                            "g:one",
                            MatchHints(guid = "one"),
                            "played" to bool(true),
                            "playedAt" to int(9L),
                        ),
                    ),
                    roundId = now,
                )
                val result =
                    applier.applyPage(
                        listOf(
                            episodeRecord(
                                podcastSyncId,
                                "g:one",
                                MatchHints(guid = "one"),
                                "played" to FieldValue(JsonPrimitive(true), clock(11).toString()),
                                "playedAt" to FieldValue(JsonPrimitive(10L), clock(11).toString()),
                            ),
                        ),
                        roundId = now + 1,
                    )
                assertEquals(1, result.remotePlayed.size)
                assertEquals(2, playback.remotePlayed.size)
            } finally {
                db.close()
            }
        }

    private fun applier(
        db: NeutrodyneDatabase,
        playback: PlaybackSyncPort = NoPlaybackSync,
        notices: SyncNoticeBus = SyncNoticeBus(),
        deviceNames: DeviceNames = DeviceNames { emptyList() },
        coordinator: CredentialCommitCoordinator = FakeCredentialCommitCoordinator(),
    ): SyncApplier {
        val settings = FakeSettingsSyncPort()
        return SyncApplier(
            db = db,
            clock = TestClock(now),
            secrets = FakeSecretStore(),
            settings = settings,
            playback = playback,
            syncIngestHook = SyncIngestHook.None,
            channelSync =
                object : GroupChannelSync {
                    override suspend fun sync() = Unit

                    override suspend fun onUuidChanged(
                        oldUuid: String,
                        newUuid: String,
                    ) = Unit

                    override suspend fun onDeleted(uuid: String) = Unit
                },
            notices = notices,
            deviceNames = deviceNames,
            sharePasswords = { false },
            coordinator = coordinator,
        )
    }

    private suspend fun link(db: NeutrodyneDatabase) {
        db.withWriteTransaction {
            db
                .syncStateDao()
                .upsert(SyncStateEntity(enabled = true, nodeId = node.value, hlc = 1L shl 16))
        }
    }

    private suspend fun seedPodcast(
        db: NeutrodyneDatabase,
        syncId: String,
        block: PodcastEntity.() -> PodcastEntity = { this },
    ): Long =
        db
            .podcastDao()
            .insertPodcast(
                podcastEntity(
                    syncId = syncId,
                    feedUrl = "https://example.com/$syncId",
                    feedKey = "https://example.com/$syncId",
                    block = block,
                ),
            )

    private fun podcastRecord(
        rid: String,
        vararg fields: Pair<String, FieldValue>,
    ): RecordDto = record("podcast", JsonPrimitive(rid), null, *fields)

    private fun episodeRecord(
        podcast: String,
        key: String,
        match: MatchHints?,
        vararg fields: Pair<String, FieldValue>,
    ): RecordDto {
        val id =
            buildJsonObject {
                put("p", podcast)
                put("k", key)
            }
        return record("episode", id, match, *fields)
    }

    private fun record(
        coll: String,
        id: JsonElement,
        match: MatchHints?,
        vararg fields: Pair<String, FieldValue>,
    ): RecordDto =
        RecordDto(
            coll = coll,
            id = id,
            seq = 1,
            by = deviceId,
            fields = fields.toMap(),
            match = match,
            matchClocks = match?.let { mapOf("guid" to clock(10).toString()) } ?: emptyMap(),
        )

    private fun bool(value: Boolean): FieldValue = FieldValue(JsonPrimitive(value), clock(10).toString())

    private fun text(value: String): FieldValue = FieldValue(JsonPrimitive(value), clock(10).toString())

    private fun int(value: Long): FieldValue = FieldValue(JsonPrimitive(value), clock(10).toString())

    private fun variants(
        value: Int,
        counter: Long,
    ): FieldValue = FieldValue(JsonPrimitive(value.toLong()), clock(counter).toString())

    private fun pos(
        ms: Long,
        reset: Boolean = false,
    ): FieldValue = FieldValue(JsonObject(buildMap { put("ms", JsonPrimitive(ms)) }), clock(10).toString(), reset)

    private fun clock(counter: Long): Hlc = Hlc.of(now, counter, remote)
}
