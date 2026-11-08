// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The app-foreground sync trigger (10 Scheduling on Android): `ON_START` flips the scheduler's
 * UI-visible flag — which moves the debounced outbox path onto in-process rounds — and enqueues
 * a `sync-now` round when the link is live; `ON_STOP` flips the flag back so further changes
 * ride `sync-push`. The linked check happens inside the launched coroutine: the lifecycle
 * callback itself must stay cheap.
 */
@SingleIn(AppScope::class)
class SyncForegroundObserver
    @Inject
    constructor(
        private val workScheduler: Provider<WorkManagerSyncScheduler>,
        private val db: Provider<NeutrodyneDatabase>,
        @param:ApplicationScope private val appScope: CoroutineScope,
    ) : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            workScheduler().uiVisible.value = true
            appScope.launch {
                if (!db().isSyncLinked()) return@launch
                workScheduler().enqueueSyncNow()
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            workScheduler().uiVisible.value = false
        }
    }
