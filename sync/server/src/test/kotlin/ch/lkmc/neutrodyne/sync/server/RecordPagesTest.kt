// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.ProtocolException
import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncErrorCode
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordPagesTest {
    @Test
    fun invalidExpiredAndImpossibleCursorsFailBeforeMutationOrAcknowledgement() =
        runBlocking {
            val f = WriterFixture()
            f.push(f.podcast())
            val before = f.record(podcastA)
            val cursors =
                mapOf(
                    "bad" to SyncErrorCode.INVALID,
                    "c:ffffffff:1" to SyncErrorCode.CURSOR_EXPIRED,
                    "c:00000001:2" to SyncErrorCode.CURSOR_EXPIRED,
                )
            for ((cursor, code) in cursors) {
                val failure =
                    assertFailsWith<ProtocolException> {
                        f.push(f.write(podcastA, "title", JsonPrimitive("change"), f.clock(1)), since = cursor)
                    }
                assertEquals(code, failure.code)
                assertEquals(before, f.record(podcastA))
            }
            f.store.write(1) { setAccount(account.copy(cursor = account.cursor.copy(minCursor = 1))) }
            assertFailsWith<ProtocolException> { f.writer.changes(f.principal, "c:00000001:0") }
            assertEquals(
                1,
                f.writer
                    .changes(f.principal, null)
                    .records.size,
            )
            assertTrue(
                f.writer
                    .changes(f.principal, "c:00000001:1")
                    .records
                    .isEmpty(),
            )
        }

    @Test
    fun pageRecordCapHasMoreAndAcknowledgementUsesRequestCursor() =
        runBlocking {
            val f = WriterFixture()
            f.store.write(1) {
                repeat(ProtocolLimits.MAX_RECORDS + 1) { i ->
                    val key = key(SyncCollection.SETTING, "page.$i")
                    put(StoredRecord(key, f.state(key, "v", JsonPrimitive(i)), nextSequence(), null, f.now, null))
                }
            }
            val first = f.writer.changes(f.principal, null)
            assertEquals(1000, first.records.size)
            assertTrue(first.hasMore)
            assertEquals("c:00000001:1000", first.cursor)
            f.store.read(
                1,
            ) { assertEquals(null, (this as FakeSyncTransaction).acknowledgements[f.principal.deviceId]?.first) }
            val second = f.writer.changes(f.principal, first.cursor)
            assertEquals(1, second.records.size)
            assertFalse(second.hasMore)
            f.store.read(1) {
                assertEquals(first.cursor, (this as FakeSyncTransaction).acknowledgements[f.principal.deviceId]?.first)
            }
        }

    @Test
    fun escapedUtf8ByteBudgetIsMeasuredAfterEncodingAndPaginationLosesNothing() =
        runBlocking {
            val f = WriterFixture()
            // Escaped controls expand sixfold in JSON; a character count would overfill this page.
            val fields =
                (0 until ProtocolLimits.MAX_FIELDS_PER_RECORD).associate {
                    "x$it" to
                        JsonPrimitive("\u0001".repeat(4096))
                }
            f.store.write(1) {
                repeat(7) { i ->
                    val key = key(SyncCollection.SETTING, "large.$i")
                    val raw =
                        f.change(key, fields).fields.mapValues { (_, v) ->
                            FieldState(v.v!!, f.clock())
                        }
                    put(
                        StoredRecord(
                            key,
                            RecordState(key.collection, raw),
                            nextSequence(),
                            null,
                            f.now,
                            null,
                        ),
                    )
                }
            }
            val first = f.writer.changes(f.principal, null)
            assertEquals(5, first.records.size)
            assertTrue(first.hasMore)
            val bytes =
                first.records.sumOf {
                    SyncJson
                        .encodeToString(RecordDto.serializer(), it.raw.toDto(it.key.id, it.seq, it.byDevice))
                        .encodeToByteArray()
                        .size
                        .toLong()
                } + first.records.size + 1
            assertTrue(bytes <= ProtocolLimits.MAX_PAGE_BYTES)
            val second = f.writer.changes(f.principal, first.cursor)
            assertEquals(2, second.records.size)
            assertFalse(second.hasMore)
            assertEquals((1L..7L).toList(), (first.records + second.records).map { it.seq })
        }

    @Test
    fun staleExtrasBelowCursorAreMandatoryAndDoNotAdvanceIt() =
        runBlocking {
            val f = WriterFixture()
            assertEquals("c:00000001:0", f.push().page.cursor)
            f.push(f.write(setting, "v", JsonPrimitive(0), f.clock(-1)))
            f.push(f.write(setting, "v", JsonPrimitive(1)))
            assertEquals(
                1,
                f.writer
                    .changes(f.principal, null)
                    .records.size,
            )
            f.push(f.podcast())
            val cursor = f.cursor()
            val result = f.push(f.write(setting, "v", JsonPrimitive(0), f.clock(-1)), since = cursor)
            assertEquals("stale", result.results.single().status)
            assertTrue(result.page.records.isEmpty())
            assertEquals(listOf(setting), result.page.extras.map { it.key })
            assertEquals(cursor, result.page.cursor)
            assertFalse(result.page.hasMore)
            val empty = f.writer.changes(f.principal, cursor)
            assertTrue(empty.records.isEmpty())
            assertEquals(cursor, empty.cursor)
        }

    @Test
    fun mergedExtrasOutsideFullPageIncludeWinnerAndRedirectAndNeverCountTowardCap() =
        runBlocking {
            val f = WriterFixture()
            f.push(f.podcast(A), f.podcast(B, subscribedAt = 2, at = f.clock(1)))
            val cursor = f.cursor()
            f.store.write(1) {
                repeat(ProtocolLimits.MAX_RECORDS + 1) { i ->
                    val key = key(SyncCollection.SETTING, "extra.$i")
                    put(StoredRecord(key, f.state(key, "v", JsonPrimitive(i)), nextSequence(), null, f.now, null))
                }
            }
            val result =
                f.push(
                    f.write(key(SyncCollection.PODCAST, B), "title", JsonPrimitive("latest"), f.clock(2)),
                    since = cursor,
                )
            assertEquals("merged", result.results.single().status)
            assertEquals(1000, result.page.records.size)
            assertTrue(result.page.hasMore)
            assertEquals(
                setOf(podcastA, key(SyncCollection.PODCAST, B)),
                result.page.extras
                    .map { it.key }
                    .toSet(),
            )
            assertTrue(
                result.page.extras.any {
                    it.seq >
                        result.page.cursor
                            .substringAfterLast(':')
                            .toLong()
                },
            )
            val rest = f.writer.changes(f.principal, result.page.cursor)
            assertTrue(rest.records.any { it.key == podcastA })
        }
}
