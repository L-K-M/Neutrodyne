// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.archive

import ch.lkmc.neutrodyne.feeds.backup.AppInfoV1
import ch.lkmc.neutrodyne.feeds.backup.ArchiveCaps
import ch.lkmc.neutrodyne.feeds.backup.ArchiveReader
import ch.lkmc.neutrodyne.feeds.backup.BackupCodec
import ch.lkmc.neutrodyne.feeds.backup.BackupCodecException
import ch.lkmc.neutrodyne.feeds.backup.BackupKind
import ch.lkmc.neutrodyne.feeds.backup.BackupManifest
import ch.lkmc.neutrodyne.feeds.backup.CountsV1
import ch.lkmc.neutrodyne.feeds.importing.ImportSourceSniffer
import ch.lkmc.neutrodyne.feeds.importing.SniffResult
import ch.lkmc.neutrodyne.feeds.importing.SniffedFormat
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The codec legs of 05's `HostileInputTest` (M3 acceptance 3, 09 Untrusted-input robustness):
 * multi-megabyte entries generated at test time — a huge *unknown* array beside a small valid
 * object, 400,000 known queue references, and a `manifest.json` whose unknown field nests
 * 10,000 arrays deep — decoded through the real `ZipGuard` path on this task's 128 MB heap.
 * The old whole-`String` + `JsonElement`-tree decode died with `OutOfMemoryError`; the deep
 * manifest died with `StackOverflowError` inside `manifestFormat`.
 */
class BackupCodecHostileInputTest {
    private val codec = BackupCodec()

    @Test
    fun aHugeUnknownLibraryArrayIsSkippedNotMaterialised() {
        val path =
            backupZip { zip ->
                zip.inflatedEntry(
                    BackupCodec.ENTRY_LIBRARY,
                    """{"podcasts":[],"groups":[],"future":[""",
                    "0,",
                    UNKNOWN_ELEMENTS,
                    "0]}",
                )
            }
        openBackup(path) { reader ->
            val library = codec.decodeLibrary(reader.open(BackupCodec.ENTRY_LIBRARY))
            assertTrue(library.podcasts.isEmpty())
            assertTrue(library.groups.isEmpty())
        }
    }

    @Test
    fun aHugeUnknownQueueArrayIsSkippedNotMaterialised() {
        val path =
            backupZip { zip ->
                zip.inflatedEntry(
                    BackupCodec.ENTRY_QUEUE,
                    """{"upNext":[],"future":[""",
                    "0,",
                    UNKNOWN_ELEMENTS,
                    "0]}",
                )
            }
        openBackup(path) { reader ->
            assertTrue(codec.decodeQueue(reader.open(BackupCodec.ENTRY_QUEUE)).upNext.isEmpty())
        }
    }

    @Test
    fun aHugeUnknownSettingsArrayIsSkippedNotMaterialised() {
        val path =
            backupZip { zip ->
                zip.inflatedEntry(
                    BackupCodec.ENTRY_SETTINGS,
                    """{"values":[],"future":[""",
                    "0,",
                    UNKNOWN_ELEMENTS,
                    "0]}",
                )
            }
        openBackup(path) { reader ->
            assertTrue(codec.decodeSettings(reader.open(BackupCodec.ENTRY_SETTINGS)).values.isEmpty())
        }
    }

    @Test
    fun aFullQueueOfKnownRefsDecodesStreamed() {
        // queue-known.json's shape: 400,000 valid up-next references (~7 MB inflated).
        val path =
            backupZip { zip ->
                zip.inflatedEntry(
                    BackupCodec.ENTRY_QUEUE,
                    """{"upNext":[""",
                    """{"p":"a","k":"b"},""",
                    QUEUE_REFS - 1,
                    """{"p":"a","k":"b"}]}""",
                )
            }
        openBackup(path) { reader ->
            val queue = codec.decodeQueue(reader.open(BackupCodec.ENTRY_QUEUE))
            assertEquals(QUEUE_REFS, queue.upNext.size.toLong())
        }
    }

    @Test
    fun aDeepUnknownManifestFieldSniffsAndDecodes() {
        val path =
            backupZip { zip ->
                zip.deepFieldEntry(
                    BackupCodec.ENTRY_MANIFEST,
                    manifestJson(BackupCodec.MIN_READER_VERSION).dropLast(1) + ",\"future\":",
                    DEEP_ARRAYS,
                    "}",
                )
            }
        // The sniffer probes only `format`: the deep unknown field must not keep the
        // archive off the backup row (05 Sniffing).
        assertEquals(
            SniffResult.Recognized(SniffedFormat.NEUTRODYNE_BACKUP),
            ImportSourceSniffer.sniff({ FileSystem.SYSTEM.source(path) }, path, ZipGuard()),
        )
        openBackup(path) { reader ->
            val manifest = codec.decodeManifest(reader.open(BackupCodec.ENTRY_MANIFEST))
            assertEquals(BackupCodec.FORMAT, manifest.format)
        }
    }

    @Test
    fun aNewerManifestWithTheSameDeepFieldReportsNewerFormat() {
        // "Update the app" must win over "corrupt": the gate reads minReaderVersion before
        // any v1 decode, so the deep unknown field never reaches a recursive reader.
        val path =
            backupZip { zip ->
                zip.deepFieldEntry(
                    BackupCodec.ENTRY_MANIFEST,
                    manifestJson(BackupCodec.MIN_READER_VERSION + 1).dropLast(1) + ",\"future\":",
                    DEEP_ARRAYS,
                    "}",
                )
            }
        openBackup(path) { reader ->
            val e =
                assertFailsWith<BackupCodecException.NewerFormat> {
                    codec.decodeManifest(reader.open(BackupCodec.ENTRY_MANIFEST))
                }
            assertEquals(BackupCodec.MIN_READER_VERSION + 1, e.minReaderVersion)
        }
    }

    private fun manifestJson(minReaderVersion: Int): String =
        codec
            .encodeManifest(
                BackupManifest(
                    createdAt = "2026-10-17T00:00:00Z",
                    kind = BackupKind.MANUAL,
                    app = AppInfoV1("0.1", 1, "arm64-v8a"),
                    dbSchemaVersion = 12,
                    episodeKeysVersion = 1,
                    urlNormalizerVersion = 1,
                    installationId = "inst-1",
                    counts = CountsV1(0, 0, 0, 0, 0, 0, 0),
                    entries = emptyList(),
                ),
            ).replace(
                "\"minReaderVersion\":${BackupCodec.MIN_READER_VERSION}",
                "\"minReaderVersion\":$minReaderVersion",
            )

    private fun backupZip(write: (ZipOutputStream) -> Unit): Path {
        val path = Files.createTempFile("backup-codec", ".zip").toOkioPath()
        ZipOutputStream(BufferedOutputStream(Files.newOutputStream(path.toNioPath()))).use(write)
        return path
    }

    private fun openBackup(
        path: Path,
        block: (ArchiveReader) -> Unit,
    ) = ZipGuard().open(path, ArchiveCaps.BACKUP).use(block)

    /**
     * An entry holding [prefix], [repeats] copies of [token], then [suffix], written in
     * bounded chunks — several inflated megabytes without a payload-sized literal.
     */
    private fun ZipOutputStream.inflatedEntry(
        name: String,
        prefix: String,
        token: String,
        repeats: Long,
        suffix: String,
    ) {
        putNextEntry(ZipEntry(name))
        write(prefix.toByteArray(Charsets.UTF_8))
        val unit = token.toByteArray(Charsets.UTF_8)
        val chunk = ByteArray(unit.size * TOKENS_PER_CHUNK)
        for (i in 0 until TOKENS_PER_CHUNK) unit.copyInto(chunk, i * unit.size)
        var left = repeats
        while (left >= TOKENS_PER_CHUNK) {
            write(chunk)
            left -= TOKENS_PER_CHUNK
        }
        if (left > 0) write(chunk, 0, (left * unit.size).toInt())
        write(suffix.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    /** An entry holding [prefix], [depth] `[`, `0`, [depth] `]`, then [suffix]. */
    private fun ZipOutputStream.deepFieldEntry(
        name: String,
        prefix: String,
        depth: Int,
        suffix: String,
    ) {
        putNextEntry(ZipEntry(name))
        write(prefix.toByteArray(Charsets.UTF_8))
        val open = byteArrayOf('['.code.toByte())
        val close = byteArrayOf(']'.code.toByte())
        repeat(depth) { write(open) }
        write(byteArrayOf('0'.code.toByte()))
        repeat(depth) { write(close) }
        write(suffix.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private companion object {
        /** The review fixture's scale: ~4 MiB inflated — a JsonElement tree over it exceeds 128 MB. */
        const val UNKNOWN_ELEMENTS = 2_097_152L

        /** queue-known.json's 400,000 known references (~7 MB inflated). */
        const val QUEUE_REFS = 400_000L

        /** manifest-array-depth-10000.json's nesting: beyond any recursive tree reader's stack. */
        const val DEEP_ARRAYS = 10_000
        const val TOKENS_PER_CHUNK = 8_192
    }
}
