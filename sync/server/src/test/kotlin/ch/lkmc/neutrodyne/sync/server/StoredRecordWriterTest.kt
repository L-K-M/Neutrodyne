// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.ProtocolException
import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncErrorCode
import ch.lkmc.neutrodyne.sync.protocol.dto.AccountResetRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto
import ch.lkmc.neutrodyne.sync.protocol.dto.FieldValue
import ch.lkmc.neutrodyne.sync.protocol.dto.MatchHints
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoredRecordWriterTest {
    @Test
    fun identicalRetryDoesNotChurnSequencesOrEvents() =
        runBlocking {
            val f = WriterFixture()
            val change = f.podcast()
            val first = f.push(change, f.write(episode(), "fav", JsonPrimitive(true)))
            assertEquals(1, (f.store as FakeSyncStore).writes)
            assertEquals(listOf(1L, 2L), first.page.records.map { it.seq })
            val before = f.record(podcastA)
            val retry = f.push(change)
            assertEquals("applied", retry.results.single().status)
            assertEquals(before, f.record(podcastA))
            f.store.read(1) { assertEquals(1, (this as FakeSyncTransaction).events.size) }
        }

    @Test
    fun invalidFieldsAndUnknownCollectionsRejectIndependently() =
        runBlocking {
            val f = WriterFixture()
            val good = f.write(setting, "v", JsonNull)
            val result =
                f.push(
                    good.copy(coll = "future"),
                    f.write(setting, "bad-name", JsonPrimitive(1)),
                    f.write(setting, "x".repeat(65), JsonPrimitive(1)),
                    f.write(podcastA, "subscribed", JsonPrimitive("true")),
                    f.write(setting, "future.Field~_1", JsonNull),
                    good,
                )
            assertEquals(
                listOf("unknown_collection", "invalid", "invalid", "invalid", null, null),
                result.results.map { it.code },
            )
            assertEquals(
                JsonNull,
                f
                    .raw(setting)
                    .fields
                    .getValue("future.Field~_1")
                    .v,
            )
            assertEquals(
                2,
                f.raw(setting).fields.size,
            )
        }

    @Test
    fun admissionChecksExactBoundsAndNeverClamps() =
        runBlocking {
            val f = WriterFixture()
            val stamps =
                listOf(
                    ProtocolLimits.MIN_CLOCK_MS - 1,
                    ProtocolLimits.MIN_CLOCK_MS,
                    f.now + ProtocolLimits.MAX_SKEW_MS,
                    f.now + ProtocolLimits.MAX_SKEW_MS + 1,
                )
            val changes =
                stamps.mapIndexed { i, ms ->
                    f.write(key(SyncCollection.SETTING, "test.$i"), "v", JsonPrimitive(i), Hlc.of(ms, 0, NodeId.SERVER))
                }
            val result = f.push(*changes.toTypedArray())
            assertEquals(listOf("clock_skew", null, null, "clock_skew"), result.results.map { it.code })
            assertEquals(stamps[2], f.record(key(SyncCollection.SETTING, "test.2")).maxClock!!.millis)
        }

    @Test
    fun rawNullResetGrowSetsAndHintProvenanceSurviveWriterRoundTrip() =
        runBlocking {
            val f = WriterFixture()
            val pos = buildJsonObject { put("ms", 0) }
            val change =
                ChangeDto(
                    "episode",
                    episode().id.toWire(),
                    match = MatchHints(title = "hint", guid = "guid"),
                    fields =
                        mapOf(
                            "pos" to FieldValue(pos, f.clock().toString(), reset = true),
                            "future" to FieldValue(JsonNull, f.clock().toString()),
                        ),
                    matchClocks = mapOf("title" to f.clock(-100).toString(), "guid" to f.clock(-200).toString()),
                )
            f.push(
                change,
                f.podcast().copy(
                    fields =
                        f.podcast().fields + ("feedKeys" to FieldValue(add = listOf("alias.test/"))),
                ),
            )
            val raw = f.raw(episode())
            assertTrue(raw.fields.getValue("pos").reset)
            assertEquals(JsonNull, raw.fields.getValue("future").v)
            assertEquals(f.clock(-200), raw.matchClocks["guid"])
            assertEquals(setOf("alias.test/"), f.raw(podcastA).growSets["feedKeys"])
            assertEquals(
                raw,
                f
                    .push()
                    .page.records
                    .first { it.key == episode() }
                    .raw,
            )
        }

    @Test
    fun quotaRejectsGrowthButAllowsUpdatesRemovalsAndDedupe() =
        runBlocking {
            val f = WriterFixture()
            f.store.write(1) { setAccount(account.copy(quotaRecords = 1)) }
            val result =
                f.push(
                    f.podcast(),
                    f.write(setting, "v", JsonPrimitive(1)),
                    f.podcast(B, subscribedAt = 2, at = f.clock(1)),
                    f.write(podcastA, "title", JsonPrimitive("edit"), f.clock(2)),
                )
            assertEquals(listOf(null, "quota", null, null), result.results.map { it.code })
            assertEquals("merged", result.results[2].status)
            f.push(f.write(podcastA, "subscribed", JsonPrimitive(false), f.clock(3)))
            assertEquals(
                "applied",
                f
                    .push(f.write(setting, "v", JsonNull))
                    .results
                    .single()
                    .status,
            )
        }

    @Test
    fun aPausedRequestCannotActAfterItsCredentialDies() =
        runBlocking {
            val f = WriterFixture()
            f.push(f.podcast())
            val tx = (f.store as FakeSyncStore).accounts.getValue(1)
            tx.invalidCredentials += f.principal.tokenHash
            val push =
                assertFailsWith<ProtocolException> {
                    f.push(f.write(setting, "v", JsonPrimitive(1)))
                }
            assertEquals(SyncErrorCode.UNAUTHORIZED, push.code)
            assertFailsWith<ProtocolException> { f.writer.changes(f.principal, null) }
            assertFailsWith<ProtocolException> {
                f.writer.resetLibrary(f.principal, AccountResetRequest("DELETE", "library"))
            }
            assertNull(tx.raw[setting])
        }

    @Test
    fun pushAndResetNeverMaterializeTheAccount() =
        runBlocking {
            val f = WriterFixture()
            f.push(f.podcast(), f.write(setting, "v", JsonPrimitive(1)))
            val tx = (f.store as FakeSyncStore).accounts.getValue(1)
            tx.failOnRecords = true
            // The quota check must aggregate in SQL, never load the record set.
            assertTrue(f.push().results.isEmpty())
            assertTrue(
                f.writer
                    .resetLibrary(f.principal, AccountResetRequest("DELETE", "library", f.cursor()))
                    .isNotEmpty(),
            )
        }

    @Test
    fun aValueBeyondTheAggregateCapIsRejectedAtAdmission() =
        runBlocking {
            val f = WriterFixture()
            val huge = oversizedValue(ProtocolLimits.MAX_VALUE_BYTES + 65_536)
            val result = f.push(f.write(setting, "v", huge))
            assertEquals("rejected", result.results.single().status)
            assertEquals("payload_too_large", result.results.single().code)
            assertNull((f.store as FakeSyncStore).accounts.getValue(1).raw[setting])
        }

    @Test
    fun aMergedRecordBeyondTheValueCapIsRejectedAndNeverPoisonsPages() =
        runBlocking {
            val f = WriterFixture()
            // Each single write stays under the aggregate cap; merged together they exceed it.
            val big = oversizedValue(ProtocolLimits.MAX_VALUE_BYTES * 3 / 4)
            assertEquals(
                "applied",
                f
                    .push(f.write(setting, "v", big))
                    .results
                    .single()
                    .status,
            )
            val result = f.push(f.write(setting, "extra", big, f.clock(1)))
            assertEquals("rejected", result.results.single().status)
            assertEquals("payload_too_large", result.results.single().code)
            val raw = f.raw(setting)
            assertEquals(setOf("v"), raw.fields.keys)
            // The stored record still fits one response page.
            assertEquals(
                listOf(setting),
                f
                    .push()
                    .page.records
                    .map { it.key },
            )
        }

    /** A JSON array of individually legal elements whose serialized sum reaches [target] bytes. */
    private fun oversizedValue(target: Long): JsonArray {
        val element = JsonPrimitive("y".repeat(ProtocolLimits.MAX_STRING - 96))
        return JsonArray(List((target / (ProtocolLimits.MAX_STRING - 90) + 1).toInt()) { element })
    }
}
