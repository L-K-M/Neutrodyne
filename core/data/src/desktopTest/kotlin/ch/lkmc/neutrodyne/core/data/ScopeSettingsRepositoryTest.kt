// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteException
import app.cash.turbine.test
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.settings.ScopeSettingsRepositoryImpl
import ch.lkmc.neutrodyne.core.database.PodcastGroupSettingsEntity
import ch.lkmc.neutrodyne.core.database.PodcastSettingsEntity
import ch.lkmc.neutrodyne.core.database.ScopeOverrides
import ch.lkmc.neutrodyne.core.domain.RefreshController
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.model.PlaybackScopeWrite.APPLIED
import ch.lkmc.neutrodyne.core.model.PlaybackScopeWrite.MISSING_PODCAST
import ch.lkmc.neutrodyne.core.model.PlaybackScopeWrite.NO_CONTEXT_GROUP
import ch.lkmc.neutrodyne.core.model.PlaybackSettingChange.SkipSilence
import ch.lkmc.neutrodyne.core.model.PlaybackSettingChange.Speed
import ch.lkmc.neutrodyne.core.model.SettingField
import ch.lkmc.neutrodyne.core.model.SettingOverrides
import ch.lkmc.neutrodyne.core.model.SettingScope.GLOBAL
import ch.lkmc.neutrodyne.core.model.SettingScope.GROUP
import ch.lkmc.neutrodyne.core.model.SettingScope.PODCAST
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.model.settings.AllSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingsFile
import ch.lkmc.neutrodyne.core.testing.FakeGroupChannelSync
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeSnapshotScheduler
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScopeSettingsRepositoryTest {
    private val defaults =
        SettingOverrides(
            240,
            false,
            1f,
            false,
            autoDownload = false,
            autoDownloadKeepLatest = 3,
            autoDownloadNetwork = ch.lkmc.neutrodyne.core.model.NetworkPolicy.UNMETERED,
            autoDownloadRequireCharging = false,
            includeVideoInAutoDownload = false,
            deleteAfterPlayed = ch.lkmc.neutrodyne.core.model.DeleteAfter.AFTER_24H,
        )

    private val db = TestDb.inMemory()
    private val settings = FakeSettingsRepository()
    private val refresh = FakeRefreshController()
    private val snapshots = FakeSnapshotScheduler()
    private val repo =
        ScopeSettingsRepositoryImpl(
            db,
            settings,
            { refresh },
            FakeGroupChannelSync(),
            snapshots,
            capabilities =
                ch.lkmc.neutrodyne.core.testing
                    .FakeYouTubeCapabilitiesSource(),
        )
    private val dao = db.scopeSettingsDao()

    @AfterTest fun close() = db.close()

    @Test fun externalChannelPolicyIsNotSupportedAndCannotBeEdited() =
        runTest {
            val caps =
                ch.lkmc.neutrodyne.core.testing
                    .FakeYouTubeCapabilitiesSource()
            val repository =
                ScopeSettingsRepositoryImpl(
                    db,
                    settings,
                    { refresh },
                    FakeGroupChannelSync(),
                    snapshots,
                    caps,
                )
            val id =
                db.podcastDao().insertPodcast(
                    podcastEntity {
                        copy(sourceType = ch.lkmc.neutrodyne.core.model.SourceType.YOUTUBE_CHANNEL)
                    },
                )
            settings.set(ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys.AUTO_DOWNLOAD, true)
            repository.observePodcast(id).test {
                assertEquals(SettingSource.NotSupported, awaitItem()?.sources?.get(SettingField.AUTO_DOWNLOAD))
                caps.capabilities.value =
                    ch.lkmc.neutrodyne.youtube.api
                        .YouTubeCapabilities(true, true, true, true, true, null)
                assertEquals(SettingSource.AppDefault, awaitItem()?.sources?.get(SettingField.AUTO_DOWNLOAD))
                caps.capabilities.value = caps.capabilities.value.copy(downloads = false)
                assertEquals(SettingSource.NotSupported, awaitItem()?.sources?.get(SettingField.AUTO_DOWNLOAD))
                cancelAndIgnoreRemainingEvents()
            }
            assertTrue(
                repository.updatePodcast(id) {
                    it.copy(autoDownload = true, autoDownloadKeepLatest = 7)
                } is Outcome.Failure,
            )
            assertNull(dao.forPodcast(id))
        }

    @Test fun writesEachScopeAndClearsAllNullRows() =
        runTest {
            val podcast = podcast()
            val group = group()
            assertEquals(Outcome.Success(Unit), repo.updatePodcast(podcast) { SettingOverrides(120, false) })
            assertEquals(Outcome.Success(Unit), repo.updateGroup(group) { SettingOverrides(0, true) })
            assertEquals(
                ScopeOverrides(
                    refreshIntervalMinutes = 120,
                    notifyNewEpisodes = false,
                ),
                dao.forPodcast(podcast)?.o,
            )
            assertEquals(
                ScopeOverrides(refreshIntervalMinutes = 0, notifyNewEpisodes = true),
                dao.forGroup(group)?.o,
            )
            repo.updatePodcast(podcast) { it.copy(refreshIntervalMinutes = null) }
            assertNotNull(dao.forPodcast(podcast), "False is an override, not an empty value")
            repo.updatePodcast(podcast) { it.copy(notifyNewEpisodes = null) }
            repo.updateGroup(group) { SettingOverrides() }
            assertNull(dao.forPodcast(podcast))
            assertNull(dao.forGroup(group))
        }

    @Test fun editsPreserveEveryOtherOverrideAndDoNotDeleteNonEmptyRows() =
        runTest {
            val podcast = podcast()
            val group = group()
            val original =
                ScopeOverrides(
                    playbackSpeed = 1.5f,
                    skipSilence = false,
                    boostDb = 2f,
                    introSkipMs = 5,
                    outroSkipMs = 6,
                    autoDownload = true,
                    autoDownloadKeepLatest = 3,
                    autoDownloadNetwork = ch.lkmc.neutrodyne.core.model.NetworkPolicy.UNMETERED,
                    autoDownloadRequireCharging = false,
                    deleteAfterPlayed = ch.lkmc.neutrodyne.core.model.DeleteAfter.NEVER,
                    includeVideoInAutoDownload = true,
                )
            dao.upsertPodcast(PodcastSettingsEntity(podcast, original))
            dao.upsertGroup(PodcastGroupSettingsEntity(group, original))
            repo.updatePodcast(podcast) { it.copy(refreshIntervalMinutes = 60, notifyNewEpisodes = true) }
            repo.updateGroup(group) { it.copy(refreshIntervalMinutes = 720, notifyNewEpisodes = false) }
            assertEquals(
                original.copy(refreshIntervalMinutes = 60, notifyNewEpisodes = true),
                dao.forPodcast(podcast)?.o,
            )
            assertEquals(
                original.copy(refreshIntervalMinutes = 720, notifyNewEpisodes = false),
                dao.forGroup(group)?.o,
            )
            repo.updatePodcast(podcast) { it.copy(refreshIntervalMinutes = null, notifyNewEpisodes = null) }
            repo.updateGroup(group) { it.copy(refreshIntervalMinutes = null, notifyNewEpisodes = null) }
            assertEquals(original, dao.forPodcast(podcast)?.o)
            assertEquals(original, dao.forGroup(group)?.o)
        }

    @Test fun refreshChoicesValidateAtBothScopesWithoutPartialWrites() =
        runTest {
            val podcast = podcast()
            val group = group()
            for (minutes in listOf(null, 0, 60, 120, 240, 480, 720, 1440)) {
                assertEquals(
                    Outcome.Success(Unit),
                    repo.updatePodcast(podcast) { it.copy(refreshIntervalMinutes = minutes) },
                )
                assertEquals(
                    Outcome.Success(Unit),
                    repo.updateGroup(group) { it.copy(refreshIntervalMinutes = minutes) },
                )
            }
            val beforePodcast = dao.forPodcast(podcast)
            val beforeGroup = dao.forGroup(group)
            for (minutes in listOf(-1, 1, 30, 61, Int.MAX_VALUE)) {
                val failure = Outcome.Failure(SettingsError.OutOfRange("refreshIntervalMinutes"))
                assertEquals(failure, repo.updatePodcast(podcast) { SettingOverrides(minutes, true) })
                assertEquals(failure, repo.updateGroup(group) { SettingOverrides(minutes, true) })
                assertEquals(beforePodcast, dao.forPodcast(podcast))
                assertEquals(beforeGroup, dao.forGroup(group))
            }
        }

    @Test fun missingScopesAreNullAndWritesAreSuccessfulNoOps() =
        runTest {
            assertNull(repo.observePodcast(-1).first())
            assertNull(repo.observeGroup(-1).first())
            assertEquals(
                Outcome.Success(Unit),
                repo.updatePodcast(-1) { error("Must not run for missing scope") },
            )
            assertEquals(
                Outcome.Success(Unit),
                repo.updateGroup(-1) { error("Must not run for missing scope") },
            )
            assertNull(dao.forPodcast(-1))
            assertNull(dao.forGroup(-1))
        }

    @Test fun concurrentDifferentFieldEditsAreNotLostAcrossRepositoryInstances() =
        runTest {
            val podcast = podcast()
            val group = group()
            repeat(8) {
                repo.updatePodcast(podcast) { SettingOverrides() }
                repo.updateGroup(group) { SettingOverrides() }
                val start = CompletableDeferred<Unit>()
                val writes =
                    (0..3).map { field ->
                        async(Dispatchers.Default) {
                            val independent =
                                ScopeSettingsRepositoryImpl(
                                    db,
                                    settings,
                                    { FakeRefreshController() },
                                    FakeGroupChannelSync(),
                                    FakeSnapshotScheduler(),
                                    capabilities =
                                        ch.lkmc.neutrodyne.core.testing
                                            .FakeYouTubeCapabilitiesSource(),
                                )
                            start.await()
                            when (field) {
                                0 -> independent.updatePodcast(podcast) { it.copy(refreshIntervalMinutes = 120) }
                                1 -> independent.updatePodcast(podcast) { it.copy(notifyNewEpisodes = true) }
                                2 -> independent.updateGroup(group) { it.copy(refreshIntervalMinutes = 0) }
                                else -> independent.updateGroup(group) { it.copy(notifyNewEpisodes = false) }
                            }
                        }
                    }
                start.complete(Unit)
                assertTrue(writes.awaitAll().all { it == Outcome.Success(Unit) })
                assertEquals(
                    ScopeOverrides(
                        refreshIntervalMinutes = 120,
                        notifyNewEpisodes = true,
                    ),
                    dao.forPodcast(podcast)?.o,
                )
                assertEquals(
                    ScopeOverrides(refreshIntervalMinutes = 0, notifyNewEpisodes = false),
                    dao.forGroup(group)?.o,
                )
            }
        }

    @Test fun groupViewSeparatesOwnInheritFromManualOnlyAndAttributesSelf() =
        runTest {
            val group = group()
            val identity = assertNotNull(db.groupDao().groupById(group))
            repo.observeGroup(group).test {
                val inherited = assertNotNull(awaitItem())
                assertEquals(SettingOverrides(), inherited.own)
                assertEquals(defaults, inherited.effective)
                assertTrue(inherited.sources.values.all { it == SettingSource.AppDefault })
                settings.set(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES, 480)
                assertEquals(480, awaitItem()?.effective?.refreshIntervalMinutes)
                repo.updateGroup(group) { SettingOverrides(0, true) }
                val own = assertNotNull(awaitItem())
                assertEquals(SettingOverrides(0, true), own.own)
                assertEquals(defaults.copy(refreshIntervalMinutes = null, notifyNewEpisodes = true), own.effective)
                assertEquals(
                    SettingSource.Group(identity.uuid, identity.name),
                    own.sources[SettingField.REFRESH_INTERVAL_MINUTES],
                )
                assertEquals(
                    SettingSource.Group(identity.uuid, identity.name),
                    own.sources[SettingField.NOTIFY_NEW_EPISODES],
                )
                repo.updateGroup(group) { SettingOverrides() }
                assertEquals(defaults.copy(refreshIntervalMinutes = 480), awaitItem()?.effective)
            }
        }

    @Test fun podcastViewObservesGlobalOverridesMembershipAndRemoval() =
        runTest {
            val podcast = podcast()
            val group = group()
            repo.updateGroup(group) { SettingOverrides(60, true) }
            repo.observePodcast(podcast).test {
                assertEquals(defaults, awaitItem()?.effective)
                settings.set(FeedsSettingKeys.NOTIFY_NEW_EPISODES, true)
                assertEquals(true, awaitItem()?.effective?.notifyNewEpisodes)
                db.groupDao().insertMember(memberEntity(group, podcast, "a"))
                assertEquals(60, awaitItem()?.effective?.refreshIntervalMinutes)
                repo.updatePodcast(podcast) { it.copy(notifyNewEpisodes = false) }
                val own = assertNotNull(awaitItem())
                assertEquals(false, own.effective.notifyNewEpisodes)
                assertEquals(SettingSource.PodcastOverride, own.sources[SettingField.NOTIFY_NEW_EPISODES])
                db.useWriterConnection { it.execSql("DELETE FROM podcast_group_member WHERE podcastId = $podcast") }
                val removed = assertNotNull(awaitItem())
                assertEquals(240, removed.effective.refreshIntervalMinutes)
                assertEquals(SettingSource.AppDefault, removed.sources[SettingField.REFRESH_INTERVAL_MINUTES])
                db.useWriterConnection { it.execSql("DELETE FROM podcast WHERE id = $podcast") }
                assertNull(awaitItem())
            }
            repo.observeGroup(group).test {
                assertNotNull(awaitItem())
                db.useWriterConnection { it.execSql("DELETE FROM podcast_group WHERE id = $group") }
                assertNull(awaitItem())
            }
        }

    @Test fun sqliteWriteFailureReturnsWriteFailedAndRollsBack() =
        runTest {
            val podcast = podcast()
            repo.updatePodcast(podcast) { SettingOverrides(60, false) }
            val before = dao.forPodcast(podcast)
            refresh.calls.clear()
            db.useWriterConnection {
                it.execSql(
                    "CREATE TRIGGER fail_settings BEFORE UPDATE ON podcast_settings" +
                        " BEGIN SELECT RAISE(ABORT, 'write fault'); END",
                )
            }
            assertEquals(
                Outcome.Failure(SettingsError.WriteFailed),
                repo.updatePodcast(podcast) {
                    SettingOverrides(
                        120,
                        true,
                    )
                },
            )
            assertEquals(before, dao.forPodcast(podcast))
            assertTrue(refresh.calls.isEmpty())
        }

    @Test fun onlyCommittedIntervalChangesReschedule() =
        runTest {
            val podcast = podcast()
            val group = group()
            repo.updatePodcast(podcast) { SettingOverrides(60) }
            repo.updateGroup(group) { SettingOverrides(0) }
            assertEquals(List(2) { "reschedulePeriodic()" }, refresh.calls)

            repo.updatePodcast(podcast) { it }
            repo.updateGroup(group) { it.copy(notifyNewEpisodes = true) }
            repo.updatePodcast(podcast) { it.copy(notifyNewEpisodes = false) }
            repo.updatePodcast(podcast) { it.copy(refreshIntervalMinutes = 61) }
            repo.updateGroup(group) { it.copy(refreshIntervalMinutes = -1) }
            repo.updatePodcast(-1) { error("Missing podcast") }
            repo.updateGroup(-1) { error("Missing group") }
            assertEquals(2, refresh.calls.size)

            repo.updatePodcast(podcast) { it.copy(refreshIntervalMinutes = null) }
            repo.updateGroup(group) { it.copy(refreshIntervalMinutes = null) }
            assertEquals(List(4) { "reschedulePeriodic()" }, refresh.calls)
        }

    @Test fun rescheduleSeesCommittedOverridesOutsideTheWriter() =
        runTest {
            val podcast = podcast()
            var observed: Int? = null
            val controller =
                object : RefreshController by refresh {
                    override suspend fun reschedulePeriodic() {
                        // An independent reader would see the old value if this hook ran before commit.
                        observed =
                            CoroutineScope(Dispatchers.Default)
                                .async {
                                    dao.forPodcast(podcast)?.o?.refreshIntervalMinutes
                                }.await()
                    }
                }
            val hooked =
                ScopeSettingsRepositoryImpl(
                    db,
                    settings,
                    { controller },
                    FakeGroupChannelSync(),
                    FakeSnapshotScheduler(),
                    capabilities =
                        ch.lkmc.neutrodyne.core.testing
                            .FakeYouTubeCapabilitiesSource(),
                )
            assertEquals(Outcome.Success(Unit), hooked.updatePodcast(podcast) { SettingOverrides(120) })
            assertEquals(120, observed)
        }

    @Test fun rescheduleFailureDoesNotMisreportTheCommittedWrite() =
        runTest {
            val podcast = podcast()
            val controller =
                object : RefreshController by refresh {
                    override suspend fun reschedulePeriodic(): Unit =
                        throw SQLiteException("Scheduler database unavailable")
                }
            val hooked =
                ScopeSettingsRepositoryImpl(
                    db,
                    settings,
                    { controller },
                    FakeGroupChannelSync(),
                    FakeSnapshotScheduler(),
                    capabilities =
                        ch.lkmc.neutrodyne.core.testing
                            .FakeYouTubeCapabilitiesSource(),
                )
            assertEquals(Outcome.Success(Unit), hooked.updatePodcast(podcast) { SettingOverrides(60) })
            assertEquals(60, dao.forPodcast(podcast)?.o?.refreshIntervalMinutes)
        }

    @Test fun globalNotificationKeyIsRegisteredPortableAndNotSynced() {
        val key = FeedsSettingKeys.NOTIFY_NEW_EPISODES
        assertEquals("feeds.notify_new_episodes", key.name)
        assertFalse(key.default)
        assertFalse(key.synced)
        assertEquals(SettingsFile.PORTABLE, key.file)
        assertTrue(key in AllSettingKeys.list)
    }

    @Test fun playbackWritesValidateAndPreserveReservedColumns() =
        runTest {
            val podcast = podcast()
            val group = group()
            val reserved = ScopeOverrides(boostDb = 2f, autoDownload = true, introSkipMs = 42)
            dao.upsertPodcast(PodcastSettingsEntity(podcast, reserved))
            dao.upsertGroup(PodcastGroupSettingsEntity(group, reserved))
            for (speed in listOf(0.5f, 1f, 1.05f, 2.95f, 3f, null)) {
                assertEquals(
                    Outcome.Success(Unit),
                    repo.updatePodcast(podcast) { it.copy(playbackSpeed = speed, skipSilence = false) },
                )
                assertEquals(
                    Outcome.Success(Unit),
                    repo.updateGroup(group) { it.copy(playbackSpeed = speed, skipSilence = true) },
                )
                assertEquals(reserved.copy(playbackSpeed = speed, skipSilence = false), dao.forPodcast(podcast)?.o)
                assertEquals(reserved.copy(playbackSpeed = speed, skipSilence = true), dao.forGroup(group)?.o)
            }
            for (speed in listOf(0.49f, 3.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
                val before = dao.forPodcast(podcast)
                val failure = Outcome.Failure(SettingsError.OutOfRange("playbackSpeed"))
                assertEquals(failure, repo.updatePodcast(podcast) { it.copy(playbackSpeed = speed) })
                assertEquals(failure, repo.updateGroup(group) { it.copy(playbackSpeed = speed) })
                assertEquals(before, dao.forPodcast(podcast))
            }
            assertTrue(refresh.calls.isEmpty())
        }

    @Test fun anInvalidLegacySpeedDoesNotBlockUnrelatedEdits() =
        runTest {
            val podcast = podcast()
            val group = group()
            val old = ScopeOverrides(playbackSpeed = 1.21f, refreshIntervalMinutes = 60, boostDb = 2f)
            dao.upsertPodcast(PodcastSettingsEntity(podcast, old))
            dao.upsertGroup(PodcastGroupSettingsEntity(group, old))
            assertEquals(
                Outcome.Success(Unit),
                repo.updatePodcast(podcast) {
                    assertNull(it.playbackSpeed)
                    it.copy(notifyNewEpisodes = true)
                },
            )
            assertEquals(Outcome.Success(Unit), repo.updateGroup(group) { it.copy(notifyNewEpisodes = true) })
            val healed = old.copy(playbackSpeed = null, notifyNewEpisodes = true)
            assertEquals(healed, dao.forPodcast(podcast)?.o)
            assertEquals(healed, dao.forGroup(group)?.o)
            assertTrue(refresh.calls.isEmpty())
        }

    @Test fun playerScopeWritesClearOnlyTheEditedFieldOnTheCurrentMemberChain() =
        runTest {
            val podcast = podcast()
            val otherPodcast = podcast()
            val group = group()
            val unrelatedGroup = group("Other")
            db.groupDao().insertMember(memberEntity(group, podcast, "a"))
            db.groupDao().insertMember(memberEntity(unrelatedGroup, podcast, "a"))
            val original =
                ScopeOverrides(
                    playbackSpeed = 2f,
                    skipSilence = true,
                    refreshIntervalMinutes = 60,
                    boostDb = 3f,
                )
            for (id in listOf(podcast, otherPodcast)) dao.upsertPodcast(PodcastSettingsEntity(id, original))
            for (id in listOf(group, unrelatedGroup)) dao.upsertGroup(PodcastGroupSettingsEntity(id, original))
            assertEquals(
                Outcome.Success(APPLIED),
                repo.writePlayback(
                    podcast,
                    group,
                    GROUP,
                    Speed(1.5f),
                ),
            )
            assertEquals(original.copy(playbackSpeed = null), dao.forPodcast(podcast)?.o)
            assertEquals(original.copy(playbackSpeed = 1.5f), dao.forGroup(group)?.o)
            assertEquals(
                Outcome.Success(APPLIED),
                repo.writePlayback(
                    podcast,
                    group,
                    GLOBAL,
                    SkipSilence(false),
                ),
            )
            assertEquals(original.copy(playbackSpeed = null, skipSilence = null), dao.forPodcast(podcast)?.o)
            assertEquals(original.copy(playbackSpeed = 1.5f, skipSilence = null), dao.forGroup(group)?.o)
            assertEquals(false, settings.get(PlaybackSettingKeys.SKIP_SILENCE))
            assertEquals(original, dao.forGroup(unrelatedGroup)?.o)
            assertEquals(original, dao.forPodcast(otherPodcast)?.o)
            assertTrue(refresh.calls.isEmpty())
        }

    @Test fun nonmemberAndDeletedGroupCannotBeWrittenOrClearedByPlayer() =
        runTest {
            val podcast = podcast()
            val group = group()
            repo.updatePodcast(podcast) { it.copy(playbackSpeed = 2f) }
            repo.updateGroup(group) { it.copy(playbackSpeed = 1.5f) }
            assertEquals(
                Outcome.Success(NO_CONTEXT_GROUP),
                repo.writePlayback(
                    podcast,
                    group,
                    GROUP,
                    Speed(1.1f),
                ),
            )
            assertEquals(2f, dao.forPodcast(podcast)?.o?.playbackSpeed)
            repo.writePlayback(
                podcast,
                group,
                GLOBAL,
                Speed(1.1f),
            )
            assertEquals(1.5f, dao.forGroup(group)?.o?.playbackSpeed)
            assertNull(dao.forPodcast(podcast))
            db.groupDao().insertMember(memberEntity(group, podcast, "a"))
            db.useWriterConnection { it.execSql("DELETE FROM podcast_group WHERE id = $group") }
            assertEquals(
                Outcome.Success(NO_CONTEXT_GROUP),
                repo.writePlayback(
                    podcast,
                    group,
                    GROUP,
                    Speed(1.1f),
                ),
            )
            assertEquals(
                Outcome.Success(MISSING_PODCAST),
                repo.writePlayback(
                    -1,
                    group,
                    PODCAST,
                    Speed(1.1f),
                ),
            )
        }

    @Test fun playerWriteRollsBackClearsOnSqlAndGlobalStoreFailures() =
        runTest {
            val podcast = podcast()
            val group = group()
            db.groupDao().insertMember(memberEntity(group, podcast, "a"))
            val original = ScopeOverrides(playbackSpeed = 2f, skipSilence = true)
            dao.upsertPodcast(PodcastSettingsEntity(podcast, original))
            dao.upsertGroup(PodcastGroupSettingsEntity(group, original))
            settings.failNextSet = SettingsError.WriteFailed
            assertEquals(
                Outcome.Failure(SettingsError.WriteFailed),
                repo.writePlayback(
                    podcast,
                    group,
                    GLOBAL,
                    Speed(1.1f),
                ),
            )
            assertEquals(original, dao.forPodcast(podcast)?.o)
            assertEquals(original, dao.forGroup(group)?.o)
            assertEquals(1f, settings.get(PlaybackSettingKeys.SPEED))
            // Fail after the podcast clear, proving the group failure rolls the chain back.
            db.useWriterConnection {
                it.execSql(
                    "CREATE TRIGGER fail_playback BEFORE UPDATE ON podcast_group_settings" +
                        " BEGIN SELECT RAISE(ABORT, 'fault'); END",
                )
            }
            assertEquals(
                Outcome.Failure(SettingsError.WriteFailed),
                repo.writePlayback(
                    podcast,
                    group,
                    GROUP,
                    Speed(1.1f),
                ),
            )
            assertEquals(original, dao.forPodcast(podcast)?.o)
            assertEquals(original, dao.forGroup(group)?.o)
            assertEquals(
                Outcome.Failure(SettingsError.WriteFailed),
                repo.writePlayback(
                    podcast,
                    group,
                    GLOBAL,
                    Speed(1.1f),
                ),
            )
            assertEquals(1f, settings.get(PlaybackSettingKeys.SPEED))
        }

    @Test fun settingsViewsExposePlaybackAndMemberHintsWithoutContextInheritance() =
        runTest {
            val podcast = podcast()
            val group = group()
            db.groupDao().insertMember(memberEntity(group, podcast, "a"))
            repo.updateGroup(group) { it.copy(playbackSpeed = 1.5f, skipSilence = true) }
            val podcastView = assertNotNull(repo.observePodcast(podcast).first())
            assertEquals(1f, podcastView.effective.playbackSpeed)
            assertEquals(SettingSource.AppDefault, podcastView.sources[SettingField.PLAYBACK_SPEED])
            assertEquals(listOf(group), podcastView.groupHints.map { it.groupId })
            assertEquals(1.5f, podcastView.groupHints.single().speed)
            val groupView = assertNotNull(repo.observeGroup(group).first())
            assertEquals(1.5f, groupView.effective.playbackSpeed)
            assertTrue(groupView.sources[SettingField.PLAYBACK_SPEED] is SettingSource.Group)
        }

    private suspend fun podcast(): Long = db.podcastDao().insertPodcast(podcastEntity())

    private suspend fun group(name: String = "Group"): Long =
        db.groupDao().insert(groupEntity(name = name, orderKey = "a"))
}
