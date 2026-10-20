// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.database.EpisodeEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupSettingsEntity
import ch.lkmc.neutrodyne.core.database.ScopeOverrides
import ch.lkmc.neutrodyne.core.datastore.SettingStore
import ch.lkmc.neutrodyne.core.domain.EffectiveSettingsResolver
import ch.lkmc.neutrodyne.core.model.DeleteAfter
import ch.lkmc.neutrodyne.core.model.EffectiveAutoDownload
import ch.lkmc.neutrodyne.core.model.NetworkPolicy
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.model.SourceKind
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.testing.FakeEffectiveSettingsResolver
import ch.lkmc.neutrodyne.core.testing.FakePlaybackMediaLookup
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.downloadEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.download.api.DeleteReason
import ch.lkmc.neutrodyne.download.api.OrphanDeleteResult
import ch.lkmc.neutrodyne.download.api.RootChangeMode
import ch.lkmc.neutrodyne.download.api.RootChangeResult
import ch.lkmc.neutrodyne.download.api.ShareableFile
import ch.lkmc.neutrodyne.download.api.StorageUsage
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.IOException
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ch.lkmc.neutrodyne.core.model.DownloadError as Error
import ch.lkmc.neutrodyne.core.model.DownloadLane as Lane
import ch.lkmc.neutrodyne.core.model.DownloadState as State
import ch.lkmc.neutrodyne.core.model.WaitReason as Wait

@OptIn(ExperimentalCoroutinesApi::class)
class AutoDownloadPlannerTest {
    @Test fun ac4GroupKeepsNewestThreeAndTombstonesFreeSlots() =
        plannerTest {
            val group = db.groupDao().insert(groupEntity(name = "Automatic", orderKey = "a"))
            val podcast = podcast()
            db.groupDao().insertMember(memberEntity(group, podcast, "a"))
            db.scopeSettingsDao().upsertGroup(PodcastGroupSettingsEntity(group, ScopeOverrides(autoDownload = true)))
            policy(podcast, enabled.copy(source = SettingSource.Group("g", "Automatic")))
            val ids = (1..5).map { episode(podcast, it.toLong()) }
            planner.plan(listOf(podcast))
            assertEquals(ids.takeLast(3).reversed(), queuedOrder())
            for (row in dao.allRows()) {
                assertEquals(Lane.AUTO, row.lane)
                assertEquals(0, row.priority)
                assertFalse(row.allowMetered)
                assertFalse(row.requireCharging)
                assertEquals("old", row.rootId)
            }
            val deleted = ids.last()
            dao.removeUnclaimed(listOf(deleted))
            db.episodeStateDao().upsert(episodeStateEntity(deleted) { copy(downloadDismissedAt = clock.now()) })
            planner.plan(listOf(podcast))
            assertNull(dao.byEpisode(deleted))
            assertEquals(ids.subList(1, 4).toSet(), dao.allRows().map { it.episodeId }.toSet())
            assertEquals(clock.now(), db.episodeStateDao().byEpisode(deleted)?.downloadDismissedAt)
            planner.plan(listOf(podcast))
            assertEquals(3, dao.allRows().size)
            assertEquals(listOf(Lane.AUTO, Lane.AUTO, Lane.AUTO), scheduled)
        }

    @Test fun d45TwoGroupsUseOneResolvedPolicyAndOneRowPerEpisode() =
        plannerTest {
            val podcast = podcast()
            val groups =
                (1..2).map { n ->
                    val id = db.groupDao().insert(groupEntity(name = "Group $n", orderKey = "$n"))
                    db.groupDao().insertMember(memberEntity(id, podcast, "a"))
                    db.scopeSettingsDao().upsertGroup(
                        PodcastGroupSettingsEntity(
                            id,
                            ScopeOverrides(autoDownload = true, autoDownloadKeepLatest = n + 2),
                        ),
                    )
                    SettingSource.Group("g$n", "Group $n")
                }
            // W1 tests the merge matrix. W2 consumes its single attributed policy, never group rows.
            policy(podcast, enabled.copy(keepLatest = 4, source = SettingSource.Groups(groups)))
            repeat(5) { episode(podcast, it.toLong()) }
            planner.plan(listOf(podcast, podcast))
            planner.plan(listOf(podcast))
            assertEquals(4, dao.allRows().size)
            assertEquals(
                4,
                dao
                    .allRows()
                    .map { it.episodeId }
                    .distinct()
                    .size,
            )
        }

    @Test fun fullWatermarkPassRunsEvenWithEmptyOrUnrelatedTrigger() =
        plannerTest {
            device.set(DownloadSettingKeys.PLANNER_BASELINE_AT, 500L)
            val newlySubscribed = podcast(subscribedAt = 600L, watermark = null)
            val previouslySeen = podcast(subscribedAt = 500L, watermark = null)
            val disabled = podcast(watermark = 300L)
            val unsupported = podcast(watermark = 200L)
            policy(disabled, enabled.copy(enabled = false))
            policy(unsupported, enabled.copy(enabled = false, source = SettingSource.NotSupported))
            planner.plan(emptyList())
            assertEquals(600L, watermark(newlySubscribed))
            assertEquals(clock.now(), watermark(previouslySeen))
            assertNull(watermark(disabled))
            assertEquals(200L, watermark(unsupported))
            assertEquals(clock.now(), device.get(DownloadSettingKeys.PLANNER_BASELINE_AT))
            assertTrue(scheduled.isEmpty())
            driver.watermarkUpdates.clear()
            clock.nowMs++
            planner.plan(listOf(-1L))
            assertTrue(driver.watermarkUpdates.isEmpty(), "unchanged watermarks must not be written")
        }

    @Test fun firstSubscriptionUsesSubscribeTimeThenEnableUsesNow() =
        plannerTest {
            val podcast = podcast(subscribedAt = 100L, watermark = null)
            val before = episode(podcast, 1, firstSeenAt = 100)
            val after = episode(podcast, 2, firstSeenAt = 101)
            planner.plan(listOf(podcast))
            assertEquals(100L, watermark(podcast))
            assertNull(dao.byEpisode(before))
            assertNotNull(dao.byEpisode(after))
            policy(podcast, enabled.copy(enabled = false))
            planner.plan(listOf(podcast))
            assertNull(watermark(podcast))
            assertTrue(dao.allRows().isEmpty())
            clock.nowMs += 1_000
            policy(podcast, enabled)
            planner.plan(listOf(podcast))
            assertEquals(clock.now(), watermark(podcast))
            assertTrue(dao.allRows().isEmpty())
            val newer = episode(podcast, 3, firstSeenAt = clock.now() + 1)
            planner.plan(listOf(podcast))
            assertEquals(listOf(newer), queuedOrder())
        }

    @Test fun killedBeforeWatermarkCommitKeepsBaselineAndRollsBackWholePass() =
        plannerTest {
            device.set(DownloadSettingKeys.PLANNER_BASELINE_AT, 500L)
            val old = podcast(subscribedAt = 100L, watermark = null)
            val newer = podcast(subscribedAt = 600L, watermark = null)
            driver.failWatermarkUpdate = 2
            assertFailsWith<IOException> { planner.plan(emptyList()) }
            assertNull(watermark(old))
            assertNull(watermark(newer))
            assertEquals(500L, device.get(DownloadSettingKeys.PLANNER_BASELINE_AT))
            driver.failWatermarkUpdate = null
            clock.nowMs++
            newPlanner().plan(emptyList())
            assertEquals(clock.now(), watermark(old))
            assertEquals(600L, watermark(newer))
        }

    @Test fun killedAfterWatermarkCommitBeforeBaselinePreservesCommittedWatermark() =
        plannerTest {
            device.set(DownloadSettingKeys.PLANNER_BASELINE_AT, 500L)
            val podcast = podcast(subscribedAt = 100L, watermark = null)
            failBaseline = true
            assertFailsWith<IOException> { planner.plan(emptyList()) }
            val committed = watermark(podcast)
            assertEquals(clock.now(), committed)
            assertEquals(500L, device.get(DownloadSettingKeys.PLANNER_BASELINE_AT))
            failBaseline = false
            clock.nowMs += 1_000
            newPlanner().plan(emptyList())
            assertEquals(committed, watermark(podcast))
            assertEquals(clock.now(), device.get(DownloadSettingKeys.PLANNER_BASELINE_AT))
        }

    @Test fun watermarkPassBatchesFullLibraryAndSkipsUnchangedWrites() =
        plannerTest {
            val ids = (1..1_003).map { podcast(subscribedAt = 100L, watermark = null) }
            driver.watermarkReads.clear()
            driver.watermarkUpdates.clear()
            planner.plan(emptyList())
            assertEquals(listOf(500, 500, 3), driver.watermarkReads.map { it.count { c -> c == '?' } })
            // UPDATE has two value binds in addition to the 500 podcast IDs.
            assertEquals(listOf(502, 502, 5), driver.watermarkUpdates.map { it.count { c -> c == '?' } })
            val values =
                db
                    .podcastDao()
                    .autoDownloadWatermarks(ids)
                    .map { it.autoDownloadEligibleAfter }
                    .toSet()
            assertEquals(setOf(100L), values)
            driver.watermarkUpdates.clear()
            clock.nowMs++
            planner.plan(emptyList())
            assertTrue(driver.watermarkUpdates.isEmpty())
        }

    @Test fun noBackfillFromInitialIngestOldArrivalOrDumpGuard() =
        plannerTest {
            val podcast = podcast(watermark = 100L)
            episode(podcast, 1, firstSeenAt = 101) { copy(isNew = false) }
            episode(podcast, 2, firstSeenAt = 100)
            episode(podcast, 3, firstSeenAt = 99)
            repeat(12) { episode(podcast, 10L + it, firstSeenAt = 101) { copy(isNew = false) } }
            val genuineNew = (1..3).map { episode(podcast, 30L + it, firstSeenAt = 101) }
            planner.plan(listOf(podcast))
            assertEquals(genuineNew.reversed(), queuedOrder())
        }

    @Test fun disableRemovesEveryUntouchedAutoStateButRetainsManualPausedAndCompleted() =
        plannerTest {
            val podcast = podcast()
            val ids =
                State.entries.associateWith { state ->
                    episode(podcast).also { insert(it, state) }
                }
            val manual = State.entries.map { state -> episode(podcast).also { insert(it, state, Lane.MANUAL) } }
            policy(podcast, enabled.copy(enabled = false))
            planner.plan(listOf(podcast))
            assertEquals(
                manual.toSet() + setOf(ids.getValue(State.PAUSED), ids.getValue(State.COMPLETED)),
                dao.allRows().map { it.episodeId }.toSet(),
            )
            assertTrue(stops.all { it.second == StopIntent.Delete(DeleteReason.LIFECYCLE) })
            assertEquals(3, stops.size)
            assertTrue(journaled.isNotEmpty())
            for (id in ids.values) assertNull(db.episodeStateDao().byEpisode(id)?.downloadDismissedAt)
            assertNull(watermark(podcast))
        }

    @Test fun narrowingWindowDeletesUnwantedIncludingLiveOwnersWithoutTombstones() =
        plannerTest {
            val podcast = podcast()
            val old = episode(podcast, 1)
            insert(old, State.DOWNLOADING)
            val newer = episode(podcast, 2)
            insert(newer, State.QUEUED)
            val paused = episode(podcast, 3)
            insert(paused, State.PAUSED)
            val completed = episode(podcast, 4)
            insert(completed, State.COMPLETED)
            policy(podcast, enabled.copy(keepLatest = 1))
            planner.plan(listOf(podcast))
            assertNull(dao.byEpisode(old))
            assertNull(dao.byEpisode(newer))
            assertEquals(State.PAUSED, row(paused).state)
            assertEquals(State.COMPLETED, row(completed).state)
            assertEquals(listOf(old), stops.map { it.first })
            assertNull(db.episodeStateDao().byEpisode(old)?.downloadDismissedAt)
        }

    @Test fun notSupportedPreservesWatermarkAndExistingRowsAndNeverCreatesYoutube() =
        plannerTest {
            val podcast = podcast(watermark = 150L, sourceType = SourceType.YOUTUBE_CHANNEL)
            val existing = episode(podcast) { copy(enclosureUrl = null, externalMediaId = "video") }
            insert(existing, State.QUEUED)
            policy(podcast, enabled.copy(enabled = false, source = SettingSource.NotSupported))
            val before = row(existing)
            planner.plan(listOf(podcast))
            assertEquals(150L, watermark(podcast))
            assertEquals(before, row(existing))
            val future = podcast(sourceType = SourceType.YOUTUBE_CHANNEL)
            episode(future, 10, firstSeenAt = 151) { copy(externalMediaId = "other") }
            planner.plan(listOf(future))
            assertEquals(1, dao.allRows().size)
        }

    @Test fun queuedPolicyChangesPreserveRetryBytesAndManualConsent() =
        plannerTest {
            val podcast = podcast()
            val id = episode(podcast, 1)
            val initial =
                insert(id) {
                    copy(
                        waitReason = Wait.BACKOFF,
                        downloadedBytes = 70,
                        tempPath = "part",
                        etag = "etag",
                        attempt = 3,
                        nextAttemptAt = Long.MAX_VALUE,
                    )
                }
            val manual = episode(podcast, 2)
            val manualRow = insert(manual, lane = Lane.MANUAL)
            val active = episode(podcast, 3)
            val activeRow = insert(active, State.DOWNLOADING)
            policy(podcast, enabled.copy(network = NetworkPolicy.ANY, requireCharging = true))
            planner.plan(listOf(podcast))
            assertEquals(initial.copy(allowMetered = true, requireCharging = true), row(id))
            assertEquals(manualRow, row(manual))
            assertEquals(activeRow, row(active))
            assertEquals(listOf(Lane.AUTO), scheduled)
        }

    @Test fun missingFileRestartsButUnavailableVolumeAndPausedNeverResume() =
        plannerTest {
            val podcast = podcast()
            val missing = episode(podcast, 1)
            insert(missing, State.MISSING) {
                copy(
                    totalBytes = 500,
                    downloadedBytes = 500,
                    relativePath = "final",
                    finalUri = "file:///final",
                    etag = "old",
                    lastError = Error.UNKNOWN,
                )
            }
            val unavailable = episode(podcast, 2)
            val unavailableRow = insert(unavailable, State.MISSING) { copy(lastError = Error.STORAGE_UNAVAILABLE) }
            val paused = episode(podcast, 3)
            val pausedRow = insert(paused, State.PAUSED)
            planner.plan(listOf(podcast))
            val requeued = row(missing)
            assertEquals(State.QUEUED, requeued.state)
            assertEquals(0L, requeued.downloadedBytes)
            assertNull(requeued.totalBytes)
            assertNull(requeued.relativePath)
            assertNull(requeued.finalUri)
            assertNull(requeued.etag)
            assertEquals(unavailableRow, row(unavailable))
            assertEquals(pausedRow, row(paused))
        }

    @Test fun roundRobinOrdersPodcastsByNewestThenOnePerRound() =
        plannerTest {
            val a = podcast()
            val b = podcast()
            val c = podcast()
            val aIds = listOf(90L, 100L, 110L).map { episode(a, it) }
            val bIds = listOf(40L, 50L).map { episode(b, it) }
            val cIds = listOf(130L).map { episode(c, it) }
            planner.plan(listOf(b, a, c, a))
            assertEquals(listOf(cIds[0], aIds[2], bIds[1], aIds[1], bIds[0], aIds[0]), queuedOrder())
            val times = queuedOrder().map { row(it).requestedAt }
            assertEquals((0..5).map { clock.now() + it }, times)
        }

    @Test fun capStopsAtFirstRefusalWithoutSkippingToSmallerOrLaterPodcast() =
        plannerTest {
            settings.set(DownloadSettingKeys.STORAGE_CAP_MB, 1_024)
            val a = podcast()
            val b = podcast()
            val a1 = episode(a, 100) { copy(enclosureLength = 600 * MB) }
            val a2 = episode(a, 90) { copy(enclosureLength = MB) }
            val b1 = episode(b, 80) { copy(enclosureLength = 500 * MB) }
            planner.plan(listOf(a, b))
            assertEquals(listOf(a1), queuedOrder())
            assertNull(dao.byEpisode(a2))
            assertNull(dao.byEpisode(b1))
            assertEquals(500 * MB, quota.refusedEstimate.value)
            assertTrue(
                quota.autoBlocked(
                    dao.allRows(),
                    DownloadQuota.capBytes(settings.get(DownloadSettingKeys.STORAGE_CAP_MB)),
                ),
            )
            settings.set(DownloadSettingKeys.STORAGE_CAP_MB, 0)
            planner.plan(listOf(a, b))
            assertEquals(3, dao.allRows().size)
            assertNull(quota.refusedEstimate.value)
            assertFalse(
                quota.autoBlocked(
                    dao.allRows(),
                    DownloadQuota.capBytes(settings.get(DownloadSettingKeys.STORAGE_CAP_MB)),
                ),
            )
        }

    @Test fun passWithoutPendingCandidatesClearsPreviousAdmissionRefusal() =
        plannerTest {
            settings.set(DownloadSettingKeys.STORAGE_CAP_MB, 1_024)
            val podcast = podcast()
            episode(podcast) { copy(enclosureLength = 2_048 * MB) }
            planner.plan(listOf(podcast))
            assertEquals(2_048 * MB, quota.refusedEstimate.value)
            policy(podcast, enabled.copy(enabled = false))
            planner.plan(listOf(podcast))
            assertNull(quota.refusedEstimate.value)
            assertFalse(quota.autoBlocked(dao.allRows(), 1_024 * MB))
        }

    @Test fun capCountsBothCompletedLanesAndRemainingActiveAutoAcrossLibrary() =
        plannerTest {
            settings.set(DownloadSettingKeys.STORAGE_CAP_MB, 1_024)
            val untargeted = podcast()
            val target = podcast()
            insert(episode(untargeted), State.COMPLETED) { copy(totalBytes = 200 * MB) }
            insert(episode(untargeted), State.COMPLETED, Lane.MANUAL) { copy(totalBytes = 300 * MB) }
            insert(episode(untargeted), State.DOWNLOADING) { copy(totalBytes = 500 * MB, downloadedBytes = 100 * MB) }
            insert(episode(untargeted), lane = Lane.MANUAL) { copy(estimatedBytes = Long.MAX_VALUE) }
            insert(episode(untargeted), State.PAUSED) { copy(estimatedBytes = Long.MAX_VALUE) }
            val fitsExactly = episode(target, 10) { copy(enclosureLength = 124 * MB) }
            val extra = episode(target, 9) { copy(enclosureLength = MB) }
            planner.plan(listOf(target))
            assertNotNull(dao.byEpisode(fitsExactly))
            assertNull(dao.byEpisode(extra))
            assertTrue(
                quota.autoBlocked(
                    dao.allRows(),
                    DownloadQuota.capBytes(settings.get(DownloadSettingKeys.STORAGE_CAP_MB)),
                ),
            )
        }

    @Test fun unknownEstimateUses150MiBAndDurationUsesCorrectUnits() =
        plannerTest {
            settings.set(DownloadSettingKeys.STORAGE_CAP_MB, 1_024)
            val podcast = podcast()
            policy(podcast, enabled.copy(keepLatest = 10))
            val ids = (1..8).map { episode(podcast, it.toLong()) }
            planner.plan(listOf(podcast))
            assertEquals(ids.takeLast(6).reversed(), queuedOrder())
            assertTrue(dao.allRows().all { it.estimatedBytes == null })
            assertTrue(
                quota.autoBlocked(
                    dao.allRows(),
                    DownloadQuota.capBytes(settings.get(DownloadSettingKeys.STORAGE_CAP_MB)),
                ),
            )
            assertEquals(160_000L, downloadEstimate(99L, 10_000L))
            assertEquals(100_000L, downloadEstimate(100_000L, 10_000L))
            assertEquals(Long.MAX_VALUE / 16 * 16, downloadEstimate(null, Long.MAX_VALUE))
            assertNull(downloadEstimate(-1L, 0L))
        }

    @Test fun failedChangedEnclosureRequeuesOnlyThroughRefreshSeamAndKeepsCompleted() =
        plannerTest {
            val podcast = podcast()
            val failed = episode(podcast, 1)
            insert(failed, State.FAILED) {
                copy(
                    sourceRef = "https://old/missing.mp3",
                    lastError = Error.HTTP_NOT_FOUND,
                    attempt = 7,
                    lastHttpStatus = 404,
                    downloadedBytes = 70,
                    etag = "old",
                )
            }
            val completed = episode(podcast, 2)
            val completeRow = insert(completed, State.COMPLETED) { copy(sourceRef = "https://old/complete.mp3") }
            planner.plan(listOf(podcast))
            assertEquals(State.FAILED, row(failed).state)
            // W5/W7 own refresh wiring and journaling. Exercise the existing atomic DAO seam here.
            assertEquals(listOf(failed), dao.requeueChangedEnclosures(clock.now()).map { it.episodeId })
            planner.plan(listOf(podcast))
            val row = row(failed)
            assertEquals(State.QUEUED, row.state)
            assertEquals("https://example.com/audio.mp3", row.sourceRef)
            assertEquals(0, row.attempt)
            assertEquals(0L, row.downloadedBytes)
            assertNull(row.lastError)
            assertNull(row.etag)
            assertEquals(completeRow, row(completed))
            assertTrue(dao.requeueChangedEnclosures(clock.now()).isEmpty())
        }

    @Test fun singleFlightSerializesPassesAndReleasesMutexAfterFailure() =
        plannerTest {
            val podcast = podcast()
            val id = episode(podcast)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var reads = 0
            val gated =
                object : EffectiveSettingsResolver by resolver {
                    override fun observeAutoDownload() =
                        flow {
                            reads++
                            entered.complete(Unit)
                            release.await()
                            emit(resolver.autoDownloadValues.value)
                        }
                }
            val owner = newPlanner(effective = gated)
            val first = async { owner.plan(listOf(podcast)) }
            entered.await()
            val second = async { owner.plan(listOf(podcast)) }
            runCurrent()
            assertEquals(1, reads)
            release.complete(Unit)
            awaitAll(first, second)
            assertEquals(2, reads)
            assertEquals(listOf(id), queuedOrder())
            failSchedule = true
            assertFailsWith<IOException> { owner.plan(listOf(podcast)) }
            failSchedule = false
            owner.plan(listOf(podcast))
            assertEquals(1, dao.allRows().size)
        }

    @Test fun storagePublishesPlannerAdmissionRefusal() =
        engineTest {
            val rig = ReconcileRig(this)
            val settings = FakeSettingsRepository()
            val quota = DownloadQuota(settings)
            val storage =
                DownloadStorage(
                    db,
                    files,
                    roots,
                    rig.store,
                    rig.index,
                    rig.deletes,
                    rig.reconciler,
                    engine.claimMutex,
                    FakePlaybackMediaLookup(),
                    TestArtwork(),
                    { _, _ -> null },
                    quota,
                )
            settings.set(DownloadSettingKeys.STORAGE_CAP_MB, 1_024)
            val podcast = db.podcastDao().insertPodcast(podcastEntity { copy(autoDownloadEligibleAfter = 100L) })
            val id =
                db.episodeDao().insertStub(
                    episodeEntity(podcastId = podcast) {
                        copy(
                            isNew = true,
                            firstSeenAt = 101L,
                            enclosureUrl = "https://example.com/audio.mp3",
                            enclosureLength = 2_048 * MB,
                        )
                    },
                )
            val resolver = FakeEffectiveSettingsResolver()
            resolver.autoDownloadValues.value =
                mapOf(
                    podcast to
                        EffectiveAutoDownload(
                            true,
                            3,
                            NetworkPolicy.UNMETERED,
                            true,
                            false,
                            DeleteAfter.AFTER_24H,
                            SettingSource.AppDefault,
                        ),
                )
            val planner =
                AutoDownloadPlannerImpl(
                    db,
                    resolver,
                    quota,
                    rig.store,
                    clock,
                    roots,
                    scheduler,
                    engine.claimMutex,
                    engine::stopTransfer,
                    storage,
                    FakePlaybackMediaLookup(),
                    PlatformKind.DESKTOP,
                    dispatcher,
                )
            planner.plan(listOf(podcast))
            val blocked = storage.observeStorage().first()
            assertEquals(1_024 * MB, blocked.capBytes)
            assertEquals(0L, blocked.usedBytes)
            assertTrue(blocked.autoBlockedByCap)
            assertEquals(2_048 * MB, quota.refusedEstimate.value)
            assertNull(dao.byEpisode(id))
            // A setting change clears the notice without requiring another planner pass.
            settings.set(DownloadSettingKeys.STORAGE_CAP_MB, 0)
            assertFalse(storage.observeStorage().first().autoBlockedByCap)
            planner.plan(listOf(podcast))
            assertNull(quota.refusedEstimate.value)
            val admitted = assertNotNull(dao.byEpisode(id))
            assertTrue(admitted.allowMetered)
            assertFalse(admitted.requireCharging)
        }

    @Test fun youtubeAutoAdmitsOnlyUnderCapabilityAndByVideoId() =
        plannerTest {
            val channel =
                podcast(
                    sourceType = SourceType.YOUTUBE_CHANNEL,
                    youtubeChannelId = "UCaaaaaaaaaaaaaaaaaaaaaA",
                )
            val ids =
                (1..3).map { n ->
                    episode(channel, n.toLong()) {
                        copy(
                            externalMediaId = "ytvideo000$n",
                            enclosureUrl = null,
                            enclosureType = null,
                        )
                    }
                }
            planner.plan(listOf(channel))
            assertTrue(dao.allRows().isEmpty())
            caps.capabilities.value = YouTubeCapabilities(true, true, true, true, true, null)
            planner.plan(listOf(channel))
            val rows = dao.allRows()
            assertEquals(ids.toSet(), rows.map { it.episodeId }.toSet())
            assertTrue(rows.all { it.sourceKind == SourceKind.YOUTUBE && it.lane == Lane.AUTO })
            assertEquals(
                setOf("ytvideo0001", "ytvideo0002", "ytvideo0003"),
                rows.map { it.sourceRef }.toSet(),
            )
            assertTrue(rows.all { !it.allowMetered })
        }

    private fun plannerTest(block: suspend Rig.() -> Unit) =
        runTest {
            Rig(this).use { it.block() }
        }

    private class Rig(
        private val scope: TestScope,
    ) : AutoCloseable,
        kotlinx.coroutines.CoroutineScope by scope {
        val clock = TestClock(1_000L)
        val driver = PlannerDriver()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val db = TestDb.inMemory(driver = driver, clock = clock, queryContext = dispatcher)
        val dao = db.downloadDao()
        val resolver = FakeEffectiveSettingsResolver()
        val settings = FakeSettingsRepository()
        val quota = DownloadQuota(settings)
        private val store = JournalTestStore()
        var failBaseline = false
        val device =
            object : SettingStore by store {
                override suspend fun <T : Any> set(
                    key: SettingKey<T>,
                    value: T,
                ) {
                    if (failBaseline && key == DownloadSettingKeys.PLANNER_BASELINE_AT) {
                        throw IOException("Killed before baseline")
                    }
                    store.set(key, value)
                }
            }
        val claimMutex = Mutex()
        val roots = TestRoots()
        val caps = FakeYouTubeCapabilitiesSource()
        val media = FakePlaybackMediaLookup()
        val scheduled = mutableListOf<Lane>()
        val stops = mutableListOf<Pair<Long, StopIntent>>()
        val journaled = mutableListOf<Long>()
        var failSchedule = false
        val enabled =
            EffectiveAutoDownload(
                true,
                3,
                NetworkPolicy.UNMETERED,
                false,
                false,
                DeleteAfter.AFTER_24H,
                SettingSource.AppDefault,
            )
        private val files =
            object : DownloadControllerFiles {
                override suspend fun prepareRemoval(rows: List<DownloadEntity>) {
                    for (row in rows) {
                        assertNotNull(dao.byEpisode(row.episodeId), "journal must precede deletion")
                        journaled += row.episodeId
                    }
                }

                override suspend fun finishRemoval(rows: List<DownloadEntity>) {
                    for (row in rows) {
                        assertNull(dao.byEpisode(row.episodeId), "file drain must follow commit")
                    }
                }

                override suspend fun reportFileMissing(episodeId: Long) = Unit

                override fun observeStorage() = MutableStateFlow(StorageUsage("old", 0, 0, 0, null, null, false))

                override suspend fun changeRoot(
                    rootId: String,
                    mode: RootChangeMode,
                ) = RootChangeResult.Applied

                override suspend fun deleteOrphanFiles() = OrphanDeleteResult.Unavailable

                override suspend fun shareableFile(episodeId: Long): ShareableFile? = null
            }
        val planner = newPlanner()

        fun runCurrent() = scope.runCurrent()

        fun newPlanner(
            platform: PlatformKind = PlatformKind.ANDROID,
            effective: EffectiveSettingsResolver = resolver,
        ) = AutoDownloadPlannerImpl(
            db,
            effective,
            quota,
            device,
            clock,
            roots,
            object : DownloadScheduler {
                override suspend fun ensureScheduled(lane: Lane) {
                    assertFalse(claimMutex.isLocked, "scheduler must be outside the claim lock")
                    assertEquals(dispatcher, currentCoroutineContext()[ContinuationInterceptor])
                    if (failSchedule) throw IOException("Scheduler unavailable")
                    scheduled += lane
                }

                override fun wake(lane: Lane) = Unit

                override suspend fun scheduleWake(
                    lane: Lane,
                    earliestAt: Long?,
                    reason: Wait,
                ) = Unit
            },
            claimMutex,
            { id, intent ->
                stops += id to intent
                val row = row(id)
                // Simulate a token owner finishing, so the planner must read fresh state.
                dao.transition(row.copy(state = State.QUEUED), row.state, checkNotNull(row.runnerToken))
            },
            files,
            media,
            platform,
            dispatcher,
            capabilities = caps,
        )

        suspend fun podcast(
            subscribedAt: Long = 100L,
            watermark: Long? = 100L,
            sourceType: SourceType = SourceType.RSS,
            youtubeChannelId: String? = null,
        ): Long {
            val id =
                db.podcastDao().insertPodcast(
                    podcastEntity(subscribedAt = subscribedAt) {
                        copy(
                            autoDownloadEligibleAfter = watermark,
                            sourceType = sourceType,
                            youtubeChannelId = youtubeChannelId,
                        )
                    },
                )
            policy(id, enabled)
            return id
        }

        fun policy(
            id: Long,
            value: EffectiveAutoDownload,
        ) {
            resolver.autoDownloadValues.value = resolver.autoDownloadValues.value + (id to value)
        }

        suspend fun episode(
            podcast: Long,
            sortDate: Long = 200L,
            firstSeenAt: Long = 201L,
            change: EpisodeEntity.() -> EpisodeEntity = { this },
        ): Long =
            db.episodeDao().insertStub(
                episodeEntity(podcastId = podcast, sortDate = sortDate) {
                    copy(
                        isNew = true,
                        firstSeenAt = firstSeenAt,
                        enclosureUrl = "https://example.com/audio.mp3",
                        enclosureType = "audio/mpeg",
                    ).change()
                },
            )

        suspend fun insert(
            id: Long,
            state: State = State.QUEUED,
            lane: Lane = Lane.AUTO,
            change: DownloadEntity.() -> DownloadEntity = { this },
        ): DownloadEntity =
            downloadEntity(id, state)
                .copy(
                    lane = lane,
                    rootId = "old",
                    allowMetered = false,
                    runnerToken =
                        if (state in
                            setOf(State.RESOLVING, State.DOWNLOADING, State.VERIFYING)
                        ) {
                            "owner:$id"
                        } else {
                            null
                        },
                ).change()
                .also { dao.insert(it) }

        suspend fun row(id: Long) = assertNotNull(dao.byEpisode(id))

        suspend fun watermark(id: Long) = db.podcastDao().byId(id)?.autoDownloadEligibleAfter

        suspend fun queuedOrder() =
            dao
                .allRows()
                .filter { it.state == State.QUEUED }
                .sortedWith(compareBy<DownloadEntity> { it.requestedAt }.thenBy { it.episodeId })
                .map { it.episodeId }

        override fun close() = db.close()
    }

    private class PlannerDriver(
        private val delegate: SQLiteDriver = TestDb.bundledDriver(),
    ) : SQLiteDriver by delegate {
        val watermarkReads = mutableListOf<String>()
        val watermarkUpdates = mutableListOf<String>()
        var failWatermarkUpdate: Int? = null

        override fun open(fileName: String): SQLiteConnection {
            val connection = delegate.open(fileName)
            return object : SQLiteConnection by connection {
                override fun prepare(sql: String): SQLiteStatement {
                    val statement = connection.prepare(sql)
                    var recordedRead = false
                    return object : SQLiteStatement by statement {
                        override fun reset() {
                            recordedRead = false
                            statement.reset()
                        }

                        override fun step(): Boolean {
                            if (!recordedRead && sql.startsWith("SELECT id, subscribedAt, autoDownloadEligibleAfter")) {
                                watermarkReads += sql
                                recordedRead = true
                            }
                            if (sql.startsWith("UPDATE podcast SET autoDownloadEligibleAfter")) {
                                watermarkUpdates += sql
                                if (watermarkUpdates.size == failWatermarkUpdate) {
                                    throw IOException("Killed before watermark commit")
                                }
                            }
                            return statement.step()
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val MB = 1024L * 1024
    }
}
