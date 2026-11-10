// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.ConnectionPoolEvictor
import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.LocalNetworkAccess
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.common.PowerEvent
import ch.lkmc.neutrodyne.core.common.PowerMonitor
import ch.lkmc.neutrodyne.core.common.UserAgentProvider
import ch.lkmc.neutrodyne.core.data.refresh.DesktopJobRunner
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.NoGroupChannels
import ch.lkmc.neutrodyne.core.domain.NoPlaybackSync
import ch.lkmc.neutrodyne.core.domain.SettingsWriteCoordinator
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.network.okhttp.AuthInterceptor
import ch.lkmc.neutrodyne.core.network.okhttp.CoreClients
import ch.lkmc.neutrodyne.core.network.okhttp.DnsFamilyHints
import ch.lkmc.neutrodyne.core.network.okhttp.LocalNetworkGuard
import ch.lkmc.neutrodyne.core.network.okhttp.NetworkClients
import ch.lkmc.neutrodyne.core.network.okhttp.UserAgentInterceptor
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsSyncPort
import ch.lkmc.neutrodyne.core.testing.FakeSyncServerAddress
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.core.testing.database.queueEntryEntity
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.OrderKey
import ch.lkmc.neutrodyne.sync.protocol.PROTOCOL_VERSION
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceInfoDto
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * The two-client wire acceptance test (MS3): the packaged `:sync:server` fat JAR on loopback, two
 * independent clients on real file-backed Room databases, the production `KtorSyncClient` and
 * `KtorSyncEventTransport` over OkHttp, and the real `SyncEngine`/`SyncEventsClient`/`SyncScheduler`
 * wired through `DesktopSyncLane` + `DesktopSyncScheduleHooks` into the production
 * `DesktopJobRunner`/`JobLanePoker` routing. No `syncNow`/`round`/`requestSync` is invoked for the
 * tested edits — only the explicit bootstrap round — and the timer opens once both live streams
 * report `connected`.
 *
 * The path under test for a local edit is Room trigger -> `sync_outbox` -> invalidation flow ->
 * the 2-s debounce -> `schedulePush` -> lane poke -> runner tick -> `engine.round` -> HTTP push ->
 * server merge + `changed` publish -> peer SSE -> the 500-ms coalesce -> peer lane poke -> peer
 * tick -> peer pull -> `SyncApplier` -> the peer's Room rows. Four edits run A->B (played, group
 * rename, two Up next inserts) and three run B->A (Up next reorder/removal and a second played
 * mark), each bounded at 10 s of wall time from the local write to the peer's DAO projection.
 *
 * The boundary between real and substituted is narrow: server process, HTTP/SSE transport, Room
 * files, HLC clocks, capture triggers, engine, applier, scheduler and job routing are all
 * production code — including `SyncParkedStateApplier` and `DesktopSyncLocalNetworkGate`.
 * Substituted seams are the unrelated platform dependencies only: `FakeSecretStore` (token +
 * secret storage), `FakeSettingsSyncPort`/`FakeSyncServerAddress`/`PerKeySettingsLocks` (settings
 * persistence), `FakeCredentialCommitCoordinator`, `NoPlaybackSync` (a player substitute; no player action is
 * exercised), a `FakeNetworkMonitor` pinned online (the real desktop monitor requires a non-loopback interface and would report offline on loopback-only CI while
 * the wire itself stays OkHttp) and a silent `PowerMonitor` (no suspend/resume in a test
 * process). Throwaway invite codes and bearer tokens never reach the output: the CLI children are
 * read into memory and the failure dump redacts them.
 */
class TwoClientWireTest {
    @Test(timeout = TEST_BUDGET_MS)
    fun localWritesPropagateBetweenTwoRealClientsOverHttpAndSse() =
        runBlocking {
            val jar = serverJar()
            val root = Files.createTempDirectory("nd-sync-wire-")
            val clients = mutableListOf<WireClient>()
            var server: ServerChild? = null
            val timings = linkedMapOf<String, Long>()
            try {
                // The throwaway account and one invite per device come through the real CLI; the
                // device registration itself rides the real redeem API.
                val port = freePort()
                val properties = writeServerProperties(root, port)
                val inviteA = serverCli(jar, properties, "user", "add", "wire-tester").requireInvite()
                val inviteB = serverCli(jar, properties, "user", "invite", "wire-tester").requireInvite()
                server = ServerChild(jar, properties, root.resolve("server.log")).start()
                val baseUrl = "http://127.0.0.1:$port"
                server.awaitDiscovery(baseUrl)

                val a = WireClient(root.resolve("client-a")).also(clients::add)
                val b = WireClient(root.resolve("client-b")).also(clients::add)

                // Identical podcast/episode/group identity on both sides while `enabled = 0`: the
                // seeds are bootstrap state — the capture triggers must not have seen them.
                a.seedLibrary()
                b.seedLibrary()
                assertEquals(0, a.db.syncOutboxDao().count(), "the A seed must stay out of the outbox")
                assertEquals(0, b.db.syncOutboxDao().count(), "the B seed must stay out of the outbox")
                a.link(baseUrl, inviteA)
                b.link(baseUrl, inviteB)

                // Explicit bootstrap rounds — the allowed startup synchronization, distinct from
                // the tested edits below.
                a.bootstrap()
                b.bootstrap()

                a.startAutomatic()
                b.startAutomatic()
                awaitStreamsConnected(a, b)

                timings["A->B played"] =
                    propagate("A->B played", a, b, {
                        a.markPlayed(EP_ONE, PLAYED_AT)
                    }) {
                        b.playedAt(EP_ONE) == PLAYED_AT
                    }
                timings["A->B group rename"] =
                    propagate("A->B group rename", a, b, {
                        a.renameGroup(RENAMED_GROUP)
                    }) {
                        b.groupName() == RENAMED_GROUP
                    }
                timings["A->B upnext add"] =
                    propagate("A->B upnext add", a, b, {
                        a.enqueue(EP_ONE)
                    }) {
                        b.queued(EP_ONE)
                    }
                timings["A->B second upnext add"] =
                    propagate("A->B second upnext add", a, b, {
                        a.enqueue(EP_TWO, "a1")
                    }) {
                        b.queued(EP_TWO)
                    }
                timings["B->A upnext reorder"] =
                    propagate("B->A upnext reorder", b, a, {
                        b.moveBefore(EP_TWO, EP_ONE)
                    }) {
                        a.orderedBefore(EP_TWO, EP_ONE)
                    }
                timings["B->A upnext remove"] =
                    propagate("B->A upnext remove", b, a, {
                        b.dequeue(EP_ONE)
                    }) {
                        !a.queued(EP_ONE)
                    }
                timings["B->A played"] =
                    propagate("B->A played", b, a, {
                        b.markPlayed(EP_TWO, PLAYED_AT_B)
                    }) {
                        a.playedAt(EP_TWO) == PLAYED_AT_B
                    }
                println("[sync-wire] propagation timings: $timings")
            } catch (failure: Throwable) {
                server?.dumpTail()
                throw failure
            } finally {
                clients.forEach { runCatching { it.close() } }
                server?.stop()
                root.toFile().deleteRecursively()
            }
        }

    // --- measured section -----------------------------------------------------------------------

    /**
     * Wall-time from the local Room write to the peer's DAO projection. The 50-ms poll observes
     * the result only; propagation itself is driven by the real scheduler/lane/runner wiring —
     * nothing here nudges it along.
     */
    private suspend fun propagate(
        name: String,
        writer: WireClient,
        observer: WireClient,
        write: suspend () -> Unit,
        probe: suspend () -> Boolean,
    ): Long {
        val start = System.nanoTime()
        try {
            withTimeout(PROPAGATION_BUDGET_MS) {
                write()
                while (!probe()) delay(POLL_MS)
            }
        } catch (timeout: TimeoutCancellationException) {
            fail(
                "$name did not reach the peer within ${PROPAGATION_BUDGET_MS}ms\n" +
                    "writer ${writer.diagnose()}\nobserver ${observer.diagnose()}",
            )
        }
        val elapsed = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsed <= PROPAGATION_BUDGET_MS, "$name took ${elapsed}ms")
        return elapsed
    }

    private suspend fun awaitStreamsConnected(
        a: WireClient,
        b: WireClient,
    ) {
        withTimeout(STREAM_BUDGET_MS) {
            awaitAll(
                async { a.events.connected.first { it } },
                async { b.events.connected.first { it } },
            )
        }
    }

    // --- server process -------------------------------------------------------------------------

    private fun serverJar(): Path {
        val path =
            System.getProperty("neutrodyne.serverJar")
                ?: fail("neutrodyne.serverJar is unset — :sync:impl:desktopTest supplies it via :sync:server:fatJar")
        val jar = Path.of(path)
        check(Files.isRegularFile(jar)) { "server fat JAR missing at $jar — run :sync:server:fatJar first" }
        return jar
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun writeServerProperties(
        root: Path,
        port: Int,
    ): Path {
        val properties = root.resolve("server.properties")
        Files.writeString(
            properties,
            buildString {
                append("data.dir=").append(root.resolve("server-data").toAbsolutePath()).append('\n')
                append("listen=127.0.0.1:").append(port).append('\n')
                append("log.level=info\n")
                append("update.check=false\n")
            },
        )
        return properties
    }

    /** CLI output stays in a private temporary file; a hung child cannot block a pipe read. */
    private fun serverCli(
        jar: Path,
        properties: Path,
        vararg args: String,
    ): String {
        val outputFile = Files.createTempFile(properties.parent, "cli-", ".output")
        var child: Process? = null
        try {
            child =
                ProcessBuilder(
                    listOf(javaBin(), "-jar", jar.toAbsolutePath().toString()) +
                        args.toList() +
                        listOf("--config", properties.toAbsolutePath().toString()),
                ).redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile())
                    .start()
            check(child.waitFor(CLI_SECONDS, TimeUnit.SECONDS)) {
                "server CLI ${args.joinToString(" ")} did not exit"
            }
            check(Files.size(outputFile) <= CLI_OUTPUT_LIMIT_BYTES) { "server CLI output exceeded its limit" }
            val output = Files.readString(outputFile)
            check(child.exitValue() == 0) { "server CLI ${args.joinToString(" ")} failed: ${redact(output)}" }
            return output
        } finally {
            child?.let {
                if (it.isAlive) {
                    it.destroyForcibly()
                    it.waitFor(SHUTDOWN_SECONDS, TimeUnit.SECONDS)
                }
            }
            Files.deleteIfExists(outputFile)
        }
    }

    private fun javaBin(): String = Path.of(System.getProperty("java.home"), "bin", "java").toString()

    private fun String.requireInvite(): String =
        INVITE_PATTERN.find(this)?.groupValues?.get(1)
            ?: fail("no invite in the CLI output: ${redact(this)}")

    /** Never print a bearer token, an invite code or a password-ish field. */
    private fun redact(text: String): String =
        text
            .replace(INVITE_PATTERN, "Invite (24 hours, shown once): ***")
            .replace(Regex("Bearer \\S+"), "Bearer ***")
            .replace(Regex("(?i)(token|password|secret)([\"'=:\\s]+)[A-Za-z0-9._~+/=-]+"), "$1$2***")

    private class ServerChild(
        private val jar: Path,
        private val properties: Path,
        private val logFile: Path,
    ) {
        private var process: Process? = null

        fun start(): ServerChild {
            process =
                ProcessBuilder(
                    javaBin(),
                    "-jar",
                    jar.toAbsolutePath().toString(),
                    "serve",
                    "--config",
                    properties.toAbsolutePath().toString(),
                ).redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())
                    .start()
            return this
        }

        /** Bounded startup: the server must answer its discovery document. */
        suspend fun awaitDiscovery(baseUrl: String) {
            val url = URI("$baseUrl/.well-known/neutrodyne-sync").toURL()
            val deadline = System.nanoTime() + STARTUP_BUDGET_MS * 1_000_000
            var lastError: String? = null
            while (System.nanoTime() < deadline) {
                check(process?.isAlive != false) { "server child exited during startup" }
                try {
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 1_000
                    connection.readTimeout = 1_000
                    try {
                        if (connection.responseCode == 200) return
                        lastError = "HTTP ${connection.responseCode}"
                    } finally {
                        connection.disconnect()
                    }
                } catch (failure: Exception) {
                    lastError = failure.message
                }
                delay(100)
            }
            fail("server did not answer discovery within ${STARTUP_BUDGET_MS}ms (last: $lastError)")
        }

        fun dumpTail() {
            if (!Files.isRegularFile(logFile)) return
            val lines = Files.readAllLines(logFile)
            println("--- server.log tail (last $LOG_TAIL_LINES lines, redacted) ---")
            lines.takeLast(LOG_TAIL_LINES).forEach { println(redact(it)) }
            println("--- end of server.log tail ---")
        }

        fun stop() {
            val child = process ?: return
            child.destroy()
            if (!child.waitFor(SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
                child.destroyForcibly()
                child.waitFor(SHUTDOWN_SECONDS, TimeUnit.SECONDS)
            }
        }

        private companion object {
            const val SHUTDOWN_SECONDS = 15L
            const val LOG_TAIL_LINES = 60

            fun javaBin(): String = Path.of(System.getProperty("java.home"), "bin", "java").toString()

            fun redact(text: String): String =
                text
                    .replace(INVITE_PATTERN, "Invite (24 hours, shown once): ***")
                    .replace(Regex("Bearer \\S+"), "Bearer ***")
        }
    }

    // --- one client -----------------------------------------------------------------------------

    /**
     * One independent client: own scope, own file-backed Room database, own OkHttp SYNC stack and
     * the real scheduling chain — `SyncScheduler` debounces into `DesktopSyncScheduleHooks` ->
     * `DesktopSyncLane.poke` -> `JobLanePoker` -> `DesktopJobRunner` -> `lane.run` ->
     * `SyncEngine.round`, and `SyncEventsClient` feeds remote `changed` events back into the
     * scheduler. `lateinit` mirrors Metro's Provider-based construction of the same cycle.
     */
    private class WireClient(
        dir: Path,
    ) {
        val label: String = dir.fileName.toString()
        private val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val db: NeutrodyneDatabase = TestDb.file(dir, clock = WallClock)
        val secrets = FakeSecretStore()
        val settingsPort = FakeSettingsSyncPort()
        val address = FakeSyncServerAddress()
        val tokens = SyncTokenStore(secrets)
        val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)
        val http = newHttpClients()
        val client =
            KtorSyncClient(
                http,
                tokenProvider = { address.serverUrl()?.let { tokens.load(hostOf(it)) } },
                baseUrlProvider = { address.serverUrl() ?: "" },
            )
        private val writeLocks = PerKeySettingsLocks()
        private val credentials = FakeCredentialCommitCoordinator()
        private val notices = SyncNoticeBus()
        private val capture = SettingsCapture(db, settingsPort, writeLocks) { true }
        private val effects =
            SyncApplyEffects(
                db,
                settingsPort,
                writeLocks,
                { true },
                secrets,
                credentials,
                dir.toString().toPath(),
            )
        private lateinit var applier: SyncApplier
        private lateinit var lane: DesktopSyncLane
        private lateinit var runner: DesktopJobRunner
        private lateinit var scheduler: SyncScheduler
        lateinit var events: SyncEventsClient
            private set
        val engine: SyncEngine

        // Library row ids of the seeded identity; per-client values, equal keys on the wire.
        private var podcastId = -1L
        private val episodeIds = mutableListOf<Long>()
        private var groupId = -1L

        init {
            applier =
                SyncApplier(
                    db = db,
                    clock = WallClock,
                    secrets = secrets,
                    settings = settingsPort,
                    playback = NoPlaybackSync,
                    syncIngestHook = SyncParkedStateApplier(Provider { db }, Provider { applier }),
                    channelSync = NoGroupChannels,
                    sharePasswords = { false },
                    coordinator = credentials,
                    notices = notices,
                    deviceNames = ClientDeviceNames { client.devices() },
                )
            engine =
                SyncEngine(
                    db = db,
                    client = client,
                    applier = applier,
                    clock = WallClock,
                    secrets = secrets,
                    sharePasswords = { false },
                    address = address,
                    tokens = tokens,
                    activity = SyncActivityState(),
                    gate = DesktopSyncLocalNetworkGate(),
                    effects = effects,
                    capture = capture,
                    notices = notices,
                )
            lane =
                DesktopSyncLane(
                    db = db,
                    round = { reason ->
                        println("[$label] lane round $reason")
                        engine.round(reason).also {
                            println(
                                "[$label] round $reason pushed=${it.pushed} pulled=${it.pulled} applied=${it.applied}",
                            )
                        }
                    },
                    network = network,
                    clock = WallClock,
                    poker = Provider { runner },
                    onRevoked = { scheduler.onUnlinked() },
                )
            runner =
                DesktopJobRunner(
                    lanes = setOf(lane),
                    clock = WallClock,
                    power = NeverSuspends,
                    network = network,
                    poolEvictor = NoPoolEvict,
                    scope = scope,
                )
            scheduler =
                SyncScheduler(
                    db = db,
                    round = { reason -> engine.round(reason) },
                    playback = NoPlaybackSync,
                    network = network,
                    clock = WallClock,
                    scope = scope,
                    hooks = DesktopSyncScheduleHooks(lane),
                    linkStateChanged = { events.reevaluate() },
                )
            events =
                SyncEventsClient(
                    db = db,
                    transport = KtorSyncEventTransport(http),
                    discover = { url -> client.discover(url) },
                    address = address,
                    tokens = tokens,
                    network = network,
                    scope = scope,
                    onRemoteChanged = { scheduler.onRemoteChanged() },
                    onRevoked = { scheduler.onUnlinked() },
                )
        }

        /** Bootstrap state: equal identity on both clients, written before `enabled` flips. */
        suspend fun seedLibrary() {
            db.withWriteTransaction {
                podcastId =
                    db
                        .podcastDao()
                        .insertPodcast(
                            podcastEntity(
                                syncId = PODCAST_SYNC_ID,
                                feedUrl = FEED_URL,
                                feedKey = FEED_KEY,
                                title = "Wire feed",
                            ),
                        )
                episodeIds +=
                    db
                        .ingestDao()
                        .insertEpisodes(
                            listOf(
                                episodeEntity(
                                    podcastId = podcastId,
                                    identityKey = EPISODE_KEY,
                                    title = "Wire episode",
                                ),
                                episodeEntity(
                                    podcastId = podcastId,
                                    identityKey = EPISODE_KEY_2,
                                    title = "Wire episode two",
                                ),
                            ),
                        )
                groupId =
                    db
                        .groupDao()
                        .insert(
                            groupEntity(
                                uuid = GROUP_UUID,
                                name = ORIGINAL_GROUP_NAME,
                                orderKey = "a0",
                            ),
                        )
                db
                    .groupDao()
                    .insertMember(
                        memberEntity(groupId = groupId, podcastId = podcastId, orderKey = "a0"),
                    )
            }
        }

        /**
         * The real invite redeem registers the device; the durable link writes mirror the
         * controller's (`enabled` -> `enableLinked` -> `capture.reconcile`).
         */
        suspend fun link(
            baseUrl: String,
            inviteCode: String,
        ) {
            val nodeId = NodeId.random()
            val response =
                client.redeemInvite(
                    baseUrl,
                    inviteCode,
                    DeviceInfoDto(
                        id = newDeviceId(),
                        name = "wire-$label",
                        platform = "linux",
                        appVersion = "wire-test",
                        nodeId = nodeId.value,
                    ),
                )
            tokens.save(hostOf(baseUrl), response.token)
            address.setServerUrl(baseUrl)
            address.setUsername(response.username)
            db.withWriteTransaction {
                db
                    .syncStateDao()
                    .upsert(
                        SyncStateEntity(
                            enabled = false,
                            serverUrl = baseUrl,
                            accountId = response.accountId,
                            deviceId = response.deviceId,
                            nodeId = nodeId.value,
                            protocol = PROTOCOL_VERSION,
                        ),
                    )
                db.syncStateDao().enableLinked(WallClock.now())
            }
            capture.reconcile()
        }

        /** The explicit bootstrap round — allowed startup synchronization, not a tested edit. */
        suspend fun bootstrap() {
            engine.round(SyncRoundReason.SYNC_NOW)
        }

        /** Arms the automatic path: collectors, the runner's tick loop and the events stream. */
        suspend fun startAutomatic() {
            scheduler.start()
            runner.start()
            events.start(flowOf(true), SSE_HEARTBEAT_DESKTOP_SECONDS)
            scheduler.onLinked()
        }

        // The local writes under test — the same DAO calls the repositories make.
        suspend fun markPlayed(
            episode: Int,
            playedAt: Long,
        ) {
            val id = episodeIds[episode]
            db.withWriteTransaction {
                db.episodeStateDao().ensure(EpisodeStateEntity(episodeId = id, updatedAt = WallClock.now()))
                db.episodeStateDao().markPlayed(listOf(id), playedAt)
            }
        }

        suspend fun renameGroup(name: String) {
            db.withWriteTransaction {
                db.groupDao().rename(groupId, name, name.lowercase(), WallClock.now())
            }
        }

        suspend fun enqueue(
            episode: Int,
            orderKey: String = "a0",
        ) {
            db.withWriteTransaction {
                db.queueDao().insert(
                    queueEntryEntity(episodeId = episodeIds[episode], orderKey = orderKey, addedAt = WallClock.now()),
                )
            }
        }

        suspend fun moveBefore(
            episode: Int,
            before: Int,
        ) {
            db.withWriteTransaction {
                val entry = requireNotNull(db.queueDao().entryByEpisode(episodeIds[episode]))
                val anchor = requireNotNull(db.queueDao().entryByEpisode(episodeIds[before]))
                db.queueDao().updateOrderKey(entry.id, OrderKey.before(anchor.orderKey))
            }
        }

        suspend fun orderedBefore(
            episode: Int,
            other: Int,
        ): Boolean {
            val key = db.queueDao().entryByEpisode(episodeIds[episode])?.orderKey ?: return false
            val otherKey = db.queueDao().entryByEpisode(episodeIds[other])?.orderKey ?: return false
            return key < otherKey
        }

        suspend fun dequeue(episode: Int) {
            db.withWriteTransaction {
                db.queueDao().removeEpisodes(listOf(episodeIds[episode]))
            }
        }

        // The peer's DAO projections under observation.
        suspend fun playedAt(episode: Int): Long? = db.episodeStateDao().byEpisode(episodeIds[episode])?.playedAt

        suspend fun groupName(): String? = db.groupDao().groupByUuid(GROUP_UUID)?.name

        suspend fun queued(episode: Int): Boolean = db.queueDao().entryByEpisode(episodeIds[episode]) != null

        /** A stall snapshot: link state, pending outbox, SSE flag and the lane's last run. */
        suspend fun diagnose(): String {
            val state = db.syncStateDao().get()
            val lane = runner.status.value[SYNC_LANE_NAME]
            val rows =
                db.syncOutboxDao().rows().joinToString(",") { row ->
                    "${row.coll}:${row.rid.takeLast(16)}:${row.field}=${row.value?.take(60)}@${row.hlc}/${row.nodeId}"
                }
            return buildString {
                append(label)
                append(" enabled=").append(state?.enabled)
                append(" linkedAt=").append(state?.linkedAt)
                append(" lastError=").append(state?.lastError)
                append(" nodeId=").append(state?.nodeId)
                append(" outbox=").append(db.syncOutboxDao().count())
                append(" [").append(rows).append("]")
                append(" sse=").append(events.connected.value)
                append(" lane=").append(lane)
            }
        }

        suspend fun close() {
            runCatching { runner.stop(3.seconds) }
            job.cancelAndJoin()
            runCatching { http.close() }
            db.close()
        }
    }

    /** The settings-write mutex the app hands `:core:data`; a per-key map is all it contracts for. */
    private class PerKeySettingsLocks : SettingsWriteCoordinator {
        private val pool = Mutex()
        private val locks = mutableMapOf<String, Mutex>()

        override suspend fun <T> withLock(
            key: SettingKey<*>,
            block: suspend () -> T,
        ): T {
            val mutex = pool.withLock { locks.getOrPut(key.name) { Mutex() } }
            return mutex.withLock { block() }
        }
    }

    /** No suspend/resume inside a test process; the runner just never sees a wake event. */
    private object NeverSuspends : PowerMonitor {
        override val events = flowOf<PowerEvent>()
    }

    /** No wake events means pool eviction never runs. */
    private object NoPoolEvict : ConnectionPoolEvictor {
        override fun evict() = Unit
    }

    /** A real clock — wall time for persisted timestamps, monotonic time for the runner's cadence. */
    private object WallClock : Clock {
        override fun now(): Long = System.currentTimeMillis()

        override fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000
    }

    private companion object {
        /** The acceptance bound: each local edit reaches the peer DAO projection inside 10 s. */
        const val PROPAGATION_BUDGET_MS = 10_000L
        const val STREAM_BUDGET_MS = 20_000L
        const val STARTUP_BUDGET_MS = 30_000L
        const val CLI_SECONDS = 30L
        const val CLI_OUTPUT_LIMIT_BYTES = 65_536L
        const val SHUTDOWN_SECONDS = 5L
        const val POLL_MS = 50L
        const val TEST_BUDGET_MS = 300_000L

        const val EP_ONE = 0
        const val EP_TWO = 1
        const val PODCAST_SYNC_ID = "11111111-2222-4333-8444-555555555555"
        const val FEED_URL = "https://feeds.example/wire.xml"
        const val FEED_KEY = "https://feeds.example/wire.xml"
        const val EPISODE_KEY = "g:wire-episode-1"
        const val EPISODE_KEY_2 = "g:wire-episode-2"
        const val GROUP_UUID = "66666666-7777-4888-8999-000000000000"
        const val ORIGINAL_GROUP_NAME = "Commute"
        const val RENAMED_GROUP = "Weekend queue"
        const val PLAYED_AT = 1_800_000_000_000L
        const val PLAYED_AT_B = 1_800_000_100_000L

        val INVITE_PATTERN = Regex("Invite \\(24 hours, shown once\\): (\\S+)")

        fun newHttpClients(): OkHttpNeutrodyneHttpClients {
            val platform =
                object : PlatformInfo {
                    override val kind = PlatformKind.DESKTOP
                    override val userAgentPlatform = "Linux; x64"
                    override val androidSdkInt: Int? = null
                    override val regionCode = "DE"
                }
            val userAgent = UserAgentProvider(platform, "wire-test", "https://example.com/repo")
            return OkHttpNeutrodyneHttpClients(
                NetworkClients(
                    CoreClients(
                        ua = UserAgentInterceptor(userAgent),
                        lanGuard = LocalNetworkGuard(LocalNetworkAccess(), platform),
                        hints = DnsFamilyHints(),
                        debugInterceptors = emptySet(),
                    ),
                    AuthInterceptor { CredentialLookup.None },
                ),
                userAgent,
            )
        }
    }
}
