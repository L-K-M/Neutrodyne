// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import ch.lkmc.neutrodyne.core.model.QueueItem
import ch.lkmc.neutrodyne.core.model.QueueOrigin
import ch.lkmc.neutrodyne.core.model.SourceType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MediaLibraryTreeTest {
    @Test fun rootOnlyOffersUpNextAndPagesCurrentPlusPlayableEntries() {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h ->
            h.seedCurrent()
            h.lookup.items.value +=
                mapOf(
                    2L to media().copy(episodeId = 2),
                    3L to media(type = SourceType.YOUTUBE_CHANNEL).copy(episodeId = 3),
                )
            h.publish(
                h.store.queue.copy(
                    upNext = listOf(QueueItem(2, QueueOrigin.UP_NEXT), QueueItem(3, QueueOrigin.UP_NEXT)),
                ),
            )
            val root = h.run { h.tree.children("root", 0, 100) }
            assertEquals(listOf("upnext"), root.map { it.mediaId })
            val children = h.run { h.tree.children("upnext", 0, 100) }
            assertEquals(listOf("episode:1@upnext", "episode:2@upnext"), children.map { it.mediaId })
            assertEquals("episode:2@upnext", h.run { h.tree.children("upnext", 1, 1) }.single().mediaId)
            assertTrue(h.run { h.tree.children("upnext", Int.MAX_VALUE, Int.MAX_VALUE) }.isEmpty())
            assertTrue(children.all { it.mediaMetadata.isPlayable == true && it.mediaMetadata.isBrowsable == false })
            assertNull(h.connection.controller)
        }
    }

    @Test fun invalidParentsAndPaginationFailExplicitly() {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h ->
            assertFailsWith<IllegalArgumentException> { h.run { h.tree.children("unknown", 0, 1) } }
            assertFailsWith<IllegalArgumentException> { h.run { h.tree.children("root", -1, 1) } }
            assertFailsWith<IllegalArgumentException> { h.run { h.tree.children("root", 0, 0) } }
        }
    }
}
