// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.datastore.SettingStore
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.SourceKind
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.model.settings.SettingsFile
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.download.api.OrphanDeleteResult
import ch.lkmc.neutrodyne.download.api.RootChangeMode
import ch.lkmc.neutrodyne.download.api.RootChangeResult
import ch.lkmc.neutrodyne.download.api.ShareableFile
import ch.lkmc.neutrodyne.download.api.StorageRootInfo
import ch.lkmc.neutrodyne.download.api.StorageUsage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UidtSlotBlockedDrainTest {
    private val context = RuntimeEnvironment.getApplication()
    private val clock = TestClock()
    private val registry = LaneRegistry()
    private val starts = mutableListOf<Long>()
    private var queries = 0

    @Test fun slotBlockedUidtCloseWaitsForEngineChangesWithoutSpinning() =
        runTest {
            val db =
                TestDb.inMemory(
                    context,
                    clock = clock,
                    queryContext = StandardTestDispatcher(testScheduler),
                )
            val dao = db.downloadDao()
            val podcast = db.podcastDao().insertPodcast(podcastEntity())
            val ids =
                listOf("a.example", "b.example", "c.example", "d.example").map { host ->
                    val id =
                        db.episodeDao().insertStub(
                            episodeEntity(podcastId = podcast) { copy(guid = "g-$host") },
                        )
                    dao.insert(
                        DownloadEntity(
                            id,
                            DownloadLane.MANUAL,
                            DownloadState.QUEUED,
                            priority = 100,
                            requestedAt = id,
                            sourceKind = SourceKind.RSS_ENCLOSURE,
                            sourceRef = "https://$host/$id.mp3",
                            rootId = "old",
                            allowMetered = true,
                        ),
                    )
                    id
                }
            val source =
                object : TransferSource {
                    override val kind = SourceKind.RSS_ENCLOSURE

                    override suspend fun prepare(
                        row: DownloadEntity,
                        part: PartFile,
                    ) = Prepared.Go(
                        TransferPlan(row.episodeId, row.sourceRef, 0, row.totalBytes, row.etag),
                        row.rootId,
                        part.path,
                        row.sourceRef,
                    )

                    override suspend fun transfer(
                        plan: TransferPlan,
                        part: PartFile,
                        sink: ProgressSink,
                    ): TransferResult {
                        starts += plan.episodeId
                        awaitCancellation()
                    }
                }
            val dir = context.cacheDir.absolutePath + "/uidt-test"
            val roots =
                object : StorageRoots {
                    override suspend fun current() = "old"

                    override suspend fun list(): List<StorageRootInfo> = emptyList()

                    override suspend fun resolve(rootId: String) =
                        if (rootId == "old") ResolvedRoot(rootId, dir) else null

                    override suspend fun ensureSpace(
                        rootId: String,
                        requiredBytes: Long,
                    ): StorageFailure? = null

                    override suspend fun classify(
                        error: Throwable,
                        rootId: String,
                    ) = StorageFailure.IO
                }
            val files = OkioDownloadFileSystem(roots)
            val deletes = DeferredDeletes(MemoryStore(), files, roots, dao::allRows)
            lateinit var engine: DownloadEngine
            val scheduler =
                AndroidDownloadScheduler(
                    rows = {
                        // A rejected close that re-queries without waiting grows this bound.
                        check(++queries <= 8) { "scheduler polled the database $queries times" }
                        dao.allRows()
                    },
                    markForeground = { ids, reason -> dao.markWait(ids, reason, null) },
                    conditions = { Conditions(true, true, true, false, true) },
                    visible = { true },
                    backgroundLimited = { false },
                    scheduleUidt = { true },
                    enqueue = { _, _ -> },
                    armWake = {},
                    runtimeWake = { engine.wake(it) },
                    waitingNotice = {},
                    clock = clock,
                    registry = registry,
                    sdk = 34,
                )
            engine =
                DownloadEngine(
                    db,
                    clock,
                    CredentialLookup.None,
                    MutableStateFlow(Conditions(true, true, true, true, true)),
                    setOf(source),
                    files,
                    roots,
                    DownloadFiles(
                        files,
                        roots,
                        PlatformSriHasher(roots, FileSystem.SYSTEM),
                        PlatformLocalUris(),
                        deletes,
                    ),
                    object : DownloadControllerFiles {
                        override suspend fun prepareRemoval(rows: List<DownloadEntity>) = Unit

                        override suspend fun finishRemoval(rows: List<DownloadEntity>) = Unit

                        override suspend fun reportFileMissing(episodeId: Long) = Unit

                        override fun observeStorage() =
                            MutableStateFlow(StorageUsage("old", 0, 0, 0, null, null, false))

                        override suspend fun changeRoot(
                            rootId: String,
                            mode: RootChangeMode,
                        ) = RootChangeResult.Unsupported

                        override suspend fun deleteOrphanFiles() = OrphanDeleteResult.Unavailable

                        override suspend fun shareableFile(episodeId: Long): ShareableFile? = null
                    },
                    object : LocalMediaIndexStore {
                        override val changes = MutableSharedFlow<Set<Long>>()

                        override fun localUriOrNull(episodeId: Long): String? = null

                        override suspend fun replace(snapshot: Map<Long, String>) = Unit

                        override suspend fun put(
                            episodeId: Long,
                            uri: String,
                        ) = Unit

                        override suspend fun remove(episodeId: Long) = Unit
                    },
                    scheduler,
                    DownloadProgressHub(backgroundScope, clock),
                    Mutex(),
                    {},
                    {},
                )
            val reporter =
                object : DrainReporter {
                    override fun active(episodeIds: Set<Long>) = Unit

                    override fun bytes(
                        downloaded: Long,
                        total: Long?,
                    ) = Unit
                }
            val worker =
                async {
                    engine.drain(
                        DrainRequest(
                            setOf(DownloadLane.MANUAL),
                            RunnerToken("work:test"),
                            null,
                            60_000,
                            reporter,
                            scheduler.exit(),
                        ),
                    )
                }
            runCurrent()
            val held = starts.toSet()
            assertEquals(3, held.size)
            val uidtToken = RunnerToken("uidt:test")
            registry.uidtStarted(uidtToken)
            val uidt =
                async {
                    engine.drain(
                        DrainRequest(
                            setOf(DownloadLane.MANUAL),
                            uidtToken,
                            null,
                            300_000,
                            reporter,
                            scheduler.exit(),
                        ),
                    )
                }
            runCurrent()
            assertTrue(queries <= 4, "UIDT close polled $queries times while slots were held")
            engine.stopTransfer(held.first(), StopIntent.Pause)
            runCurrent()
            assertEquals((ids.toSet() - held).single(), starts.last())
            uidt.cancelAndJoin()
            worker.cancelAndJoin()
            db.close()
        }

    private class MemoryStore : SettingStore {
        private val values = MutableStateFlow<Map<String, Any>>(emptyMap())
        override val file = SettingsFile.DEVICE

        override fun <T : Any> observe(key: SettingKey<T>): Flow<T> = values.map { (it[key.name] as? T) ?: key.default }

        override suspend fun <T : Any> get(key: SettingKey<T>): T = (values.value[key.name] as? T) ?: key.default

        override suspend fun <T : Any> set(
            key: SettingKey<T>,
            value: T,
        ) {
            values.value = values.value + (key.name to value)
        }

        override suspend fun <T : Any> update(
            key: SettingKey<T>,
            transform: (T) -> T,
        ) {
            set(key, transform(get(key)))
        }

        override suspend fun reset(key: SettingKey<*>) {
            values.value = values.value - key.name
        }
    }
}
