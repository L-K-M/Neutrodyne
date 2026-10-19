// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.protocol

import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceInfoDto
import ch.lkmc.neutrodyne.sync.protocol.dto.DiscoveryDocument
import ch.lkmc.neutrodyne.sync.protocol.dto.FieldValue
import ch.lkmc.neutrodyne.sync.protocol.dto.MatchHints
import ch.lkmc.neutrodyne.sync.protocol.dto.ProtocolRange
import ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolContractTest {
    private val podcast = PodcastId("11111111-1111-4111-8111-111111111111")
    private val group = GroupId("22222222-2222-4222-8222-222222222222")
    private val clock = Hlc.of(ProtocolLimits.MIN_CLOCK_MS, 0, NodeId("0000000000000001"))

    @Test
    fun everyIdRoundTripsBothStorageAndWire() {
        val pairs =
            listOf(
                SyncCollection.PODCAST to RecordId.Podcast(podcast),
                SyncCollection.GROUP to RecordId.Group(group),
                SyncCollection.MEMBER to RecordId.Member(group, podcast),
                SyncCollection.EPISODE to RecordId.Episode(podcast, EpisodeKey("g:opaque:key:with:colons")),
                SyncCollection.UPNEXT to RecordId.Episode(podcast, EpisodeKey("v2:g:key")),
                SyncCollection.SESSION to RecordId.Session,
                SyncCollection.SETTING to RecordId.Setting("playback.speed"),
            )
        pairs.forEach { (coll, id) ->
            assertEquals(id, RecordId.fromWire(coll, id.toWire()))
            assertEquals(id, RecordId.fromRid(coll, id.rid))
        }
        assertEquals(group.value + podcast.value, pairs[2].second.rid)
    }

    @Test
    fun hostileIdsAreRejectedWithoutChangingOpaqueKeys() {
        listOf("", "ABC", podcast.value.uppercase(), podcast.value.replace('-', '_')).forEach { id ->
            // A numeric-only UUID is unchanged by uppercase; use an explicit alpha digit too.
            if (id != podcast.value) assertFailsWith<IllegalArgumentException> { PodcastId(id) }
        }
        assertFailsWith<IllegalArgumentException> { PodcastId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaA") }
        listOf("", "plain", "g:\n", "g:" + "é".repeat(1_024)).forEach { key ->
            assertFailsWith<IllegalArgumentException> { EpisodeKey(key) }
        }
        val max = EpisodeKey("g:" + "x".repeat(ProtocolLimits.MAX_IDENTITY_KEY - 2))
        assertEquals(max, (RecordId.fromRid(SyncCollection.EPISODE, podcast.value + max.value) as RecordId.Episode).key)
        listOf("A", "key-with-dash", "x".repeat(65)).forEach { key ->
            assertFailsWith<IllegalArgumentException> { RecordId.Setting(key) }
        }
        assertFailsWith<IllegalArgumentException> { RecordId.fromRid(SyncCollection.MEMBER, group.value) }
        assertFailsWith<IllegalArgumentException> { RecordId.fromWire(SyncCollection.SESSION, JsonPrimitive("other")) }
    }

    @Test
    fun registryIncludesExactlyTheSevenCollectionsAndExcludesLocalColumns() {
        assertEquals(
            setOf("podcast", "group", "member", "episode", "upnext", "session", "setting"),
            SyncCollection.entries.map { it.wire }.toSet(),
        )
        SyncCollection.entries.forEach { coll ->
            assertEquals(coll, SyncCollection.fromWire(coll.wire))
            assertTrue(coll.fields.isNotEmpty())
            assertEquals(
                coll.fields.keys,
                coll.fields.values
                    .map { it.name }
                    .toSet(),
            )
        }
        assertEquals(
            FieldKind.LWW_POSITION,
            SyncCollection.EPISODE.fields
                .getValue("pos")
                .kind,
        )
        assertEquals(
            FieldKind.GROW_SET,
            SyncCollection.PODCAST.fields
                .getValue("feedKeys")
                .kind,
        )
        assertEquals(
            FieldKind.MIN_FIELD,
            SyncCollection.MEMBER.fields
                .getValue("addedAt")
                .kind,
        )
        assertEquals(
            FieldKind.MAX_FIELD,
            SyncCollection.EPISODE.fields
                .getValue("lastPlayedAt")
                .kind,
        )
        assertTrue(
            listOf("status", "guidCoverageSince", "autoDownload", "updatedAt", "startedAt").none { name ->
                SyncCollection.entries.any { name in it.fields }
            },
        )
    }

    @Test
    fun everyRegistryFieldEnforcesShapeClockAndNullability() {
        SyncCollection.entries.forEach { coll ->
            coll.fields.values.forEach { spec ->
                val sample = sample(spec)
                ProtocolValidation.field(coll, spec.name, sample)
                if (spec.kind == FieldKind.GROW_SET) {
                    assertInvalid { ProtocolValidation.field(coll, spec.name, sample.copy(c = clock.toString())) }
                    assertInvalid { ProtocolValidation.field(coll, spec.name, FieldValue(v = JsonArray(emptyList()))) }
                } else {
                    assertInvalid { ProtocolValidation.field(coll, spec.name, sample.copy(v = null)) }
                    if (spec.kind != FieldKind.MIN_FIELD && spec.kind != FieldKind.MAX_FIELD) {
                        assertInvalid { ProtocolValidation.field(coll, spec.name, sample.copy(c = null)) }
                    }
                    if (spec.nullability == Nullability.NULLABLE) {
                        ProtocolValidation.field(coll, spec.name, sample.copy(v = JsonNull))
                    } else {
                        assertInvalid { ProtocolValidation.field(coll, spec.name, sample.copy(v = JsonNull)) }
                    }
                    if (spec.type !in setOf(ValueType.JSON, ValueType.TEXT, ValueType.URL)) {
                        assertInvalid {
                            ProtocolValidation.field(
                                coll,
                                spec.name,
                                sample.copy(v = JsonPrimitive("wrong-type")),
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun schemeFreeFeedKeysRemainOpaqueBoundedIdentityStrings() {
        ProtocolValidation.field(
            SyncCollection.PODCAST,
            "feedKeys",
            FieldValue(add = listOf("feeds.example.com/Show?a=1")),
        )
    }

    @Test
    fun numericAndEnumValidationRejectsCoercionAndNonFiniteValues() {
        listOf(JsonPrimitive("3"), JsonPrimitive(1.5), JsonPrimitive(-1), JsonPrimitive("NaN")).forEach { value ->
            assertInvalid { ProtocolValidation.field(SyncCollection.EPISODE, "playCount", fv(value)) }
        }
        assertInvalid { ProtocolValidation.field(SyncCollection.PODCAST, "s.playbackSpeed", fv(JsonPrimitive(3.5))) }
        assertInvalid { ProtocolValidation.field(SyncCollection.GROUP, "colorArgb", fv(JsonPrimitive(Long.MAX_VALUE))) }
        assertInvalid { ProtocolValidation.field(SyncCollection.GROUP, "feedOrder", fv(JsonPrimitive("RANDOM"))) }
        assertInvalid { ProtocolValidation.field(SyncCollection.PODCAST, "includeInAll", fv(JsonPrimitive("true"))) }
    }

    @Test
    fun utf8CapsApplyToDecodedStrings() {
        ProtocolValidation.field(SyncCollection.PODCAST, "title", fv(JsonPrimitive("é".repeat(512))))
        assertInvalid { ProtocolValidation.field(SyncCollection.PODCAST, "title", fv(JsonPrimitive("é".repeat(513)))) }
        ProtocolValidation.field(SyncCollection.PODCAST, "title", fv(JsonPrimitive("😀".repeat(256))))
        assertInvalid { ProtocolValidation.field(SyncCollection.PODCAST, "title", fv(JsonPrimitive("😀".repeat(257)))) }
        assertInvalid {
            ProtocolValidation.field(
                SyncCollection.PODCAST,
                "future",
                fv(JsonPrimitive("x".repeat(4_097))),
            )
        }
    }

    @Test
    fun urlFieldsRejectUserinfoAndUnsafeSchemes() {
        listOf(
            "file:///etc/passwd",
            "javascript:alert(1)",
            "https://u:p@example.com/x",
            "https:///x",
            "https://a b/x",
            "https://example.com\\evil",
        ).forEach { value ->
            assertInvalid { ProtocolValidation.field(SyncCollection.PODCAST, "feedUrl", fv(JsonPrimitive(value))) }
        }
        ProtocolValidation.field(
            SyncCollection.PODCAST,
            "feedUrl",
            fv(JsonPrimitive("https://example.com/private?token=x")),
        )
    }

    @Test
    fun explicitNullSurvivesDtoAndRawSerializationWhileOmissionDoesNotInherit() {
        val nullValue = FieldValue(v = JsonNull, c = clock.toString())
        val encoded = SyncJson.encodeToString(FieldValue.serializer(), nullValue)
        assertTrue("\"v\":null" in encoded)
        assertEquals(nullValue, SyncJson.decodeFromString<FieldValue>(encoded))
        assertNull(SyncJson.decodeFromString<FieldValue>("{}").v)
        assertEquals("{}", SyncJson.encodeToString(SyncRequest.serializer(), SyncRequest()))
        assertEquals(FieldValue(), SyncJson.decodeFromString<FieldValue>("{\"future\":true}"))
    }

    @Test
    fun fieldNameGrammarIsSharedAndFailuresStayPerChange() {
        val value = fv(JsonPrimitive("extension"))
        listOf("A0_.~", "x".repeat(64)).forEach {
            ProtocolValidation.field(SyncCollection.PODCAST, it, value)
        }
        listOf("", "x".repeat(65), "bad-name", "é", "bad name").forEach { name ->
            val bad = ChangeDto("podcast", podcastWire(), fields = mapOf(name to value))
            val good = bad.copy(fields = mapOf("title" to value))
            val request =
                ProtocolCodec.decodeRequest(
                    SyncJson.encodeToString(SyncRequest.serializer(), SyncRequest(changes = listOf(bad, good))),
                )
            assertInvalid { ProtocolValidation.change(request.changes[0]) }
            assertTrue(ProtocolValidation.change(request.changes[1]) is ValidatedChange.Write)
        }
        assertInvalid { ProtocolCodec.parse("\"" + "x".repeat(4_097) + "\"") }
    }

    private fun podcastWire() = JsonPrimitive(podcast.value)

    @Test
    fun malformedChangesAreRejectedIndependently() {
        val bad = ChangeDto("future", JsonPrimitive("id"))
        val good =
            ChangeDto(
                "podcast",
                JsonPrimitive(podcast.value),
                fields = mapOf("title" to fv(JsonPrimitive("valid"))),
            )
        val request =
            ProtocolCodec.decodeRequest(
                SyncJson.encodeToString(
                    SyncRequest.serializer(),
                    SyncRequest(changes = listOf(bad, good)),
                ),
            )
        assertCode(SyncErrorCode.UNKNOWN_COLLECTION) { ProtocolValidation.change(request.changes[0]) }
        assertTrue(ProtocolValidation.change(request.changes[1]) is ValidatedChange.Write)
        assertInvalid { ProtocolValidation.change(good.copy(op = "remove")) }
        assertInvalid { ProtocolValidation.change(good.copy(to = JsonPrimitive(podcast.value))) }
        assertInvalid { ProtocolValidation.change(good.copy(fields = emptyMap())) }
    }

    @Test
    fun rekeyValidatesCollectionPodcastTargetAndPayload() {
        val from = RecordId.Episode(podcast, EpisodeKey("g:old")).toWire()
        val to = RecordId.Episode(podcast, EpisodeKey("g:new")).toWire()
        val valid = ChangeDto("episode", from, "rekey", to)
        assertTrue(ProtocolValidation.change(valid) is ValidatedChange.Rekey)
        assertInvalid { ProtocolValidation.change(valid.copy(to = from)) }
        assertInvalid { ProtocolValidation.change(valid.copy(coll = "upnext")) }
        assertInvalid { ProtocolValidation.change(valid.copy(fields = mapOf("fav" to fv(JsonPrimitive(true))))) }
        assertInvalid { ProtocolValidation.change(valid.copy(match = MatchHints(title = "unexpected"))) }
    }

    @Test
    fun playedPairAndPositionHaveSeparateValidationRules() {
        val id = RecordId.Episode(podcast, EpisodeKey("g:ep")).toWire()
        val pair = mapOf("played" to fv(JsonPrimitive(false)), "playedAt" to fv(JsonNull))
        ProtocolValidation.change(ChangeDto("episode", id, fields = pair))
        assertInvalid { ProtocolValidation.change(ChangeDto("episode", id, fields = pair - "playedAt")) }
        assertInvalid {
            ProtocolValidation.change(
                ChangeDto(
                    "episode",
                    id,
                    fields = pair + ("playedAt" to fv(JsonPrimitive(1))),
                ),
            )
        }
        assertInvalid {
            ProtocolValidation.field(
                SyncCollection.EPISODE,
                "fav",
                fv(JsonPrimitive(true)).copy(reset = false),
            )
        }
        assertInvalid { ProtocolValidation.field(SyncCollection.EPISODE, "pos", fv(buildJsonObject { put("ms", -1) })) }
        assertInvalid {
            ProtocolValidation.field(
                SyncCollection.EPISODE,
                "pos",
                fv(
                    buildJsonObject {
                        put(
                            "ms",
                            1,
                        )
                        ; put("src", "CACHE")
                    },
                ),
            )
        }
    }

    @Test
    fun replayHintProvenanceIsNotPromotedToTheNewestFieldClock() {
        val id = RecordId.Episode(podcast, EpisodeKey("g:ep"))
        val latest = Hlc(clock.packed + 3, clock.nodeId)
        val change =
            ChangeDto(
                "episode",
                id.toWire(),
                fields =
                    mapOf("fav" to FieldValue(JsonPrimitive(true), latest.toString())),
                match = MatchHints(title = "old"),
                matchClocks = mapOf("title" to clock.toString()),
            )
        val decoded = SyncJson.decodeFromString<ChangeDto>(SyncJson.encodeToString(ChangeDto.serializer(), change))
        val accepted = ProtocolValidation.change(decoded, clock.millis) as ValidatedChange.Write
        assertEquals(clock, accepted.state.matchClocks["title"])
        val invalidClock = Hlc.of(clock.millis + ProtocolLimits.MAX_SKEW_MS + 1, 0, clock.nodeId)
        assertCode(SyncErrorCode.CLOCK_SKEW) {
            ProtocolValidation.change(
                change.copy(matchClocks = mapOf("title" to invalidClock.toString())),
                clock.millis,
            )
        }
    }

    @Test
    fun hintProvenanceSurvivesFullRecordRelayAndRejectsOrphans() {
        val id = RecordId.Episode(podcast, EpisodeKey("g:ep"))
        val early =
            RecordState(
                SyncCollection.EPISODE,
                mapOf("fav" to FieldState(JsonPrimitive(true), clock)),
                match = MatchHints(title = "early", guid = "g"),
            )
        val middle =
            RecordState(
                SyncCollection.EPISODE,
                mapOf("measuredDurationMs" to FieldState(JsonPrimitive(10), Hlc(clock.packed + 1, clock.nodeId))),
                match = MatchHints(title = "middle"),
            )
        val late =
            RecordState(
                SyncCollection.EPISODE,
                mapOf(
                    "pos" to
                        FieldState(
                            buildJsonObject { put("ms", 5) },
                            Hlc(clock.packed + 2, clock.nodeId),
                        ),
                ),
            )
        val relayed =
            ProtocolValidation.record(
                RecordMerger
                    .merge(
                        RecordMerger.merge(null, early).record,
                        late,
                    ).record
                    .toDto(id, 1, null),
            )
        val expected =
            RecordMerger
                .merge(
                    RecordMerger
                        .merge(
                            RecordMerger.merge(null, early).record,
                            middle,
                        ).record,
                    late,
                ).record
        assertEquals(expected, RecordMerger.merge(relayed, middle).record)
        assertInvalid { ProtocolValidation.state(early.copy(matchClocks = mapOf("enc" to clock))) }
        assertInvalid { RecordMerger.merge(null, RecordState(SyncCollection.PODCAST, match = MatchHints(title = "x"))) }
    }

    @Test
    fun orderKeysAreValidatedWithoutGeneratingNewKeys() {
        OrderKey.rewrite(100).forEach { assertTrue(OrderKey.isValid(it)) }
        listOf("", "x", "a0!", "a0V0", "a🙂").forEach { assertFalse(OrderKey.isValid(it), it) }
        val a = RecordId.Podcast(podcast)
        val b = RecordId.Podcast(PodcastId("33333333-3333-4333-8333-333333333333"))
        assertTrue(ReplicatedOrder.compare("a0", a, "a0", b) < 0)
        assertTrue(ReplicatedOrder.compare("a1", a, "a0", b) > 0)
    }

    @Test
    fun discoveryAndDeviceValidationMatchesTheWireContract() {
        val doc =
            DiscoveryDocument(
                "Home",
                "1.0",
                ProtocolRange(1, 1),
                emptyList(),
                1_000,
                300_000,
                "2026-10-19T00:00:00.000Z",
            )
        ProtocolValidation.discovery(doc)
        assertInvalid { ProtocolValidation.discovery(doc.copy(protocol = ProtocolRange(2, 1))) }
        assertInvalid { ProtocolValidation.discovery(doc.copy(maxBatch = 0)) }
        assertInvalid { ProtocolValidation.discovery(doc.copy(serverTime = "yesterday")) }
        val device = DeviceInfoDto(podcast.value, " Pixel ", "android", "1.0", clock.nodeId.value)
        ProtocolValidation.device(device)
        assertInvalid { ProtocolValidation.device(device.copy(platform = "gpodder")) }
        assertInvalid { ProtocolValidation.device(device.copy(name = " ")) }
        assertInvalid { ProtocolValidation.device(device.copy(nodeId = "invalid")) }
        assertInvalid { ProtocolValidation.device(device.copy(name = "x".repeat(65))) }
    }

    @Test
    fun cursorValidationRejectsOverflowAndBadEpochs() {
        ProtocolValidation.cursor("c:0123abcd:0")
        listOf("c:ABCDefab:1", "c:0123abcd:-1", "c:0123abcd:9223372036854775808", "c:0123abcd:1:2").forEach { value ->
            assertInvalid { ProtocolValidation.cursor(value) }
        }
    }

    @Test
    fun vectorRunnerCanExerciseAServerAdapterWithTheSameAssertions() {
        val changes =
            (0..2).map { index ->
                VectorRecord(
                    fields =
                        mapOf(
                            "title" to
                                FieldValue(
                                    JsonPrimitive("v$index"),
                                    Hlc(clock.packed + index, clock.nodeId).toString(),
                                ),
                        ),
                )
            }
        val vector =
            ConformanceVector(
                "adapter-seam",
                "podcast",
                JsonPrimitive(podcast.value),
                changes = changes,
                expect = VectorExpectation(status = List(3) { "applied" }, fields = changes.last().fields),
                permutations = true,
            )
        val text = SyncJson.encodeToString(ConformanceVector.serializer(), vector)
        var calls = 0
        val run =
            ConformanceVectors.run(text) { stored, incoming ->
                calls++
                RecordMerger.merge(stored, incoming)
            }
        assertEquals(6, run.permutationsChecked)
        assertEquals(21, calls)
        assertFailsWith<IllegalStateException> {
            ConformanceVectors.run(
                text,
            ) { _, incoming -> MergeResult(RecordState(incoming.coll), emptySet(), emptySet()) }
        }
    }

    @Test
    fun responseExtrasCanExceedPageCount() {
        val dto =
            RecordDto(
                "podcast",
                JsonPrimitive(podcast.value),
                1,
                null,
                mapOf("title" to fv(JsonPrimitive("title"))),
            )
        val response = SyncResponse(emptyList(), List(1_001) { dto }, "c:1234abcd:1", false, "2026-10-19T00:00:00.000Z")
        assertEquals(
            response,
            ProtocolCodec.decodeResponse(
                SyncJson.encodeToString(
                    SyncResponse.serializer(),
                    response,
                ),
            ),
        )
    }

    private fun sample(spec: FieldSpec): FieldValue {
        if (spec.kind == FieldKind.GROW_SET) return FieldValue(add = listOf("https://example.com/feed"))
        val value: JsonElement =
            when (spec.type) {
                ValueType.JSON -> {
                    JsonNull
                }

                ValueType.TEXT -> {
                    JsonPrimitive(spec.choices.firstOrNull() ?: if (spec.name == "ok") "a0" else "text")
                }

                ValueType.URL -> {
                    JsonPrimitive("https://example.com/feed")
                }

                ValueType.BOOLEAN -> {
                    JsonPrimitive(true)
                }

                ValueType.INTEGER -> {
                    JsonPrimitive(spec.min?.toLong()?.coerceAtLeast(1) ?: 1)
                }

                ValueType.NUMBER -> {
                    JsonPrimitive(1.0)
                }

                ValueType.POSITION -> {
                    buildJsonObject {
                        put("ms", 1)
                        put("dur", JsonNull)
                        put("src", "STREAM")
                    }
                }

                ValueType.AUTH -> {
                    buildJsonObject {
                        put("u", "user")
                        put("p", "secret")
                    }
                }

                ValueType.EPISODE_REF -> {
                    RecordId.Episode(podcast, EpisodeKey("g:ep")).toWire()
                }

                ValueType.CONTEXT -> {
                    buildJsonObject { put("type", "ALL") }
                }

                ValueType.STRING_SET -> {
                    error("handled above")
                }
            }
        return fv(value)
    }

    private fun fv(value: JsonElement): FieldValue = FieldValue(v = value, c = clock.toString())

    private fun assertInvalid(block: () -> Unit) = assertCode(SyncErrorCode.INVALID, block)

    private fun assertCode(
        code: SyncErrorCode,
        block: () -> Unit,
    ) {
        assertEquals(code, assertFailsWith<ProtocolException>(block = block).code)
    }
}
