// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.refresh.NextRefreshRebaser
import ch.lkmc.neutrodyne.core.data.refresh.RefreshPolicy
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
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

            rebaser(backgroundScope).rebase()

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

            rebaser(backgroundScope).rebase()

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

            rebaser(backgroundScope).rebase()

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

            rebaser(backgroundScope).rebase()

            assertNull(db.podcastDao().byId(id)!!.lastSuccessAt)
            assertEquals(SUBSCRIBED + 60 * 60_000L, db.podcastDao().byId(id)!!.nextRefreshAt)
        }

    private fun rebaser(scope: kotlinx.coroutines.CoroutineScope) = NextRefreshRebaser(db, settings, clock, scope)

    private companion object {
        const val NOW = TestClock.DEFAULT_NOW
        const val DAY = 86_400_000L
        const val SUBSCRIBED = NOW - 30 * DAY
    }
}
