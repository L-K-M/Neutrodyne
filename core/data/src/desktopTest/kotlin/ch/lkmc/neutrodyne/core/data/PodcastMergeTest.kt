// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.data.repo.PodcastMerger
import ch.lkmc.neutrodyne.core.data.repo.PodcastRepositoryImpl
import ch.lkmc.neutrodyne.core.database.CredentialEntity
import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.database.EpisodeEntity
import ch.lkmc.neutrodyne.core.database.EpisodePositionEntity
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.database.ImportItemEntity
import ch.lkmc.neutrodyne.core.database.ImportSessionEntity
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupMemberEntity
import ch.lkmc.neutrodyne.core.database.PodcastSettingsEntity
import ch.lkmc.neutrodyne.core.database.PodcastUrlAliasEntity
import ch.lkmc.neutrodyne.core.database.QueueEntryEntity
import ch.lkmc.neutrodyne.core.database.ScopeOverrides
import ch.lkmc.neutrodyne.core.domain.CredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.ChangeOrigin
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.GuidKnowledge
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportItemKind
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.MemberSource
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.SourceKind
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.feeds.jvm.html.JsoupShowNotesSanitizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real Room transactions on TestDb's bundled SQLite driver, including injected SQL failures. */
class PodcastMergeTest {
    private val clock = TestClock(NOW)
    private val db = newDb(clock)
    private val coordinator = FakeCredentialCommitCoordinator()
    private val merger = PodcastMerger(db, coordinator, clock)

    @AfterTest
    fun close() = db.close()

    @Test
    fun membershipsAliasesAndWinnerPreferencesSurvive() =
        runTest {
            val loser = podcast("loser") { copy(customTitle = "Losing title", includeInAll = true) }
            val winner = podcast("winner") { copy(customTitle = "Winning title", includeInAll = false) }
            val shared = group("shared")
            val onlyLoser = group("loser")
            val onlyWinner = group("winner")
            val losingMember = PodcastGroupMemberEntity(onlyLoser, loser, "a1", 10, MemberSource.RULE)
            val winningMember = PodcastGroupMemberEntity(shared, winner, "a2", 20)
            db.groupDao().insertMember(losingMember)
            db.groupDao().insertMember(winningMember)
            db.groupDao().insertMember(PodcastGroupMemberEntity(shared, loser, "a0", 5))
            db.groupDao().insertMember(PodcastGroupMemberEntity(onlyWinner, winner, "a3", 30))
            val losingAlias = PodcastUrlAliasEntity("old.example/loser", loser, AliasReason.REDIRECT, 1)
            val winningAlias = PodcastUrlAliasEntity("old.example/winner", winner, AliasReason.NEW_FEED_URL, 2)
            db.podcastDao().insertAlias(losingAlias)
            db.podcastDao().insertAlias(winningAlias)
            // A stale alias equal to the winner's key must not survive the merge.
            db.podcastDao().insertAlias(PodcastUrlAliasEntity("feeds.example/winner", loser, AliasReason.MERGE, 3))
            val settings = PodcastSettingsEntity(winner, ScopeOverrides(playbackSpeed = 1.5f))
            db.scopeSettingsDao().upsertPodcast(settings)
            db.scopeSettingsDao().upsertPodcast(PodcastSettingsEntity(loser, ScopeOverrides(skipSilence = true)))

            merge(loser, winner)

            assertEquals(listOf(losingMember.copy(podcastId = winner)), db.groupDao().membersOf(onlyLoser))
            assertEquals(listOf(winningMember), db.groupDao().membersOf(shared))
            assertEquals(
                winner,
                db
                    .groupDao()
                    .membersOf(onlyWinner)
                    .single()
                    .podcastId,
            )
            val aliases = db.podcastDao().observeAliases(winner).first()
            assertEquals(
                setOf("old.example/loser", "old.example/winner", "feeds.example/loser"),
                aliases.map { it.url }.toSet(),
            )
            assertEquals(losingAlias.copy(podcastId = winner), aliases.single { it.url == losingAlias.url })
            assertEquals(winningAlias, aliases.single { it.url == winningAlias.url })
            assertEquals(AliasReason.MERGE, aliases.single { it.url == "feeds.example/loser" }.reason)
            assertEquals(settings, db.scopeSettingsDao().forPodcast(winner))
            val surviving = assertNotNull(db.podcastDao().byId(winner))
            assertEquals("Winning title", surviving.customTitle)
            assertFalse(surviving.includeInAll)
            assertEquals("winner", surviving.title)
            assertNull(db.podcastDao().byId(loser))
            assertEquals(1, coordinator.commits)
        }

    @Test
    fun missingSettingsAndTitleAreCopiedAndBothOldUrlKeysRemain() =
        runTest {
            val loser = podcast("loser") { copy(feedKey = "legacy.example/loser", customTitle = "Custom") }
            val winner = podcast("winner")
            val overrides =
                ScopeOverrides(
                    playbackSpeed = 1.25f,
                    skipSilence = true,
                    boostDb = 2f,
                    introSkipMs = 100,
                    outroSkipMs = 200,
                    autoDownload = true,
                    autoDownloadKeepLatest = 4,
                    autoDownloadNetwork = ch.lkmc.neutrodyne.core.model.NetworkPolicy.UNMETERED,
                    autoDownloadRequireCharging = true,
                    deleteAfterPlayed = ch.lkmc.neutrodyne.core.model.DeleteAfter.AFTER_24H,
                    includeVideoInAutoDownload = true,
                    notifyNewEpisodes = true,
                    refreshIntervalMinutes = 240,
                )
            db.scopeSettingsDao().upsertPodcast(PodcastSettingsEntity(loser, overrides))

            merge(loser, winner)

            assertEquals(PodcastSettingsEntity(winner, overrides), db.scopeSettingsDao().forPodcast(winner))
            assertEquals("Custom", db.podcastDao().byId(winner)!!.customTitle)
            assertEquals(
                setOf("legacy.example/loser", "feeds.example/loser"),
                db
                    .podcastDao()
                    .observeAliases(winner)
                    .first()
                    .map { it.url }
                    .toSet(),
            )
        }

    @Test
    fun matchedStateUnionsMarksAndKeepsTheNewerPosition() =
        runTest {
            val loser = podcast("loser")
            val winner = podcast("winner")
            val l = episode(loser, "g:one")
            val w = episode(winner, "g:one")
            db.episodeStateDao().upsert(
                EpisodeStateEntity(
                    l,
                    startedAt = 5,
                    playedAt = 100,
                    playCount = 3,
                    lastPlayedAt = 130,
                    isFavorite = true,
                    downloadDismissedAt = 140,
                    measuredDurationMs = 50_000,
                    updatedAt = 150,
                ),
            )
            db.episodeStateDao().upsert(
                EpisodeStateEntity(
                    w,
                    startedAt = 10,
                    playedAt = 90,
                    playCount = 2,
                    lastPlayedAt = 160,
                    downloadDismissedAt = 120,
                    measuredDurationMs = 60_000,
                    updatedAt = 170,
                ),
            )
            db.positionDao().upsert(EpisodePositionEntity(l, 2_000, 55_000, PositionSource.DOWNLOAD, 200))
            db.positionDao().upsert(EpisodePositionEntity(w, 1_000, 65_000, PositionSource.STREAM, 180))

            merge(loser, winner)

            assertEquals(
                EpisodeStateEntity(
                    w,
                    playedAt = 100,
                    playCount = 3,
                    lastPlayedAt = 160,
                    isFavorite = true,
                    downloadDismissedAt = 140,
                    measuredDurationMs = 60_000,
                    updatedAt = 170,
                ),
                db.episodeStateDao().byEpisode(w),
            )
            assertEquals(
                EpisodePositionEntity(w, 2_000, 55_000, PositionSource.DOWNLOAD, 200),
                db.positionDao().byEpisode(w),
            )
            assertNull(db.episodeDao().byId(l))
        }

    @Test
    fun identicalStateDoesNotTriggerARepeatedWrite() =
        runTest {
            val loser = podcast("loser")
            val winner = podcast("winner")
            val l = episode(loser, "g:one")
            val w = episode(winner, "g:one")
            val state = EpisodeStateEntity(w, isFavorite = true, updatedAt = 10)
            db.episodeStateDao().upsert(state)
            db.episodeStateDao().upsert(state.copy(episodeId = l))
            sql(
                "CREATE TRIGGER fail_identical_state BEFORE UPDATE ON episode_state" +
                    " WHEN old.episodeId = $w AND new.startedAt IS old.startedAt" +
                    " AND new.playedAt IS old.playedAt AND new.playCount IS old.playCount" +
                    " AND new.lastPlayedAt IS old.lastPlayedAt AND new.isFavorite IS old.isFavorite" +
                    " AND new.downloadDismissedAt IS old.downloadDismissedAt" +
                    " AND new.measuredDurationMs IS old.measuredDurationMs AND new.updatedAt IS old.updatedAt" +
                    " BEGIN SELECT RAISE(ABORT, 'identical state write'); END",
            )

            merge(loser, winner)

            assertEquals(state, db.episodeStateDao().byEpisode(w))
        }

    @Test
    fun stateAndPositionCanBeCopiedIntoAnEmptyWinner() =
        runTest {
            val loser = podcast("loser")
            val winner = podcast("winner")
            val l = episode(loser, "g:one")
            val w = episode(winner, "g:one")
            val state =
                EpisodeStateEntity(
                    l,
                    startedAt = 7,
                    isFavorite = true,
                    downloadDismissedAt = 9,
                    measuredDurationMs = 40_000,
                    updatedAt = 10,
                )
            val position = EpisodePositionEntity(l, 500, 40_000, PositionSource.DOWNLOAD, 11)
            db.episodeStateDao().upsert(state)
            db.positionDao().upsert(position)

            merge(loser, winner)

            assertEquals(state.copy(episodeId = w), db.episodeStateDao().byEpisode(w))
            assertEquals(position.copy(episodeId = w), db.positionDao().byEpisode(w))
        }

    @Test
    fun positionRulesPreserveProgressResetOlderProgressAndDeriveStartedAt() =
        runTest {
            val loser = podcast("loser")
            val winner = podcast("winner")
            val pairs = (0..4).map { episode(loser, "g:$it") to episode(winner, "g:$it") }
            // Newer zero and older non-zero positions cannot replace winner progress; ties keep it.
            for ((index, times) in listOf(200L to 100L, 50L to 100L, 100L to 100L).withIndex()) {
                val (l, w) = pairs[index]
                db.positionDao().upsert(
                    EpisodePositionEntity(
                        l,
                        if (index == 0) 0 else 999,
                        positionSource = PositionSource.DOWNLOAD,
                        updatedAt = times.first,
                    ),
                )
                db.positionDao().upsert(
                    EpisodePositionEntity(
                        w,
                        123,
                        positionSource = PositionSource.STREAM,
                        updatedAt = times.second,
                    ),
                )
            }
            val (playedLoser, playedWinner) = pairs[3]
            db.episodeStateDao().upsert(EpisodeStateEntity(playedLoser, playedAt = 300, updatedAt = 300))
            db.positionDao().upsert(
                EpisodePositionEntity(
                    playedWinner,
                    123,
                    positionSource = PositionSource.STREAM,
                    updatedAt = 200,
                ),
            )
            val (progressLoser, progressWinner) = pairs[4]
            db.positionDao().upsert(
                EpisodePositionEntity(
                    progressLoser,
                    999,
                    positionSource = PositionSource.STREAM,
                    updatedAt = 222,
                ),
            )

            merge(loser, winner)

            for ((_, w) in pairs.take(3)) {
                assertEquals(123L, db.positionDao().byEpisode(w)!!.positionMs)
                assertEquals(100L, db.episodeStateDao().byEpisode(w)!!.startedAt)
            }
            assertEquals(0L, db.positionDao().byEpisode(playedWinner)!!.positionMs)
            assertEquals(300L, db.positionDao().byEpisode(playedWinner)!!.updatedAt)
            assertNull(db.episodeStateDao().byEpisode(playedWinner)!!.startedAt)
            assertEquals(
                EpisodeStateEntity(progressWinner, startedAt = 222, updatedAt = 222),
                db.episodeStateDao().byEpisode(progressWinner),
            )
        }

    @Test
    fun earliestStartAndExistingWinnerOnlyStateSurvive() =
        runTest {
            val loser = podcast("loser")
            val winner = podcast("winner")
            val l = episode(loser, "g:one")
            val w = episode(winner, "g:one")
            val onlyW = episode(winner, "g:two")
            episode(loser, "g:two")
            val winnerOnly = EpisodeStateEntity(onlyW, startedAt = 2, isFavorite = true, updatedAt = 50)
            db.episodeStateDao().upsert(winnerOnly)
            db.episodeStateDao().upsert(
                EpisodeStateEntity(l, startedAt = 3, measuredDurationMs = 30_000, updatedAt = 100),
            )
            db.episodeStateDao().upsert(EpisodeStateEntity(w, startedAt = 6, updatedAt = 50))

            merge(loser, winner)

            assertEquals(
                EpisodeStateEntity(w, startedAt = 3, measuredDurationMs = 30_000, updatedAt = 100),
                db.episodeStateDao().byEpisode(w),
            )
            assertEquals(winnerOnly, db.episodeStateDao().byEpisode(onlyW))
        }

    @Test
    fun referencesMoveAndDuplicateQueueAndDownloadRowsPreferWinner() =
        runTest {
            val loser = podcast("loser")
            val winner = podcast("winner")
            val l1 = episode(loser, "g:one")
            val w1 = episode(winner, "g:one")
            val l2 = episode(loser, "g:two")
            val w2 = episode(winner, "g:two")
            val movedQueue = QueueEntryEntity(episodeId = l1, orderKey = "a0", addedAt = 1)
            val movedQueueId = db.queueDao().insert(movedQueue)
            db.queueDao().insert(QueueEntryEntity(episodeId = l2, orderKey = "a1", addedAt = 2))
            val winnerQueue = QueueEntryEntity(episodeId = w2, orderKey = "a2", addedAt = 3)
            val winnerQueueId = db.queueDao().insert(winnerQueue)
            val movedDownload = download(l1, "loser-one.mp3")
            val winnerDownload = download(w2, "winner-two.mp3")
            db.downloadDao().insert(movedDownload)
            db.downloadDao().insert(download(l2, "loser-two.mp3"))
            db.downloadDao().insert(winnerDownload)
            sql(
                "UPDATE play_session SET currentEpisodeId = $l1, contextType = 'PODCAST', contextId =" +
                    " $loser, generation = 7 WHERE id = 0",
            )
            val session = db.playSessionDao().get()!!
            val importSession =
                db.importDao().insertSession(
                    ImportSessionEntity(
                        createdAt = 10,
                        sourceFormat = ImportFormat.OPML,
                        state = ImportState.DONE,
                    ),
                )
            val item =
                ImportItemEntity(
                    importSession,
                    0,
                    originalUrl = "https://feeds.example/loser",
                    kind = ImportItemKind.RSS,
                    groupNamesJson = "[]",
                    selected = true,
                    status = ImportItemStatus.SUBSCRIBED,
                    podcastId = loser,
                )
            db.importDao().insertItems(listOf(item))

            merge(loser, winner)

            assertEquals(
                listOf(
                    movedQueue.copy(id = movedQueueId, episodeId = w1),
                    winnerQueue.copy(id = winnerQueueId),
                ),
                db.queueDao().entries(),
            )
            assertEquals(movedDownload.copy(episodeId = w1), db.downloadDao().byEpisode(w1))
            assertEquals(winnerDownload, db.downloadDao().byEpisode(w2))
            assertNull(db.downloadDao().byEpisode(l2))
            assertEquals(session.copy(currentEpisodeId = w1, contextId = winner), db.playSessionDao().get())
            assertEquals(listOf(item.copy(podcastId = winner)), db.importDao().itemsOf(importSession))
        }

    @Test
    fun identityMatchesBeforeNormalisedUrlAndUnmatchedEpisodesKeepAllChildren() =
        runTest {
            val loser = podcast("loser")
            val winner = podcast("winner")
            val byKey = episode(winner, "g:key", "https://cdn.example/key.mp3")
            val byUrl = episode(winner, "g:url", "https://cdn.example/shared.mp3")
            val lKey = episode(loser, "g:key", "http://CDN.example:80/shared.mp3#ignored")
            val lUrl = episode(loser, "g:rewritten", "http://CDN.example:80/shared.mp3/#ignored")
            val unmatched = episode(loser, "g:unmatched", "https://cdn.example/unique.mp3")
            db.episodeStateDao().upsert(EpisodeStateEntity(lKey, isFavorite = true, updatedAt = 1))
            db.episodeStateDao().upsert(EpisodeStateEntity(lUrl, playedAt = 2, updatedAt = 2))
            val state =
                EpisodeStateEntity(
                    unmatched,
                    startedAt = 3,
                    isFavorite = true,
                    downloadDismissedAt = 4,
                    updatedAt = 5,
                )
            val position =
                EpisodePositionEntity(unmatched, 100, positionSource = PositionSource.DOWNLOAD, updatedAt = 6)
            db.episodeStateDao().upsert(state)
            db.positionDao().upsert(position)
            db.downloadDao().insert(download(unmatched, "unmatched.mp3"))
            db.queueDao().insert(QueueEntryEntity(episodeId = unmatched, orderKey = "a0", addedAt = 7))
            sql(
                "UPDATE play_session SET currentEpisodeId = $unmatched, contextType = 'PODCAST'," +
                    " contextId = $loser, contextAnchorEpisodeId = $unmatched WHERE id = 0",
            )
            seedEpisodeChildren(unmatched)
            val childrenBefore = snapshot(CHILD_TABLES)
            val oldEpisode = db.episodeDao().byId(unmatched)!!

            merge(loser, winner)

            assertTrue(db.episodeStateDao().byEpisode(byKey)!!.isFavorite)
            assertNull(db.episodeStateDao().byEpisode(byKey)!!.playedAt)
            assertEquals(2L, db.episodeStateDao().byEpisode(byUrl)!!.playedAt)
            assertEquals(oldEpisode.copy(podcastId = winner, inFeed = false), db.episodeDao().byId(unmatched))
            assertEquals(state, db.episodeStateDao().byEpisode(unmatched))
            assertEquals(position, db.positionDao().byEpisode(unmatched))
            assertEquals(download(unmatched, "unmatched.mp3"), db.downloadDao().byEpisode(unmatched))
            assertEquals(
                unmatched,
                db
                    .queueDao()
                    .entries()
                    .single()
                    .episodeId,
            )
            assertEquals(unmatched, db.playSessionDao().get()!!.currentEpisodeId)
            assertEquals(unmatched, db.playSessionDao().get()!!.contextAnchorEpisodeId)
            assertEquals(winner, db.playSessionDao().get()!!.contextId)
            assertEquals(childrenBefore, snapshot(CHILD_TABLES))
        }

    @Test
    fun provenanceUnionIsStickyAndNeverWidensWinnerCoverage() =
        runTest {
            for (coverage in listOf(null, 10L)) {
                val loser = podcast("loser-$coverage")
                val winner = podcast("winner-$coverage") { copy(guidCoverageSince = coverage) }
                db.ingestDao().recordGuidKnowledge(
                    winner,
                    setOf("w-amb"),
                    setOf("l-amb", "w-independent"),
                    setOf("w-observed"),
                )
                db.ingestDao().recordGuidKnowledge(
                    loser,
                    setOf("l-amb", "only-l-amb"),
                    setOf(
                        "w-amb",
                        "only-l-independent",
                        "w-observed",
                    ),
                    setOf("only-l-observed", "w-independent"),
                )

                merge(loser, winner)

                assertEquals(
                    mapOf(
                        "w-amb" to GuidKnowledge.KNOWN_AMBIGUOUS,
                        "l-amb" to GuidKnowledge.KNOWN_AMBIGUOUS,
                        "only-l-amb" to GuidKnowledge.KNOWN_AMBIGUOUS,
                        "w-independent" to GuidKnowledge.KNOWN_INDEPENDENT,
                        "only-l-independent" to GuidKnowledge.KNOWN_INDEPENDENT,
                    ),
                    db.ingestDao().guidKnowledge(winner),
                )
                assertEquals(
                    setOf(
                        "w-amb",
                        "l-amb",
                        "only-l-amb",
                        "w-independent",
                        "only-l-independent",
                        "w-observed",
                        "only-l-observed",
                    ),
                    db.ingestDao().recordedGuids(winner).toSet(),
                )
                assertEquals(coverage, db.podcastDao().byId(winner)!!.guidCoverageSince)
                assertTrue(db.ingestDao().recordedGuids(loser).isEmpty())
            }
        }

    @Test
    fun aFailureAtLoserDeletionRollsBackEveryRelationAndCredential() =
        runTest {
            val loser = podcast("loser")
            val winner = podcast("winner")
            val l = episode(loser, "g:one")
            val w = episode(winner, "g:one")
            val unmatched = episode(loser, "g:unmatched")
            db.groupDao().insertMember(PodcastGroupMemberEntity(group("group"), loser, "a0", 1))
            db.podcastDao().insertAlias(PodcastUrlAliasEntity("old.example/feed", loser, AliasReason.REDIRECT, 1))
            db.scopeSettingsDao().upsertPodcast(PodcastSettingsEntity(loser, ScopeOverrides(playbackSpeed = 1.5f)))
            db.podcastDao().setCustomTitle(loser, "Title")
            db.episodeStateDao().upsert(EpisodeStateEntity(l, isFavorite = true, updatedAt = 1))
            db.positionDao().upsert(
                EpisodePositionEntity(l, 999, positionSource = PositionSource.DOWNLOAD, updatedAt = 2),
            )
            db.queueDao().insert(QueueEntryEntity(episodeId = l, orderKey = "a0", addedAt = 3))
            db.downloadDao().insert(download(l, "one.mp3"))
            db.downloadDao().insert(download(unmatched, "unmatched.mp3"))
            seedEpisodeChildren(l)
            val credential = credential("https://loser.example")
            db.podcastDao().setCredentialId(loser, credential)
            db.ingestDao().recordGuidKnowledge(loser, setOf("sticky"), emptySet(), emptySet())
            sql(
                "UPDATE play_session SET currentEpisodeId = $l, contextType = 'PODCAST', contextId =" +
                    " $loser WHERE id = 0",
            )
            val session =
                db.importDao().insertSession(
                    ImportSessionEntity(
                        createdAt = 1,
                        sourceFormat = ImportFormat.OPML,
                        state = ImportState.DONE,
                    ),
                )
            db.importDao().insertItems(
                listOf(
                    ImportItemEntity(
                        session,
                        0,
                        originalUrl = "https://feeds.example/loser",
                        kind = ImportItemKind.RSS,
                        groupNamesJson = "[]",
                        selected = true,
                        status = ImportItemStatus.SUBSCRIBED,
                        podcastId = loser,
                    ),
                ),
            )
            val before = snapshot(MERGE_TABLES)
            // Cascade bookkeeping and all merge writes have run by the time this trigger aborts.
            sql(
                "CREATE TRIGGER fail_merge BEFORE DELETE ON podcast WHEN old.id = $loser BEGIN SELECT" +
                    " RAISE(ABORT, 'merge fault'); END",
            )

            assertFailsWith<androidx.sqlite.SQLiteException> { merge(loser, winner) }

            assertEquals(before, snapshot(MERGE_TABLES))
            assertNotNull(db.episodeDao().byId(w))
        }

    @Test
    fun sharedCredentialsSurviveAndLoserOnlyLastReferencesAreDeleted() =
        runTest {
            for (origin in listOf(
                "https://shared.example",
                "https://only-loser.example",
                "podcastindex",
                "sync:example",
            )) {
                val loser = podcast("loser-$origin")
                val winner = podcast("winner-$origin")
                val id = credential(origin)
                db.podcastDao().setCredentialId(loser, id)
                if (origin == "https://shared.example") db.podcastDao().setCredentialId(winner, id)

                merge(loser, winner)

                if (origin == "https://only-loser.example") {
                    assertNull(db.credentialDao().byOrigin(origin).singleOrNull())
                } else {
                    assertNotNull(db.credentialDao().byOrigin(origin).singleOrNull())
                }
                if (origin == "https://shared.example") assertEquals(id, db.podcastDao().byId(winner)!!.credentialId)
            }
        }

    @Test
    fun matchingIsRecheckedAfterWaitingForCredentialCoordination() =
        runTest {
            val loser = podcast("loser")
            val winner = podcast("winner")
            val l = episode(loser, "g:one")
            db.episodeStateDao().upsert(EpisodeStateEntity(l, isFavorite = true, updatedAt = 1))
            val entered = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            val gate =
                object : CredentialCommitCoordinator {
                    override suspend fun <T> withCredentialCommit(block: suspend () -> T): T {
                        entered.complete(Unit)
                        proceed.await()
                        return coordinator.withCredentialCommit(block)
                    }
                }
            val pending =
                async(Dispatchers.Default) {
                    PodcastMerger(db, gate, clock).merge(
                        loser,
                        winner,
                        ChangeOrigin.LOCAL,
                    )
                }
            entered.await()
            // This write completing while the merge waits also proves it has not acquired the writer.
            val w = episode(winner, "g:one")
            proceed.complete(Unit)
            pending.await()

            assertTrue(db.episodeStateDao().byEpisode(w)!!.isFavorite)
            assertNull(db.episodeDao().byId(l))
            assertEquals(1, db.ingestDao().existing(winner).size)
        }

    @Test
    fun repositoryDelegatesAndSelfMergeOrMissingPodcastsAreRejected() =
        runTest {
            val loser = podcast("loser")
            val winner = podcast("winner")
            val before = snapshot(MERGE_TABLES)
            assertFailsWith<IllegalArgumentException> { merge(winner, winner) }
            assertFailsWith<IllegalArgumentException> { merge(loser, Long.MAX_VALUE) }
            assertFailsWith<IllegalArgumentException> { merge(Long.MAX_VALUE, winner) }
            assertEquals(before, snapshot(MERGE_TABLES))
            val repository =
                PodcastRepositoryImpl(
                    db,
                    newRefresher(db, emptyMap(), clock),
                    FakeRefreshScheduler(),
                    JsoupShowNotesSanitizer(),
                    clock,
                    merger,
                )

            repository.merge(loser, winner, ChangeOrigin.SYNC)

            assertNull(db.podcastDao().byId(loser))
            assertNotNull(db.podcastDao().byId(winner))
        }

    private suspend fun merge(
        loser: Long,
        winner: Long,
    ) = merger.merge(loser, winner, ChangeOrigin.LOCAL)

    private suspend fun podcast(
        name: String,
        block: PodcastEntity.() -> PodcastEntity = { this },
    ): Long =
        seedPodcast(db, "https://feeds.example/$name", feedKey = "feeds.example/$name", title = name, block = block)

    private suspend fun episode(
        podcastId: Long,
        key: String,
        url: String? = null,
    ): Long =
        db.episodeDao().insertStub(
            EpisodeEntity(
                podcastId = podcastId,
                identityKey = key,
                title = key,
                sortDate = NOW,
                feedOrder = 0,
                firstSeenAt = NOW,
                lastSeenAt = NOW,
                enclosureUrl = url,
                contentHash = 1,
            ),
        )

    private suspend fun group(name: String): Long =
        db.groupDao().insert(
            PodcastGroupEntity(
                uuid = "group-$name",
                name = name,
                nameKey = name,
                orderKey = "a0",
                createdAt = 1,
                updatedAt = 1,
            ),
        )

    private fun download(
        episodeId: Long,
        path: String,
    ) = DownloadEntity(
        episodeId = episodeId,
        lane = DownloadLane.MANUAL,
        state = DownloadState.COMPLETED,
        priority = 100,
        requestedAt = 1,
        sourceKind = SourceKind.RSS_ENCLOSURE,
        sourceRef = "https://cdn.example/$path",
        rootId = "int",
        relativePath = path,
        allowMetered = false,
    )

    private suspend fun credential(origin: String): Long =
        db.credentialDao().putByOrigin(
            CredentialEntity(origin = origin, username = "user", secretCipher = null, iv = null, createdAt = NOW),
        )

    private suspend fun sql(statement: String) = db.useWriterConnection { it.execSql(statement) }

    private suspend fun seedEpisodeChildren(id: Long) {
        sql("INSERT INTO episode_description(episodeId, html) VALUES ($id, X'0102')")
        sql(
            "INSERT INTO episode_transcript(episodeId, url, type) VALUES ($id," +
                " 'https://cdn.example/transcript', 'text/plain')",
        )
        sql(
            "INSERT INTO episode_alt_enclosure(episodeId, ordinal, sourcesJson, type) VALUES" +
                " ($id, 0, '[]', 'audio/mpeg')",
        )
        sql(
            "INSERT INTO chapter(episodeId, source, ordinal, startMs, title) VALUES ($id, 'PSC'," +
                " 0, 0, 'Chapter')",
        )
        sql("INSERT INTO person(ownerType, ownerId, name) VALUES ('EPISODE', $id, 'Host')")
        sql(
            "INSERT INTO funding(ownerType, ownerId, url) VALUES ('EPISODE', $id," +
                " 'https://example.com/fund')",
        )
    }

    private suspend fun snapshot(tables: List<String>): Map<
        String,
        List<List<String>>,
    > =
        db.useReaderConnection { conn ->
            tables.associateWith { table ->
                val columns =
                    conn.usePrepared("PRAGMA table_info($table)") { stmt ->
                        buildList { while (stmt.step()) add(stmt.getText(1)) }
                    }
                val projection = columns.joinToString { "quote($it)" }
                val order = columns.joinToString()
                conn.usePrepared("SELECT $projection FROM $table ORDER BY $order") { stmt ->
                    buildList { while (stmt.step()) add(columns.indices.map { stmt.getText(it) }) }
                }
            }
        }

    private companion object {
        const val NOW = 1_791_000_000_000L
        val CHILD_TABLES =
            listOf(
                "episode_description",
                "episode_transcript",
                "episode_alt_enclosure",
                "chapter",
                "person",
                "funding",
            )
        val MERGE_TABLES =
            listOf(
                "podcast",
                "episode",
                "podcast_group_member",
                "podcast_url_alias",
                "podcast_settings",
                "episode_state",
                "episode_position",
                "queue_entry",
                "download",
                "play_session",
                "import_item",
                "credential",
                "episode_guid_provenance",
            ) + CHILD_TABLES
    }
}
