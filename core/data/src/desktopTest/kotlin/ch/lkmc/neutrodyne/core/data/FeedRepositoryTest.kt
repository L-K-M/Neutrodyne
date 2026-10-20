// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.paging.PagingData
import androidx.paging.testing.asSnapshot
import app.cash.turbine.test
import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.data.repo.FeedRepositoryImpl
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.datastore.DataStoreBindings
import ch.lkmc.neutrodyne.core.datastore.SettingsDataStoreFactory
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.model.EpisodeRow
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedPrefs
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.FeedSourceRef
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.ShowType
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.GroupsSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakePlayContextResolver
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real Room + both DataStore files, including close/reopen of every persistence owner (AC10). */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedRepositoryTest {
    private val root = createTempDirectory("nd-feed-prefs")
    private lateinit var db: NeutrodyneDatabase
    private lateinit var settings: DataStoreSettingsRepository
    private lateinit var settingsJob: Job
    private lateinit var repo: FeedRepositoryImpl
    private lateinit var clock: TestClock

    @Test fun tabsFollowGroupOrderAndUngroupedRequiresBothSettingAndMembershipGap() =
        repositoryTest {
            val later = db.groupDao().insert(groupEntity(name = "Later", orderKey = "z"))
            val first =
                db.groupDao().insert(
                    groupEntity(name = "First", orderKey = "a").copy(colorArgb = 123, iconKey = "star"),
                )
            db.groupDao().insert(groupEntity(name = "Hidden", orderKey = "b").copy(showAsTab = false))
            repo.observeTabs().test {
                val initial = awaitItem()
                assertEquals(
                    listOf(FeedSource.All, FeedSource.Group(first), FeedSource.Group(later)),
                    initial.map { it.source },
                )
                val tab = initial[1]
                assertEquals("First", tab.title)
                assertEquals(123, tab.colorArgb)
                assertEquals("star", tab.iconKey)
                assertEquals(db.groupDao().groupById(first)?.uuid, tab.groupUuid)
                settings.set(GroupsSettingKeys.SHOW_UNGROUPED_TAB, true)
                expectNoEvents()
                val podcast = db.podcastDao().insertPodcast(podcastEntity { copy(includeInAll = false) })
                assertEquals(FeedSource.Ungrouped, awaitItem().last().source)
                db.groupDao().insertMember(memberEntity(first, podcast, "a"))
                assertEquals(initial, awaitItem())
                val other = db.podcastDao().insertPodcast(podcastEntity())
                assertEquals(FeedSource.Ungrouped, awaitItem().last().source)
                settings.set(GroupsSettingKeys.SHOW_UNGROUPED_TAB, false)
                assertEquals(initial, awaitItem())
                settings.set(GroupsSettingKeys.SHOW_UNGROUPED_TAB, true)
                assertEquals(FeedSource.Ungrouped, awaitItem().last().source)
                db.podcastDao().deleteCascade(other, clock.now())
                assertEquals(initial, awaitItem())
            }
        }

    @Test fun perSourcePreferencesAndSelectionsSurviveDatabaseAndSettingsReopen() =
        repositoryTest {
            val group = db.groupDao().insert(groupEntity(orderKey = "a").copy(playOrder = FeedOrder.OLDEST_FIRST))
            val podcast = db.podcastDao().insertPodcast(podcastEntity())
            val sources = listOf(FeedSource.All, FeedSource.Ungrouped, FeedSource.Group(group))
            val filters =
                listOf(
                    FeedFilters(unplayedOnly = true, media = MediaFilter.AUDIO, minSortDate = 123),
                    FeedFilters(downloadedOnly = true, media = MediaFilter.VIDEO),
                    FeedFilters(inProgressOnly = true, unplayedOnly = true, downloadedOnly = true),
                )
            for ((index, source) in sources.withIndex()) {
                repo.setFilters(source, filters[index])
                repo.setHideOlderThanDays(source, listOf(1, 7, 30)[index])
                repo.markVisited(source, 100L + index)
            }
            repo.setFeedOrder(FeedSource.Group(group), FeedOrder.OLDEST_FIRST)
            repo.setFeedOrder(FeedSource.Podcast(podcast), FeedOrder.OLDEST_FIRST)
            val ref = FeedSourceRef.Group(assertNotNull(db.groupDao().groupById(group)).uuid)
            settings.set(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE, ref.encode())
            settings.set(AppearanceSettingKeys.LIBRARY_GROUP_FILTER, ref.uuid)
            val before = (sources + FeedSource.Podcast(podcast)).associateWith { repo.observePrefs(it).first() }
            assertEquals(
                filters.map {
                    it.copy(minSortDate = null)
                },
                sources.map { before.getValue(it).filters.copy(minSortDate = null) },
            )
            assertEquals(FeedOrder.OLDEST_FIRST, before.getValue(FeedSource.Group(group)).playOrder)
            close()
            open()
            for ((source, prefs) in before) assertEquals(prefs, repo.observePrefs(source).first())
            assertEquals(ref, FeedSourceRef.decode(settings.get(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE)))
            assertEquals(ref.uuid, settings.get(AppearanceSettingKeys.LIBRARY_GROUP_FILTER))
            val portable = settings.observePortableSnapshot().first()
            assertTrue("groups.all_filter_flags" in portable)
            assertTrue(portable.keys.none { it.startsWith("ui.") || it.endsWith("last_viewed_at") })
        }

    @Test fun podcastReadsOnlyOrderWithSerialFallbackAndRejectsPersistedFilters() =
        repositoryTest {
            val serial = db.podcastDao().insertPodcast(podcastEntity { copy(showType = ShowType.SERIAL) })
            val episodic = db.podcastDao().insertPodcast(podcastEntity())
            assertEquals(FeedOrder.OLDEST_FIRST, repo.observePrefs(FeedSource.Podcast(serial)).first().feedOrder)
            assertEquals(FeedPrefs(), repo.observePrefs(FeedSource.Podcast(episodic)).first())
            val source = FeedSource.Podcast(serial)
            assertFailsWith<IllegalArgumentException> { repo.setFilters(source, FeedFilters()) }
            assertFailsWith<IllegalArgumentException> { repo.setHideOlderThanDays(source, null) }
            repo.setFeedOrder(source, FeedOrder.NEWEST_FIRST)
            assertEquals(FeedPrefs(), repo.observePrefs(source).first())
            for (virtual in listOf(FeedSource.All, FeedSource.Ungrouped)) {
                assertFailsWith<IllegalArgumentException> { repo.setFeedOrder(virtual, FeedOrder.NEWEST_FIRST) }
            }
        }

    @Test fun ageValidationAndIndependentWritesPreserveOtherGroupColumns() =
        repositoryTest {
            val group = db.groupDao().insert(groupEntity(orderKey = "a").copy(playOrder = FeedOrder.OLDEST_FIRST))
            for (source in listOf(FeedSource.All, FeedSource.Ungrouped, FeedSource.Group(group))) {
                repo.setFilters(source, FeedFilters(unplayedOnly = true, media = MediaFilter.VIDEO, minSortDate = 12))
                for (days in listOf(null, 1, 3, 7, 14, 30, 90, 365)) {
                    repo.setHideOlderThanDays(source, days)
                    val prefs = repo.observePrefs(source).first()
                    assertEquals(days, prefs.hideOlderThanDays)
                    assertTrue(prefs.filters.unplayedOnly)
                    assertEquals(MediaFilter.VIDEO, prefs.filters.media)
                }
                val before = repo.observePrefs(source).first()
                for (invalid in listOf(-1, 0, 2, Int.MAX_VALUE)) {
                    assertFailsWith<IllegalArgumentException> { repo.setHideOlderThanDays(source, invalid) }
                    assertEquals(before, repo.observePrefs(source).first())
                }
                repo.setFilters(source, FeedFilters(inProgressOnly = true))
                assertEquals(365, repo.observePrefs(source).first().hideOlderThanDays)
            }
            assertEquals(FeedOrder.OLDEST_FIRST, db.groupDao().groupById(group)?.playOrder)
        }

    @Test fun visitWritesOnlyItsTargetAndInitializesVirtualVisitsOnce() =
        repositoryTest {
            val group = db.groupDao().insert(groupEntity(orderKey = "a"))
            val initial = assertNotNull(db.groupDao().groupById(group))
            assertNull(repo.observePrefs(FeedSource.Group(group)).first().lastViewedAt)
            assertEquals(clock.now(), repo.observePrefs(FeedSource.All).first().lastViewedAt)
            assertEquals(0, settings.get(GroupsSettingKeys.UNGROUPED_LAST_VIEWED_AT))
            repo.markVisited(FeedSource.All, 11)
            repo.markVisited(FeedSource.Ungrouped, 22)
            repo.markVisited(FeedSource.Group(group), 33)
            repo.markVisited(FeedSource.Podcast(-1), 44)
            assertEquals(11, repo.observePrefs(FeedSource.All).first().lastViewedAt)
            assertEquals(22, repo.observePrefs(FeedSource.Ungrouped).first().lastViewedAt)
            assertEquals(initial.copy(lastViewedAt = 33), db.groupDao().groupById(group))
            close()
            open()
            assertEquals(11, repo.observePrefs(FeedSource.All).first().lastViewedAt)
            assertEquals(22, repo.observePrefs(FeedSource.Ungrouped).first().lastViewedAt)
            assertEquals(33, repo.observePrefs(FeedSource.Group(group)).first().lastViewedAt)
        }

    @Test fun ageWindowRoundsDownAndAdvancesAtTheHourWhileCollected() =
        repositoryTest {
            val source = FeedSource.All
            repo.setHideOlderThanDays(source, 1)
            repo.observePrefs(source).test {
                val first = awaitItem()
                val rounded = clock.now() - clock.now().mod(FeedPrefs.HOUR_MS) - DAY_MS
                assertEquals(rounded, first.filters.minSortDate)
                clock.nowMs += 1_000
                advanceTimeBy(1_000)
                runCurrent()
                expectNoEvents()
                // A settings emission in the same hour must leave the query bound unchanged.
                repo.markVisited(source, 88)
                assertEquals(rounded, awaitItem().filters.minSortDate)
                val untilHour = FeedPrefs.HOUR_MS - clock.now().mod(FeedPrefs.HOUR_MS)
                clock.nowMs += untilHour
                advanceTimeBy(untilHour)
                runCurrent()
                assertEquals(rounded + FeedPrefs.HOUR_MS, awaitItem().filters.minSortDate)
                repo.setHideOlderThanDays(source, null)
                assertNull(awaitItem().filters.minSortDate)
            }
        }

    @Test fun persistedPagerAppliesFiltersAndAgeAndIgnoresVisitOnlyChanges() =
        repositoryTest {
            val podcast = db.podcastDao().insertPodcast(podcastEntity())
            val bound = clock.now() - clock.now().mod(FeedPrefs.HOUR_MS) - DAY_MS
            val episodes =
                listOf(
                    episodeEntity(podcastId = podcast, identityKey = "old", title = "old", sortDate = bound - 1),
                    episodeEntity(podcastId = podcast, identityKey = "audio", title = "audio", sortDate = bound),
                    episodeEntity(
                        podcastId = podcast,
                        identityKey = "video",
                        title = "video",
                        sortDate = bound + 1,
                    ).copy(isVideo = true),
                )
            db.ingestDao().insertEpisodes(episodes)
            repo.setHideOlderThanDays(FeedSource.All, 1)
            repo.setFilters(FeedSource.All, FeedFilters(media = MediaFilter.AUDIO))
            val channel = Channel<PagingData<EpisodeRow>>(Channel.UNLIMITED)
            val job = backgroundScope.launch { repo.pagedFeed(FeedSource.All).collect { channel.send(it) } }
            val first = channel.receive()
            assertEquals(listOf("audio"), flowOf(first).asSnapshot().map { it.title })
            repo.markVisited(FeedSource.All, 99)
            runCurrent()
            assertTrue(channel.tryReceive().isFailure)
            val untilHour = FeedPrefs.HOUR_MS - clock.now().mod(FeedPrefs.HOUR_MS)
            clock.nowMs += untilHour
            advanceTimeBy(untilHour)
            runCurrent()
            assertEquals(emptyList(), flowOf(channel.receive()).asSnapshot())
            repo.setHideOlderThanDays(FeedSource.All, null)
            assertEquals(2, flowOf(channel.receive()).asSnapshot().size)
            job.cancelAndJoin()
        }

    @Test fun settingsFailuresAreNotReportedAsSuccessfulWrites() =
        repositoryTest {
            val failingSettings = FakeSettingsRepository()
            val failingRepo =
                FeedRepositoryImpl(
                    db,
                    failingSettings,
                    clock,
                    FakePlayContextResolver(),
                    FakeYouTubeCapabilitiesSource(),
                )
            failingSettings.failNextSet = SettingsError.WriteFailed
            assertFailsWith<IllegalStateException> {
                failingRepo.setFilters(
                    FeedSource.All,
                    FeedFilters(unplayedOnly = true),
                )
            }
            assertEquals(0, failingSettings.get(GroupsSettingKeys.ALL_FILTER_FLAGS))
            failingSettings.failNextSet = SettingsError.WriteFailed
            assertFailsWith<IllegalStateException> { failingRepo.markVisited(FeedSource.All, 1) }
            failingSettings.failNextSet = SettingsError.WriteFailed
            assertFailsWith<IllegalStateException> { failingRepo.observePrefs(FeedSource.Ungrouped).first() }
        }

    private fun repositoryTest(block: suspend TestScope.() -> Unit) =
        runTest {
            // Start inside an hour to prove rounding and boundary-aligned recomputation.
            clock = TestClock(nowMs = TestClock.DEFAULT_NOW + 123_456)
            open()
            try {
                block()
            } finally {
                close()
                root.toFile().deleteRecursively()
            }
        }

    private fun TestScope.open() {
        db = TestDb.file(root.resolve("db"), clock = clock)
        settingsJob = SupervisorJob()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val dirs = AppDirs(data = root, config = root, cache = root, state = root, logs = root, downloadsDefault = root)
        val factory = SettingsDataStoreFactory(StoragePaths(dirs), CoroutineScope(settingsJob + dispatcher), dispatcher)
        settings =
            DataStoreSettingsRepository(
                DataStoreBindings.settingsStore(DataStoreBindings.portableDataStore(factory)),
                DataStoreBindings.deviceSettingsStore(DataStoreBindings.deviceDataStore(factory)),
            )
        repo = FeedRepositoryImpl(db, settings, clock, FakePlayContextResolver(), FakeYouTubeCapabilitiesSource())
    }

    private suspend fun close() {
        db.close()
        settingsJob.cancelAndJoin()
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
