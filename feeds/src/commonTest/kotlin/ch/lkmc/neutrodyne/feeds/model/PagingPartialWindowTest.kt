// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feeds.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PagingPartialWindowTest {
    @Test
    fun pagingWindowRuleIsSharedByRefreshSubscribeAndIngest() {
        val next = "https://example.test/next"
        val prevArchive = "https://example.test/archive"
        val cases =
            listOf(
                Paging() to false,
                Paging(next = next) to true,
                Paging(prevArchive = prevArchive) to true,
                Paging(next = next, prevArchive = prevArchive) to true,
                Paging(fhArchive = true) to true,
                Paging(next = next, fhArchive = true) to true,
                Paging(prevArchive = prevArchive, fhArchive = true) to true,
                Paging(next = next, prevArchive = prevArchive, fhArchive = true) to true,
            )
        for ((paging, partial) in cases) {
            assertEquals(partial, paging.isPartialWindow, paging.toString())
            assertFalse(paging.copy(fhComplete = true).isPartialWindow, paging.toString())
        }
        assertFalse(Paging(first = "https://example.test/first").isPartialWindow)
    }
}
