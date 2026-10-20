// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data

import app.cash.turbine.test
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.settings.EffectiveSettingsResolverImpl
import ch.lkmc.neutrodyne.core.data.settings.ScopeSettingsRepositoryImpl
import ch.lkmc.neutrodyne.core.database.PodcastGroupSettingsEntity
import ch.lkmc.neutrodyne.core.database.PodcastSettingsEntity
import ch.lkmc.neutrodyne.core.database.ScopeOverrides
import ch.lkmc.neutrodyne.core.model.DeleteAfter
import ch.lkmc.neutrodyne.core.model.NetworkPolicy
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.FakeGroupChannelSync
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeSnapshotScheduler
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys as Keys

class AutoDownloadResolutionTest {
    private val db = TestDb.inMemory()
    private val settings = FakeSettingsRepository()
    private val capabilities = FakeYouTubeCapabilitiesSource()
    private val resolver = EffectiveSettingsResolverImpl(db, settings, capabilities)
    private val scopes =
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
    private val dao = db.scopeSettingsDao()

    @AfterTest fun close() = db.close()

    @Test fun defaultsBatchSelectionAndFullMap() =
        runTest {
            val id = podcast()
            val policy = resolver.autoDownload(listOf(id, id, -1)).getValue(id)
            assertFalse(policy.enabled)
            assertEquals(3, policy.keepLatest)
            assertEquals(NetworkPolicy.UNMETERED, policy.network)
            assertFalse(policy.requireCharging)
            assertFalse(policy.includeVideo)
            assertEquals(DeleteAfter.AFTER_24H, policy.deleteAfterPlayed)
            assertEquals(SettingSource.AppDefault, policy.source)
            assertEquals(mapOf(id to policy), resolver.observeAutoDownload().first())
            assertTrue(resolver.autoDownload(emptyList()).isEmpty())
        }

    @Test fun d45EnabledGroupMergeAndIndependentPodcastOverrides() =
        runTest {
            val id = podcast()
            group(
                id,
                "first",
                ScopeOverrides(
                    autoDownload = true,
                    autoDownloadKeepLatest = 2,
                    autoDownloadNetwork = NetworkPolicy.ANY,
                    autoDownloadRequireCharging = false,
                    includeVideoInAutoDownload = true,
                    deleteAfterPlayed = DeleteAfter.IMMEDIATELY,
                ),
            )
            val singleGroup = resolver.autoDownload(listOf(id)).getValue(id)
            assertEquals(2, singleGroup.keepLatest)
            assertEquals(NetworkPolicy.ANY, singleGroup.network)
            assertFalse(singleGroup.requireCharging)
            assertTrue(singleGroup.includeVideo)
            assertEquals(DeleteAfter.IMMEDIATELY, singleGroup.deleteAfterPlayed)
            group(
                id,
                "second",
                ScopeOverrides(
                    autoDownload = true,
                    autoDownloadKeepLatest = 7,
                    autoDownloadNetwork = NetworkPolicy.UNMETERED,
                    autoDownloadRequireCharging = true,
                    includeVideoInAutoDownload = false,
                    deleteAfterPlayed = DeleteAfter.NEVER,
                ),
            )
            group(
                id,
                "off",
                ScopeOverrides(
                    autoDownload = false,
                    autoDownloadKeepLatest = 10,
                    deleteAfterPlayed = DeleteAfter.AFTER_24H,
                ),
            )
            val merged = resolver.autoDownload(listOf(id)).getValue(id)
            assertTrue(merged.enabled)
            assertEquals(7, merged.keepLatest)
            assertEquals(NetworkPolicy.UNMETERED, merged.network)
            assertTrue(merged.requireCharging)
            assertFalse(merged.includeVideo)
            assertEquals(DeleteAfter.NEVER, merged.deleteAfterPlayed)
            assertIs<SettingSource.Groups>(merged.source)
            dao.upsertPodcast(
                PodcastSettingsEntity(
                    id,
                    ScopeOverrides(
                        autoDownload = false,
                        autoDownloadKeepLatest = 1,
                        autoDownloadNetwork = NetworkPolicy.ANY,
                        autoDownloadRequireCharging = false,
                        includeVideoInAutoDownload = true,
                        deleteAfterPlayed = DeleteAfter.IMMEDIATELY,
                    ),
                ),
            )
            val own = resolver.autoDownload(listOf(id)).getValue(id)
            assertFalse(own.enabled)
            assertEquals(1, own.keepLatest)
            assertEquals(NetworkPolicy.ANY, own.network)
            assertFalse(own.requireCharging)
            assertTrue(own.includeVideo)
            assertEquals(DeleteAfter.IMMEDIATELY, own.deleteAfterPlayed)
            assertEquals(SettingSource.PodcastOverride, own.source)
            // Independent fields still shape a group-enabled policy when enabled itself inherits.
            dao.upsertPodcast(PodcastSettingsEntity(id, ScopeOverrides(autoDownloadKeepLatest = 4)))
            val independent = resolver.autoDownload(listOf(id)).getValue(id)
            assertTrue(independent.enabled)
            assertEquals(4, independent.keepLatest)
            assertEquals(DeleteAfter.NEVER, independent.deleteAfterPlayed)
        }

    @Test fun falseGroupBeatsGlobalAndUnsetGroupFallsBack() =
        runTest {
            settings.set(Keys.AUTO_DOWNLOAD, true)
            settings.set(Keys.AUTO_DOWNLOAD_KEEP_LATEST, 5)
            val id = podcast()
            val group = group(id, "off", ScopeOverrides(autoDownload = false, autoDownloadKeepLatest = 10))
            assertFalse(resolver.autoDownload(listOf(id)).getValue(id).enabled)
            assertEquals(5, resolver.autoDownload(listOf(id)).getValue(id).keepLatest)
            dao.upsertGroup(PodcastGroupSettingsEntity(group, ScopeOverrides()))
            assertTrue(resolver.autoDownload(listOf(id)).getValue(id).enabled)
        }

    @Test fun groupWritesClearDependentsButKeepDeletionAndPodcastFields() =
        runTest {
            val id = podcast()
            val group = group(id, "group", ScopeOverrides())
            scopes.updateGroup(group) {
                it.copy(
                    autoDownload = true,
                    autoDownloadKeepLatest = 8,
                    autoDownloadNetwork = NetworkPolicy.ANY,
                    autoDownloadRequireCharging = true,
                    includeVideoInAutoDownload = true,
                    deleteAfterPlayed = DeleteAfter.NEVER,
                )
            }
            for (enabled in listOf(false, null)) {
                scopes.updateGroup(group) { it.copy(autoDownload = enabled, autoDownloadKeepLatest = 8) }
                val stored = requireNotNull(dao.forGroup(group)).o
                assertEquals(null, stored.autoDownloadKeepLatest)
                assertEquals(null, stored.autoDownloadNetwork)
                assertEquals(null, stored.autoDownloadRequireCharging)
                assertEquals(null, stored.includeVideoInAutoDownload)
                assertEquals(DeleteAfter.NEVER, stored.deleteAfterPlayed)
            }
            assertIs<Outcome.Failure<*>>(scopes.updatePodcast(id) { it.copy(autoDownloadKeepLatest = 11) })
            scopes.updatePodcast(id) { it.copy(autoDownload = false, autoDownloadKeepLatest = 6) }
            assertEquals(6, requireNotNull(dao.forPodcast(id)).o.autoDownloadKeepLatest)
            assertEquals(6, requireNotNull(scopes.observePodcast(id).first()).own.autoDownloadKeepLatest)
        }

    @Test fun notSupportedUsesGlobalDeletionAndFlipsWithoutSettingsWrites() =
        runTest {
            settings.set(Keys.AUTO_DOWNLOAD, true)
            val group = db.groupDao().insert(groupEntity(orderKey = "a0"))
            dao.upsertGroup(PodcastGroupSettingsEntity(group, ScopeOverrides(autoDownload = true)))
            val id = db.podcastDao().insertPodcast(podcastEntity { copy(sourceType = SourceType.YOUTUBE_CHANNEL) })
            dao.upsertPodcast(
                PodcastSettingsEntity(
                    id,
                    ScopeOverrides(
                        autoDownload = true,
                        autoDownloadNetwork = NetworkPolicy.ANY,
                        deleteAfterPlayed = DeleteAfter.NEVER,
                    ),
                ),
            )
            db.groupDao().insertMember(memberEntity(group, id, "a0"))
            resolver.observeAutoDownload().test {
                val absent = awaitItem().getValue(id)
                assertFalse(absent.enabled)
                assertEquals(SettingSource.NotSupported, absent.source)
                assertEquals(NetworkPolicy.UNMETERED, absent.network)
                assertEquals(DeleteAfter.AFTER_24H, absent.deleteAfterPlayed)
                capabilities.capabilities.value = capabilities.capabilities.value.copy(downloads = true)
                val supported = awaitItem().getValue(id)
                assertTrue(supported.enabled)
                assertEquals(SettingSource.PodcastOverride, supported.source)
                capabilities.capabilities.value = capabilities.capabilities.value.copy(enrichment = true)
                expectNoEvents()
                capabilities.capabilities.value = capabilities.capabilities.value.copy(downloads = false)
                assertEquals(absent, awaitItem().getValue(id))
                settings.set(Keys.DELETE_AFTER_PLAYED, DeleteAfter.IMMEDIATELY)
                assertEquals(DeleteAfter.IMMEDIATELY, awaitItem().getValue(id).deleteAfterPlayed)
            }
        }

    @Test fun observationsTrackMembershipNamesAndGlobalPolicy() =
        runTest {
            val id = podcast()
            resolver.observeAutoDownload().test {
                assertFalse(awaitItem().getValue(id).enabled)
                settings.set(Keys.AUTO_DOWNLOAD, true)
                assertTrue(awaitItem().getValue(id).enabled)
                val group = group(id, "news", ScopeOverrides(autoDownload = true))
                assertIs<SettingSource.Group>(awaitItem().getValue(id).source)
                db.groupDao().rename(group, "renamed", "renamed", 1)
                assertEquals("renamed", assertIs<SettingSource.Group>(awaitItem().getValue(id).source).name)
            }
        }

    private suspend fun podcast() = db.podcastDao().insertPodcast(podcastEntity())

    private suspend fun group(
        podcast: Long,
        name: String,
        overrides: ScopeOverrides,
    ): Long {
        val id = db.groupDao().insert(groupEntity(name = name, orderKey = "a"))
        dao.upsertGroup(PodcastGroupSettingsEntity(id, overrides))
        db.groupDao().insertMember(memberEntity(id, podcast, "a"))
        return id
    }
}
