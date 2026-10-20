// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.minutes

/** Only dereference database-backed objects after the startup gate, on IO. */
public class ArtworkSyncWorker
    @AssistedInject
    internal constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val opener: DatabaseOpener,
        private val runner: Provider<ArtworkSyncRunner>,
        @param:Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
    ) : CoroutineWorker(context, params) {
        @AssistedFactory
        public interface Factory {
            public fun create(
                context: Context,
                params: WorkerParameters,
            ): ArtworkSyncWorker
        }

        override suspend fun doWork(): Result =
            withContext(io) {
                suspendRunCatching {
                    opener.awaitOpen()
                    // The runner owns the soft deadline and durable continuation. A second
                    // overlapping timer can enqueue twice when both expire on the same tick.
                    runner().runOnce(DEADLINE)
                    Result.success()
                }.getOrElse { Result.retry() }
            }

        internal companion object {
            val DEADLINE = 8.minutes
        }
    }
