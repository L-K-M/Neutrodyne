// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.feeds.backup.ArchiveCaps
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipArchiveWriter
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipGuard
import ch.lkmc.neutrodyne.feeds.jvm.opml.XmlPullOpmlReader
import ch.lkmc.neutrodyne.feeds.opml.OpmlReadResult
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.buffer
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The M3 C3a bindings of [DesktopDataBindings]: `OpmlReader` (kxml2 through
 * `PullParserFactory.Discovered` — proves the runtime parser is present wherever this module's
 * graph is bound), the `ZipGuard`/`ZipArchiveWriter` archive pair and D34's no-op
 * `SnapshotScheduler`. Metro proves the bindings exist at compile time; these prove the bound
 * implementations actually work against real files.
 */
class DesktopDataBindingsTest {
    @Test
    fun theBoundOpmlReaderParsesARealOpmlFixture() {
        val reader = DesktopDataBindings.opmlReader(DesktopDataBindings.pullParserFactory())
        assertIs<XmlPullOpmlReader>(reader)

        val fixture = opmlFixture("antennapod_flat_atom")
        val result = reader.read({ FileSystem.SYSTEM.source(fixture.toPath().toOkioPath()) })

        val document = assertIs<OpmlReadResult.Ok>(result).document
        assertTrue(document.entries.isNotEmpty())
    }

    @Test
    fun theBoundArchivePairRoundTripsARealZip() {
        val dir = createTempDirectory("nd-archive-bindings").toFile()
        try {
            val zip = dir.resolve("roundtrip.zip").toPath().toOkioPath()
            DesktopDataBindings.archiveWriterFactory().create(zip).use { writer ->
                writer.entry("manifest.json").buffer().use { it.writeUtf8("{}") }
                writer.entry("subscriptions.opml").buffer().use { it.writeUtf8("<opml/>") }
            }

            val guard = DesktopDataBindings.archiveReaderFactory()
            assertIs<ZipGuard>(guard)
            assertIs<ZipArchiveWriter>(DesktopDataBindings.archiveWriterFactory())

            guard.open(zip, ArchiveCaps.BACKUP).use { archive ->
                assertEquals(listOf("manifest.json", "subscriptions.opml"), archive.entryNames)
                assertEquals("{}", archive.open("manifest.json").buffer().readUtf8())
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theBoundSnapshotSchedulerIsTheDocumentedNoOp() =
        runTest {
            // D34: desktop has no Auto Backup — `requestSoon` must simply complete.
            DesktopDataBindings.snapshotScheduler().requestSoon()
        }
}
