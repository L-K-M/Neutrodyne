// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.model.DIRECTORY_PROVIDER_ORDER
import ch.lkmc.neutrodyne.core.model.ProviderStatus
import ch.lkmc.neutrodyne.core.model.SearchResults
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** W2 reuses this assertion on real, recorded-provider flows; it never contacts live directories. */
suspend fun Flow<SearchResults>.assertSearchContract(query: String): List<SearchResults> {
    val snapshots = toList()
    assertTrue(snapshots.isNotEmpty())
    assertEquals(1, snapshots.count { it.complete })
    assertTrue(snapshots.last().complete)
    if (snapshots.size > 1) {
        assertTrue(snapshots.first().hits.isEmpty())
        assertTrue(
            snapshots
                .first()
                .statuses.values
                .all { it == ProviderStatus.Pending || it == ProviderStatus.Disabled },
        )
    }
    snapshots.forEachIndexed { index, snapshot ->
        assertEquals(query.trim(), snapshot.query)
        assertEquals(DIRECTORY_PROVIDER_ORDER.toSet(), snapshot.statuses.keys)
        if (index < snapshots.lastIndex) assertFalse(snapshot.complete)
        if (snapshot.complete) assertFalse(ProviderStatus.Pending in snapshot.statuses.values)
        snapshot.hits.forEach { hit ->
            assertEquals(hit.providers, hit.ranks.keys)
            assertTrue(hit.ranks.values.all { it > 0 })
        }
    }
    return snapshots
}
