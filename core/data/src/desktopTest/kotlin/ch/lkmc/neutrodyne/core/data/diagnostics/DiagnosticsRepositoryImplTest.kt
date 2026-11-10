// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.diagnostics

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.common.RingBufferLogSink
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.data.exporting.ExportDestination
import ch.lkmc.neutrodyne.core.database.DatabaseFactory
import ch.lkmc.neutrodyne.core.domain.DiagnosticsContributor
import ch.lkmc.neutrodyne.core.domain.DiagnosticsError
import ch.lkmc.neutrodyne.core.domain.ExportError
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsLine
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsReport
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsSection
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsSectionId
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `DiagnosticsRepositoryImpl` — the plain-text budget and the export handoff. */
class DiagnosticsRepositoryImplTest {
    private val temp =
        Files
            .createTempDirectory("nd-diag-repo")
            .toFile()
            .apply { deleteOnExit() }
            .toPath()

    private class FakeDestination(
        var share: String? = "content://share",
    ) : ExportDestination {
        override fun shareUri(fileName: String): String? = share

        override suspend fun save(
            source: Path,
            destinationUri: String,
        ): Outcome<Unit, ExportError> = Outcome.Success(Unit)
    }

    private class FakePlatform(
        override val kind: PlatformKind,
    ) : PlatformInfo {
        override val userAgentPlatform: String = "test"

        override val androidSdkInt: Int? = null

        override val regionCode: String = "DE"
    }

    private class FakeFactory(
        override val databasePath: String,
        private val usable: Long?,
    ) : DatabaseFactory {
        override fun builder() = throw UnsupportedOperationException()

        override fun exists() = throw UnsupportedOperationException()

        override fun usableSpaceBytes(): Long? = usable

        override fun quarantine(stamp: String) = throw UnsupportedOperationException()

        override fun pruneQuarantine(now: Long) = throw UnsupportedOperationException()

        override var quarantineMarker: Boolean
            get() = throw UnsupportedOperationException()
            set(_) = throw UnsupportedOperationException()

        override var pendingQuarantine: String?
            get() = throw UnsupportedOperationException()
            set(_) = throw UnsupportedOperationException()
    }

    private fun repository(
        platform: PlatformInfo,
        destination: FakeDestination = FakeDestination(),
        factory: DatabaseFactory = FakeFactory(databasePath = "unused.db", usable = null),
        contributors: Set<DiagnosticsContributor> = emptySet(),
    ): DiagnosticsRepositoryImpl {
        val dirs =
            AppDirs(
                data = temp,
                config = temp,
                cache = temp,
                state = temp,
                logs = temp,
                downloadsDefault = temp,
            )
        val exporter =
            DatabaseCopyExporter(
                TestDb.inMemory(),
                factory,
                BundledSQLiteDriver(),
                StoragePaths(dirs),
                FileSystem.SYSTEM,
                TestClock(),
                Dispatchers.Default,
            )
        return DiagnosticsRepositoryImpl(
            contributors = contributors,
            exporter = exporter,
            destination = destination,
            platformInfo = platform,
            logSink = RingBufferLogSink(TestClock()),
            clock = TestClock(),
            io = Dispatchers.Default,
        )
    }

    @Test
    fun sameSectionContributorsProduceOneDeterministicCard() =
        runTest {
            val first = contributor(listOf(DiagnosticsLine("enabled", "true")))
            val second =
                contributor(listOf(DiagnosticsLine("heldChanges", "2"), DiagnosticsLine("cursorPrefix", "12345678")))
            val forward =
                repository(
                    FakePlatform(PlatformKind.DESKTOP),
                    contributors = linkedSetOf(first, second),
                ).snapshot()
            val reverse =
                repository(
                    FakePlatform(PlatformKind.DESKTOP),
                    contributors = linkedSetOf(second, first),
                ).snapshot()
            assertEquals(1, forward.sections.size)
            assertEquals(forward.sections, reverse.sections)
            assertEquals(
                listOf("cursorPrefix", "enabled", "heldChanges"),
                forward.sections
                    .single()
                    .lines
                    .map { it.key },
            )
        }

    @Test
    fun aFailedRuntimeContributorKeepsTheDatabaseSubsetAndRedaction() =
        runTest {
            val database = contributor(listOf(DiagnosticsLine("enabled", "true")))
            val runtime =
                object : DiagnosticsContributor {
                    override val section = DiagnosticsSectionId.SYNC

                    override suspend fun collect(): List<DiagnosticsLine> =
                        error("never export https://private.example/?token=abc")
                }
            val text =
                contributor(listOf(DiagnosticsLine("deviceName", "https://alice:secret@example.org/path?token=abc")))
            val report =
                repository(
                    FakePlatform(PlatformKind.DESKTOP),
                    contributors = linkedSetOf(database, runtime, text),
                ).snapshot()
            assertEquals(1, report.sections.size)
            val lines = report.sections.single().lines
            assertTrue(DiagnosticsLine("enabled", "true") in lines)
            assertTrue(lines.any { it.key == "unavailable" && it.value == "IllegalStateException" })
            assertTrue(lines.none { it.value.contains("secret") || it.value.contains("token=abc") })
        }

    private fun contributor(lines: List<DiagnosticsLine>): DiagnosticsContributor =
        object : DiagnosticsContributor {
            override val section = DiagnosticsSectionId.SYNC

            override suspend fun collect(): List<DiagnosticsLine> = lines
        }

    @Test
    fun theByteBudgetCountsEncodedBytesNotUtf16Chars() {
        // 'é' is 1 UTF-16 char but 2 bytes: a char-counted budget keeps too many log lines.
        val report =
            DiagnosticsReport(
                generatedAt = 0,
                sections =
                    listOf(
                        DiagnosticsSection(
                            DiagnosticsSectionId.DATABASE,
                            listOf(DiagnosticsLine("k", "é".repeat(100))),
                        ),
                        DiagnosticsSection(
                            DiagnosticsSectionId.LOG,
                            listOf(
                                DiagnosticsLine("", "a".repeat(50)),
                                DiagnosticsLine("", "b".repeat(20)),
                            ),
                        ),
                    ),
            )
        val text = repository(FakePlatform(PlatformKind.DESKTOP)).toPlainText(report, maxBytes = 250)
        assertTrue(
            text.encodeToByteArray().size <= 250,
            "rendered report exceeds the byte budget: ${text.encodeToByteArray().size}",
        )
    }

    @Test
    fun androidWithoutAShareUriFailsInsteadOfReturningACachePath() =
        runTest {
            val dbFile = temp.resolve("neutrodyne.db")
            Files.write(dbFile, byteArrayOf(1, 2, 3))
            val repo =
                repository(
                    FakePlatform(PlatformKind.ANDROID),
                    destination = FakeDestination(share = null),
                    factory = FakeFactory(databasePath = dbFile.toString(), usable = Long.MAX_VALUE),
                )

            // A null FileProvider URI left Success holding the private cache path —
            // the screen would "share" a filesystem path no other app can read.
            assertEquals(
                Outcome.Failure(DiagnosticsError.FAILED),
                repo.exportDatabaseCopy(null),
            )
        }
}
