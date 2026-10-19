// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.RecordMerger
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import ch.lkmc.neutrodyne.sync.protocol.dto.AccountResetRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Executes the four MS1 scenarios through the production RecordWriter and frozen store contract. */
@RunWith(Parameterized::class)
class ServerWriterVectorTest(
    private val file: File,
) {
    @Test
    fun vector() =
        runBlocking {
            val vector = SyncJson.parseToJsonElement(file.readText()).jsonObject
            val scenario = vector.getValue("scenario").jsonObject
            val f = WriterFixture()
            val id = vector.getValue("id").jsonPrimitive.content
            val podcast = key(SyncCollection.fromWire(vector.getValue("collection").jsonPrimitive.content), id)
            when (scenario.getValue("operation").jsonPrimitive.content) {
                "reset" -> {
                    if ("before" in scenario) {
                        resetBoundary(f, scenario)
                    } else {
                        val offset = scenario.getValue("storedAheadMs").jsonPrimitive.long
                        f.push(f.podcast(id, at = f.clock(offset)))
                        val before = f.record(podcast).maxClock!!
                        StoredRecordWriter(f.store, f.inputs).resetLibrary(
                            f.principal,
                            AccountResetRequest("DELETE", "library", f.cursor()),
                        )
                        val after = f.record(podcast)
                        assertTrue(RecordMerger.isDead(after.raw))
                        assertTrue(after.maxClock!! > before)
                        assertEquals(NodeId.SERVER, after.maxClock!!.nodeId)
                    }
                }

                "revive" -> {
                    val offset = scenario.getValue("storedAheadMs").jsonPrimitive.long
                    f.push(f.podcast(id), f.write(podcast, "subscribed", JsonPrimitive(false), f.clock(offset)))
                    val tombstone = f.record(podcast).maxClock!!
                    val result = f.push(f.podcast(B, subscribedAt = 2, at = f.clock(1)))
                    assertEquals("merged", result.results.single().status)
                    assertEquals(podcast.id.toWire(), result.results.single().mergedInto)
                    assertFalse(RecordMerger.isDead(f.raw(podcast)))
                    assertTrue(f.record(podcast).maxClock!! > tombstone)
                    assertEquals(NodeId.SERVER, f.record(podcast).maxClock!!.nodeId)
                }

                "death-and-revival" -> {
                    val queue = episode(id, coll = SyncCollection.UPNEXT)
                    val history = episode(id)
                    val member = member(podcast = id)
                    f.push(
                        f.podcast(id),
                        f.write(queue, "in", JsonPrimitive(true)),
                        f.write(history, "fav", JsonPrimitive(true)),
                        f.write(member, "in", JsonPrimitive(true)),
                    )
                    f.push(f.write(podcast, "subscribed", JsonPrimitive(false), f.clock(1)))
                    val deadQueue = f.record(queue)
                    val cutoff = f.cursor()
                    val before = listOf(history, member).associateWith { f.record(it) }
                    val result = f.push(f.podcast(B, subscribedAt = 2, at = f.clock(2)), since = cutoff)
                    val removed = scenario.getValue("expectRemoved").jsonArray.map { it.jsonPrimitive.content }
                    assertTrue(queue.collection.wire in removed && RecordMerger.isDead(deadQueue.raw))
                    assertEquals(deadQueue, f.record(queue))
                    val resequenced = scenario.getValue("expectResequenced").jsonArray.map { it.jsonPrimitive.content }
                    before.filterKeys { it.collection.wire in resequenced }.forEach { (key, old) ->
                        assertTrue(f.record(key).seq > old.seq)
                        assertEquals(old.raw, f.raw(key))
                        assertTrue(result.page.records.any { it.key == key })
                    }
                }

                else -> {
                    error("unhandled server vector operation")
                }
            }
        }

    private suspend fun resetBoundary(
        f: WriterFixture,
        scenario: JsonObject,
    ) {
        val cursor = scenario.getValue("before").jsonPrimitive.content
        val keep = scenario.getValue("keepSeq").jsonArray.map { it.jsonPrimitive.long }
        val remove = scenario.getValue("removeSeq").jsonArray.map { it.jsonPrimitive.long }
        f.store.write(1) { setAccount(account.copy(cursor = account.cursor.copy(epoch = cursor.split(':')[1]))) }
        (remove + keep).sorted().forEach { seq ->
            val id = "${seq.toString().padStart(8, '0')}-1111-4111-8111-111111111111"
            val key = key(SyncCollection.PODCAST, id)
            // Later sequence, lower clock: sequence alone is the reset boundary.
            f.seed(key, f.state(key, "subscribed", JsonPrimitive(true), f.clock(-seq)), seq)
        }
        f.writer.resetLibrary(f.principal, AccountResetRequest("DELETE", "library", cursor))
        f.store.read(1) {
            records().forEach {
                val oldSequence =
                    (it.key.id as RecordId.Podcast)
                        .id.value
                        .substringBefore('-')
                        .toLong()
                assertEquals(oldSequence in remove, RecordMerger.isDead(it.raw))
                if (oldSequence in keep) {
                    assertEquals(oldSequence, it.seq)
                } else {
                    assertTrue(it.seq > (remove + keep).max())
                }
            }
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun vectors(): List<Array<File>> {
            val root = File(System.getProperty("neutrodyne.moduleDir"), "../protocol/vectors")
            val vectors =
                requireNotNull(root.listFiles())
                    .filter {
                        it.name.startsWith("server-") &&
                            it.extension == "json"
                    }.sortedBy { it.name }
            check(vectors.size == 4) { "Expected all four frozen server scenarios" }
            return vectors.map { arrayOf(it) }
        }
    }
}
