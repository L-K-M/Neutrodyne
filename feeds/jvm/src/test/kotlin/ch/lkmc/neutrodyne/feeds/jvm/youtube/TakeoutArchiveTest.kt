// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.youtube

import ch.lkmc.neutrodyne.feeds.backup.ArchiveException
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipGuard
import ch.lkmc.neutrodyne.feeds.youtube.TakeoutSubscriptionsParser
import okio.Path.Companion.toPath
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TakeoutArchiveTest {
    @Test
    fun candidateSelectionUsesTheApplicationClassifierPredicate() {
        withZip(
            listOf(
                "unrelated.csv" to "Id,Url,Title\ninvalid,https://www.youtube.com/playlist?list=PLinvalid,Playlist",
                "subscriptions.csv" to "Id,Url,Title\n$ID,ignored,Channel",
            ),
        ) { path ->
            val doc = TakeoutSubscriptionsParser.parseZip(path, ZipGuard()) { it == ID }
            assertEquals("Channel", doc.entries.single().title)
        }
    }

    @Test
    fun localizedNestedCsvScanUsesFirstValidFileAndSkipsUnrelatedData() {
        withZip(
            listOf(
                "Archive/readme.csv" to "not,subscriptions,here",
                "Daten/日本語/ABOS.CSV" to "Kennung,Adresse,Titel\n$ID,ignored,Name\n",
                "later.csv" to "bad",
            ),
        ) { path ->
            val doc = TakeoutSubscriptionsParser.parseZip(path, ZipGuard())
            assertEquals(ID, doc.entries.single().url)
            assertEquals("Name", doc.entries.single().title)
        }
    }

    @Test
    fun tooManyCsvEntriesFailBeforeReadingAny() {
        withZip((0..50).map { "$it.csv" to "bad" }) { path ->
            val error = assertFailsWith<ArchiveException> { TakeoutSubscriptionsParser.parseZip(path, ZipGuard()) }
            assertEquals("zip_entries", error.reason)
        }
    }

    @Test
    fun inflatedCsvCapIsCountedAndNothingIsExtracted() {
        withZip(listOf("big.csv" to "x".repeat(5_000_001))) { path ->
            val error = assertFailsWith<ArchiveException> { TakeoutSubscriptionsParser.parseZip(path, ZipGuard()) }
            assertEquals("zip_bomb", error.reason)
        }
    }

    @Test
    fun hostilePathsAndCorruptArchivesRemainFailures() {
        withZip(listOf("../subscriptions.csv" to "Id,Url,Title\n$ID,url,Name")) { path ->
            assertEquals(
                ArchiveException.Kind.HOSTILE,
                assertFailsWith<ArchiveException> {
                    TakeoutSubscriptionsParser.parseZip(path, ZipGuard())
                }.kind,
            )
        }
        val file = Files.createTempFile("w5-corrupt", ".zip").toFile()
        try {
            file.writeBytes(byteArrayOf(0x50, 0x4b, 3, 4, 0))
            assertEquals(
                ArchiveException.Kind.CORRUPT,
                assertFailsWith<ArchiveException> {
                    TakeoutSubscriptionsParser.parseZip(file.absolutePath.toPath(), ZipGuard())
                }.kind,
            )
        } finally {
            file.delete()
        }
    }

    private fun withZip(
        entries: List<Pair<String, String>>,
        block: (okio.Path) -> Unit,
    ) {
        val file = Files.createTempFile("w5-takeout", ".zip").toFile()
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                for ((name, text) in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
            block(file.absolutePath.toPath())
        } finally {
            file.delete()
        }
    }

    private companion object {
        const val ID = "UCBJycsmduvYEL83R_U4JriQ"
    }
}
