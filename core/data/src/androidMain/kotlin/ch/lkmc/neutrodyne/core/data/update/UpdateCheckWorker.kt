// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckState
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.Provider

class UpdateCheckWorker
    @AssistedInject
    internal constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val executor: Provider<UpdateCheckExecutor>,
    ) : CoroutineWorker(context, params) {
        @AssistedFactory
        interface Factory {
            fun create(
                context: Context,
                params: WorkerParameters,
            ): UpdateCheckWorker
        }

        override suspend fun doWork(): Result {
            val origin =
                if (inputData.getBoolean(
                        MANUAL_INPUT,
                        false,
                    )
                ) {
                    UpdateInvocation.MANUAL
                } else {
                    UpdateInvocation.SCHEDULED
                }
            // A fresh worker process restores durable choices before admitting a GET.
            val state = executor().execute(origin)
            val failed =
                state is UpdateCheckState.Failed || (state is UpdateCheckState.Available && state.lastError != null)
            return if (origin == UpdateInvocation.SCHEDULED && failed) Result.retry() else Result.success()
        }

        internal companion object {
            const val MANUAL_INPUT = "manual"
        }
    }
