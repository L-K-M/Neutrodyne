// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.refresh

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.NetworkStatus
import ch.lkmc.neutrodyne.core.data.FakeRefreshScheduler
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app-foreground trigger (03 Triggers): a `refresh-now` goes out only when the setting is on,
 * the last completed All run is older than the scheduled tick, the network is unmetered under
 * Wi-Fi-only, and this observer last fired ≥ 10 min ago.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshForegroundObserverTest {
    private val owner =
        object : LifecycleOwner {
            private val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }

    @Test
    fun `enqueues a foreground run when every gate is open`() =
        runTest {
            val deps = deps()

            deps.observer().onStart(owner)
            advanceUntilIdle()

            deps.scheduler.nowRequests.single().let {
                assertThat(it.scope).isEqualTo(RefreshScope.All)
                assertThat(it.force).isFalse()
                assertThat(it.pagesOnly).isFalse()
                assertThat(it.origin).isEqualTo(RefreshOrigin.FOREGROUND)
            }
        }

    @Test
    fun `stays quiet when the setting is off`() =
        runTest {
            val deps = deps()
            deps.settings.set(FeedsSettingKeys.REFRESH_ON_APP_OPEN, false)

            deps.observer().onStart(owner)
            advanceUntilIdle()

            assertThat(deps.scheduler.nowRequests).isEmpty()
        }

    @Test
    fun `stays quiet inside the scheduled tick`() =
        runTest {
            val deps = deps()
            // Ten minutes ago is well inside the 60-min floor.
            deps.settings.set(FeedsSettingKeys.LAST_ALL_RUN_FINISHED_AT, deps.clock.nowMs - 10 * MINUTE_MS)

            deps.observer().onStart(owner)
            advanceUntilIdle()

            assertThat(deps.scheduler.nowRequests).isEmpty()
        }

    @Test
    fun `honours the stored scheduled tick`() =
        runTest {
            val deps = deps()
            deps.settings.set(FeedsSettingKeys.SCHEDULED_TICK_MINUTES, 240L)
            deps.settings.set(FeedsSettingKeys.LAST_ALL_RUN_FINISHED_AT, deps.clock.nowMs - 120 * MINUTE_MS)
            val observer = deps.observer()

            observer.onStart(owner)
            advanceUntilIdle()
            assertThat(deps.scheduler.nowRequests).isEmpty()

            // Older than the 240-min tick: the gate opens.
            deps.settings.set(FeedsSettingKeys.LAST_ALL_RUN_FINISHED_AT, deps.clock.nowMs - 250 * MINUTE_MS)
            observer.onStart(owner)
            advanceUntilIdle()

            assertThat(deps.scheduler.nowRequests).hasSize(1)
        }

    @Test
    fun `wifi only waits for an unmetered network`() =
        runTest {
            val deps = deps()
            deps.settings.set(FeedsSettingKeys.REFRESH_WIFI_ONLY, true)
            deps.network.setStatus(METERED)
            val observer = deps.observer()

            observer.onStart(owner)
            advanceUntilIdle()
            assertThat(deps.scheduler.nowRequests).isEmpty()

            deps.network.setStatus(FakeNetworkMonitor.ONLINE)
            observer.onStart(owner)
            advanceUntilIdle()

            assertThat(deps.scheduler.nowRequests).hasSize(1)
        }

    @Test
    fun `the ten minute cooldown collapses repeated foregrounds`() =
        runTest {
            val deps = deps()
            val observer = deps.observer()

            observer.onStart(owner)
            advanceUntilIdle()
            deps.clock.elapsedMs += 5 * MINUTE_MS
            observer.onStart(owner)
            advanceUntilIdle()
            assertThat(deps.scheduler.nowRequests).hasSize(1)

            deps.clock.elapsedMs += 10 * MINUTE_MS
            observer.onStart(owner)
            advanceUntilIdle()

            assertThat(deps.scheduler.nowRequests).hasSize(2)
        }

    @Test
    fun `concurrent foregrounds claim the cooldown once`() =
        runTest {
            val deps = deps()
            val observer = deps.observer()

            // The claim is atomic inside the enqueuing coroutine (R12): two ON_STARTs inside
            // the same cooldown window collapse to one enqueue — the first coroutine to reach
            // the CAS wins and the second sees the fresh stamp.
            observer.onStart(owner)
            observer.onStart(owner)
            advanceUntilIdle()

            assertThat(deps.scheduler.nowRequests).hasSize(1)
        }

    @Test
    fun `a monotonic step back does not suppress the trigger`() =
        runTest {
            val deps = deps()
            val observer = deps.observer()

            observer.onStart(owner)
            advanceUntilIdle()
            assertThat(deps.scheduler.nowRequests).hasSize(1)

            // elapsedRealtime moving backwards makes `now - last` negative — a `&lt;` check would
            // suppress every trigger until the stale stamp ages out; `in 0 until` re-claims.
            deps.clock.elapsedMs -= 60_000L
            observer.onStart(owner)
            advanceUntilIdle()

            assertThat(deps.scheduler.nowRequests).hasSize(2)
        }

    // --- plumbing ---------------------------------------------------------------------------------

    private class Deps(
        val scheduler: FakeRefreshScheduler,
        val settings: FakeSettingsRepository,
        val network: FakeNetworkMonitor,
        val clock: TestClock,
        val scope: TestScope,
    ) {
        fun observer() = RefreshForegroundObserver(scheduler, settings, network, clock, scope)
    }

    private fun TestScope.deps(): Deps =
        Deps(
            FakeRefreshScheduler(),
            FakeSettingsRepository(),
            FakeNetworkMonitor(FakeNetworkMonitor.ONLINE),
            TestClock(),
            this,
        )

    private companion object {
        const val MINUTE_MS = 60_000L

        val METERED =
            NetworkStatus(
                isConnected = true,
                isValidated = true,
                isMetered = true,
                isVpn = false,
            )
    }
}
