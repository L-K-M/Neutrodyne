// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.desktop

import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.PlayContextInfo
import ch.lkmc.neutrodyne.core.model.PlaySessionInfo
import ch.lkmc.neutrodyne.core.model.QueueItem
import ch.lkmc.neutrodyne.core.model.QueueOrigin
import ch.lkmc.neutrodyne.core.model.VirtualQueue
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The queue and session flows invalidate independently, so the controller's combine can pair a
 * fresh queue with a stale session emission. A transition landing in that gap used to resolve the
 * arrival's settings with a null group and abort before reloading the saver.
 */
class CoherentContextTest {
    private val group = context(ContextType.GROUP, 1)
    private val podcast = context(ContextType.PODCAST, 2)

    @Test
    fun noSessionAndNoPreviousContextIsNull() {
        val queue = queue(generation = 0, current = null)
        assertEquals(null, coherentContext(queue, session = null, previous = null))
    }

    @Test
    fun aStaleQueueWithNoSessionKeepsThePreviousContext() {
        val queue = queue(generation = 0, current = null)
        assertEquals(group, coherentContext(queue, session = null, previous = group))
    }

    @Test
    fun aFreshQueueWithAStaleNullSessionKeepsThePreviousContext() {
        val queue = queue(generation = 1, current = 3)
        assertEquals(group, coherentContext(queue, session = null, previous = group))
    }

    @Test
    fun aCoherentSessionSuppliesItsContext() {
        val queue = queue(generation = 2, current = 3)
        assertEquals(podcast, coherentContext(queue, PlaySessionInfo(3, podcast, 2), previous = group))
    }

    @Test
    fun aCoherentSessionWithoutAContextClearsThePreviousOne() {
        val queue = queue(generation = 2, current = 3)
        assertEquals(null, coherentContext(queue, PlaySessionInfo(3, null, 2), previous = group))
    }

    @Test
    fun aStaleSessionKeepsThePreviousContext() {
        val queue = queue(generation = 2, current = 3)
        assertEquals(group, coherentContext(queue, PlaySessionInfo(3, podcast, 1), previous = group))
    }

    private fun queue(
        generation: Long,
        current: Long?,
    ) = VirtualQueue(
        generation,
        current?.let { QueueItem(it, QueueOrigin.CURRENT) },
        emptyList(),
        emptyList(),
    )

    private fun context(
        type: ContextType,
        id: Long,
    ) = PlayContextInfo(type, id, "context $id", FeedOrder.NEWEST_FIRST, 0, MediaFilter.ALL)
}
