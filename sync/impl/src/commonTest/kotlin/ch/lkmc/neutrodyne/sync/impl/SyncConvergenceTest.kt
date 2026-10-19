// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.testing.sync.SeededSyncOperation
import ch.lkmc.neutrodyne.core.testing.sync.SeededSyncOperations
import ch.lkmc.neutrodyne.core.testing.sync.SyncConvergenceHarness
import ch.lkmc.neutrodyne.core.testing.sync.SyncOperation
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

class SyncConvergenceTest {
    @Test
    fun seededFiveReplicaConvergence() {
        val count = convergenceSeedCount()
        require(count > 0) { "syncSeeds must be positive" }
        val elapsed =
            measureTime {
                repeat(count) { seed -> SyncConvergenceHarness(seed.toLong()).run() }
            }
        println("SyncConvergenceTest: seeds=$count replicas=${SeededSyncOperations.REPLICAS} elapsed=$elapsed")
    }

    @Test
    fun rekeyParentMergeRegression() {
        SyncConvergenceHarness(128).run()
    }

    @Test
    fun minimizedRegressionSeedsConverge() {
        val fixtures = SyncJson.parseToJsonElement(convergenceRegressions()).jsonArray
        assertTrue(fixtures.isNotEmpty())
        for (fixture in fixtures) {
            val obj = fixture.jsonObject
            val seed = obj.getValue("seed").jsonPrimitive.long
            val operations =
                obj.getValue("operations").jsonArray.map { row ->
                    val args = row.jsonArray.map { it.jsonPrimitive }
                    SeededSyncOperation(
                        args[0].int,
                        SyncOperation.valueOf(requireNotNull(args[1].contentOrNull)),
                        args[2].int,
                        args[3].int,
                        args[4].int,
                    )
                }
            SyncConvergenceHarness(seed).run(operations)
        }
    }

    @Test
    fun sameSeedHasIdenticalCanonicalRawStateAndProjections() {
        val first = SyncConvergenceHarness(42).run()
        val second = SyncConvergenceHarness(42).run()
        assertEquals(first, second)
        assertEquals(first.canonicalRaw(), second.canonicalRaw())
    }
}

internal expect fun convergenceSeedCount(): Int

internal expect fun convergenceRegressions(): String
