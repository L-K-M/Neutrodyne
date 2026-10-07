// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import ch.lkmc.neutrodyne.core.common.WorkerCreator
import dev.zacsweers.metro.Inject
import kotlin.reflect.KClass

/**
 * WorkManager's factory over the multibound worker map (01 DI, S8 (5)). Returns null for an unknown class, so
 * WorkManager falls back to reflection only for workers we do not own (none today).
 */
@Inject
class MetroWorkerFactory(
    private val creators: Map<KClass<out ListenableWorker>, WorkerCreator>,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        val creator = creators.entries.firstOrNull { it.key.qualifiedName == workerClassName }?.value ?: return null
        return creator(appContext, workerParameters)
    }
}
