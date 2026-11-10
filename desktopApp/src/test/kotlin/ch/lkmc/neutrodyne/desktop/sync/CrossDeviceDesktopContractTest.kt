// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.sync

import ch.lkmc.neutrodyne.core.database.SyncClockEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.testing.CrossDevice
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import ch.lkmc.neutrodyne.sync.api.SyncRoundSummary
import ch.lkmc.neutrodyne.sync.impl.RawSyncRecord
import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bounded contract controls for the desktop driver: the default-exclusion selector key, the
 * clear missing-config failure, fixture determinism, config validation, canonical JSON, auth
 * stripping, the SYNC_NOW barrier predicate, and the seed/edit/dump semantics against a real
 * in-memory Room database. None of these starts a graph, a server or a notification.
 */
class CrossDeviceDesktopContractTest {
    @Test
    fun theDriverIsExcludedByTheDefaultCrossDeviceCategory() {
        val category = CrossDeviceSyncDesktopTest::class.java.getAnnotation(Category::class.java)
        assertNotNull(category)
        assertEquals(1, category.value.size)
        // The build's selector key is the category's fully-qualified name.
        assertEquals("ch.lkmc.neutrodyne.core.testing.CrossDevice", category.value[0].qualifiedName)
        assertNull(CrossDeviceSyncDesktopTest::class.java.getAnnotation(org.junit.Ignore::class.java))
        assertThat(
            CrossDeviceSyncDesktopTest::class.java.methods.filter { it.isAnnotationPresent(Test::class.java) },
        ).isNotEmpty()
    }

    @Test
    fun missingConfigFailsClearlyWithoutCreatingState() {
        val root = Files.createTempDirectory("nd-cross-device-missing")
        try {
            val failure =
                assertFailsWith<AssertionError> {
                    runBlocking { CrossDeviceDesktopRunner(root, "seed").run() }
                }
            assertEquals("config: config file is missing", failure.message)
            val result =
                crossDeviceJson
                    .parseToJsonElement(root.resolve("cross-device/result.json").toFile().readText())
                    .jsonObject
            assertThat(result["ok"]!!.jsonPrimitive.boolean).isFalse()
            assertThat(result["error"]!!.jsonPrimitive.content)
                .isEqualTo("config: config file is missing")
            assertThat(result["phase"]!!.jsonPrimitive.content).isEqualTo("seed")
            assertThat(result["runId"]).isSameInstanceAs(JsonNull)
            // No graph, no database, no ownership marker: the failure happened before any mutation.
            assertThat(Files.exists(root.resolve("app"))).isFalse()
            assertThat(Files.exists(root.resolve("cross-device/ownership.json"))).isFalse()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun fixtureIsDeterministicForRunId() {
        val base = "http://127.0.0.1:1234"
        val first = crossDeviceFixture("run-a", base)
        val second = crossDeviceFixture("run-a", base)
        assertEquals(first, second)
        val otherRun = crossDeviceFixture("run-b", base)
        assertThat(first.podcasts.map { it.syncId }).isNotEqualTo(otherRun.podcasts.map { it.syncId })
        assertThat(first.podcasts).hasSize(PODCAST_COUNT)
        assertThat(first.episodes).hasSize(PODCAST_COUNT)
        assertThat(first.groups).hasSize(2)
        assertThat(first.groups[0].memberPodcastIndices).hasSize(25)
        assertThat(first.groups[1].memberPodcastIndices).hasSize(25)
        assertEquals(T_BASE, first.episodes[0].pubDate)
        assertEquals(T_BASE - 1000, first.episodes[1].pubDate)
        assertThat(first.podcasts.map { it.syncId }.toSet()).hasSize(PODCAST_COUNT)
        assertThat(first.episodes.map { it.identityKey }.toSet()).hasSize(PODCAST_COUNT)
        assertThat(first.podcasts.all { it.syncId.matches(FIXTURE_UUID) }).isTrue()
        assertThat(first.episodes.all { it.identityKey.startsWith("g:") }).isTrue()
    }

    @Test
    fun configValidationRejectsMissingAndNonLoopbackFields() {
        assertPhaseError("field 'runId' is required") {
            validateConfig(CrossDeviceConfig("", "http://127.0.0.1:1", "user", "pass", "http://127.0.0.1:1"))
        }
        assertPhaseError("field 'username' is required") {
            validateConfig(CrossDeviceConfig("run", "http://127.0.0.1:1", " ", "pass", "http://127.0.0.1:1"))
        }
        assertPhaseError("field 'password' is required") {
            validateConfig(CrossDeviceConfig("run", "http://127.0.0.1:1", "user", "", "http://127.0.0.1:1"))
        }
        assertPhaseError(loopbackMessage("serverUrl")) {
            validateConfig(CrossDeviceConfig("run", "https://127.0.0.1:1", "user", "pass", "http://127.0.0.1:1"))
        }
        assertPhaseError(loopbackMessage("serverUrl")) {
            validateConfig(CrossDeviceConfig("run", "http://example.com:1", "user", "pass", "http://127.0.0.1:1"))
        }
        assertPhaseError(loopbackMessage("serverUrl")) {
            validateConfig(
                CrossDeviceConfig("run", "http://user:pass@127.0.0.1:1", "user", "pass", "http://127.0.0.1:1"),
            )
        }
        assertPhaseError(loopbackMessage("serverUrl")) {
            validateConfig(CrossDeviceConfig("run", "http://127.0.0.1:1/?x=1", "user", "pass", "http://127.0.0.1:1"))
        }
        assertPhaseError(loopbackMessage("serverUrl")) {
            validateConfig(CrossDeviceConfig("run", "http://127.0.0.1", "user", "pass", "http://127.0.0.1:1"))
        }
        assertPhaseError(loopbackMessage("feedBaseUrl")) {
            validateConfig(CrossDeviceConfig("run", "http://127.0.0.1:1", "user", "pass", "http://localhost"))
        }
        // The accepted spellings.
        validateConfig(
            CrossDeviceConfig("run", "http://127.0.0.1:8787", "user", "pass", "http://localhost:8787"),
        )
    }

    @Test
    fun parseConfigReadsTheContractAndEdits() {
        val config =
            parseConfig(
                """
                {"runId":"run","serverUrl":"http://127.0.0.1:1","username":"u","password":"p",
                 "feedBaseUrl":"http://localhost:2",
                 "edits":[{"op":"played","podcast":1,"episode":1,"value":5}]}
                """.trimIndent(),
            )
        assertEquals("run", config.runId)
        assertEquals("http://127.0.0.1:1", config.serverUrl)
        assertEquals(1, config.edits.size)
        assertEquals("played", config.edits[0].op)
        assertEquals(1, config.edits[0].podcast)
        assertEquals(5L, config.edits[0].value)
    }

    @Test
    fun canonicalJsonSortsKeysAndPreservesArrays() {
        val element =
            buildJsonObject {
                put("b", JsonPrimitive(2))
                put(
                    "a",
                    buildJsonArray {
                        add(JsonPrimitive(1))
                        add(JsonPrimitive(2))
                    },
                )
            }
        assertEquals("""{"a":[1,2],"b":2}""", canonicalJson(element))
    }

    @Test
    fun sanitizeRemovesTheProtectedAuthField() {
        val state =
            RecordState(
                coll = SyncCollection.PODCAST,
                fields =
                    mapOf(
                        "auth" to FieldState(JsonPrimitive("secret"), null),
                        "title" to FieldState(JsonPrimitive("title"), null),
                    ),
            )
        val sanitized = sanitize(state)
        assertFalse(sanitized.fields.containsKey("auth"))
        assertThat(sanitized.fields).containsKey("title")
    }

    @Test
    fun syncBarrierAcceptsOnlyNewSyncNowRounds() {
        val round = SyncRoundSummary(SyncRoundReason.SYNC_NOW, completedAtMs = 1_000, report = null)
        assertTrue(isNewSyncNowRound(round, startMs = 1_000))
        assertTrue(isNewSyncNowRound(round, startMs = 2_000))
        assertFalse(isNewSyncNowRound(round, startMs = 10_000))
        assertFalse(isNewSyncNowRound(round.copy(reason = SyncRoundReason.OUTBOX), startMs = 1_000))
    }

    @Test
    fun seedWriterProducesTheFixtureAndKeepsTheOutboxEmpty() =
        runBlocking {
            val db = TestDb.inMemory()
            try {
                val fixture = crossDeviceFixture("run-seed", "http://127.0.0.1:1234")
                val writer = CrossDeviceSeedWriter(db, fixture)
                writer.checkFresh()
                val counts = writer.write()
                assertEquals(PODCAST_COUNT, counts.podcasts)
                assertEquals(2, counts.groups)
                assertEquals(PODCAST_COUNT, counts.episodes)
                assertEquals(PODCAST_COUNT, counts.members)
                assertEquals(QUEUE_COUNT, counts.upNext)
                assertEquals(0, db.syncOutboxDao().count())
                assertEquals(PODCAST_COUNT, db.podcastDao().allForExport().size)
                assertEquals(PODCAST_COUNT, db.episodeDao().all().size)
                assertEquals(2, db.groupDao().groupsInOrder().size)
                assertEquals(PODCAST_COUNT, db.groupDao().membershipPairs().size)
                assertEquals(QUEUE_COUNT, db.queueDao().entries().size)
                // The seed is bootstrap state: a second seed is refused.
                val failure =
                    assertFailsWith<PhaseError> {
                        runBlocking { writer.checkFresh() }
                    }
                assertEquals("seed: existing non-fixture library or sync state", failure.safeMessage)
            } finally {
                db.close()
            }
        }

    @Test
    fun defaultEditsWriteThroughTheDaosAndCaptureTheOutbox() =
        runBlocking {
            val db = TestDb.inMemory()
            try {
                val fixture = crossDeviceFixture("run-edit", "http://127.0.0.1:1234")
                CrossDeviceSeedWriter(db, fixture).write()
                // The edit phase runs while linked: capture is on. The capture triggers read the
                // node identity from sync_state, so the fixture link state carries one.
                db.syncStateDao().upsert(
                    SyncStateEntity(enabled = true, linkedAt = T_BASE, nodeId = FIXTURE_NODE_ID),
                )
                val applied = CrossDeviceEditApplier(db, fixture).apply(defaultEdits())
                assertEquals(10, applied)
                assertThat(db.syncOutboxDao().count()).isGreaterThan(0)
                val episodes = db.episodeDao().all().associateBy { it.identityKey }
                val episode0 = episodes.getValue(fixture.episodes[0].identityKey)
                val episode1 = episodes.getValue(fixture.episodes[1].identityKey)
                val episode2 = episodes.getValue(fixture.episodes[2].identityKey)
                val episode3 = episodes.getValue(fixture.episodes[3].identityKey)
                assertNull(db.episodeStateDao().byEpisode(episode0.id)?.playedAt)
                assertEquals(T_BASE + 1_000, db.episodeStateDao().byEpisode(episode1.id)?.playedAt)
                assertEquals(60_000L, db.positionDao().byEpisode(episode2.id)?.positionMs)
                assertEquals(true, db.episodeStateDao().byEpisode(episode3.id)?.isFavorite)
                // membership_add(25) then membership_remove(0): group A keeps 25 members.
                val groupA = db.groupDao().groupsInOrder().first { it.uuid == fixture.groups[0].uuid }
                assertThat(db.groupDao().membersOf(groupA.id)).hasSize(25)
                // group_order moves group B ahead of group A.
                assertThat(db.groupDao().groupsInOrder().map { it.uuid })
                    .isEqualTo(listOf(fixture.groups[1].uuid, fixture.groups[0].uuid))
                // queue_add(episode 4 at the front) and queue_remove(episode 3): 8 entries remain.
                assertThat(db.queueDao().entries()).hasSize(QUEUE_COUNT)
                // unsubscribe(49): 49 podcasts remain.
                assertThat(db.podcastDao().allForExport()).hasSize(PODCAST_COUNT - 1)
            } finally {
                db.close()
            }
        }

    @Test
    fun editApplierRejectsUnknownOperations() =
        runBlocking {
            val db = TestDb.inMemory()
            try {
                val fixture = crossDeviceFixture("run-edit-bad", "http://127.0.0.1:1234")
                CrossDeviceSeedWriter(db, fixture).write()
                db.syncStateDao().upsert(
                    SyncStateEntity(enabled = true, linkedAt = T_BASE, nodeId = FIXTURE_NODE_ID),
                )
                val failure =
                    assertFailsWith<PhaseError> {
                        runBlocking {
                            CrossDeviceEditApplier(db, fixture).apply(listOf(CrossDeviceEdit("nonsense")))
                        }
                    }
                assertEquals("edit: unknown operation", failure.safeMessage)
            } finally {
                db.close()
            }
        }

    @Test
    fun dumperMaterializesTheSeedAndStripsAuth() =
        runBlocking {
            val db = TestDb.inMemory()
            try {
                val fixture = crossDeviceFixture("run-dump", "http://127.0.0.1:1234")
                CrossDeviceSeedWriter(db, fixture).write()
                // One accepted clock row with a protected auth field and a normal field.
                val clock = Hlc.of(T_BASE, 0, NodeId("0000000000000001"))
                val state =
                    RecordState(
                        coll = SyncCollection.PODCAST,
                        fields =
                            mapOf(
                                "auth" to FieldState(JsonPrimitive("secret"), clock),
                                "title" to FieldState(JsonPrimitive("Feed title"), clock),
                            ),
                    )
                db.syncClockDao().upsert(
                    SyncClockEntity(
                        coll = SyncCollection.PODCAST.wire,
                        rid = fixture.podcasts[0].syncId,
                        clocks = SyncJson.encodeToString(RawSyncRecord.serializer(), RawSyncRecord(state = state)),
                    ),
                )
                val result = CrossDeviceDumper(db, fixture).dump()
                val wire = result["wire"]!!.jsonObject
                val record = wire["podcast/${fixture.podcasts[0].syncId}"]!!.jsonObject
                assertThat(record["fields"]!!.jsonObject).doesNotContainKey("auth")
                assertThat(record["fields"]!!.jsonObject).containsKey("title")
                val library = result["library"]!!.jsonObject
                assertThat(library["podcasts"]!!.jsonArray).hasSize(PODCAST_COUNT)
                assertThat(library["episodes"]!!.jsonArray).hasSize(PODCAST_COUNT)
                assertThat(library["groups"]!!.jsonArray).hasSize(2)
                assertThat(library["upNext"]!!.jsonArray).hasSize(QUEUE_COUNT)
                assertThat(result["skippedClockRows"]!!.jsonPrimitive.int).isEqualTo(0)
            } finally {
                db.close()
            }
        }

    private inline fun assertPhaseError(
        expected: String,
        block: () -> Unit,
    ) {
        val failure = assertFailsWith<PhaseError> { block() }
        assertEquals("config: $expected", failure.safeMessage)
    }

    private companion object {
        const val FIXTURE_NODE_ID = "0000000000000001"
        val FIXTURE_UUID =
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

        fun loopbackMessage(field: String): String =
            "field '$field' must be http://127.0.0.1:<port> or http://localhost:<port> " +
                "with no credentials, query or fragment"
    }
}
