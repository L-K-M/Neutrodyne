// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.refresh

import androidx.work.Data
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The `refresh-*` input-data codec (03 Work requests): every scope shape round-trips, an invalid
 * or missing origin falls back to `PERIODIC`, and the worker's runtime knobs pass through.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RefreshWorkDataTest {
    @Test
    fun `all scope round-trips`() {
        val request = decode(RefreshWorkData.of(RefreshScope.All, force = false, pagesOnly = false, RefreshOrigin.PERIODIC))
        assertThat(request.scope).isEqualTo(RefreshScope.All)
    }

    @Test
    fun `group scope round-trips`() {
        val request = decode(RefreshWorkData.of(RefreshScope.Group(42), force = false, pagesOnly = false, RefreshOrigin.MANUAL))
        assertThat(request.scope).isEqualTo(RefreshScope.Group(42))
    }

    @Test
    fun `podcast ids round-trip`() {
        val request =
            decode(
                RefreshWorkData.of(
                    RefreshScope.Podcasts(listOf(3L, 7L, 400L)),
                    force = true,
                    pagesOnly = true,
                    RefreshOrigin.SUBSCRIBE,
                ),
            )
        assertThat(request.scope).isEqualTo(RefreshScope.Podcasts(listOf(3L, 7L, 400L)))
        assertThat(request.force).isTrue()
        assertThat(request.pagesOnly).isTrue()
        assertThat(request.origin).isEqualTo(RefreshOrigin.SUBSCRIBE)
    }

    @Test
    fun `ids win over a scope string`() {
        // Both keys set: the array encoding takes precedence (03 Work requests).
        val data =
            Data
                .Builder()
                .putString("scope", "all")
                .putLongArray("ids", longArrayOf(9L))
                .build()
        assertThat(decode(data).scope).isEqualTo(RefreshScope.Podcasts(listOf(9L)))
    }

    @Test
    fun `missing fields decode to the periodic defaults`() {
        val request = decode(Data.EMPTY)
        assertThat(request.scope).isEqualTo(RefreshScope.All)
        assertThat(request.force).isFalse()
        assertThat(request.pagesOnly).isFalse()
        assertThat(request.origin).isEqualTo(RefreshOrigin.PERIODIC)
    }

    @Test
    fun `an unknown origin falls back to periodic`() {
        val data = Data.Builder().putString("origin", "NOT_A_THING").build()
        assertThat(decode(data).origin).isEqualTo(RefreshOrigin.PERIODIC)
    }

    @Test
    fun `runtime knobs pass through`() {
        val request =
            RefreshWorkData.request(
                Data.EMPTY,
                deadlineElapsedMs = 123_456L,
                dueSlackMs = 789L,
                pagingBudgetMs = 4_567L,
            )
        assertThat(request.deadlineElapsedMs).isEqualTo(123_456L)
        assertThat(request.dueSlackMs).isEqualTo(789L)
        assertThat(request.pagingBudgetMs).isEqualTo(4_567L)
    }

    @Test
    fun `every origin round-trips`() {
        for (origin in RefreshOrigin.entries) {
            val request = decode(RefreshWorkData.of(RefreshScope.All, force = false, pagesOnly = false, origin))
            assertThat(request.origin).isEqualTo(origin)
        }
    }

    @Test
    fun `scope id cap matches the data size budget`() {
        assertThat(RefreshWorkData.MAX_SCOPE_IDS).isEqualTo(500)
    }

    private fun decode(data: Data) =
        RefreshWorkData.request(
            data,
            deadlineElapsedMs = RefreshRequest.NO_DEADLINE,
            dueSlackMs = 0,
            pagingBudgetMs = 0,
        )
}
