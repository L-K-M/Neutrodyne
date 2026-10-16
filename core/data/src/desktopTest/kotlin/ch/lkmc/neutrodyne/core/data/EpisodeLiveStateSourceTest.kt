// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.withWriteTransaction
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import ch.lkmc.neutrodyne.core.data.repo.EpisodeLiveStateSourceImpl
import ch.lkmc.neutrodyne.core.database.EpisodePositionEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SqliteDriverBindings
import ch.lkmc.neutrodyne.core.model.DownloadError
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.RowLive
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.downloadEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * `EpisodeLiveStateSourceImpl` (08 Live row state) over a real bundled-driver Room database: the
 * three `IN (:ids)` flows of 02, their 200-id chunking, the persisted merge rules and the
 * first-immediate / 100 ms-debounce / conflate pipeline. The injected default dispatcher is a
 * [StandardTestDispatcher] shared with Room, so queries, debounce and back-pressure are controlled
 * by the test scheduler while SQL still executes against the bundled driver.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeLiveStateSourceTest {
    private fun source(
        db: NeutrodyneDatabase,
        scope: TestScope,
    ) = EpisodeLiveStateSourceImpl(db, StandardTestDispatcher(scope.testScheduler))

    /** Every persisted field lands, and the documented precedence applies (08 merge rules). */
    @Test
    fun mergesPersistedRowsWithFieldPrecedence() =
        runTest(timeout = 30.seconds) {
            val db = newDb(queryContext = StandardTestDispatcher(testScheduler))
            try {
                val podcast = db.podcastDao().insertPodcast(podcastEntity())
                val episode = insertEpisode(db, podcast, "g:merge")
                db.positionDao().upsert(positionEntity(episode, positionMs = 12_000, durationMs = 90_000))
                db.episodeStateDao().upsert(episodeStateEntity(episode) { copy(measuredDurationMs = 60_000) })
                db.downloadDao().insert(
                    downloadEntity(episode, state = DownloadState.QUEUED).copy(
                        waitReason = WaitReason.BACKOFF,
                        downloadedBytes = 120,
                        totalBytes = null,
                        estimatedBytes = 5_000,
                        nextAttemptAt = 9_999,
                        lastError = DownloadError.NETWORK_IO,
                    ),
                )

                val map = source(db, this).observe(flowOf(setOf(episode))).first()

                assertEquals(
                    mapOf(
                        episode to
                            RowLive(
                                positionMs = 12_000,
                                // episode_position.durationMs wins over measuredDurationMs.
                                durationMs = 90_000,
                                downloadState = DownloadState.QUEUED,
                                downloadedBytes = 120,
                                // totalBytes falls back to estimatedBytes.
                                totalBytes = 5_000,
                                waitReason = WaitReason.BACKOFF,
                                nextAttemptAt = 9_999,
                                lastError = DownloadError.NETWORK_IO,
                            ),
                    ),
                    map,
                )

                val knownTotal = insertEpisode(db, podcast, "g:known-total")
                db.downloadDao().insert(
                    downloadEntity(knownTotal).copy(totalBytes = 2_500, estimatedBytes = 5_000),
                )
                val known = source(db, this).observe(flowOf(setOf(knownTotal))).first()
                assertEquals(2_500L, known.getValue(knownTotal).totalBytes)
            } finally {
                db.close()
            }
        }

    /** `measuredDurationMs` is only a fallback: it fills when the position row has no duration. */
    @Test
    fun durationFallsBackToMeasuredDuration() =
        runTest(timeout = 30.seconds) {
            val db = newDb(queryContext = StandardTestDispatcher(testScheduler))
            try {
                val podcast = db.podcastDao().insertPodcast(podcastEntity())
                val withNullDuration = insertEpisode(db, podcast, "g:fallback")
                val stateOnly = insertEpisode(db, podcast, "g:state-only")
                db.positionDao().upsert(positionEntity(withNullDuration, positionMs = 8_000, durationMs = null))
                db
                    .episodeStateDao()
                    .upsert(episodeStateEntity(withNullDuration) { copy(measuredDurationMs = 60_000) })
                db.episodeStateDao().upsert(episodeStateEntity(stateOnly) { copy(measuredDurationMs = 33_000) })

                val map = source(db, this).observe(flowOf(setOf(withNullDuration, stateOnly))).first()

                assertEquals(60_000L, map.getValue(withNullDuration).durationMs)
                assertEquals(8_000L, map.getValue(withNullDuration).positionMs)
                assertEquals(RowLive(durationMs = 33_000), map.getValue(stateOnly))
            } finally {
                db.close()
            }
        }

    /** An id without live data gets no entry; played/favourite/started stay paged fields (08). */
    @Test
    fun absentAndPagedOnlyRowsProduceNoEntries() =
        runTest(timeout = 30.seconds) {
            val db = newDb(queryContext = StandardTestDispatcher(testScheduler))
            try {
                val podcast = db.podcastDao().insertPodcast(podcastEntity())
                val noRows = insertEpisode(db, podcast, "g:none")
                val pagedOnly = insertEpisode(db, podcast, "g:paged")
                db
                    .episodeStateDao()
                    .upsert(
                        episodeStateEntity(pagedOnly, playedAt = 5) {
                            copy(startedAt = 2, isFavorite = true)
                        },
                    )

                val map = source(db, this).observe(flowOf(setOf(noRows, pagedOnly))).first()

                assertEquals(emptyMap(), map)
            } finally {
                db.close()
            }
        }

    /** The empty id set short-circuits before any table subscription (08). */
    @Test
    fun emptyIdSetEmitsEmptyMapImmediately() =
        runTest(timeout = 30.seconds) {
            val db = newDb(queryContext = StandardTestDispatcher(testScheduler))
            try {
                db.close()
                val source = source(db, this)
                val map = source.observe(flowOf(emptySet())).first()
                assertEquals(emptyMap(), map)
                assertEquals(0L, testScheduler.currentTime, "empty set never waits for a debounce")
            } finally {
                db.close()
            }
        }

    /** > 200 ids split into several `IN` chunks whose results merge back into one map (02). */
    @Test
    fun idsAboveTheChunkLimitUseBoundedQueriesForEveryTable() =
        runTest(timeout = 30.seconds) {
            val driver = InQueryDriver()
            val db = TestDb.inMemory(driver = driver, queryContext = StandardTestDispatcher(testScheduler))
            try {
                val podcast = db.podcastDao().insertPodcast(podcastEntity())
                val ids =
                    db
                        .ingestDao()
                        .insertEpisodes(
                            (1..450).map { episodeEntity(podcastId = podcast, identityKey = "ep-$it") },
                        )
                db.withWriteTransaction {
                    ids.forEach { db.positionDao().upsert(positionEntity(it, positionMs = it)) }
                }

                val map = source(db, this).observe(flowOf(ids.toSet())).first()

                assertEquals(450, map.size)
                for (table in listOf("episode_position", "download", "episode_state")) {
                    val sizes = driver.queries.filter { (sql, _) -> "FROM $table " in sql }.map { it.second }
                    assertEquals(listOf(50, 200, 200), sizes.sorted(), table)
                }
                assertEquals(ids.first(), map.getValue(ids.first()).positionMs)
                assertEquals(ids.last(), map.getValue(ids.last()).positionMs)
            } finally {
                db.close()
            }
        }

    /**
     * The first visible set passes at once (a list's first frame is not delayed); later sets
     * debounce by 100 ms and a burst inside the window emits only the latest (08).
     */
    @Test
    fun firstSetIsImmediateLaterSetsDebounceAndCoalesce() =
        runTest(timeout = 30.seconds) {
            val db = newDb(queryContext = StandardTestDispatcher(testScheduler))
            try {
                val podcast = db.podcastDao().insertPodcast(podcastEntity())
                val e1 = insertEpisode(db, podcast, "g:e1")
                val e2 = insertEpisode(db, podcast, "g:e2")
                val e3 = insertEpisode(db, podcast, "g:e3")
                db.positionDao().upsert(positionEntity(e1, positionMs = 1))
                db.positionDao().upsert(positionEntity(e2, positionMs = 2))
                db.positionDao().upsert(positionEntity(e3, positionMs = 3))

                // A back-to-back second set: had the first been debounced it would be coalesced
                // away, so receiving its map proves the first set passes immediately.
                assertEquals(
                    mapOf(e1 to RowLive(positionMs = 1)),
                    source(db, this)
                        .observe(flowOf(setOf(e1), setOf(e2)))
                        .first(),
                )

                val ids = MutableStateFlow(setOf(e1))
                val emissions = Channel<Map<Long, RowLive>>(Channel.UNLIMITED)
                val job =
                    backgroundScope.launch { source(db, this@runTest).observe(ids).collect { emissions.send(it) } }
                try {
                    testScheduler.runCurrent()
                    assertEquals(mapOf(e1 to RowLive(positionMs = 1)), emissions.receive())
                    assertEquals(0L, testScheduler.currentTime, "the first set is not debounced")

                    ids.value = setOf(e2)
                    testScheduler.runCurrent()
                    testScheduler.advanceTimeBy(99)
                    assertTrue(emissions.tryReceive().isFailure, "second set debounces by 100 ms")

                    // A burst inside the window: e3's set replaces e2's pending emission.
                    ids.value = setOf(e3)
                    testScheduler.runCurrent()
                    testScheduler.advanceTimeBy(100)
                    assertEquals(mapOf(e3 to RowLive(positionMs = 3)), emissions.receive())
                    assertTrue(emissions.tryReceive().isFailure, "only the latest set survives")
                } finally {
                    job.cancel()
                }
            } finally {
                db.close()
            }
        }

    /** A write that changes nothing produces no re-emission (`distinctUntilChanged`). */
    @Test
    fun identicalRowsDoNotReemit() =
        runTest(timeout = 30.seconds) {
            val db = newDb(queryContext = StandardTestDispatcher(testScheduler))
            try {
                val podcast = db.podcastDao().insertPodcast(podcastEntity())
                val episode = insertEpisode(db, podcast, "g:dup")
                db.positionDao().upsert(positionEntity(episode, positionMs = 200))

                val emissions = Channel<Map<Long, RowLive>>(Channel.UNLIMITED)
                val job =
                    backgroundScope.launch {
                        source(db, this@runTest).observe(MutableStateFlow(setOf(episode))).collect {
                            emissions.send(it)
                        }
                    }
                try {
                    emissions.receive()
                    // Room invalidates and re-queries on the write; the merged map is unchanged.
                    db.positionDao().upsert(positionEntity(episode, positionMs = 200))
                    testScheduler.runCurrent()
                    assertTrue(emissions.tryReceive().isFailure)
                } finally {
                    job.cancel()
                }
            } finally {
                db.close()
            }
        }

    /** Under back-pressure the conflated output keeps only the newest burst result (08). */
    @Test
    fun slowCollectorReceivesOnlyTheNewestOfABurst() =
        runTest(timeout = 30.seconds) {
            val db = newDb(queryContext = StandardTestDispatcher(testScheduler))
            try {
                val podcast = db.podcastDao().insertPodcast(podcastEntity())
                val episode = insertEpisode(db, podcast, "g:burst")
                db.positionDao().upsert(positionEntity(episode, positionMs = 0))

                val emissions = Channel<Map<Long, RowLive>>(Channel.UNLIMITED)
                val release = Channel<Unit>(Channel.UNLIMITED)
                val job =
                    backgroundScope.launch {
                        source(db, this@runTest).observe(MutableStateFlow(setOf(episode))).collect {
                            emissions.send(it)
                            release.receive()
                        }
                    }
                try {
                    assertEquals(0L, emissions.receive().getValue(episode).positionMs)
                    for (position in listOf(100L, 200L, 300L)) {
                        db.positionDao().upsert(positionEntity(episode, positionMs = position))
                        testScheduler.runCurrent()
                    }
                    release.send(Unit)
                    assertEquals(300L, emissions.receive().getValue(episode).positionMs)
                    assertTrue(emissions.tryReceive().isFailure)
                } finally {
                    job.cancel()
                }
            } finally {
                db.close()
            }
        }

    private suspend fun insertEpisode(
        db: NeutrodyneDatabase,
        podcastId: Long,
        key: String,
    ): Long =
        db
            .ingestDao()
            .insertEpisodes(
                listOf(episodeEntity(podcastId = podcastId, identityKey = key, title = key)),
            ).single()

    private fun positionEntity(
        episodeId: Long,
        positionMs: Long,
        durationMs: Long? = null,
    ) = EpisodePositionEntity(
        episodeId = episodeId,
        positionMs = positionMs,
        durationMs = durationMs,
        positionSource = PositionSource.STREAM,
        updatedAt = 0,
    )

    /** Records the real Room query placeholders, so a single oversized IN query fails the test. */
    private class InQueryDriver(
        private val delegate: SQLiteDriver = SqliteDriverBindings.sqliteDriver(),
    ) : SQLiteDriver by delegate {
        val queries = CopyOnWriteArrayList<Pair<String, Int>>()

        override fun open(fileName: String): SQLiteConnection {
            val connection = delegate.open(fileName)
            return object : SQLiteConnection by connection {
                override fun prepare(sql: String): SQLiteStatement {
                    val statement = connection.prepare(sql)
                    if (!sql.startsWith("SELECT ") || "WHERE episodeId IN (" !in sql) return statement
                    return object : SQLiteStatement by statement {
                        private var started = false

                        override fun step(): Boolean {
                            if (!started) {
                                queries += sql to sql.count { it == '?' }
                                started = true
                            }
                            return statement.step()
                        }

                        override fun reset() {
                            statement.reset()
                            started = false
                        }
                    }
                }
            }
        }
    }
}
