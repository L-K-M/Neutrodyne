// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.sync

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.database.SyncClockEntity
import ch.lkmc.neutrodyne.core.database.SyncHeldEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.testing.CrossDevice
import ch.lkmc.neutrodyne.core.testing.FakeSyncController
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.sync.api.HeldResolution
import ch.lkmc.neutrodyne.sync.api.SyncActivity
import ch.lkmc.neutrodyne.sync.api.SyncController
import ch.lkmc.neutrodyne.sync.api.SyncDiagnosticsError
import ch.lkmc.neutrodyne.sync.api.SyncDiagnosticsSnapshot
import ch.lkmc.neutrodyne.sync.api.SyncProblem
import ch.lkmc.neutrodyne.sync.api.SyncReport
import ch.lkmc.neutrodyne.sync.api.SyncRoundFailure
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import ch.lkmc.neutrodyne.sync.api.SyncRoundSummary
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import ch.lkmc.neutrodyne.sync.impl.RawSyncRecord
import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
        assertPhaseError("field 'expectedHeldPodcastCount' must be positive") {
            validateConfig(
                CrossDeviceConfig(
                    "run",
                    "http://127.0.0.1:1",
                    "user",
                    "pass",
                    "http://127.0.0.1:1",
                    expectedHeldPodcastCount = 0,
                ),
            )
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
        assertNull(config.expectedHeldPodcastCount)
    }

    @Test
    fun parseConfigReadsTheOptionalHeldPodcastCount() {
        val config =
            parseConfig(
                """
                {"runId":"run","serverUrl":"http://127.0.0.1:1","username":"u","password":"p",
                 "feedBaseUrl":"http://localhost:2","expectedHeldPodcastCount":7}
                """.trimIndent(),
            )
        assertEquals(7, config.expectedHeldPodcastCount)
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
    fun deferredControllerObservesTheOriginalPasswordAfterTheDriverReturns() {
        val controller = DeferredPasswordController()
        val password = "correct horse battery staple".toCharArray()
        val transfer = CrossDevicePasswordTransfer(password)

        controller.submitPassword("alice", transfer.array)
        transfer.launched()

        assertThat(String(controller.received!!)).isEqualTo("correct horse battery staple")
        assertThat(password.all { it == '\u0000' }).isTrue()
    }

    @Test
    fun synchronousRejectionClearsTheTransfer() {
        val password = "secret".toCharArray()
        val transfer = CrossDevicePasswordTransfer(password)

        transfer.close()

        assertThat(transfer.array.all { it == '\u0000' }).isTrue()
        assertThat(password.all { it == '\u0000' }).isTrue()
    }

    @Test
    fun delayedLinkedStatusIsAccepted() =
        runTest {
            val status = MutableStateFlow<SyncStatus>(SyncStatus.NotConfigured)
            val clock = FakeClock()
            clock.schedule(200) { status.value = linkedStatus() }

            val linked =
                awaitLinkedStatus(
                    status,
                    timeoutMs = 5_000,
                    pollMs = 10,
                    nanoTime = clock::nanoTime,
                    delay = clock::delay,
                )

            assertThat(linked).isTrue()
        }

    @Test
    fun missingLinkedStatusTimesOut() =
        runTest {
            val status = MutableStateFlow<SyncStatus>(SyncStatus.NotConfigured)
            val clock = FakeClock()

            val linked =
                awaitLinkedStatus(
                    status,
                    timeoutMs = 100,
                    pollMs = 10,
                    nanoTime = clock::nanoTime,
                    delay = clock::delay,
                )

            assertThat(linked).isFalse()
        }

    @Test
    fun staleSyncNowRoundIsNotAccepted() =
        runTest {
            val stale = summary(completedAtMs = 1_000)
            val controller = ScriptedSyncController(listOf(stale))
            val clock = FakeClock()
            val barrier = barrier(controller, clock)
            assertThat(barrier.arm()).isNull()

            // A same-millisecond, equal-valued new object must still be accepted: identity is the
            // baseline, not the timestamp or the values.
            val fresh = summary(completedAtMs = 1_000)
            controller.onSyncNow = { controller.rounds = controller.rounds + fresh }

            assertThat(barrier.syncNowAndAwait()).isSameInstanceAs(fresh)
        }

    @Test
    fun fullHistoryStillAcceptsTheFreshRound() =
        runTest {
            val initial = (0 until 20).map { summary(completedAtMs = it.toLong()) }
            val controller = ScriptedSyncController(initial)
            val clock = FakeClock()
            val barrier = barrier(controller, clock)
            assertThat(barrier.arm()).isNull()

            val fresh = summary(completedAtMs = 20)
            controller.onSyncNow = { controller.rounds = (controller.rounds + fresh).takeLast(20) }

            assertThat(barrier.syncNowAndAwait()).isSameInstanceAs(fresh)
        }

    @Test
    fun runningRoundIsNotAccepted() =
        runTest {
            val controller = ScriptedSyncController(emptyList())
            val clock = FakeClock()
            val barrier = barrier(controller, clock)
            assertThat(barrier.arm()).isNull()

            val fresh = summary(completedAtMs = 1)
            controller.onSyncNow = {
                controller.syncing.value = true
                controller.rounds = controller.rounds + fresh
                clock.schedule(50) { controller.syncing.value = false }
            }

            assertThat(barrier.syncNowAndAwait()).isSameInstanceAs(fresh)
            assertThat(controller.syncing.value).isFalse()
        }

    @Test
    fun armWaitsForThePriorRoundToFinish() =
        runTest {
            val controller = ScriptedSyncController(emptyList())
            controller.syncing.value = true
            val clock = FakeClock()
            val barrier = barrier(controller, clock)
            clock.schedule(50) { controller.syncing.value = false }

            assertThat(barrier.arm()).isNull()
            assertThat(clock.nowMs).isAtLeast(50)
        }

    @Test
    fun armReportsABusyPriorRound() =
        runTest {
            val controller = ScriptedSyncController(emptyList())
            controller.syncing.value = true
            val clock = FakeClock()
            val barrier = barrier(controller, clock)

            assertThat(barrier.arm()).isNotNull()
        }

    @Test
    fun armReportsUnavailableDiagnostics() =
        runTest {
            val controller = ScriptedSyncController(emptyList())
            controller.diagnosticsFailure = true
            val clock = FakeClock()
            val barrier = barrier(controller, clock)

            assertThat(barrier.arm()).isNotNull()
        }

    @Test
    fun syncNowTimesOutWithoutAFreshRound() =
        runTest {
            val controller = ScriptedSyncController(emptyList())
            val clock = FakeClock()
            val barrier = barrier(controller, clock)
            assertThat(barrier.arm()).isNull()

            assertThat(barrier.syncNowAndAwait()).isNull()
        }

    @Test
    fun defaultSyncStateIsClean() {
        assertThat(SyncStateEntity().hasLinkOrSyncResidue()).isFalse()
        assertThat(null.hasLinkOrSyncResidue()).isFalse()
    }

    @Test
    fun linkClockCursorProtocolErrorAndApplyingResidueIsRejected() {
        assertThat(SyncStateEntity(enabled = true).hasLinkOrSyncResidue()).isTrue()
        assertThat(SyncStateEntity(applying = true).hasLinkOrSyncResidue()).isTrue()
        assertThat(SyncStateEntity(serverUrl = "http://127.0.0.1:8787").hasLinkOrSyncResidue()).isTrue()
        assertThat(SyncStateEntity(accountId = "account").hasLinkOrSyncResidue()).isTrue()
        assertThat(SyncStateEntity(deviceId = "device").hasLinkOrSyncResidue()).isTrue()
        assertThat(SyncStateEntity(cursor = "cursor").hasLinkOrSyncResidue()).isTrue()
        assertThat(SyncStateEntity(hlc = 1).hasLinkOrSyncResidue()).isTrue()
        assertThat(SyncStateEntity(nodeId = "node").hasLinkOrSyncResidue()).isTrue()
        assertThat(SyncStateEntity(clockOffsetMs = 1).hasLinkOrSyncResidue()).isTrue()
        assertThat(SyncStateEntity(protocol = 1).hasLinkOrSyncResidue()).isTrue()
        assertThat(SyncStateEntity(lastSyncAt = 1).hasLinkOrSyncResidue()).isTrue()
        assertThat(SyncStateEntity(lastError = "Revoked").hasLinkOrSyncResidue()).isTrue()
    }

    @Test
    fun rejectedRecordsFailTheRound() {
        val round = summary(report = report(rejected = 1))

        assertThat(syncRoundFailure(round, linkedStatus(), null)).isNotNull()
    }

    @Test
    fun failedRoundAndMissingReportFail() {
        assertThat(syncRoundFailure(summary(failure = SyncRoundFailure.PROTOCOL), linkedStatus(), null))
            .isNotNull()
        assertThat(syncRoundFailure(summary(report = null), linkedStatus(), null)).isNotNull()
    }

    @Test
    fun revokedStatusFailsTheRound() {
        val round = summary()

        assertThat(syncRoundFailure(round, SyncStatus.Revoked("http://127.0.0.1:8787"), null)).isNotNull()
    }

    @Test
    fun clockSkewProblemFailsTheRound() {
        val round = summary()

        assertThat(syncRoundFailure(round, linkedStatus(problem = SyncProblem.ClockSkew), null)).isNotNull()
    }

    @Test
    fun persistedClockAndRevocationMarkersFailTheRound() {
        val round = summary()

        assertThat(syncRoundFailure(round, linkedStatus(), "ClockSkew")).isNotNull()
        assertThat(syncRoundFailure(round, linkedStatus(), "clock_skew")).isNotNull()
        assertThat(syncRoundFailure(round, linkedStatus(), "Revoked")).isNotNull()
    }

    @Test
    fun cleanRoundPasses() {
        val round = summary()

        assertThat(syncRoundFailure(round, linkedStatus(), null)).isNull()
    }

    @Test
    fun blockingPersistedErrorsAreRecognized() {
        assertThat(isBlockingPersistedError(null)).isFalse()
        assertThat(isBlockingPersistedError("SomeOtherError")).isFalse()
        assertThat(isBlockingPersistedError("Revoked")).isTrue()
        assertThat(isBlockingPersistedError("ClockSkew")).isTrue()
        assertThat(isBlockingPersistedError("clock_skew")).isTrue()
    }

    @Test
    fun malformedClockRowsFailTheDump() {
        assertThat(skippedClockRowsFailure(0)).isNull()
        assertThat(skippedClockRowsFailure(1)).isNotNull()
    }

    @Test
    fun fixtureNextRefreshAtIsAFutureBound() {
        val now = System.currentTimeMillis()

        assertThat(fixtureNextRefreshAt(now)).isGreaterThan(now)
        assertThat(fixtureNextRefreshAt(now)).isEqualTo(now + REFRESH_FAR_FUTURE_MS)
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
                // `T_BASE + one year` is in the past for any run after 2025-10-27; the fixture
                // must not arm the refresh scheduler against the feed during the test.
                val now = System.currentTimeMillis()
                assertThat(
                    db.podcastDao().allForExport().all {
                        val next = it.nextRefreshAt
                        next != null && next > now
                    },
                ).isTrue()
            } finally {
                db.close()
            }
        }

    @Test
    fun seedWriterRefusesLinkClockCursorProtocolErrorAndApplyingResidue() =
        runBlocking {
            val db = TestDb.inMemory()
            try {
                val fixture = crossDeviceFixture("run-seed-residue", "http://127.0.0.1:1234")
                val writer = CrossDeviceSeedWriter(db, fixture)
                db.syncStateDao().upsert(
                    SyncStateEntity(cursor = "cursor", hlc = 1, protocol = 1, lastError = "Revoked", applying = true),
                )
                val failure =
                    assertFailsWith<PhaseError> {
                        runBlocking { writer.checkFresh() }
                    }
                assertEquals("seed: existing non-fixture link or sync state", failure.safeMessage)
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

    @Test
    fun dumperFailsOnAMalformedClockRowInsteadOfPartialOk() =
        runBlocking {
            val db = TestDb.inMemory()
            try {
                val fixture = crossDeviceFixture("run-dump-bad", "http://127.0.0.1:1234")
                db.syncClockDao().upsert(
                    SyncClockEntity(
                        coll = SyncCollection.PODCAST.wire,
                        rid = "not-a-record-id",
                        clocks = "not json",
                    ),
                )
                val failure =
                    assertFailsWith<PhaseError> {
                        runBlocking { CrossDeviceDumper(db, fixture).dump() }
                    }
                assertEquals("dump: malformed sync_clock rows: 1", failure.safeMessage)
            } finally {
                db.close()
            }
        }

    @Test
    fun dumperEmitsExactHeldIdentitiesFromThePayload() =
        runBlocking {
            val db = TestDb.inMemory()
            try {
                val fixture = crossDeviceFixture("run-held-dump", "http://127.0.0.1:1234")
                CrossDeviceSeedWriter(db, fixture).write()
                val rids = fixture.podcasts.take(2).map { it.syncId }
                db.syncHeldDao().insert(
                    SyncHeldEntity(
                        batch =
                            heldPayloadJson(
                                state = "HELD",
                                podcasts = rids.map { it to "1000" },
                                groups = emptyList(),
                            ),
                        summary =
                            heldSummaryJson(
                                podcasts = listOf("Same title", "Same title"),
                                groups = emptyList(),
                            ),
                        heldAt = T_BASE,
                    ),
                )

                val result = CrossDeviceDumper(db, fixture).dump()
                val held = result["held"]!!.jsonArray
                assertThat(held).hasSize(1)
                val entry = held.single().jsonObject
                assertEquals("HELD", entry.string("state"))
                assertThat(entry.objects("podcasts").map { it.string("rid") })
                    .containsExactly(rids[0], rids[1])
                    .inOrder()
                assertThat(entry.objects("podcasts").map { it.string("clock") })
                    .containsExactly("1000", "1000")
                    .inOrder()
                assertThat(entry.strings("podcastTitles"))
                    .containsExactly("Same title", "Same title")
                    .inOrder()
                assertThat(entry.strings("groupTitles")).isEmpty()
            } finally {
                db.close()
            }
        }

    @Test
    fun dumperFailsOnAMalformedHeldPayload() =
        runBlocking {
            val db = TestDb.inMemory()
            try {
                val fixture = crossDeviceFixture("run-held-dump-bad-payload", "http://127.0.0.1:1234")
                CrossDeviceSeedWriter(db, fixture).write()
                db.syncHeldDao().insert(
                    SyncHeldEntity(
                        batch = """{"state":"HELD","podcasts":[{"rid":"a","clock":1}],"groups":[]}""",
                        summary = heldSummaryJson(listOf("Title"), emptyList()),
                        heldAt = T_BASE,
                    ),
                )
                val failure =
                    assertFailsWith<PhaseError> {
                        runBlocking { CrossDeviceDumper(db, fixture).dump() }
                    }
                assertEquals(
                    "dump: held payload podcast removal 0 clock is missing or not a string",
                    failure.safeMessage,
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun dumperFailsOnAMalformedHeldSummary() =
        runBlocking {
            val db = TestDb.inMemory()
            try {
                val fixture = crossDeviceFixture("run-held-dump-bad-summary", "http://127.0.0.1:1234")
                CrossDeviceSeedWriter(db, fixture).write()
                db.syncHeldDao().insert(
                    SyncHeldEntity(
                        batch = heldPayloadJson("HELD", listOf("rid-a" to "1"), emptyList()),
                        summary = "{}",
                        heldAt = T_BASE,
                    ),
                )
                val failure =
                    assertFailsWith<PhaseError> {
                        runBlocking { CrossDeviceDumper(db, fixture).dump() }
                    }
                assertEquals("dump: held summary podcast titles are not an array", failure.safeMessage)
            } finally {
                db.close()
            }
        }

    @Test
    fun dumperFailsOnAHeldSummaryCountMismatch() =
        runBlocking {
            val db = TestDb.inMemory()
            try {
                val fixture = crossDeviceFixture("run-held-dump-count", "http://127.0.0.1:1234")
                CrossDeviceSeedWriter(db, fixture).write()
                db.syncHeldDao().insert(
                    SyncHeldEntity(
                        batch = heldPayloadJson("HELD", listOf("rid-a" to "1", "rid-b" to "2"), emptyList()),
                        summary = heldSummaryJson(listOf("Only one"), emptyList()),
                        heldAt = T_BASE,
                    ),
                )
                val failure =
                    assertFailsWith<PhaseError> {
                        runBlocking { CrossDeviceDumper(db, fixture).dump() }
                    }
                assertEquals("dump: held summary podcast count does not match the payload", failure.safeMessage)
            } finally {
                db.close()
            }
        }

    @Test
    fun heldDumpEntryEmitsExactIdentitiesFromThePayload() {
        val row =
            heldRow(
                batch =
                    heldPayloadJson(
                        state = "HELD",
                        podcasts = listOf("rid-a" to "1000", "rid-b" to ""),
                        groups = listOf("group-a" to "2000"),
                    ),
                summary =
                    heldSummaryJson(
                        podcasts = listOf("Same title", "Same title"),
                        groups = listOf("Group A"),
                    ),
            )

        val entry = heldDumpEntry(row)

        assertEquals("HELD", entry.string("state"))
        assertEquals(7L, entry.long("id"))
        assertEquals(123L, entry.long("heldAt"))
        assertThat(entry.objects("podcasts").map { it.string("rid") })
            .containsExactly("rid-a", "rid-b")
            .inOrder()
        assertThat(entry.objects("podcasts").map { it.string("clock") })
            .containsExactly("1000", "")
            .inOrder()
        assertThat(entry.objects("groups").map { it.string("rid") }).containsExactly("group-a")
        assertThat(entry.objects("groups").map { it.string("clock") }).containsExactly("2000")
        assertThat(entry.strings("podcastTitles"))
            .containsExactly("Same title", "Same title")
            .inOrder()
        assertThat(entry.strings("groupTitles")).containsExactly("Group A")
    }

    @Test
    fun heldDumpEntryEmitsCanonicalPayloadStateRidAndClock() {
        val row =
            heldRow(
                batch =
                    heldPayloadJson(
                        state = "DEFERRED",
                        podcasts = listOf("CaseSensitive-RID" to "clock value"),
                        groups = emptyList(),
                    ),
                summary = heldSummaryJson(podcasts = listOf("Title"), groups = emptyList()),
            )

        val entry = heldDumpEntry(row)

        assertEquals("DEFERRED", entry.string("state"))
        assertEquals("CaseSensitive-RID", entry.objects("podcasts").single().string("rid"))
        assertEquals("clock value", entry.objects("podcasts").single().string("clock"))
    }

    @Test
    fun heldDumpEntryAcceptsEveryCurrentPayloadState() {
        for (state in listOf("STAGED", "HELD", "DEFERRED")) {
            val row =
                heldRow(
                    batch = heldPayloadJson(state, listOf("rid" to "1"), emptyList()),
                    summary = heldSummaryJson(listOf("Title"), emptyList()),
                )

            assertEquals(state, heldDumpEntry(row).string("state"))
        }
    }

    @Test
    fun heldDumpEntryRejectsMalformedPayloads() {
        val summary = heldSummaryJson(listOf("Title"), emptyList())
        val malformed =
            listOf(
                "[]",
                "not json",
                "{}",
                """{"state":""}""",
                """{"state":"BOGUS"}""",
                """{"state":123}""",
                """{"state":"HELD"}""",
                """{"state":"HELD","podcasts":{},"groups":[]}""",
                """{"state":"HELD","podcasts":[1],"groups":[]}""",
                """{"state":"HELD","podcasts":[{"clock":"1"}],"groups":[]}""",
                """{"state":"HELD","podcasts":[{"rid":"  ","clock":"1"}],"groups":[]}""",
                """{"state":"HELD","podcasts":[{"rid":1,"clock":"1"}],"groups":[]}""",
                """{"state":"HELD","podcasts":[{"rid":"a"}],"groups":[]}""",
                """{"state":"HELD","podcasts":[{"rid":"a","clock":1}],"groups":[]}""",
                """{"state":"HELD","podcasts":[{"rid":"a","clock":"1"},{"rid":"a","clock":"2"}],"groups":[]}""",
            )
        for (batch in malformed) {
            assertFailsWith<HeldDumpException> { heldDumpEntry(heldRow(batch, summary)) }
        }
    }

    @Test
    fun heldDumpEntryRejectsMalformedSummaries() {
        val batch = heldPayloadJson("HELD", listOf("rid" to "1"), emptyList())
        val malformed =
            listOf(
                "[]",
                "not json",
                "{}",
                """{"podcasts":{},"groups":[]}""",
                """{"podcasts":[1],"groups":[]}""",
                """{"podcasts":[],"groups":{}}""",
                """{"podcasts":[],"groups":[1]}""",
            )
        for (summary in malformed) {
            assertFailsWith<HeldDumpException> { heldDumpEntry(heldRow(batch, summary)) }
        }
    }

    @Test
    fun heldDumpEntryRejectsSummaryCountMismatch() {
        val twoPodcasts = heldPayloadJson("HELD", listOf("rid-a" to "1", "rid-b" to "2"), emptyList())
        assertFailsWith<HeldDumpException> {
            heldDumpEntry(heldRow(twoPodcasts, heldSummaryJson(listOf("Only one"), emptyList())))
        }
        val oneGroup = heldPayloadJson("HELD", emptyList(), listOf("group-a" to "1"))
        assertFailsWith<HeldDumpException> {
            heldDumpEntry(heldRow(oneGroup, heldSummaryJson(emptyList(), emptyList())))
        }
    }

    @Test
    fun heldDumpEntryAllowsTitleDuplicatesButNotIdentityDuplicates() {
        val duplicateTitles =
            heldRow(
                batch = heldPayloadJson("HELD", listOf("rid-a" to "1", "rid-b" to "2"), emptyList()),
                summary = heldSummaryJson(listOf("Same", "Same"), emptyList()),
            )
        assertThat(heldDumpEntry(duplicateTitles).strings("podcastTitles"))
            .containsExactly("Same", "Same")
            .inOrder()

        val duplicateIdentity =
            heldRow(
                batch = heldPayloadJson("HELD", listOf("rid-a" to "1", "rid-a" to "2"), emptyList()),
                summary = heldSummaryJson(listOf("A", "B"), emptyList()),
            )
        assertFailsWith<HeldDumpException> { heldDumpEntry(duplicateIdentity) }
    }

    @Test
    fun heldDumpEntryDoesNotLeakRawJsonOrExceptionDetail() {
        val sentinel = "SENTINEL-RAW-JSON"
        val row =
            heldRow(
                batch =
                    """{"state":"HELD","podcasts":[{"rid":"$sentinel","clock":1}],""" +
                        """"groups":[]}""",
                summary = "{}",
            )

        val failure = assertFailsWith<HeldDumpException> { heldDumpEntry(row) }

        assertThat(failure.message).doesNotContain(sentinel)
        assertThat(failure.message).doesNotContain("{")
        assertThat(failure.message).doesNotContain("}")
        assertNull(failure.cause)
    }

    private fun heldRow(
        batch: String,
        summary: String,
    ): SyncHeldEntity = SyncHeldEntity(id = 7, batch = batch, summary = summary, heldAt = 123L)

    private fun heldPayloadJson(
        state: String,
        podcasts: List<Pair<String, String>>,
        groups: List<Pair<String, String>>,
    ): String =
        buildJsonObject {
            put("state", state)
            put(
                "podcasts",
                buildJsonArray {
                    podcasts.forEach { (rid, clock) ->
                        add(
                            buildJsonObject {
                                put("rid", rid)
                                put("clock", clock)
                            },
                        )
                    }
                },
            )
            put(
                "groups",
                buildJsonArray {
                    groups.forEach { (rid, clock) ->
                        add(
                            buildJsonObject {
                                put("rid", rid)
                                put("clock", clock)
                            },
                        )
                    }
                },
            )
        }.toString()

    private fun heldSummaryJson(
        podcasts: List<String>,
        groups: List<String>,
    ): String =
        buildJsonObject {
            put("podcasts", buildJsonArray { podcasts.forEach { add(JsonPrimitive(it)) } })
            put("groups", buildJsonArray { groups.forEach { add(JsonPrimitive(it)) } })
        }.toString()

    private fun JsonObject.long(field: String): Long? = (this[field] as? JsonPrimitive)?.long

    private fun JsonObject.strings(field: String): List<String> =
        (this[field] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()

    private fun JsonObject.objects(field: String): List<JsonObject> =
        (this[field] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    @Test
    fun applyHeldApprovesTheExpectedBatchThroughThePublicController() =
        runBlocking {
            val db = TestDb.inMemory()
            try {
                val fixture = crossDeviceFixture("run-held", "http://127.0.0.1:1234")
                CrossDeviceSeedWriter(db, fixture).write()
                val expected = 11
                val rids = fixture.podcasts.take(expected).map { it.syncId }
                val titles = fixture.podcasts.take(expected).map { it.title }
                val id =
                    db.syncHeldDao().insert(
                        SyncHeldEntity(
                            batch = heldBatchJson(rids),
                            summary = heldSummaryJson(titles),
                            heldAt = T_BASE,
                        ),
                    )
                val controller = HeldSyncController(db, linkedStatus())
                val clock = FakeClock()
                val result =
                    CrossDeviceHeldApplier(
                        controller = controller,
                        db = db,
                        fixture = fixture,
                        expectedPodcastCount = expected,
                        timeoutMs = 1_000,
                        pollMs = 10,
                        nanoTime = clock::nanoTime,
                        delay = clock::delay,
                    ).apply()

                assertEquals(id, controller.resolvedId)
                assertEquals(HeldResolution.APPLY, controller.resolution)
                assertNull(db.syncHeldDao().byId(id))
                assertTrue(result.getValue("applied").jsonPrimitive.boolean)
            } finally {
                db.close()
            }
        }

    @Test
    fun applyHeldRefusesAWrongPodcastCount() =
        runBlocking {
            val db = TestDb.inMemory()
            try {
                val fixture = crossDeviceFixture("run-held-count", "http://127.0.0.1:1234")
                CrossDeviceSeedWriter(db, fixture).write()
                val rids = fixture.podcasts.take(10).map { it.syncId }
                val titles = fixture.podcasts.take(10).map { it.title }
                db.syncHeldDao().insert(
                    SyncHeldEntity(
                        batch = heldBatchJson(rids),
                        summary = heldSummaryJson(titles),
                        heldAt = T_BASE,
                    ),
                )
                val clock = FakeClock()
                val failure =
                    assertFailsWith<PhaseError> {
                        runBlocking {
                            CrossDeviceHeldApplier(
                                controller = FakeSyncController(status = linkedStatus()),
                                db = db,
                                fixture = fixture,
                                expectedPodcastCount = 11,
                                timeoutMs = 1_000,
                                pollMs = 10,
                                nanoTime = clock::nanoTime,
                                delay = clock::delay,
                            ).apply()
                        }
                    }
                assertEquals("apply-held: expected 11 held podcasts, found 10", failure.safeMessage)
            } finally {
                db.close()
            }
        }

    @Test
    fun applyHeldRefusesAMalformedSummary() =
        runBlocking {
            val db = TestDb.inMemory()
            try {
                val fixture = crossDeviceFixture("run-held-summary", "http://127.0.0.1:1234")
                CrossDeviceSeedWriter(db, fixture).write()
                val rids = fixture.podcasts.take(11).map { it.syncId }
                db.syncHeldDao().insert(
                    SyncHeldEntity(
                        batch = heldBatchJson(rids),
                        summary = "{}",
                        heldAt = T_BASE,
                    ),
                )
                val clock = FakeClock()
                val failure =
                    assertFailsWith<PhaseError> {
                        runBlocking {
                            CrossDeviceHeldApplier(
                                controller = FakeSyncController(status = linkedStatus()),
                                db = db,
                                fixture = fixture,
                                expectedPodcastCount = 11,
                                timeoutMs = 1_000,
                                pollMs = 10,
                                nanoTime = clock::nanoTime,
                                delay = clock::delay,
                            ).apply()
                        }
                    }
                assertEquals("apply-held: held summary is malformed", failure.safeMessage)
            } finally {
                db.close()
            }
        }

    @Test
    fun applyHeldRefusesASummaryThatDoesNotMatchTheBatch() =
        runBlocking {
            val db = TestDb.inMemory()
            try {
                val fixture = crossDeviceFixture("run-held-titles", "http://127.0.0.1:1234")
                CrossDeviceSeedWriter(db, fixture).write()
                val rids = fixture.podcasts.take(11).map { it.syncId }
                db.syncHeldDao().insert(
                    SyncHeldEntity(
                        batch = heldBatchJson(rids),
                        summary = heldSummaryJson(List(11) { "Wrong title" }),
                        heldAt = T_BASE,
                    ),
                )
                val clock = FakeClock()
                val failure =
                    assertFailsWith<PhaseError> {
                        runBlocking {
                            CrossDeviceHeldApplier(
                                controller = FakeSyncController(status = linkedStatus()),
                                db = db,
                                fixture = fixture,
                                expectedPodcastCount = 11,
                                timeoutMs = 1_000,
                                pollMs = 10,
                                nanoTime = clock::nanoTime,
                                delay = clock::delay,
                            ).apply()
                        }
                    }
                assertEquals("apply-held: held summary does not match the held podcasts", failure.safeMessage)
            } finally {
                db.close()
            }
        }

    @Test
    fun applyHeldRefusesANonHeldBatch() =
        runBlocking {
            val db = TestDb.inMemory()
            try {
                val fixture = crossDeviceFixture("run-held-state", "http://127.0.0.1:1234")
                CrossDeviceSeedWriter(db, fixture).write()
                val rids = fixture.podcasts.take(11).map { it.syncId }
                db.syncHeldDao().insert(
                    SyncHeldEntity(
                        batch = heldBatchJson(rids).replace("\"HELD\"", "\"STAGED\""),
                        summary = heldSummaryJson(fixture.podcasts.take(11).map { it.title }),
                        heldAt = T_BASE,
                    ),
                )
                val clock = FakeClock()
                val failure =
                    assertFailsWith<PhaseError> {
                        runBlocking {
                            CrossDeviceHeldApplier(
                                controller = FakeSyncController(status = linkedStatus()),
                                db = db,
                                fixture = fixture,
                                expectedPodcastCount = 11,
                                timeoutMs = 1_000,
                                pollMs = 10,
                                nanoTime = clock::nanoTime,
                                delay = clock::delay,
                            ).apply()
                        }
                    }
                assertEquals("apply-held: held batch is malformed or not HELD", failure.safeMessage)
            } finally {
                db.close()
            }
        }

    @Test
    fun applyHeldRequiresALinkedIdleController() =
        runBlocking {
            val db = TestDb.inMemory()
            try {
                val fixture = crossDeviceFixture("run-held-link", "http://127.0.0.1:1234")
                CrossDeviceSeedWriter(db, fixture).write()
                val rids = fixture.podcasts.take(11).map { it.syncId }
                db.syncHeldDao().insert(
                    SyncHeldEntity(
                        batch = heldBatchJson(rids),
                        summary = heldSummaryJson(fixture.podcasts.take(11).map { it.title }),
                        heldAt = T_BASE,
                    ),
                )
                val clock = FakeClock()
                val failure =
                    assertFailsWith<PhaseError> {
                        runBlocking {
                            CrossDeviceHeldApplier(
                                controller = FakeSyncController(status = SyncStatus.NotConfigured),
                                db = db,
                                fixture = fixture,
                                expectedPodcastCount = 11,
                                timeoutMs = 100,
                                pollMs = 10,
                                nanoTime = clock::nanoTime,
                                delay = clock::delay,
                            ).apply()
                        }
                    }
                assertEquals("apply-held: device is not linked", failure.safeMessage)
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

    private fun barrier(
        controller: ScriptedSyncController,
        clock: FakeClock,
        timeoutMs: Long = 1_000,
        pollMs: Long = 10,
    ): CrossDeviceSyncBarrier = CrossDeviceSyncBarrier(controller, timeoutMs, pollMs, clock::nanoTime, clock::delay)

    private fun summary(
        reason: SyncRoundReason = SyncRoundReason.SYNC_NOW,
        completedAtMs: Long = 0L,
        report: SyncReport? = report(),
        failure: SyncRoundFailure? = null,
    ): SyncRoundSummary = SyncRoundSummary(reason, completedAtMs, report, failure)

    private fun report(
        pushed: Int = 0,
        applied: Int = 0,
        stale: Int = 0,
        rejected: Int = 0,
        pulled: Int = 0,
        held: Int = 0,
        durationMs: Long = 1,
    ): SyncReport = SyncReport(pushed, applied, stale, rejected, pulled, held, durationMs)

    private fun linkedStatus(problem: SyncProblem? = null): SyncStatus.Linked =
        SyncStatus.Linked(
            serverUrl = "http://127.0.0.1:8787",
            accountId = "account",
            deviceId = "device",
            username = "alice",
            nodeId = "node",
            lastSyncAtMs = null,
            activity = SyncActivity.IDLE,
            problem = problem,
        )

    private fun heldBatchJson(rids: List<String>): String =
        buildJsonObject {
            put("state", "HELD")
            put(
                "podcasts",
                buildJsonArray {
                    rids.forEach { rid ->
                        add(
                            buildJsonObject {
                                put("rid", rid)
                                put("clock", "1000")
                            },
                        )
                    }
                },
            )
            put("groups", buildJsonArray { })
        }.toString()

    private fun heldSummaryJson(titles: List<String>): String =
        buildJsonObject {
            put(
                "podcasts",
                buildJsonArray {
                    titles.forEach { add(JsonPrimitive(it)) }
                },
            )
            put("groups", buildJsonArray { })
        }.toString()

    /** Holds the submitted array and reads it only after the driver has returned. */
    private class DeferredPasswordController {
        var received: CharArray? = null

        fun submitPassword(
            username: String,
            password: CharArray,
        ) {
            received = password
        }
    }

    /** A [FakeSyncController] whose diagnostics and `syncNow` effects are scripted. */
    private class ScriptedSyncController(
        initialRounds: List<SyncRoundSummary>,
    ) : SyncController by FakeSyncController() {
        var rounds: List<SyncRoundSummary> = initialRounds
        var diagnosticsFailure = false
        var onSyncNow: () -> Unit = {}

        override val syncing: MutableStateFlow<Boolean> = MutableStateFlow(false)

        override fun syncNow() {
            onSyncNow()
        }

        override suspend fun diagnostics(): Outcome<SyncDiagnosticsSnapshot, SyncDiagnosticsError> =
            if (diagnosticsFailure) {
                Outcome.Failure(SyncDiagnosticsError.UNAVAILABLE)
            } else {
                Outcome.Success(SyncDiagnosticsSnapshot(rounds = rounds))
            }
    }

    /** Deletes the held row on approval, as the production controller's APPLY path does. */
    private class HeldSyncController(
        private val db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase,
        status: SyncStatus,
    ) : SyncController by FakeSyncController(status = status) {
        override val syncing: MutableStateFlow<Boolean> = MutableStateFlow(false)
        var resolvedId: Long? = null
        var resolution: HeldResolution? = null

        override fun resolveHeld(
            id: Long,
            resolution: HeldResolution,
        ) {
            resolvedId = id
            this.resolution = resolution
            runBlocking { db.syncHeldDao().delete(id) }
        }
    }

    /** A deterministic clock: [delay] advances it and runs the actions that came due. */
    private class FakeClock {
        var nowMs: Long = 0
            private set
        private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()

        fun schedule(
            delayMs: Long,
            action: () -> Unit,
        ) {
            scheduled += (nowMs + delayMs) to action
        }

        fun nanoTime(): Long = nowMs * 1_000_000

        suspend fun delay(ms: Long) {
            nowMs += ms
            scheduled.filter { it.first <= nowMs }.forEach { it.second() }
            scheduled.removeAll { it.first <= nowMs }
            yield()
        }
    }
}
