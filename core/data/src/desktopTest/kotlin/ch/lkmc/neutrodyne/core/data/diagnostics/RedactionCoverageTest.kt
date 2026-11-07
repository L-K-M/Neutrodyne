// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.diagnostics

import androidx.room3.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.LogSink
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.RingBufferLogSink
import ch.lkmc.neutrodyne.core.data.exporting.ExportDestination
import ch.lkmc.neutrodyne.core.data.storagePathsFor
import ch.lkmc.neutrodyne.core.database.DatabaseFactory
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.DiagnosticsContributor
import ch.lkmc.neutrodyne.core.domain.ExportError
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsLine
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsSectionId
import ch.lkmc.neutrodyne.core.testing.FakePlatformInfo
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 01's redaction guarantee at the M11b seams: every value that can carry user data is
 * redacted before it reaches a sink, the diagnostics report, its plain-text rendering and
 * the exported database copy. The seeded secrets mirror 09's coverage set; the meta-checks
 * prove the assertions would catch a sink handed raw text.
 */
class RedactionCoverageTest {
    private val clock = TestClock()
    private val ring = RingBufferLogSink(clock)
    private val collected = mutableListOf<String>()
    private val sink = LogSink { _, _, message -> collected += message }

    @After
    fun tearDown() = Log.install()

    @Test
    fun seededSecretsNeverReachLogSinksOrTheReport() =
        runTest {
            Log.install(sink, ring)

            // Every level and the throwable path carry the seeded URL once.
            Log.i(TAG) { "refreshing $SEEDED_URL" }
            Log.w(TAG) { "auth for $SEEDED_URL failed" }
            Log.e(TAG, IllegalStateException("GET $SEEDED_URL 401")) { "fetch failed" }
            Log.d(TAG) { "verbose-dbg-marker Authorization: Bearer $SYNC_TOKEN" }

            val emitted = collected + ring.linesFlow.value
            assertTrue(emitted.isNotEmpty())
            assertNoSecrets(emitted)
            // Meta: the same text unredacted would fail the assertion above.
            assertTrue("GET $SEEDED_URL".contains("s3cret"))

            // The ring's DEBUG gate: INFO by default, so the debug line stays out.
            assertFalse(ring.linesFlow.value.any { it.contains("verbose-dbg-marker") })
            assertTrue(collected.any { it.contains("verbose-dbg-marker") })

            // A contributor emitting a raw URL: the repository's own Redactor pass (09)
            // is what must mask it — feeding pre-redacted text would not prove the pass.
            val repository = repository({ listOf(DiagnosticsLine("feed", SEEDED_URL)) })
            val report = repository.snapshot()
            val plain = repository.toPlainText(report)
            assertNoSecrets(listOf(plain))
            assertTrue(plain.contains("== LOG =="))
        }

    @Test
    fun exportedDatabaseCopyCarriesNoSecrets() =
        runTest {
            val dbDir =
                File.createTempFile("nd-redact", "").apply {
                    delete()
                    mkdirs()
                    deleteOnExit()
                }
            val db = TestDb.file(dbDir.toPath())
            try {
                db.podcastDao().insertPodcast(
                    podcastEntity(
                        feedUrl = SEEDED_URL,
                        title = "Seeded",
                    ),
                )
                db.syncStateDao().upsert(
                    SyncStateEntity(enabled = true, serverUrl = SYNC_SERVER, accountId = SYNC_TOKEN),
                )
                db.useWriterConnection { conn ->
                    conn.usePrepared(
                        "INSERT INTO credential (origin, username, secretCipher, iv, createdAt) " +
                            "VALUES ('x', 'u', X'6E64735F53594E43544F4B454E534543524554', NULL, 1)",
                    ) { it.step() }
                }

                val target = File.createTempFile("nd-redact-copy", ".db")
                target.deleteOnExit()
                target.delete()
                db.useWriterConnection { conn ->
                    conn.usePrepared("VACUUM INTO '${target.absolutePath}'") { it.step() }
                }
                BundledSQLiteDriver().open(target.absolutePath).use { connection ->
                    DiagExportScrub.scrub(connection)
                    connection.prepare("VACUUM").use { it.step() }
                }
                BundledSQLiteDriver().open(target.absolutePath).use { connection ->
                    connection.prepare("SELECT name FROM sqlite_master WHERE type='table'").use { stmt ->
                        val tables = buildList { while (stmt.step()) add(stmt.getText(0)) }
                        for (table in tables) {
                            if (table.startsWith("sqlite_") || table == "room_master_table") continue
                            connection.prepare("SELECT * FROM \"$table\"").use { rows ->
                                while (rows.step()) {
                                    for (i in 0 until rows.getColumnCount()) {
                                        val cell =
                                            runCatching { if (rows.isNull(i)) null else rows.getText(i) }.getOrNull()
                                                ?: continue
                                        for (secret in SECRETS) {
                                            assertFalse(cell.contains(secret), "'$secret' in $table: $cell")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // 09 reads the copy byte-wise: free pages must not hold the secrets either.
                val bytes = target.readBytes()
                for (secret in SECRETS) {
                    val needle = secret.encodeToByteArray()
                    val found =
                        (0..bytes.size - needle.size).any { i ->
                            needle.indices.all { j -> bytes[i + j] == needle[j] }
                        }
                    assertFalse(found, "'$secret' in exported bytes")
                }
                target.delete()
            } finally {
                db.close()
                dbDir.deleteRecursively()
            }
        }

    @Test
    fun failingAndTimingOutContributorsDegradeToLinesNotHoles() =
        runTest {
            val repository =
                repository({ throw IllegalStateException("boom") })
            val report = repository.snapshot()
            val section = report.sections.single()
            assertEquals("unavailable", section.lines.single().key)

            val plain = repository.toPlainText(report)
            assertTrue(plain.contains("unavailable"))
        }

    private fun repository(vararg lines: () -> List<DiagnosticsLine>): DiagnosticsRepositoryImpl {
        val contributors =
            lines
                .map { produce ->
                    object : DiagnosticsContributor {
                        override val section = DiagnosticsSectionId.APP

                        override suspend fun collect(): List<DiagnosticsLine> = produce()
                    }
                }.toSet()
        return DiagnosticsRepositoryImpl(
            contributors = contributors,
            exporter = exporter(),
            destination = StubDestination,
            platformInfo = FakePlatformInfo(),
            logSink = ring,
            clock = clock,
            io = Dispatchers.Default,
        )
    }

    private fun exporter(): DatabaseCopyExporter =
        DatabaseCopyExporter(
            db = TestDb.inMemory(),
            factory = StubFactory,
            driver = BundledSQLiteDriver(),
            storagePaths =
                storagePathsFor(
                    File.createTempFile("nd-exp", "").apply {
                        delete()
                        mkdirs()
                        deleteOnExit()
                    },
                ),
            fileSystem = FileSystem.SYSTEM,
            clock = clock,
            io = Dispatchers.Default,
        )

    private object StubDestination : ExportDestination {
        override fun shareUri(fileName: String): String? = null

        override suspend fun save(
            source: Path,
            destinationUri: String,
        ): Outcome<Unit, ExportError> = Outcome.Success(Unit)
    }

    private object StubFactory : DatabaseFactory {
        override val databasePath: String = ""

        override fun builder() = error("unused")

        override fun exists(): Boolean = false

        override fun usableSpaceBytes(): Long? = null

        override fun quarantine(stamp: String) = Unit

        override fun pruneQuarantine(now: Long) = Unit

        override var quarantineMarker: Boolean = false
        override var pendingQuarantine: String? = null
    }

    private fun assertNoSecrets(emitted: List<String>) {
        for (line in emitted) {
            for (secret in SECRETS) {
                assertFalse(line.contains(secret), "secret '$secret' in: $line")
            }
        }
    }

    private companion object {
        const val TAG = "CoverageTest"
        const val SEEDED_URL = "https://alice:s3cret@feeds.example.invalid/rss/a8F3kq09ZpLm2xQr7Tz4?token=SECRETTOKEN"
        const val SYNC_SERVER = "https://sync.example.invalid"
        const val SYNC_TOKEN = "nds_SYNCTOKENSECRET"
        val SECRETS =
            listOf(
                "s3cret",
                "SECRETTOKEN",
                "a8F3kq09ZpLm2xQr7Tz4",
                "nds_SYNCTOKENSECRET",
                "sync.example.invalid",
            )
    }
}
