// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.common

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.MapKey
import dev.zacsweers.metro.Multibinds
import kotlin.reflect.KClass

/** Creates one worker; contributed per worker class into the map `MetroWorkerFactory` reads (01 DI, S8 (5)). */
typealias WorkerCreator = (Context, WorkerParameters) -> ListenableWorker

/**
 * Map key of the worker multibinding: `@Provides @IntoMap @WorkerKey(RefreshWorker::class)` in a contributed
 * binding container of the module that owns the worker (`:core:data`, `:download:impl`, … `androidMain`).
 */
@MapKey
annotation class WorkerKey(
    val value: KClass<out ListenableWorker>,
)

/** Declares the worker map so the graph compiles before any module contributes a worker (01 DI rule 5). */
@ContributesTo(AppScope::class)
@BindingContainer
interface WorkerBindings {
    @Multibinds(allowEmpty = true)
    fun workerCreators(): Map<KClass<out ListenableWorker>, WorkerCreator>
}
