// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.refresh

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The app-foreground refresh trigger (03 Triggers): on `ON_START` it enqueues a due-selection run
 * when `feeds.refresh_on_app_open` is on, the last completed All run is older than the tick
 * interval, this observer's own last trigger is ≥ 10 min ago, and — under
 * `feeds.refresh_wifi_only` — the network is unmetered. The order-220 initializer registers it on
 * `ProcessLifecycleOwner`; a late registration still sees the current `ON_START`.
 */
@SingleIn(AppScope::class)
internal class RefreshForegroundObserver
    @Inject
    constructor(
        private val scheduler: RefreshScheduler,
        private val settings: SettingsRepository,
        private val network: NetworkMonitor,
        private val clock: Clock,
        @param:ApplicationScope private val appScope: CoroutineScope,
    ) : DefaultLifecycleObserver {
        private val lastTriggerElapsed = AtomicLong(Long.MIN_VALUE)

        override fun onStart(owner: LifecycleOwner) {
            val nowElapsed = clock.elapsedRealtime()
            if (nowElapsed - lastTriggerElapsed.get() < TRIGGER_COOLDOWN_MS) return
            appScope.launch {
                if (!suspendRunCatching { gatesOpen(nowElapsed) }.getOrDefault(false)) return@launch
                lastTriggerElapsed.set(nowElapsed)
                scheduler.enqueueNow(
                    RefreshScope.All,
                    force = false,
                    pagesOnly = false,
                    origin = RefreshOrigin.FOREGROUND,
                )
            }
        }

        private suspend fun gatesOpen(nowElapsed: Long): Boolean {
            if (!settings.get(FeedsSettingKeys.REFRESH_ON_APP_OPEN)) return false
            val tickMs =
                settings.get(FeedsSettingKeys.SCHEDULED_TICK_MINUTES).coerceAtLeast(MIN_TICK_MINUTES) * 60_000L
            if (clock.now() - settings.get(FeedsSettingKeys.LAST_ALL_RUN_FINISHED_AT) < tickMs) return false
            if (settings.get(FeedsSettingKeys.REFRESH_WIFI_ONLY) && network.status.value.isMetered) return false
            return true
        }

        private companion object {
            const val TRIGGER_COOLDOWN_MS = 10 * 60_000L
            const val MIN_TICK_MINUTES = 60L
        }
    }
