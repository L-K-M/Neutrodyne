// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import ch.lkmc.neutrodyne.core.domain.PlaybackMediaLookup
import ch.lkmc.neutrodyne.core.model.PlaybackMediaInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GatedLookupTest {
    @Test
    fun onlyTheSelectedProjectionReadCanConsumeItsBarrier() =
        runTest {
            val owner = CoroutineName("explicit projection")
            val lookup = GatedLookup(EmptyLookup, owner)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            lookup.barrier = {
                entered.complete(Unit)
                release.await()
            }

            try {
                val unrelated = async { lookup.get(EPISODE_ID) }
                runCurrent()
                assertTrue(unrelated.isCompleted, "An unrelated lookup stole the projection barrier")
                assertFalse(entered.isCompleted)

                val projection = async(owner) { lookup.get(EPISODE_ID) }
                runCurrent()
                assertTrue(entered.isCompleted)
                assertFalse(projection.isCompleted)
                release.complete(Unit)
                projection.await()
            } finally {
                release.complete(Unit)
            }
        }

    @Test
    fun anUnscopedBarrierStillHoldsTheFirstRead() =
        runTest {
            val lookup = GatedLookup(EmptyLookup)
            val release = CompletableDeferred<Unit>()
            lookup.barrier = { release.await() }

            try {
                val read = async { lookup.get(EPISODE_ID) }
                runCurrent()
                assertFalse(read.isCompleted)
                release.complete(Unit)
                read.await()
            } finally {
                release.complete(Unit)
            }
        }

    private object EmptyLookup : PlaybackMediaLookup {
        override suspend fun get(episodeId: Long): PlaybackMediaInfo? = null

        override fun observe(episodeIds: Set<Long>) = flowOf(emptyMap<Long, PlaybackMediaInfo>())
    }

    private companion object {
        const val EPISODE_ID = 1L
    }
}
