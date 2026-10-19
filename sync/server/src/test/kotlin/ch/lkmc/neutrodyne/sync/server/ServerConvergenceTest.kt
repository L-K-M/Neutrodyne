// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.core.testing.sync.SeededSyncOperation
import ch.lkmc.neutrodyne.core.testing.sync.SyncAddress
import ch.lkmc.neutrodyne.core.testing.sync.SyncConvergenceHarness
import ch.lkmc.neutrodyne.core.testing.sync.SyncOperation
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.measureTime

class ServerConvergenceTest {
    @Test fun seededFiveReplicaConvergence() {
        val seeds = System.getProperty("neutrodyne.syncSeeds", "1000").toInt()
        val start = System.getProperty("neutrodyne.syncSeedStart", "0").toLong()
        require(seeds > 0 && start >= 0)
        val elapsed = measureTime { repeat(seeds) { run(start + it) } }
        println("ServerConvergenceTest: seeds=$seeds start=$start replicas=5 elapsed=$elapsed")
    }

    @Test fun emptyRekeyDestinationReplayDoesNotInventFields() {
        SqliteServerAdapter({ 1_791_072_000_000L }).use { server ->
            val from = episode().id as RecordId.Episode
            val to = episode(episode = "g:new").id as RecordId.Episode
            server.rekey(from, to)
            val before = server.snapshot()
            server.push(SyncAddress(SyncCollection.UPNEXT, to), RecordState(SyncCollection.UPNEXT))
            assertEquals(before, server.snapshot())
        }
    }

    @Test fun minimizedRegressionsUseRealSqlite() {
        val root =
            File(
                System.getProperty("neutrodyne.moduleDir"),
                "../impl/src/commonTest/resources/convergence/regressions.json",
            )
        val regressions = SyncJson.parseToJsonElement(root.readText()).jsonArray
        check(regressions.isNotEmpty())
        for (fixture in regressions) {
            val obj = fixture.jsonObject
            val seed = obj.getValue("seed").jsonPrimitive.long
            val operations =
                obj.getValue("operations").jsonArray.map { row ->
                    val args = row.jsonArray.map { it.jsonPrimitive }
                    SeededSyncOperation(
                        args[0].int,
                        SyncOperation.valueOf(args[1].content),
                        args[2].int,
                        args[3].int,
                        args[4].int,
                    )
                }
            run(seed, operations)
        }
    }

    private fun run(
        seed: Long,
        operations: List<SeededSyncOperation>? = null,
    ) {
        var adapter: SqliteServerAdapter? = null
        try {
            val harness = SyncConvergenceHarness(seed) { wall -> SqliteServerAdapter(wall).also { adapter = it } }
            if (operations == null) harness.run() else harness.run(operations)
        } finally {
            adapter?.close()
        }
    }
}
