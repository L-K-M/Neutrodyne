// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.refresh.NextRefreshRebaser
import ch.lkmc.neutrodyne.core.data.refresh.RefreshPolicy
import ch.lkmc.neutrodyne.core.data.settings.EffectiveSettingsResolverImpl
import ch.lkmc.neutrodyne.core.database.PodcastGroupSettingsEntity
import ch.lkmc.neutrodyne.core.database.PodcastSettingsEntity
import ch.lkmc.neutrodyne.core.database.ScopeOverrides
import ch.lkmc.neutrodyne.core.domain.EffectiveSettingsResolver
import ch.lkmc.neutrodyne.core.model.Effective
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeEffectiveSettingsResolver
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 03 Periodic tick step 2 — the `nextRefreshAt` rebase after interval changes: healthy rows move
 * to `COALESCE(lastSuccessAt, subscribedAt) + I` (`NEVER` under "Manual only"), and forced
 * (`nextRefreshAt = 0`), failing, `gone` and `needsCredentials` rows are left alone.
 */
class NextRefreshRebaserTest {
    private val clock = TestClock()
    private val db = newDb(clock)
    private val settings = FakeSettingsRepository()

    @Test
    fun healthyRowsRebaseOntoTheInterval() =
        runTest {
            settings.set(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES, 240)
            val stale =
                seedPodcast(
                    db,
                    "https://a.example.com/f",
                    lastSuccessAt = NOW - DAY,
                    nextRefreshAt = NOW + 60 * DAY,
                )
            val nullSchedule =
                seedPodcast(db, "https://b.example.com/f", nextRefreshAt = null, subscribedAt = SUBSCRIBED)
            val never =
                seedPodcast(
                    db,
                    "https://c.example.com/f",
                    nextRefreshAt = RefreshPolicy.NEVER,
                    subscribedAt = SUBSCRIBED,
                )
            val close = seedPodcast(db, "https://d.example.com/f", lastSuccessAt = NOW, nextRefreshAt = NOW + 1)

            rebaser().rebase()

            val target = NOW - DAY + 240 * 60_000L
            // stored > target → pulled in; the row keeps its own anchor.
            assertEquals(target, db.podcastDao().byId(stale)!!.nextRefreshAt)
            // null and NEVER schedules start fresh on the interval.
            assertEquals(SUBSCRIBED + 240 * 60_000L, db.podcastDao().byId(nullSchedule)!!.nextRefreshAt)
            assertEquals(SUBSCRIBED + 240 * 60_000L, db.podcastDao().byId(never)!!.nextRefreshAt)
            // stored <= target stays — the rebase never pushes a schedule out.
            assertEquals(NOW + 1, db.podcastDao().byId(close)!!.nextRefreshAt)
        }

    @Test
    fun manualOnlyMovesEveryHealthyRowToNever() =
        runTest {
            settings.set(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES, 0)
            val a = seedPodcast(db, "https://a.example.com/f", nextRefreshAt = NOW + 1)
            val b = seedPodcast(db, "https://b.example.com/f", nextRefreshAt = null)

            rebaser().rebase()

            assertEquals(RefreshPolicy.NEVER, db.podcastDao().byId(a)!!.nextRefreshAt)
            assertEquals(RefreshPolicy.NEVER, db.podcastDao().byId(b)!!.nextRefreshAt)
        }

    @Test
    fun forcedAndBlockedRowsAreLeftAlone() =
        runTest {
            settings.set(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES, 240)
            val forced = seedPodcast(db, "https://a.example.com/f", nextRefreshAt = 0)
            val gone =
                seedPodcast(db, "https://b.example.com/f", nextRefreshAt = NOW + 1) {
                    copy(gone = true)
                }
            val creds =
                seedPodcast(db, "https://c.example.com/f", nextRefreshAt = NOW + 1) {
                    copy(needsCredentials = true)
                }
            val failing =
                seedPodcast(db, "https://d.example.com/f", nextRefreshAt = NOW + 1) {
                    copy(failureCount = 3)
                }

            rebaser().rebase()

            for (id in listOf(forced, gone, creds, failing)) {
                val expected = if (id == forced) 0L else NOW + 1
                assertEquals(expected, db.podcastDao().byId(id)!!.nextRefreshAt)
            }
        }

    @Test
    fun subscribedAtIsTheAnchorWhenNothingSucceeded() =
        runTest {
            settings.set(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES, 60)
            val id =
                seedPodcast(
                    db,
                    "https://a.example.com/f",
                    lastSuccessAt = null,
                    nextRefreshAt = RefreshPolicy.NEVER,
                    subscribedAt = SUBSCRIBED,
                )

            rebaser().rebase()

            assertNull(db.podcastDao().byId(id)!!.lastSuccessAt)
            assertEquals(SUBSCRIBED + 60 * 60_000L, db.podcastDao().byId(id)!!.nextRefreshAt)
        }

    @Test
    fun aNewerAttemptBlocksTheStaleRebaseWrite() =
        runTest {
            settings.set(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES, 240)
            val id =
                seedPodcast(
                    db,
                    "https://a.example.com/f",
                    lastSuccessAt = NOW - DAY,
                    lastAttemptAt = NOW - DAY,
                    nextRefreshAt = NOW + 60 * DAY,
                )
            val dao = db.podcastDao()
            val snapshot = dao.rebaseCandidates().single { it.id == id }

            // A refresh commit lands between the candidate read and the write (R2): the newer
            // attempt carries its own schedule, which the conditional update must not overwrite.
            dao.updateFetchStates(
                listOf(
                    fetchState(id, nextRefreshAt = NOW + 7 * DAY)
                        .copy(lastAttemptAt = NOW, lastSuccessAt = NOW),
                ),
            )

            val target = (snapshot.lastSuccessAt ?: snapshot.subscribedAt) + 240 * 60_000L
            val written = dao.rebaseNextRefreshAt(id, target, snapshot.lastAttemptAt ?: -1)
            assertEquals(0, written)
            assertEquals(NOW + 7 * DAY, dao.byId(id)!!.nextRefreshAt)
        }

    @Test
    fun anUnattemptedRowStillRebases() =
        runTest {
            settings.set(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES, 240)
            // `lastAttemptAt` null → the `-1` sentinel matches `COALESCE(lastAttemptAt, -1) <= -1`.
            val id =
                seedPodcast(
                    db,
                    "https://a.example.com/f",
                    lastAttemptAt = null,
                    nextRefreshAt = NOW + 60 * DAY,
                    subscribedAt = SUBSCRIBED,
                )

            rebaser().rebase()

            assertEquals(SUBSCRIBED + 240 * 60_000L, db.podcastDao().byId(id)!!.nextRefreshAt)
        }

    @Test
    fun effectiveIntervalsRebaseEachPodcastAndLiftManualOnly() =
        runTest {
            settings.set(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES, 1440)
            val inherited = seedPodcast(db, "https://global.example.com/f", nextRefreshAt = null, subscribedAt = NOW)
            val grouped =
                seedPodcast(db, "https://group.example.com/f", nextRefreshAt = RefreshPolicy.NEVER, subscribedAt = NOW)
            val manual = seedPodcast(db, "https://manual.example.com/f", nextRefreshAt = NOW + 1, subscribedAt = NOW)
            val group = db.groupDao().insert(groupEntity(orderKey = "a"))
            val manualGroup = db.groupDao().insert(groupEntity(name = "manual", orderKey = "b"))
            db.groupDao().insertMember(memberEntity(group, grouped, "a"))
            db.groupDao().insertMember(memberEntity(manualGroup, grouped, "a"))
            db.scopeSettingsDao().upsertGroup(
                PodcastGroupSettingsEntity(group, ScopeOverrides(refreshIntervalMinutes = 60)),
            )
            db.scopeSettingsDao().upsertGroup(
                PodcastGroupSettingsEntity(manualGroup, ScopeOverrides(refreshIntervalMinutes = 0)),
            )
            db.scopeSettingsDao().upsertPodcast(
                PodcastSettingsEntity(manual, ScopeOverrides(refreshIntervalMinutes = 0)),
            )

            rebaser().rebase()

            assertEquals(NOW + DAY, db.podcastDao().byId(inherited)!!.nextRefreshAt)
            assertEquals(NOW + 60 * 60_000L, db.podcastDao().byId(grouped)!!.nextRefreshAt)
            assertEquals(RefreshPolicy.NEVER, db.podcastDao().byId(manual)!!.nextRefreshAt)

            db.scopeSettingsDao().upsertPodcast(
                PodcastSettingsEntity(manual, ScopeOverrides(refreshIntervalMinutes = 120)),
            )
            db.scopeSettingsDao().upsertPodcast(
                PodcastSettingsEntity(inherited, ScopeOverrides(refreshIntervalMinutes = 60)),
            )
            rebaser().rebase()

            assertEquals(NOW + 120 * 60_000L, db.podcastDao().byId(manual)!!.nextRefreshAt)
            assertEquals(NOW + 60 * 60_000L, db.podcastDao().byId(inherited)!!.nextRefreshAt)
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun concurrentRebasesApplyTheLatestInterval() =
        runTest {
            val id = seedPodcast(db, "https://race.example.com/f", nextRefreshAt = null, subscribedAt = NOW)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var interval: Int? = null
            var reads = 0
            val resolver =
                object : EffectiveSettingsResolver by FakeEffectiveSettingsResolver() {
                    override suspend fun refreshIntervals(): Map<Long, Effective<Int?>> {
                        val snapshot = mapOf(id to Effective(interval, SettingSource.PodcastOverride))
                        if (++reads == 1) {
                            entered.complete(Unit)
                            release.await()
                        }
                        return snapshot
                    }
                }
            val rebaser = NextRefreshRebaser(db, resolver)
            val older = async { rebaser.rebase() }
            entered.await()
            interval = 60
            val newer = async { rebaser.rebase() }
            runCurrent()
            val readsBeforeRelease = reads
            release.complete(Unit)
            older.await()
            newer.await()

            assertEquals(1, readsBeforeRelease, "The older Manual-only snapshot must finish before the newer rebase")
            assertEquals(NOW + 60 * 60_000L, db.podcastDao().byId(id)!!.nextRefreshAt)
        }

    private fun rebaser() = NextRefreshRebaser(db, EffectiveSettingsResolverImpl(db, settings))

    private companion object {
        const val NOW = TestClock.DEFAULT_NOW
        const val DAY = 86_400_000L
        const val SUBSCRIBED = NOW - 30 * DAY
    }
}
