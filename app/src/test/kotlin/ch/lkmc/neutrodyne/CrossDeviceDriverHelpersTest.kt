// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.database.SyncHeldEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.testing.FakeSyncController
import ch.lkmc.neutrodyne.crossdevice.CrossDevicePasswordTransfer
import ch.lkmc.neutrodyne.crossdevice.CrossDeviceSyncBarrier
import ch.lkmc.neutrodyne.crossdevice.HeldDumpException
import ch.lkmc.neutrodyne.crossdevice.REFRESH_FAR_FUTURE_MS
import ch.lkmc.neutrodyne.crossdevice.awaitLinkedStatus
import ch.lkmc.neutrodyne.crossdevice.fixtureNextRefreshAt
import ch.lkmc.neutrodyne.crossdevice.hasLinkOrSyncResidue
import ch.lkmc.neutrodyne.crossdevice.heldDumpEntry
import ch.lkmc.neutrodyne.crossdevice.isBlockingPersistedError
import ch.lkmc.neutrodyne.crossdevice.skippedClockRowsFailure
import ch.lkmc.neutrodyne.crossdevice.syncRoundFailure
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
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * Host regressions for the exact helpers the Android driver calls. The driver's link, sync and
 * dump phases delegate to these functions, so a failure here is a driver failure.
 */
class CrossDeviceDriverHelpersTest {
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

            val fresh = summary(completedAtMs = 1_000)
            controller.onSyncNow = { controller.rounds = controller.rounds + fresh }

            assertThat(barrier.syncNowAndAwait()).isSameInstanceAs(fresh)
        }

    @Test
    fun staleRoundIsNotAcceptedWhileTheFreshRoundIsPending() =
        runTest {
            val stale = summary(completedAtMs = 1_000)
            val controller = ScriptedSyncController(listOf(stale))
            val clock = FakeClock()
            val barrier = barrier(controller, clock)
            assertThat(barrier.arm()).isNull()

            val fresh = summary(completedAtMs = 1_000)
            controller.onSyncNow = { clock.schedule(200) { controller.rounds = controller.rounds + fresh } }

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
    fun heldDumpEntryEmitsExactIdentitiesFromThePayload() {
        val row =
            heldRow(
                batch =
                    payloadJson(
                        state = "HELD",
                        podcasts = listOf("rid-a" to "1000", "rid-b" to ""),
                        groups = listOf("group-a" to "2000"),
                    ),
                summary =
                    summaryJson(
                        podcasts = listOf("Same title", "Same title"),
                        groups = listOf("Group A"),
                    ),
            )

        val entry = heldDumpEntry(row)

        assertThat(entry.string("state")).isEqualTo("HELD")
        assertThat(entry.long("id")).isEqualTo(7L)
        assertThat(entry.long("heldAt")).isEqualTo(123L)
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
                    payloadJson(
                        state = "DEFERRED",
                        podcasts = listOf("CaseSensitive-RID" to "clock value"),
                        groups = emptyList(),
                    ),
                summary = summaryJson(podcasts = listOf("Title"), groups = emptyList()),
            )

        val entry = heldDumpEntry(row)

        assertThat(entry.string("state")).isEqualTo("DEFERRED")
        assertThat(entry.objects("podcasts").single().string("rid")).isEqualTo("CaseSensitive-RID")
        assertThat(entry.objects("podcasts").single().string("clock")).isEqualTo("clock value")
    }

    @Test
    fun heldDumpEntryAcceptsEveryCurrentPayloadState() {
        for (state in listOf("STAGED", "HELD", "DEFERRED")) {
            val row =
                heldRow(
                    batch = payloadJson(state, listOf("rid" to "1"), emptyList()),
                    summary = summaryJson(listOf("Title"), emptyList()),
                )

            assertThat(heldDumpEntry(row).string("state")).isEqualTo(state)
        }
    }

    @Test
    fun heldDumpEntryRejectsMalformedPayloads() {
        val summary = summaryJson(listOf("Title"), emptyList())
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
        val batch = payloadJson("HELD", listOf("rid" to "1"), emptyList())
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
        val twoPodcasts =
            payloadJson("HELD", listOf("rid-a" to "1", "rid-b" to "2"), emptyList())
        assertFailsWith<HeldDumpException> {
            heldDumpEntry(heldRow(twoPodcasts, summaryJson(listOf("Only one"), emptyList())))
        }
        val oneGroup = payloadJson("HELD", emptyList(), listOf("group-a" to "1"))
        assertFailsWith<HeldDumpException> {
            heldDumpEntry(heldRow(oneGroup, summaryJson(emptyList(), emptyList())))
        }
    }

    @Test
    fun heldDumpEntryAllowsTitleDuplicatesButNotIdentityDuplicates() {
        val duplicateTitles =
            heldRow(
                batch = payloadJson("HELD", listOf("rid-a" to "1", "rid-b" to "2"), emptyList()),
                summary = summaryJson(listOf("Same", "Same"), emptyList()),
            )
        assertThat(heldDumpEntry(duplicateTitles).strings("podcastTitles"))
            .containsExactly("Same", "Same")
            .inOrder()

        val duplicateIdentity =
            heldRow(
                batch =
                    payloadJson(
                        "HELD",
                        listOf("rid-a" to "1", "rid-a" to "2"),
                        emptyList(),
                    ),
                summary = summaryJson(listOf("A", "B"), emptyList()),
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
        assertThat(failure.cause).isNull()
    }

    private fun heldRow(
        batch: String,
        summary: String,
    ): SyncHeldEntity = SyncHeldEntity(id = 7, batch = batch, summary = summary, heldAt = 123L)

    private fun payloadJson(
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

    private fun summaryJson(
        podcasts: List<String>,
        groups: List<String>,
    ): String =
        buildJsonObject {
            put("podcasts", buildJsonArray { podcasts.forEach { add(JsonPrimitive(it)) } })
            put("groups", buildJsonArray { groups.forEach { add(JsonPrimitive(it)) } })
        }.toString()

    private fun JsonObject.string(field: String): String? = (this[field] as? JsonPrimitive)?.content

    private fun JsonObject.long(field: String): Long? = (this[field] as? JsonPrimitive)?.long

    private fun JsonObject.strings(field: String): List<String> =
        (this[field] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()

    private fun JsonObject.objects(field: String): List<JsonObject> =
        (this[field] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

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
