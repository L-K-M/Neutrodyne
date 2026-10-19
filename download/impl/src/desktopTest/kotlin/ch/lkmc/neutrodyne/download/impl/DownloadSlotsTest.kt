// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.SourceKind
import io.ktor.http.Url
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class DownloadSlotsTest {
    @Test fun randomizedAdmissionAndReleasePreserveAllLimits() {
        repeat(50) { seed ->
            val random = Random(seed)
            val slots = DownloadSlots()
            val held = mutableListOf<Pair<DownloadEntity, DownloadSlots.Lease>>()
            repeat(500) { step ->
                if (held.isNotEmpty() && random.nextBoolean()) {
                    held.removeAt(random.nextInt(held.size)).second.release()
                } else {
                    val row =
                        DownloadEntity(
                            step.toLong(),
                            if (random.nextBoolean()) DownloadLane.AUTO else DownloadLane.MANUAL,
                            DownloadState.QUEUED,
                            priority = 0,
                            requestedAt = 0,
                            sourceKind = if (random.nextInt(3) == 0) SourceKind.YOUTUBE else SourceKind.RSS_ENCLOSURE,
                            sourceRef = "https://host${random.nextInt(3)}.example/media",
                            rootId = "old",
                            allowMetered = true,
                        )
                    if (slots.canStart(row)) held += row to slots.acquire(row)
                }
                val rows = held.map { it.first }
                assertTrue(rows.size <= 3)
                assertTrue(rows.count { it.lane == DownloadLane.AUTO } <= 2)
                assertTrue(rows.count { it.sourceKind == SourceKind.YOUTUBE } <= 1)
                assertTrue(
                    rows
                        .filter { it.sourceKind == SourceKind.RSS_ENCLOSURE }
                        .groupingBy {
                            Url(
                                it.sourceRef,
                            ).host
                        }.eachCount()
                        .values
                        .all {
                            it <=
                                2
                        },
                )
            }
            held.forEach { it.second.release() }
            held.forEach { assertTrue(slots.canStart(it.first)) }
        }
    }
}
