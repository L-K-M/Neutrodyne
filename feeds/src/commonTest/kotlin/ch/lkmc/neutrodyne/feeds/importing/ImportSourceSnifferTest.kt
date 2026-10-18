// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feeds.importing

import ch.lkmc.neutrodyne.feeds.backup.ArchiveCaps
import ch.lkmc.neutrodyne.feeds.backup.ArchiveException
import ch.lkmc.neutrodyne.feeds.backup.ArchiveReader
import ch.lkmc.neutrodyne.feeds.backup.ArchiveReaderFactory
import okio.Buffer
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import okio.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ImportSourceSnifferTest {
    private val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val backupManifest = """{"format":"neutrodyne-backup"}""".encodeToByteArray()

    private fun sniffText(text: String) = sniff(text.encodeToByteArray())

    private fun sniff(
        bytes: ByteArray,
        archives: ArchiveReaderFactory? = null,
    ) = ImportSourceSniffer.sniff({ Buffer().write(bytes) }, "payload".toPath(), archives)

    private fun recognized(result: SniffResult) = assertIs<SniffResult.Recognized>(result).format

    @Test
    fun opmlIsRecognizedFromRootsCommentsAndPrologs() {
        assertEquals(SniffedFormat.OPML, recognized(sniffText("<opml version=\"2.0\">")))
        assertEquals(SniffedFormat.OPML, recognized(sniffText("<?xml version=\"1.0\"?>\n<opml>")))
        assertEquals(SniffedFormat.OPML, recognized(sniffText("<!-- hi --><opml>")))
        assertEquals(SniffedFormat.OPML, recognized(sniffText("<!DOCTYPE opml><opml>")))
        // No opml root but outlines present: the salvage row still routes to the OPML reader.
        assertEquals(SniffedFormat.OPML, recognized(sniffText("<body><outline text=\"x\"/></body>")))
    }

    @Test
    fun xmlWhitespaceAndEncodings() {
        assertEquals(SniffedFormat.OPML, recognized(sniffText("   \n\t<opml>")))
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "<opml>".encodeToByteArray()
        assertEquals(SniffedFormat.OPML, recognized(sniff(utf8Bom)))
        val le = "<opml>".toByteArray(Charsets.UTF_16LE)
        val be = "<opml>".toByteArray(Charsets.UTF_16BE)
        assertEquals(SniffedFormat.OPML, recognized(sniff(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + le)))
        assertEquals(SniffedFormat.OPML, recognized(sniff(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + be)))
        // BOM-less UTF-16 is spotted by its leading-byte pair.
        assertEquals(SniffedFormat.OPML, recognized(sniff(le)))
        assertEquals(SniffedFormat.OPML, recognized(sniff(be)))
    }

    @Test
    fun utf16OnlyYieldsXml() {
        // A BOM'd UTF-16 file that is not XML is rejected.
        val bomLe = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        assertUnsupported("unknown", sniff(bomLe + "{}".toByteArray(Charsets.UTF_16LE)))
        assertUnsupported("unknown", sniff(bomLe + "hello".toByteArray(Charsets.UTF_16LE)))
        // Without a BOM only the '<' byte pair implies UTF-16: a BOM-less UTF-16 '{' file
        // reads as UTF-8 and lands on the JSON row's "unsupported" instead.
        assertUnsupported("json", sniff("{}".toByteArray(Charsets.UTF_16LE)))
    }

    @Test
    fun singleFeedsAreUnsupported() {
        assertUnsupported("single_feed", sniffText("<rss version=\"2.0\">"))
        assertUnsupported("single_feed", sniffText("<feed xmlns=\"a\">"))
        assertUnsupported("single_feed", sniffText("<rdf:RDF>"))
        assertUnsupported("single_feed", sniffText("<RDF>"))
    }

    @Test
    fun otherXmlAndGarbageAreUnknown() {
        assertUnsupported("unknown", sniffText("<html><body>"))
        assertUnsupported("unknown", sniffText(""))
        assertUnsupported("unknown", sniffText("   "))
        assertUnsupported("unknown", sniff(byteArrayOf(0x09, 0x7A, 0xFF.toByte(), 0x00)))
    }

    @Test
    fun jsonFormats() {
        assertEquals(
            SniffedFormat.NEWPIPE_JSON,
            recognized(sniffText("""{"app_version":"x","subscriptions":[{"service_id":0,"url":"u"}]}""")),
        )
        // service_id inside a string value does not count — the check is structural.
        assertUnsupported("json", sniffText("""{"note":"service_id","subscriptions":[{"url":"u"}]}"""))
        // subscriptions without service_id is not NewPipe.
        assertUnsupported("json", sniffText("""{"subscriptions":[{"url":"u"}]}"""))
        assertEquals(
            SniffedFormat.LIBRETUBE_JSON,
            recognized(sniffText("""{"localSubscriptions":["a"],"format":"json"}""")),
        )
        assertEquals(SniffedFormat.LIBRETUBE_JSON, recognized(sniffText("""{"channelGroups":[]}""")))
        assertEquals(SniffedFormat.LIBRETUBE_JSON, recognized(sniffText("""{"groups":[]}""")))
        assertEquals(SniffedFormat.LIBRETUBE_JSON, recognized(sniffText("""{"format":"Piped"}""")))
        assertUnsupported("json", sniffText("""{"format":"piped"}"""))
        assertUnsupported("json", sniffText("""{"anything":1}"""))
    }

    @Test
    fun anEmptySubscriptionsArrayIsNewPipe() {
        // 05 Sniffing: every object in the array carrying service_id is vacuously true
        // for an empty array; the import pipeline then ends in Empty anyway.
        assertEquals(SniffedFormat.NEWPIPE_JSON, recognized(sniffText("""{"subscriptions":[]}""")))
        assertEquals(
            SniffedFormat.NEWPIPE_JSON,
            recognized(sniffText("""{"app_version":"x","subscriptions":[ ]}""")),
        )
    }

    @Test
    fun explicitLibreTubeMarkersDecideAnEmptySubscriptionsArray() {
        for (text in listOf(
            """{"format":"Piped","version":1,"subscriptions":[],"groups":[]}""",
            """{"subscriptions":[],"format":"Piped"}""",
            """{"subscriptions":[],"localSubscriptions":[]}""",
            """{"subscriptions":[],"channelGroups":[]}""",
            """{"subscriptions":[],"groups":[]}""",
            """{"format":"Piped","subscriptions":["https://www.youtube.com/channel/UCx"]}""",
        )) {
            assertEquals(SniffedFormat.LIBRETUBE_JSON, recognized(sniffText(text)), text)
        }
    }

    @Test
    fun theWindowStopsAt64KiBHoweverTheSourceReads() {
        // request() refills in segment-sized pulls, so a short-reading source leaves
        // more than 64 KiB buffered; a marker past the cap must not decide (05).
        val marker = "\",\"localSubscriptions\":[]}"
        val payload = "{\"pad\":\"" + "x".repeat(64 * 1024) + marker
        assertUnsupported(
            "json",
            ImportSourceSniffer.sniff(
                { ChunkedSource(Buffer().write(payload.encodeToByteArray()), 100) },
                "payload".toPath(),
            ),
        )
    }

    @Test
    fun csvAndUrlLists() {
        assertEquals(
            SniffedFormat.TAKEOUT_CSV,
            recognized(sniffText("Channel Id,Channel Url,Channel Title\nUCabc...,https://x,T\n")),
        )
        assertEquals(SniffedFormat.URL_LIST, recognized(sniffText("https://a\nhttps://b\nUC" + "x".repeat(22))))
        assertEquals(SniffedFormat.URL_LIST, recognized(sniffText("# comment\nhttps://a\n@podcast")))
        // 1 good line of 2 falls under the 80% bar.
        assertUnsupported("unknown", sniffText("https://a\nhello world"))
        assertUnsupported("unknown", sniffText("# only comments\n"))
    }

    @Test
    fun zipClassificationNeedsTheArchiveFactory() {
        assertUnsupported("zip", sniff(zip))
        val backup = FakeArchives(mapOf("manifest.json" to backupManifest, "library.json" to "{}".encodeToByteArray()))
        assertEquals(SniffedFormat.NEUTRODYNE_BACKUP, recognized(sniff(zip, backup)))
        assertEquals(ArchiveCaps.TAKEOUT, backup.lastCaps)
    }

    @Test
    fun zipManifestDecidesBackupForeignAndTakeout() {
        val wrongFormat = FakeArchives(mapOf("manifest.json" to """{"format":"other"}""".encodeToByteArray()))
        assertUnsupported("zip", sniff(zip, wrongFormat))
        val corruptManifest = FakeArchives(mapOf("manifest.json" to byteArrayOf(0x01)))
        assertUnsupported("zip", sniff(zip, corruptManifest))
        // A foreign manifest falls through to the .csv row.
        val takeout =
            FakeArchives(
                mapOf(
                    "manifest.json" to "{}".encodeToByteArray(),
                    "Subscriptions.CSV" to byteArrayOf(0x01),
                ),
            )
        assertEquals(SniffedFormat.TAKEOUT_CSV, recognized(sniff(zip, takeout)))
        // Exact-name rule: Manifest.JSON is not manifest.json.
        val caseVariant = FakeArchives(mapOf("Manifest.JSON" to backupManifest))
        assertUnsupported("zip", sniff(zip, caseVariant))
    }

    @Test
    fun aDamagedManifestEntryStillReachesTheCsvFallback() {
        // The manifest entry exists but its data is damaged: the backup row does not
        // apply, so the .csv row still decides (05 Sniffing's else-if table).
        val damaged =
            FakeArchives(
                entries = mapOf("manifest.json" to byteArrayOf(0x01), "Subs.csv" to byteArrayOf(0x01)),
                openErrors =
                    mapOf("manifest.json" to ArchiveException(ArchiveException.Kind.CORRUPT, "zip")),
            )
        assertEquals(SniffedFormat.TAKEOUT_CSV, recognized(sniff(zip, damaged)))
        // A guard failure on the same entry stays fatal: hostile never falls through.
        val hostile =
            FakeArchives(
                entries = mapOf("manifest.json" to byteArrayOf(0x01), "Subs.csv" to byteArrayOf(0x01)),
                openErrors =
                    mapOf("manifest.json" to ArchiveException(ArchiveException.Kind.HOSTILE, "zip_bomb")),
            )
        assertEquals(SniffResult.Hostile("zip_bomb"), sniff(zip, hostile))
    }

    @Test
    fun hostileAndCorruptArchivesMapTheirReason() {
        val hostile = FakeArchives(error = ArchiveException(ArchiveException.Kind.HOSTILE, "zip_path"))
        assertEquals(SniffResult.Hostile("zip_path"), sniff(zip, hostile))
        val corrupt = FakeArchives(error = ArchiveException(ArchiveException.Kind.CORRUPT, "zip"))
        assertUnsupported("zip", sniff(zip, corrupt))
    }

    @Test
    fun gzipAndUnknownBytes() {
        assertUnsupported("tgz", sniff(byteArrayOf(0x1F, 0x8B.toByte(), 0x08)))
    }

    private fun assertUnsupported(
        reason: String,
        result: SniffResult,
    ) {
        assertEquals(SniffResult.Unsupported(reason), result)
    }

    /** A source that answers every read with at most [chunk] bytes. */
    private class ChunkedSource(
        private val delegate: Source,
        private val chunk: Long,
    ) : Source {
        override fun read(
            sink: Buffer,
            byteCount: Long,
        ): Long = delegate.read(sink, minOf(byteCount, chunk))

        override fun timeout(): Timeout = delegate.timeout()

        override fun close() = delegate.close()
    }

    /** In-memory [ArchiveReaderFactory]; :feeds cannot see :core:testing's fakes. */
    private class FakeArchives(
        private val entries: Map<String, ByteArray>? = null,
        private val error: ArchiveException? = null,
        private val openErrors: Map<String, ArchiveException> = emptyMap(),
    ) : ArchiveReaderFactory {
        var lastCaps: ArchiveCaps? = null
            private set

        override fun open(
            path: Path,
            caps: ArchiveCaps,
        ): ArchiveReader {
            lastCaps = caps
            error?.let { throw it }
            return Reader(entries.orEmpty(), openErrors)
        }

        private class Reader(
            private val entries: Map<String, ByteArray>,
            private val openErrors: Map<String, ArchiveException>,
        ) : ArchiveReader {
            override val entryNames = entries.keys.toList()

            override fun open(name: String): Source =
                openErrors[name]?.let { throw it }
                    ?: entries[name]?.let { Buffer().write(it) }
                    ?: throw ArchiveException(ArchiveException.Kind.CORRUPT, "zip")

            override fun close() {}
        }
    }
}
