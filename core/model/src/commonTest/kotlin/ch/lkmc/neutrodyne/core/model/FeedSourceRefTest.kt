// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeedSourceRefTest {
    @Test fun selectionsRoundTrip() {
        val group = FeedSourceRef.Group("12345678-1234-1234-1234-123456789abc")
        for ((ref, encoded) in listOf(
            FeedSourceRef.All to "all",
            FeedSourceRef.Ungrouped to "ungrouped",
            group to "group:${group.uuid}",
        )) {
            assertEquals(encoded, ref.encode())
            assertEquals(ref, FeedSourceRef.decode(encoded))
            assertEquals(encoded, FeedSourceRef.encode(ref))
        }
    }

    @Test fun malformedSelectionsFallBackToAll() {
        for (value in listOf(
            "",
            "unknown",
            "group:",
            "group:no-uuid",
            "podcast:1",
            "group:12345678-1234-1234-1234-123456789ABC",
        )) {
            assertEquals(FeedSourceRef.All, FeedSourceRef.decode(value))
        }
        assertFailsWith<IllegalArgumentException> { FeedSourceRef.Group("bad") }
    }
}
