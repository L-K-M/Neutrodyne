// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.ProtocolException
import ch.lkmc.neutrodyne.sync.protocol.RecordMerger
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncErrorCode
import ch.lkmc.neutrodyne.sync.protocol.dto.AccountResetRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryResetTest {
    @Test
    fun malformedMissingForeignFutureAndExpiredCutoffsDoNotMutate() =
        runBlocking {
            val f = WriterFixture()
            f.push(f.podcast())
            val before = f.record(podcastA)
            val cases =
                listOf(
                    null to SyncErrorCode.INVALID,
                    "bad" to SyncErrorCode.INVALID,
                    "c:ffffffff:1" to SyncErrorCode.CURSOR_EXPIRED,
                    "c:00000001:2" to SyncErrorCode.INVALID,
                )
            for ((cursor, code) in cases) {
                assertEquals(
                    code,
                    assertFailsWith<ProtocolException> {
                        f.writer.resetLibrary(f.principal, AccountResetRequest("DELETE", "library", cursor))
                    }.code,
                )
                assertEquals(before, f.record(podcastA))
            }
            f.store.write(1) { setAccount(account.copy(cursor = account.cursor.copy(minCursor = 1))) }
            assertEquals(
                SyncErrorCode.CURSOR_EXPIRED,
                assertFailsWith<ProtocolException> {
                    f.writer.resetLibrary(f.principal, AccountResetRequest("DELETE", "library", "c:00000001:0"))
                }.code,
            )
            listOf(
                AccountResetRequest("delete", "library", f.cursor()),
                AccountResetRequest("DELETE", "purge", f.cursor()),
            ).forEach {
                assertFailsWith<ProtocolException> { f.writer.resetLibrary(f.principal, it) }
            }
        }

    @Test
    fun resetRemovesOnlyEligibleCollectionsAndClearsSessionWithServerClock() =
        runBlocking {
            val f = WriterFixture()
            f.push(
                f.podcast(),
                f.group(),
                f.write(member(), "in", JsonPrimitive(true)),
                f.write(episode(coll = SyncCollection.UPNEXT), "in", JsonPrimitive(true)),
                f.write(episode(), "fav", JsonPrimitive(true)),
                f.write(setting, "v", JsonPrimitive(2)),
                f.write(session, "episode", episode().id.toWire()),
            )
            val episodeBefore = f.record(episode())
            val settingBefore = f.record(setting)
            val cutoff = f.cursor()
            val cursor = f.writer.resetLibrary(f.principal, AccountResetRequest("DELETE", "library", cutoff))
            listOf(podcastA, groupG, member(), episode(coll = SyncCollection.UPNEXT)).forEach {
                assertTrue(RecordMerger.isDead(f.raw(it)))
                assertEquals(ch.lkmc.neutrodyne.sync.protocol.NodeId.SERVER, f.record(it).maxClock!!.nodeId)
            }
            assertEquals(episodeBefore, f.record(episode()))
            assertEquals(settingBefore, f.record(setting))
            assertEquals(
                JsonNull,
                f
                    .raw(session)
                    .fields["episode"]!!
                    .v,
            )
            assertTrue(cursor.substringAfterLast(':').toLong() > cutoff.substringAfterLast(':').toLong())
        }

    @Test
    fun snapshotCutoffProtectsLaterLowClockEditsAndQueueOfAnOlderParent() =
        runBlocking {
            val f = WriterFixture()
            f.push(f.podcast(), f.group(), f.write(member(), "in", JsonPrimitive(true)))
            val cutoff = f.cursor()
            f.push(
                f.write(episode(coll = SyncCollection.UPNEXT), "in", JsonPrimitive(true), f.clock(-1000)),
                f.write(groupG, "iconKey", JsonPrimitive("later"), f.clock(-1000)),
            )
            val queue = f.record(episode(coll = SyncCollection.UPNEXT))
            val group = f.record(groupG)
            f.writer.resetLibrary(f.principal, AccountResetRequest("DELETE", "library", cutoff))
            assertTrue(RecordMerger.isDead(f.raw(podcastA)))
            assertTrue(RecordMerger.isDead(f.raw(member())))
            assertEquals(queue, f.record(queue.key))
            assertEquals(group, f.record(groupG))
            assertFalse(RecordMerger.isDead(f.raw(groupG)))
        }

    @Test
    fun restartSeedsClockFromRawMaximumAndWatermarkEvenAfterGc() =
        runBlocking {
            val f = WriterFixture()
            f.push(f.podcast(at = f.clock(240_000)))
            val maximum = f.record(podcastA).maxClock!!
            f.store.write(1) { setAccount(account.copy(clockPacked = 0)) }
            val restart = StoredRecordWriter(f.store, f.inputs)
            restart.resetLibrary(f.principal, AccountResetRequest("DELETE", "library", f.cursor()))
            assertTrue(f.record(podcastA).maxClock!! > maximum)
            val watermark = f.store.read(1) { account.clockPacked }
            f.store.write(1) {
                remove(podcastA)
                setPodcastKeys(podcastA, emptySet())
            }
            f.now -= 100_000
            f.push(f.podcast())
            val latest = StoredRecordWriter(f.store, f.inputs)
            latest.resetLibrary(f.principal, AccountResetRequest("DELETE", "library", f.cursor()))
            assertTrue(f.record(podcastA).maxClock!! > Hlc(watermark, ch.lkmc.neutrodyne.sync.protocol.NodeId.SERVER))
        }
}
