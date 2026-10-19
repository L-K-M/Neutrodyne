// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.GroupMosaicMember
import ch.lkmc.neutrodyne.core.model.MosaicSnapshot
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MosaicSourcesTest {
    private val member = GroupMosaicMember(1, 2, "Show", ArtworkRef("u-" + "a".repeat(40), "https://art", 3), null)
    private val snapshot =
        MosaicSnapshot(1, "00000000-0000-0000-0000-000000000001", "Group", null, null, persistentListOf(member))

    @Test fun descriptorIsStableSha256AndIncludesEveryField() {
        val original = mosaicDescriptor(snapshot)
        assertTrue(Regex("nd:mosaic:v1:[0-9a-f]{64}").matches(original))
        assertEquals(original, mosaicDescriptor(snapshot.copy()))
        val changed =
            listOf(
                snapshot.copy(groupUuid = "00000000-0000-0000-0000-000000000002"),
                snapshot.copy(groupName = "Other"),
                snapshot.copy(groupColorArgb = 0),
                snapshot.copy(groupIconKey = ""),
                snapshot.copy(groupId = 4, members = persistentListOf(member.copy(groupId = 4))),
                snapshot.copy(members = persistentListOf()),
                snapshot.copy(members = persistentListOf(member.copy(podcastId = 3))),
                snapshot.copy(members = persistentListOf(member.copy(displayTitle = "Other"))),
                snapshot.copy(members = persistentListOf(member.copy(artworkAvgArgb = 0))),
                snapshot.copy(members = persistentListOf(member.copy(artwork = member.artwork.copy(key = "other")))),
                snapshot.copy(members = persistentListOf(member.copy(artwork = member.artwork.copy(url = null)))),
                snapshot.copy(members = persistentListOf(member.copy(artwork = member.artwork.copy(version = 4)))),
            )
        changed.forEach { assertNotEquals(original, mosaicDescriptor(it)) }
    }

    @Test fun descriptorPreservesOrderAndFramesUnicodeNullAndDelimiterText() {
        val second = member.copy(podcastId = 3, displayTitle = "🎧:a|b")
        val two = snapshot.copy(members = persistentListOf(member, second))
        assertNotEquals(
            mosaicDescriptor(two),
            mosaicDescriptor(two.copy(members = two.members.reversed().toImmutableList())),
        )
        assertNotEquals(
            mosaicDescriptor(snapshot.copy(groupName = "a:1", groupIconKey = "b")),
            mosaicDescriptor(snapshot.copy(groupName = "a", groupIconKey = "1:b")),
        )
        assertNotEquals(
            mosaicDescriptor(snapshot.copy(groupIconKey = null)),
            mosaicDescriptor(snapshot.copy(groupIconKey = "")),
        )
    }

    @Test fun tenSecondDebounceIgnoresOwnWritesAndCancelsObsoleteSources() =
        runTest {
            val sources = MutableSharedFlow<List<MosaicSnapshot>>()
            val results = mutableListOf<List<String>>()
            val job = backgroundScope.launch { sources.mosaicChanges().collect { results += it } }
            runCurrent()
            sources.emit(listOf(snapshot))
            advanceTimeBy(9_000)
            sources.emit(listOf(snapshot.copy())) // Room invalidation from g- publication.
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(listOf(listOf(mosaicDescriptor(snapshot))), results)
            sources.emit(listOf(snapshot.copy(groupName = "Old")))
            advanceTimeBy(9_000)
            val latest = snapshot.copy(groupName = "Latest")
            sources.emit(listOf(latest))
            advanceTimeBy(9_999)
            assertEquals(1, results.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(mosaicDescriptor(latest)), results.last())
            sources.emit(emptyList())
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(emptyList(), results.last())
            sources.emit(listOf(snapshot))
            job.cancel()
            advanceTimeBy(10_001)
            assertEquals(3, results.size)
        }
}
