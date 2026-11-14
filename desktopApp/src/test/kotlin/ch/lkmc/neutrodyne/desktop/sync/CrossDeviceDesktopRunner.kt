// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.sync

import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.runInitializers
import ch.lkmc.neutrodyne.core.database.DatabaseOpenState
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.desktop.buildinfo.BuildInfoLoader
import ch.lkmc.neutrodyne.desktop.crash.DesktopCrashReporter
import ch.lkmc.neutrodyne.desktop.di.DesktopAppGraph
import ch.lkmc.neutrodyne.desktop.di.createDesktopGraph
import ch.lkmc.neutrodyne.desktop.identity.WindowsAppIdentity
import ch.lkmc.neutrodyne.desktop.log.RecentLogBuffer
import ch.lkmc.neutrodyne.desktop.os.withoutOsRegistrations
import ch.lkmc.neutrodyne.desktop.platform.DesktopClock
import ch.lkmc.neutrodyne.desktop.playback.DesktopEnginePaths
import ch.lkmc.neutrodyne.desktop.shell.appDirsUnder
import ch.lkmc.neutrodyne.desktop.smoke.configureControlledJourneySettings
import ch.lkmc.neutrodyne.sync.api.FirstLinkChoice
import ch.lkmc.neutrodyne.sync.api.LinkMethod
import ch.lkmc.neutrodyne.sync.api.LinkState
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * One desktop driver invocation over the real `DesktopAppGraph`, Room and
 * `SyncController`/`KtorSyncClient` (09 E12's desktop half). The caller owns the isolated [root]:
 * `cross-device/config.json`, `cross-device/ownership.json` and `cross-device/result.json` live
 * there, and the app's throwaway `AppDirs` live under `root/app`, so the database persists across
 * phase invocations. Nothing outside [root] is touched and no existing data is cleared.
 *
 * The graph's start-up mirrors the controlled journey: update probes are disabled before the
 * initializers run and the host OS registrations are skipped. The close-down always runs, in the
 * order 11 Shutdown requires: OS surfaces, runner, graph scope, then Room.
 */
internal class CrossDeviceDesktopRunner(
    private val root: Path,
    private val phase: String?,
) {
    private val resultFile = root.resolve(CROSS_DEVICE_DIR).resolve("result.json")
    private val ownershipFile = root.resolve(CROSS_DEVICE_DIR).resolve("ownership.json")

    suspend fun run() {
        var runId: String? = null
        var graph: DesktopAppGraph? = null
        var db: NeutrodyneDatabase? = null
        try {
            val config = readConfig()
            validateConfig(config)
            runId = config.runId
            val phases = resolvePhases(phase)
            val started = startGraph()
            graph = started
            started.databaseOpener.awaitOpen()
            db = started.databaseOpener.requireDatabase()
            prepareControlledRun(started)
            runInitializers(started.initializers.withoutOsRegistrations())
            for (current in phases) {
                val payload = dispatch(started, db, config, current)
                writeResult(result(config.runId, current, ok = true, error = null, payload))
                Log.i(TAG) { "phase=$current ok=true" }
            }
        } catch (failure: PhaseError) {
            writeResult(result(runId, phase, ok = false, error = failure.safeMessage, payload = safeCounts(graph)))
            Log.i(TAG) { "phase=$phase ok=false error=${failure.safeMessage}" }
            // No cause: test runners print causes, and a raw failure can carry payloads.
            throw AssertionError(failure.safeMessage)
        } catch (failure: Throwable) {
            // The original cause is deliberately not attached: its message can carry unsafe detail.
            val safe = PhaseError("phase", failure::class.simpleName ?: "failure")
            writeResult(result(runId, phase, ok = false, error = safe.safeMessage, payload = safeCounts(graph)))
            Log.i(TAG) { "phase=$phase ok=false error=${safe.safeMessage}" }
            throw AssertionError(safe.safeMessage)
        } finally {
            closeGraph(graph, db)
        }
    }

    // --- graph lifecycle ------------------------------------------------------------------------

    /**
     * The graph's composition root on the caller-owned root. The database open is the gate every
     * phase needs; the controlled settings and initializers follow the shell's start-up order.
     */
    private fun startGraph(): DesktopAppGraph {
        val dirs = appDirsUnder(root.resolve(APP_DIR)).also { it.ensureCreated() }
        val buildInfo = BuildInfoLoader.load()
        // 11 Start-up step 4's process identity; a no-op off Windows, never a registration.
        WindowsAppIdentity.applyProcessIdentity(buildInfo, DesktopEnginePaths.current().ndmedia)
        val reporter = DesktopCrashReporter(dirs, buildInfo, DesktopClock, RecentLogBuffer())
        return createDesktopGraph(dirs, buildInfo, reporter)
    }

    /** Update probes are disabled before any initializer runs; no network other than loopback. */
    private suspend fun prepareControlledRun(graph: DesktopAppGraph) {
        try {
            configureControlledJourneySettings(graph.settingsRepository)
        } catch (_: IOException) {
            throw PhaseError("config", "cannot prepare the controlled run settings")
        }
    }

    /**
     * Closes the OS surfaces, stops the runner, cancels and joins the graph scope, then closes
     * Room. Runs even after a phase failure so no collector outlives its files.
     */
    private suspend fun closeGraph(
        graph: DesktopAppGraph?,
        db: NeutrodyneDatabase?,
    ) {
        if (graph == null) return
        runCatching { graph.osSurfaces.close() }
        val opened = graph.databaseOpener.openState.value is DatabaseOpenState.Opened
        if (opened) {
            runCatching { graph.jobRunner.stop(RUNNER_GRACE) }
        }
        graph.appScope.coroutineContext[Job]?.cancelAndJoin()
        if (db != null && opened) {
            runCatching { db.close() }
        }
    }

    // --- config, phase and ownership ------------------------------------------------------------

    private fun readConfig(): CrossDeviceConfig {
        val file = root.resolve(CROSS_DEVICE_DIR).resolve("config.json")
        if (!Files.isRegularFile(file)) throw PhaseError("config", "config file is missing")
        return parseConfig(Files.readString(file))
    }

    private fun resolvePhases(phase: String?): List<String> {
        if (phase.isNullOrBlank()) return DEFAULT_PHASES
        if (phase !in PHASES) throw PhaseError("phase", "unknown crossDevicePhase")
        return listOf(phase)
    }

    private fun requireOwnership(
        runId: String,
        phase: String,
    ) {
        if (!Files.isRegularFile(ownershipFile)) {
            throw PhaseError(phase, "no ownership marker; run the seed phase first")
        }
        val marker =
            try {
                crossDeviceJson.parseToJsonElement(Files.readString(ownershipFile)).jsonObject
            } catch (_: Exception) {
                throw PhaseError(phase, "ownership marker is not valid JSON")
            }
        if (marker.string("runId") != runId) {
            throw PhaseError(phase, "ownership marker belongs to a different run")
        }
    }

    /** The seed's extra guard: a marker from another run means this root is not a fresh app. */
    private fun requireNoForeignRun(runId: String) {
        if (!Files.isRegularFile(ownershipFile)) return
        val marker =
            try {
                crossDeviceJson.parseToJsonElement(Files.readString(ownershipFile)).jsonObject
            } catch (_: Exception) {
                throw PhaseError("seed", "ownership marker is not valid JSON")
            }
        if (marker.string("runId") != runId) {
            throw PhaseError("seed", "root belongs to a different run")
        }
    }

    private fun writeOwnership(
        runId: String,
        counts: CrossDeviceSeedCounts,
    ) {
        root.resolve(CROSS_DEVICE_DIR).toFile().mkdirs()
        Files.writeString(
            ownershipFile,
            canonicalJson(
                buildJsonObject {
                    put("runId", runId)
                    put("seededAtMs", System.currentTimeMillis())
                    put("podcastCount", counts.podcasts)
                    put("groupCount", counts.groups)
                    put("episodeCount", counts.episodes)
                },
            ),
        )
    }

    // --- phases ---------------------------------------------------------------------------------

    private suspend fun dispatch(
        graph: DesktopAppGraph,
        db: NeutrodyneDatabase,
        config: CrossDeviceConfig,
        phase: String,
    ): JsonObject {
        val fixture = crossDeviceFixture(config.runId, config.feedBaseUrl)
        return when (phase) {
            "seed" -> seed(db, config, fixture)
            "link" -> link(graph, db, config)
            "edit" -> edit(db, config, fixture)
            "sync" -> sync(graph, db, config)
            "dump" -> dump(db, config, fixture)
            "apply-held" -> applyHeld(graph, db, config, fixture)
            else -> throw PhaseError("phase", "unknown crossDevicePhase")
        }
    }

    private suspend fun seed(
        db: NeutrodyneDatabase,
        config: CrossDeviceConfig,
        fixture: CrossDeviceFixture,
    ): JsonObject {
        requireNoForeignRun(config.runId)
        val writer = CrossDeviceSeedWriter(db, fixture)
        writer.checkFresh()
        val counts = writer.write()
        writeOwnership(config.runId, counts)
        return buildJsonObject {
            put("seeded", true)
            put("podcasts", counts.podcasts)
            put("episodes", counts.episodes)
            put("groups", counts.groups)
            put("members", counts.members)
            put("upNext", counts.upNext)
            put("counts", counts(db))
        }
    }

    /**
     * The real first-link flow: `startLink(PASSWORD)` -> loopback `confirmInsecure` ->
     * `submitPassword` -> `chooseFirstLink(MERGE)`. The actual `LinkState` machine drives the
     * answers; no token or profile row is inserted directly, and the password `CharArray` is
     * cleared after use.
     */
    private suspend fun link(
        graph: DesktopAppGraph,
        db: NeutrodyneDatabase,
        config: CrossDeviceConfig,
    ): JsonObject {
        requireOwnership(config.runId, "link")
        val controller = graph.syncController
        val state = db.syncStateDao().get()
        if (state.hasLinkOrSyncResidue()) {
            throw PhaseError("link", "device is already configured or linked")
        }
        if (controller.link?.state?.value != LinkState.Address) {
            throw PhaseError("link", "a link flow is already running")
        }

        val password = config.password.toCharArray()
        var transfer: CrossDevicePasswordTransfer? = null
        var passwordSubmitted = false
        var submittedAtMs = 0L
        var mergeChosen = false
        try {
            controller.startLink(config.serverUrl, LinkMethod.PASSWORD)
            val deadline = System.nanoTime() + LINK_TIMEOUT_MS * 1_000_000
            while (true) {
                val linkState =
                    controller.link?.state?.value
                        ?: throw PhaseError("link", "link flow is unavailable")
                when (linkState) {
                    is LinkState.InsecureConnection -> {
                        controller.confirmInsecure()
                    }

                    is LinkState.NeedsLocalNetworkPermission -> {
                        controller.grantLocalNetwork(true)
                    }

                    is LinkState.AwaitingInput -> {
                        if (passwordSubmitted) {
                            // The controller launches its login asynchronously; give a slow
                            // dispatch the grace period to leave AwaitingInput before treating
                            // the state as a synchronous rejection.
                            if (System.currentTimeMillis() - submittedAtMs >= SUBMISSION_GRACE_MS) {
                                transfer?.close()
                                throw PhaseError("link", "link returned to AwaitingInput after submitPassword")
                            }
                        } else {
                            passwordSubmitted = true
                            submittedAtMs = System.currentTimeMillis()
                            val submission = CrossDevicePasswordTransfer(password)
                            transfer = submission
                            try {
                                controller.submitPassword(config.username, submission.array)
                            } catch (failure: Throwable) {
                                submission.close()
                                throw failure
                            }
                            // The controller owns the transfer from here; it clears the copy in
                            // its login coroutine. Only the driver-owned original is cleared.
                            submission.launched()
                        }
                    }

                    is LinkState.ChooseFirstLink, is LinkState.ConfirmUpload -> {
                        if (mergeChosen) {
                            throw PhaseError("link", "link returned to the first-link choice twice")
                        }
                        mergeChosen = true
                        controller.chooseFirstLink(FirstLinkChoice.MERGE)
                    }

                    is LinkState.Linked -> {
                        break
                    }

                    is LinkState.Failed -> {
                        throw PhaseError("link", "link failed: ${linkState.error}")
                    }

                    else -> {}
                }
                if (System.nanoTime() >= deadline) {
                    throw PhaseError("link", "timed out waiting for the link flow (last=${linkState.safeName()})")
                }
                delay(POLL_MS)
            }
        } finally {
            password.fill('\u0000')
        }

        // `beginMerge` publishes `LinkState.Linked` before the asynchronous status refresh;
        // wait boundedly for the linked status instead of failing in that window.
        if (!awaitLinkedStatus(controller.status, STATUS_TIMEOUT_MS, POLL_MS, System::nanoTime) { delay(it) }) {
            throw PhaseError("link", "link did not reach the linked status")
        }
        return buildJsonObject {
            put("linked", true)
            put("counts", counts(db))
        }
    }

    private suspend fun edit(
        db: NeutrodyneDatabase,
        config: CrossDeviceConfig,
        fixture: CrossDeviceFixture,
    ): JsonObject {
        requireOwnership(config.runId, "edit")
        val edits = config.edits.ifEmpty { defaultEdits() }
        val applied = CrossDeviceEditApplier(db, fixture).apply(edits)
        val outbox = db.syncOutboxDao().count()
        if (outbox == 0) {
            throw PhaseError("edit", "no outbox rows were captured")
        }
        return buildJsonObject {
            put("editsApplied", applied)
            put("outboxRows", outbox)
            put("counts", counts(db))
        }
    }

    /**
     * A real `SyncController.syncNow()` round. The baseline is armed before the call and the
     * completion barrier is a new `SYNC_NOW` round summary in the local diagnostics, so an empty
     * outbox cannot pass without an RPC. A failed, revoked or clock-skewed round fails the phase.
     */
    private suspend fun sync(
        graph: DesktopAppGraph,
        db: NeutrodyneDatabase,
        config: CrossDeviceConfig,
    ): JsonObject {
        requireOwnership(config.runId, "sync")
        val controller = graph.syncController
        val state = db.syncStateDao().get()
        if (state == null || !state.enabled || state.linkedAt == null) {
            throw PhaseError("sync", "sync state is not linked")
        }
        // The controller publishes its status from an async refresh on creation; give a linked
        // install the bounded time it needs before declaring the device not linked.
        if (!awaitLinkedStatus(controller.status, STATUS_TIMEOUT_MS, POLL_MS, System::nanoTime) { delay(it) }) {
            throw PhaseError("sync", "device is not linked")
        }

        // Arm the identity baseline before `syncNow`, after any prior round is idle, so a
        // preexisting `SYNC_NOW` summary cannot certify the round that is about to run.
        val barrier = CrossDeviceSyncBarrier(controller, SYNC_TIMEOUT_MS, POLL_MS, System::nanoTime) { delay(it) }
        val armFailure = barrier.arm()
        if (armFailure != null) {
            throw PhaseError("sync", armFailure)
        }
        val round =
            barrier.syncNowAndAwait()
                ?: throw PhaseError("sync", "no SYNC_NOW round completed within ${SYNC_TIMEOUT_MS}ms")
        val report =
            round.report
                ?: throw PhaseError("sync", "round finished without a report")
        val failure = syncRoundFailure(round, controller.status.value, db.syncStateDao().get()?.lastError)
        if (failure != null) {
            throw PhaseError("sync", failure)
        }
        return buildJsonObject {
            put(
                "round",
                buildJsonObject {
                    put("reason", round.reason.name)
                    put("pushed", report.pushed)
                    put("applied", report.applied)
                    put("stale", report.stale)
                    put("rejected", report.rejected)
                    put("pulled", report.pulled)
                    put("held", report.held)
                    put("durationMs", report.durationMs)
                },
            )
            put("counts", counts(db))
        }
    }

    private suspend fun dump(
        db: NeutrodyneDatabase,
        config: CrossDeviceConfig,
        fixture: CrossDeviceFixture,
    ): JsonObject {
        requireOwnership(config.runId, "dump")
        return CrossDeviceDumper(db, fixture).dump()
    }

    /**
     * The held-approval phase: the host has proved an exact batch is HELD and every subscription
     * remains. Approve it through the public `SyncController.resolveHeld` APPLY path and wait for
     * the row to resolve. The batch contract (one HELD row, the expected podcast count, a matching
     * summary, fixture podcasts that are still subscribed) is checked before the call; a foreign
     * or malformed batch is refused.
     */
    private suspend fun applyHeld(
        graph: DesktopAppGraph,
        db: NeutrodyneDatabase,
        config: CrossDeviceConfig,
        fixture: CrossDeviceFixture,
    ): JsonObject {
        requireOwnership(config.runId, "apply-held")
        val expected = config.expectedHeldPodcastCount ?: DEFAULT_EXPECTED_HELD_PODCAST_COUNT
        return CrossDeviceHeldApplier(
            controller = graph.syncController,
            db = db,
            fixture = fixture,
            expectedPodcastCount = expected,
            timeoutMs = HELD_TIMEOUT_MS,
            pollMs = POLL_MS,
            nanoTime = System::nanoTime,
            delay = { delay(it) },
        ).apply()
    }

    // --- result ---------------------------------------------------------------------------------

    private fun safeCounts(graph: DesktopAppGraph?): JsonObject =
        if (graph != null && graph.databaseOpener.openState.value is DatabaseOpenState.Opened) {
            runCatching { runBlocking { counts(graph.databaseOpener.requireDatabase()) } }
                .getOrElse { buildJsonObject { } }
        } else {
            buildJsonObject { }
        }

    private fun writeResult(result: JsonObject) {
        root.resolve(CROSS_DEVICE_DIR).toFile().mkdirs()
        Files.writeString(resultFile, canonicalJson(result))
    }

    private fun result(
        runId: String?,
        phase: String?,
        ok: Boolean,
        error: String?,
        payload: JsonObject,
    ): JsonObject =
        buildJsonObject {
            put("runId", JsonPrimitive(runId))
            put("phase", phase)
            put("ok", ok)
            put("error", JsonPrimitive(error))
            payload.forEach { (key, value) -> put(key, value) }
        }

    private companion object {
        const val TAG = "CrossDeviceSync"
        const val CROSS_DEVICE_DIR = "cross-device"
        const val APP_DIR = "app"
        val DEFAULT_PHASES = listOf("seed", "link", "edit", "sync", "dump")

        // `apply-held` is host-invoked only: the default sequence has no held batch to approve.
        val PHASES = (DEFAULT_PHASES + "apply-held").toSet()
        val RUNNER_GRACE = 3.seconds
    }
}
