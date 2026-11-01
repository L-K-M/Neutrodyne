// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.model.SourceKind
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakePlaybackMediaLookup
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.download.api.OrphanDeleteResult
import ch.lkmc.neutrodyne.download.api.RootChangeMode
import ch.lkmc.neutrodyne.download.api.RootChangeResult
import ch.lkmc.neutrodyne.download.api.ShareableFile
import ch.lkmc.neutrodyne.download.api.StorageUsage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import ch.lkmc.neutrodyne.core.model.DownloadLane as Lane
import ch.lkmc.neutrodyne.core.model.DownloadState as State
import ch.lkmc.neutrodyne.core.model.WaitReason as Wait

@OptIn(ExperimentalCoroutinesApi::class)
internal class EngineRig(
    private val scope: TestScope,
    credentials: CredentialLookup = CredentialLookup.None,
    driver: SQLiteDriver = TestDb.bundledDriver(),
) : AutoCloseable,
    CoroutineScope by scope {
    fun runCurrent() = scope.testScheduler.runCurrent()

    val dispatcher = StandardTestDispatcher(scope.testScheduler)
    val clock = TestClock.from(scope.testScheduler)
    val db = TestDb.inMemory(driver = driver, clock = clock, queryContext = dispatcher)
    val dao = db.downloadDao()
    val conditions = MutableStateFlow(Conditions(true, true, true, true, true))
    val roots = TestRoots()
    val faults = FaultFiles()
    val files = OkioDownloadFileSystem(roots, faults, dispatcher)
    val verification = DownloadFiles(files, roots, PlatformSriHasher(roots, faults), PlatformLocalUris())
    val progress = DownloadProgressHub(scope.backgroundScope, clock)
    val source = FakeTransferSource(SourceKind.RSS_ENCLOSURE)
    val youtube = FakeTransferSource(SourceKind.YOUTUBE)
    val indexed = ConcurrentHashMap<Long, String>()
    val completed = Collections.synchronizedList(mutableListOf<Long>())
    val events = mutableListOf<String>()
    val scheduled = mutableListOf<Lane>()
    var onSchedule: suspend () -> Unit = {}
    var resets = 0
    var mayClaim = true
    val scheduler =
        object : DownloadScheduler {
            override suspend fun ensureScheduled(lane: Lane) {
                scheduled += lane
                onSchedule()
            }

            override fun wake(lane: Lane) = Unit

            override suspend fun scheduleWake(
                lane: Lane,
                earliestAt: Long?,
                reason: Wait,
            ) = Unit
        }
    val disk =
        object : DownloadControllerFiles {
            override suspend fun prepareRemoval(rows: List<DownloadEntity>) {
                for (row in rows) {
                    assertNotNull(dao.byEpisode(row.episodeId))
                    events += "journal:${row.episodeId}"
                    indexed.remove(row.episodeId)
                }
            }

            override suspend fun finishRemoval(rows: List<DownloadEntity>) {
                for (row in rows) {
                    assertNull(dao.byEpisode(row.episodeId))
                    events += "delete:${row.episodeId}"
                    row.tempPath?.let { files.delete(row.rootId, it) }
                    row.relativePath?.let { files.delete(row.rootId, it) }
                }
            }

            override suspend fun reportFileMissing(episodeId: Long) = Unit

            override fun observeStorage() = MutableStateFlow(StorageUsage("old", 0, 0, 0, null, null, false))

            override suspend fun changeRoot(
                rootId: String,
                mode: RootChangeMode,
            ) = RootChangeResult.Unsupported

            override suspend fun deleteOrphanFiles() = OrphanDeleteResult.Unavailable

            override suspend fun shareableFile(episodeId: Long): ShareableFile? = null
        }
    val engine =
        DownloadEngine(
            db,
            clock,
            credentials,
            conditions,
            setOf(source, youtube),
            files,
            roots,
            verification,
            disk,
            object : LocalMediaIndexStore {
                override val changes = MutableSharedFlow<Set<Long>>()

                override fun localUriOrNull(episodeId: Long) = indexed[episodeId]

                override suspend fun replace(snapshot: Map<Long, String>) {
                    indexed.clear()
                    indexed.putAll(snapshot)
                }

                override suspend fun put(
                    episodeId: Long,
                    uri: String,
                ) {
                    assertEquals(State.COMPLETED, row(episodeId).state)
                    indexed[episodeId] = uri
                }

                override suspend fun remove(episodeId: Long) {
                    indexed.remove(episodeId)
                }
            },
            scheduler,
            progress,
            Mutex(),
            { resets++ },
            { completed += it.episodeId },
            { mayClaim },
            { 1.0 },
        )
    val controller =
        DownloadControllerImpl(
            db,
            FakeSettingsRepository(),
            FakeNetworkMonitor(FakeNetworkMonitor.ONLINE),
            clock,
            roots,
            scheduler,
            engine.claimMutex,
            engine::stopTransfer,
            disk,
            FakePlaybackMediaLookup(),
            { false },
        )

    suspend fun seed(
        count: Int = 1,
        transform: (DownloadEntity) -> DownloadEntity = { it },
    ): List<Long> {
        val podcast = db.podcastDao().insertPodcast(podcastEntity())
        return (1..count).map { n ->
            val id =
                db.episodeDao().insertStub(
                    episodeEntity(podcastId = podcast) {
                        copy(
                            guid = "guid-$n",
                            title = "Episode $n",
                            enclosureUrl = "https://origin.example/$n.mp3",
                            enclosureType = "audio/mpeg",
                        )
                    },
                )
            dao.insert(
                transform(
                    DownloadEntity(
                        id,
                        Lane.MANUAL,
                        State.QUEUED,
                        priority = 100,
                        requestedAt = n.toLong(),
                        sourceKind = SourceKind.RSS_ENCLOSURE,
                        sourceRef = "https://origin.example/$n.mp3",
                        rootId = "old",
                        allowMetered = true,
                    ),
                ),
            )
            id
        }
    }

    suspend fun row(id: Long) = assertNotNull(dao.byEpisode(id))

    suspend fun audit() {
        db.useWriterConnection { connection ->
            connection.usePrepared("CREATE TABLE engine_audit(state TEXT NOT NULL)") { it.step() }
            connection.usePrepared(
                "CREATE TRIGGER engine_audit_update AFTER UPDATE ON download BEGIN INSERT INTO engine_audit VALUES(new.state); END",
            ) { it.step() }
        }
    }

    suspend fun writes(): List<String> =
        db.useWriterConnection { connection ->
            connection.usePrepared("SELECT state FROM engine_audit ORDER BY rowid") { statement ->
                buildList { while (statement.step()) add(statement.getText(0)) }
            }
        }

    override fun close() {
        db.close()
        faults.fake.checkNoOpenFiles()
        faults.close()
    }
}

internal class FakeTransferSource(
    override val kind: SourceKind,
) : TransferSource {
    val starts = Channel<Long>(Channel.UNLIMITED)
    val calls = Collections.synchronizedList(mutableListOf<Long>())
    var beforePrepare: suspend (DownloadEntity, PartFile) -> Prepared? = { _, _ -> null }
    var action: suspend (TransferPlan, PartFile, ProgressSink) -> TransferResult = { plan, part, sink ->
        sink.started(TransferMetadata(plan.offset, 4096, "audio/mpeg", "\"tag\"", null))
        if (part.length() == 0L) part.append(mediaBytes(), 4096)
        sink.bytes(4096, 4096)
        TransferResult.Complete(4096, "audio/mpeg", "\"tag\"", null, MediaKind.MP3)
    }

    override suspend fun prepare(
        row: DownloadEntity,
        part: PartFile,
    ): Prepared {
        beforePrepare(row, part)?.let { return it }
        return Prepared.Go(
            TransferPlan(row.episodeId, row.sourceRef, part.length(), row.totalBytes, row.etag),
            part.rootId,
            part.path,
            row.sourceRef,
        )
    }

    override suspend fun transfer(
        plan: TransferPlan,
        part: PartFile,
        sink: ProgressSink,
    ): TransferResult {
        calls += plan.episodeId
        starts.send(plan.episodeId)
        return action(plan, part, sink)
    }
}

internal class TestDrainExit : DrainExit {
    val live = mutableSetOf<String>()
    val outcomes = mutableListOf<DrainOutcome>()
    var close: suspend () -> Boolean = { true }

    override suspend fun register(
        token: RunnerToken,
        lanes: Set<Lane>,
    ) {
        check(live.add(token.value))
    }

    override suspend fun tryClose(
        token: RunnerToken,
        outcome: DrainOutcome,
    ): Boolean {
        outcomes += outcome
        return close()
    }

    override suspend fun unregister(token: RunnerToken) {
        check(live.remove(token.value))
    }
}

internal fun drainRequest(
    token: String = "desk:test",
    lanes: Set<Lane> = setOf(Lane.MANUAL),
    deadline: Long? = null,
    horizon: Long = 0,
    exit: TestDrainExit = TestDrainExit(),
    reporter: DrainReporter =
        object : DrainReporter {
            override fun active(episodeIds: Set<Long>) = Unit

            override fun bytes(
                downloaded: Long,
                total: Long?,
            ) = Unit
        },
) = DrainRequest(lanes, RunnerToken(token), deadline, horizon, reporter, exit)

internal fun engineTest(block: suspend EngineRig.() -> Unit) = runTest { EngineRig(this).use { it.block() } }

/** Cancels after SQLite commits, before Room can deliver the successful result to the caller. */
internal class CancelOnCommitDriver(
    private val target: State,
    private val delegate: SQLiteDriver = TestDb.bundledDriver(),
) : SQLiteDriver by delegate {
    val cancelled = AtomicBoolean(false)
    var onCommit: () -> Unit = {}

    override fun open(fileName: String): SQLiteConnection {
        val connection = delegate.open(fileName)
        var targeted = false
        return object : SQLiteConnection by connection {
            override fun prepare(sql: String): SQLiteStatement {
                val statement = connection.prepare(sql)
                val update = sql.startsWith("UPDATE") && sql.contains("download")
                var matches = update && sql.contains("'${target.name}'")
                return object : SQLiteStatement by statement {
                    override fun bindText(
                        index: Int,
                        value: String,
                    ) {
                        if (update && value == target.name) matches = true
                        statement.bindText(index, value)
                    }

                    override fun step(): Boolean {
                        val result = statement.step()
                        if (matches) targeted = true
                        val command = sql.trim().substringBefore(' ').uppercase()
                        if (targeted && command in setOf("END", "COMMIT") && cancelled.compareAndSet(false, true)) {
                            onCommit()
                        }
                        return result
                    }
                }
            }
        }
    }
}
