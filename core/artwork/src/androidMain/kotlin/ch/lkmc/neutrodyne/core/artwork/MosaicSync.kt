// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/** Android tail of the existing artwork pass, after member files have been refreshed. */
@Inject
internal class MosaicSync(
    private val db: NeutrodyneDatabase,
    private val store: DefaultArtworkStore,
    private val sources: MosaicSources,
    private val renderer: MosaicRenderer,
    private val scheduler: ArtworkSyncScheduler,
    private val clock: Clock,
    @param:Dispatcher(NeutrodyneDispatchers.Default) private val cpu: CoroutineDispatcher,
) {
    suspend fun runOnce(deadline: Duration?) {
        val end = deadline?.let { clock.elapsedRealtime() + it.inWholeMilliseconds }
        store.refreshIndex()
        for (snapshot in sources.snapshots()) {
            val key = ArtworkKeys.mosaic(snapshot.groupUuid)
            val descriptor = mosaicDescriptor(snapshot)
            val row = db.artworkDao().byKey(key)
            if (row != null && backoffActive(row, clock.now())) continue
            if (row?.url == descriptor && row.lastError == null && store.pinnedFile(key) != null) continue
            val remaining = end?.minus(clock.elapsedRealtime())
            if (remaining != null && remaining <= 0) {
                scheduler.enqueueNow()
                return
            }
            val produce: suspend () -> ArtworkProduction = {
                try {
                    val image = withContext(cpu) { renderer.render(snapshot) }
                    ArtworkProduction(key, descriptor, image)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    ArtworkProduction(key, descriptor, error = artworkError(error))
                }
            }
            val result = if (remaining == null) produce() else withTimeoutOrNull(remaining) { produce() }
            if (result == null) {
                scheduler.enqueueNow()
                return
            }
            if (!store.applyMosaic(snapshot, result, clock.now())) {
                // Room may coalesce source churn back to an already observed signature.
                scheduler.enqueueNow()
            }
            yield()
        }
    }
}
