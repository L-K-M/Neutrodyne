// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.useWriterConnection
import app.cash.turbine.test
import ch.lkmc.neutrodyne.core.data.settings.EffectiveSettingsResolverImpl
import ch.lkmc.neutrodyne.core.data.settings.ScopeSettingsRepositoryImpl
import ch.lkmc.neutrodyne.core.database.PodcastGroupSettingsEntity
import ch.lkmc.neutrodyne.core.database.PodcastSettingsEntity
import ch.lkmc.neutrodyne.core.database.ScopeOverrides
import ch.lkmc.neutrodyne.core.model.Effective
import ch.lkmc.neutrodyne.core.model.SettingField
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeGroupChannelSync
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeSnapshotScheduler
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Resolution + AC12 attribution data over the bundled driver; UI owns the displayed strings. */
class EffectiveSettingsResolverTest {
    private val db = TestDb.inMemory()
    private val settings = FakeSettingsRepository()
    private val resolver = EffectiveSettingsResolverImpl(db, settings)
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

    @Test fun absentOverridesUseAppDefaultAttribution() =
        runTest {
            val id = podcast()
            assertEquals(
                Effective(240, SettingSource.AppDefault),
                resolver.refreshIntervals()[id],
                "App default",
            )
            assertEquals(
                Effective(false, SettingSource.AppDefault),
                resolver.notifications(listOf(id))[id],
                "App default",
            )
            settings.set(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES, 720)
            settings.set(FeedsSettingKeys.NOTIFY_NEW_EPISODES, true)
            assertEquals(Effective(720, SettingSource.AppDefault), resolver.refreshIntervals()[id])
            assertEquals(Effective(true, SettingSource.AppDefault), resolver.notifications(listOf(id))[id])
        }

    @Test fun unsetGroupsDoNotPreventGlobalFallback() =
        runTest {
            val id = podcast()
            group(id, "news", null, null)
            settings.set(FeedsSettingKeys.NOTIFY_NEW_EPISODES, true)
            assertEquals(Effective(true, SettingSource.AppDefault), resolver.notifications(listOf(id))[id])
            assertEquals(Effective(240, SettingSource.AppDefault), resolver.refreshIntervals()[id])
        }

    @Test fun podcastOverrideWinsForBothFieldsAndSuppliesPodcastAttribution() =
        runTest {
            val id = podcast()
            group(id, "news", 60, true)
            dao.upsertPodcast(
                PodcastSettingsEntity(
                    id,
                    ScopeOverrides(
                        refreshIntervalMinutes = 480,
                        notifyNewEpisodes = false,
                    ),
                ),
            )
            assertEquals(
                Effective(
                    480,
                    SettingSource.PodcastOverride,
                ),
                resolver.refreshIntervals()[id],
                "Set for this podcast",
            )
            assertEquals(
                Effective(
                    false,
                    SettingSource.PodcastOverride,
                ),
                resolver.notifications(listOf(id))[id],
                "Set for this podcast",
            )
        }

    @Test fun podcastTrueOverridesGroupFalseAndEachFieldInheritsIndependently() =
        runTest {
            val id = podcast()
            val source = group(id, "news", 120, false).second
            dao.upsertPodcast(PodcastSettingsEntity(id, ScopeOverrides(notifyNewEpisodes = true)))
            assertEquals(Effective(120, source), resolver.refreshIntervals()[id])
            assertEquals(Effective(true, SettingSource.PodcastOverride), resolver.notifications(listOf(id))[id])
            dao.upsertPodcast(PodcastSettingsEntity(id, ScopeOverrides(refreshIntervalMinutes = 720)))
            assertEquals(Effective(false, source), resolver.notifications(listOf(id))[id])
        }

    @Test fun singleGroupSuppliesFromGroupNewsAttribution() =
        runTest {
            val id = podcast()
            val source = group(id, "news", 120, true).second
            val view = assertNotNull(scopes.observePodcast(id).first())
            assertEquals(120, view.effective.refreshIntervalMinutes)
            assertEquals(true, view.effective.notifyNewEpisodes)
            assertEquals(source, view.sources[SettingField.REFRESH_INTERVAL_MINUTES], "From group 'news'")
            assertEquals(source, view.sources[SettingField.NOTIFY_NEW_EPISODES], "From group 'news'")
        }

    @Test fun shortestGroupIntervalWinsAndOnlyDecidingGroupIsAttributed() =
        runTest {
            val id = podcast()
            group(id, "slow", 720, false)
            val source = group(id, "news", 60, null).second
            assertEquals(Effective(60, source), resolver.refreshIntervals()[id])
        }

    @Test fun tiedGroupsAreAttributedInGroupOrder() =
        runTest {
            val id = podcast()
            val news = group(id, "news", 120, true, "b").second
            val tech = group(id, "tech", 120, true, "a").second
            group(id, "slow", 480, false, "c")
            val source = SettingSource.Groups(listOf(tech, news))
            assertEquals(Effective(120, source), resolver.refreshIntervals()[id])
            assertEquals(Effective(true, source), resolver.notifications(listOf(id))[id])
        }

    @Test fun manualOnlyGroupResolvesToNullInsteadOfGlobalInterval() =
        runTest {
            val id = podcast()
            val source = group(id, "news", 0, null).second
            assertEquals(Effective(null, source), resolver.refreshIntervals()[id], "From group 'news'")
            assertEquals(
                null,
                scopes
                    .observePodcast(id)
                    .first()
                    ?.effective
                    ?.refreshIntervalMinutes,
            )
        }

    @Test fun timedGroupBeatsManualOnlyAndAllManualGroupsAreAttributed() =
        runTest {
            val id = podcast()
            val news = group(id, "news", 0, null, "a").second
            val (timedId, tech) = group(id, "tech", 720, null, "b")
            assertEquals(Effective(720, tech), resolver.refreshIntervals()[id])
            dao.upsertGroup(PodcastGroupSettingsEntity(timedId, ScopeOverrides(refreshIntervalMinutes = 0)))
            assertEquals(
                Effective(null, SettingSource.Groups(listOf(news, tech))),
                resolver.refreshIntervals()[id],
            )
        }

    @Test fun podcastManualOnlyBeatsTimedGroupsAndGlobalManualOnlyIsNull() =
        runTest {
            val id = podcast()
            group(id, "news", 60, null)
            dao.upsertPodcast(PodcastSettingsEntity(id, ScopeOverrides(refreshIntervalMinutes = 0)))
            assertEquals(Effective(null, SettingSource.PodcastOverride), resolver.refreshIntervals()[id])
            val ungrouped = podcast()
            settings.set(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES, 0)
            assertEquals(Effective(null, SettingSource.AppDefault), resolver.refreshIntervals()[ungrouped])
        }

    @Test fun anyTrueNotificationWinsAndOnlyTrueGroupsAreAttributed() =
        runTest {
            val id = podcast()
            group(id, "off", null, false)
            val source = group(id, "news", null, true).second
            group(id, "inherit", null, null)
            assertEquals(Effective(true, source), resolver.notifications(listOf(id))[id])
        }

    @Test fun falseGroupsBeatGlobalTrueAndOnlySetGroupsAreAttributed() =
        runTest {
            val id = podcast()
            val first = group(id, "news", null, false, "a").second
            val second = group(id, "tech", null, false, "b").second
            group(id, "inherit", null, null)
            settings.set(FeedsSettingKeys.NOTIFY_NEW_EPISODES, true)
            assertEquals(
                Effective(
                    false,
                    SettingSource.Groups(
                        listOf(
                            first,
                            second,
                        ),
                    ),
                ),
                resolver.notifications(listOf(id))[id],
            )
        }

    @Test fun batchesIncludeUngroupedAndGroupedPodcastsAndOmitMissingIds() =
        runTest {
            val id = podcast()
            val other = podcast()
            val source = group(id, "news", 60, true).second
            assertEquals(
                mapOf(
                    id to
                        Effective(
                            60,
                            source,
                        ),
                    other to
                        Effective(
                            240,
                            SettingSource.AppDefault,
                        ),
                ),
                resolver.refreshIntervals(),
            )
            assertEquals(mapOf(id to Effective(true, source)), resolver.notifications(listOf(id, id, -1)))
            assertEquals(emptyMap(), resolver.notifications(emptyList()))
            assertEquals(emptyMap(), resolver.observeNotifications(emptyList()).first())
        }

    /** 02's 500-variable chunks: every podcast resolves once, with group rows kept together. */
    @Test fun batchesSpanningSeveralBindChunksResolveEveryPodcast() =
        runTest {
            val ids = List(CHUNKED_BATCH) { podcast() }
            val grouped = ids.last()
            val source = group(grouped, "news", 60, true).second
            val expected =
                ids.associateWith {
                    Effective(
                        it == grouped,
                        if (it ==
                            grouped
                        ) {
                            source
                        } else {
                            SettingSource.AppDefault
                        },
                    )
                }

            assertEquals(expected, resolver.notifications(ids.reversed()))
            assertEquals(expected, resolver.observeNotifications(ids).first())
        }

    @Test fun resolverFlowsObserveGlobalsMembershipGroupNameAndOverrides() =
        runTest {
            val id = podcast()
            resolver.observeRefreshIntervals().test {
                assertEquals(Effective(240, SettingSource.AppDefault), awaitItem()[id])
                settings.set(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES, 480)
                assertEquals(Effective(480, SettingSource.AppDefault), awaitItem()[id])
                val (groupId, source) = group(id, "news", 120, true)
                assertEquals(Effective(120, source), awaitItem()[id])
                db.groupDao().rename(groupId, "renamed", "renamed", 42)
                assertEquals(Effective(120, source.copy(name = "renamed")), awaitItem()[id])
                dao.upsertPodcast(PodcastSettingsEntity(id, ScopeOverrides(refreshIntervalMinutes = 0)))
                assertEquals(Effective(null, SettingSource.PodcastOverride), awaitItem()[id])
            }
            resolver.observeNotifications(listOf(id)).test {
                assertEquals(true, awaitItem()[id]?.value)
                dao.upsertPodcast(PodcastSettingsEntity(id, ScopeOverrides(notifyNewEpisodes = false)))
                assertEquals(Effective(false, SettingSource.PodcastOverride), awaitItem()[id])
            }
        }

    @Test fun playbackUsesOnlyMemberContextGroupAndEachFieldResolvesIndependently() =
        runTest {
            val member = podcast()
            val outsider = podcast()
            val (news, newsSource) = group(member, "news", 60, true)
            val (tech, techSource) = group(member, "tech", 120, false)
            scopes.updateGroup(news) { it.copy(playbackSpeed = 1.5f, skipSilence = true) }
            scopes.updateGroup(tech) { it.copy(playbackSpeed = 2f, skipSilence = false) }
            settings.set(PlaybackSettingKeys.SPEED, 1.1f)
            assertEquals(Effective(1.5f, newsSource), resolver.playback(member, news).speed)
            assertEquals(Effective(2f, techSource), resolver.playback(member, tech).speed)
            assertEquals(Effective(1.1f, SettingSource.AppDefault), resolver.playback(member, null).speed)
            // An Up next item's podcast can be outside the session's group.
            assertEquals(Effective(1.1f, SettingSource.AppDefault), resolver.playback(outsider, news).speed)
            assertEquals(Effective(false, SettingSource.AppDefault), resolver.playback(outsider, news).skipSilence)
            scopes.updatePodcast(member) { it.copy(playbackSpeed = 1.8f) }
            assertEquals(Effective(1.8f, SettingSource.PodcastOverride), resolver.playback(member, news).speed)
            assertEquals(Effective(true, newsSource), resolver.playback(member, news).skipSilence)
            assertEquals(Effective(60, newsSource), resolver.refreshIntervals()[member])
            assertEquals(Effective(true, newsSource), resolver.notifications(listOf(member))[member])
        }

    @Test fun playbackObservationTracksMembershipRenameOverridesGlobalsAndDeletedContext() =
        runTest {
            val id = podcast()
            val (group, source) = group(id, "news", null, null)
            scopes.updateGroup(group) { it.copy(playbackSpeed = 1.5f, skipSilence = true) }
            resolver.observePlayback(id, group).test {
                assertEquals(Effective(1.5f, source), awaitItem().speed)
                db.groupDao().rename(group, "renamed", "renamed", 10)
                assertEquals(Effective(1.5f, source.copy(name = "renamed")), awaitItem().speed)
                scopes.updatePodcast(id) { it.copy(playbackSpeed = 2f) }
                assertEquals(Effective(2f, SettingSource.PodcastOverride), awaitItem().speed)
                scopes.updatePodcast(id) { it.copy(playbackSpeed = null) }
                assertEquals(1.5f, awaitItem().speed.value)
                db.useWriterConnection { it.execSql("DELETE FROM podcast_group_member WHERE groupId = $group") }
                assertEquals(Effective(1f, SettingSource.AppDefault), awaitItem().speed)
                db.groupDao().insertMember(memberEntity(group, id, "a"))
                assertEquals(1.5f, awaitItem().speed.value)
                db.useWriterConnection { it.execSql("DELETE FROM podcast_group WHERE id = $group") }
                assertEquals(Effective(1f, SettingSource.AppDefault), awaitItem().speed)
                settings.set(PlaybackSettingKeys.SPEED, 1.2f)
                assertEquals(Effective(1.2f, SettingSource.AppDefault), awaitItem().speed)
                settings.set(PlaybackSettingKeys.SKIP_SILENCE, true)
                assertEquals(Effective(true, SettingSource.AppDefault), awaitItem().skipSilence)
            }
            assertEquals(resolver.playback(-1, group), resolver.observePlayback(-1, group).first())
        }

    private suspend fun podcast(): Long = db.podcastDao().insertPodcast(podcastEntity())

    private suspend fun group(
        podcastId: Long,
        name: String,
        refresh: Int?,
        notify: Boolean?,
        order: String = "a",
    ): Pair<Long, SettingSource.Group> {
        val row = groupEntity(name = name, orderKey = order)
        val id = db.groupDao().insert(row)
        // Populate the group before membership to make one atomic visible policy transition.
        dao.upsertGroup(
            PodcastGroupSettingsEntity(
                id,
                ScopeOverrides(
                    refreshIntervalMinutes = refresh,
                    notifyNewEpisodes = notify,
                ),
            ),
        )
        db.groupDao().insertMember(memberEntity(id, podcastId, "a"))
        return id to SettingSource.Group(row.uuid, name)
    }

    private companion object {
        /** Three 500-ID chunks, the last one partial. */
        const val CHUNKED_BATCH = 1_201
    }
}
