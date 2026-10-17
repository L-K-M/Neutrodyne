// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.archive

import ch.lkmc.neutrodyne.feeds.backup.ArchiveCaps
import ch.lkmc.neutrodyne.feeds.backup.ArchiveException
import ch.lkmc.neutrodyne.feeds.backup.ArchiveReader
import ch.lkmc.neutrodyne.feeds.backup.ArchiveReaderFactory
import okio.Buffer
import okio.ForwardingSource
import okio.Path
import okio.Source
import okio.source
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * The guarded archive reader (05 Archive guard): `java.util.zip.ZipFile` for random access over the
 * local payload copy — entries are never extracted to disk, so entry names are never paths and
 * zip-slip cannot happen through this reader. The guard instead validates names itself and counts
 * every inflated byte its sources hand out.
 *
 * Name validation runs at [open], not per entry: a name containing `..`, starting with `/` or
 * containing `\` rejects the whole archive, as does one name claimed by two central-directory
 * records (the name→entry lookup would be ambiguous). On Android an app targeting 34+ sees the
 * platform reject `..`/leading-`/` names inside the `ZipFile` constructor itself — that
 * `ZipException` is recognised by message and mapped to the same `zip_path` so the desktop, JVM
 * tests and devices agree.
 */
public class ZipGuard : ArchiveReaderFactory {
    override fun open(
        path: Path,
        caps: ArchiveCaps,
    ): ArchiveReader {
        val file = path.toFile()
        // The end records are checked before `ZipFile` sees the file: its constructor sizes
        // `byte[cenlen]`, `int[centot * 3]` and `int[(centot / 2) | 1]` straight from the
        // declared counts, so a crafted (ZIP64) END would allocate gigabytes — or a negative
        // length — before a single name could be validated.
        checkEndRecords(file, caps)
        val zip =
            try {
                ZipFile(file)
            } catch (e: ZipException) {
                throw mapOpenFailure(e)
            }
        try {
            return Reader(zip, caps, validateNames(zip, caps))
        } catch (e: Throwable) {
            try {
                zip.close()
            } catch (closeFailure: Throwable) {
                e.addSuppressed(closeFailure)
            }
            throw e
        }
    }

    /**
     * Collects the central-directory names after applying the name and count rules; bails at the
     * first offending record so a huge directory cannot hold the scan open.
     */
    private fun validateNames(
        zip: ZipFile,
        caps: ArchiveCaps,
    ): List<String> {
        val names = ArrayList<String>(minOf(caps.maxEntries, 64))
        val seen = HashSet<String>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val name = entries.nextElement().name
            if (names.size >= caps.maxEntries) throw hostile("zip_entries")
            if (!isSafeEntryName(name)) throw hostile("zip_path")
            if (!seen.add(name)) throw hostile("zip_entries")
            names += name
        }
        return names
    }

    /**
     * The constructor-time `ZipException` on Android ("Invalid zip entry path: …", thrown by the
     * platform's path validator for `..` or leading `/` when the app targets 34+) maps to the same
     * `zip_path` our own name check produces on the JVM; every other `ZipException` is a malformed
     * archive. (Only reachable on devices; the JVM constructor does no name validation.)
     */
    private fun mapOpenFailure(e: ZipException): ArchiveException =
        if (e.message?.startsWith(ANDROID_PATH_MESSAGE) == true) {
            hostile("zip_path")
        } else {
            corrupt("zip")
        }

    /** The entry count and central-directory length an END record declares. */
    private class EndRecords(
        val entries: Long,
        val centralSize: Long,
    )

    /**
     * Applies the count and directory-size caps to the END records the `ZipFile` constructor
     * will trust, before the file reaches it: `initCEN` allocates `byte[cenlen]`,
     * `int[centot * 3]` and `int[(centot / 2) | 1]` unchecked — including a `(int)`-truncated
     * ZIP64 entry count — so the declarations are resolved and capped here. A scan that finds
     * no END leaves the file to `ZipFile`, which reports that case itself.
     */
    private fun checkEndRecords(
        file: File,
        caps: ArchiveCaps,
    ) {
        RandomAccessFile(file, "r").use { raf ->
            val end = scanEnd(raf) ?: return
            if (end.entries < 0 || end.entries > caps.maxEntries) throw hostile("zip_entries")
            // A directory that cannot hold its declared records is inconsistent; one bigger
            // than the cap's worst case only exists to force the `byte[cenlen]` allocation.
            if (end.centralSize < end.entries * CEN_RECORD_MIN) throw corrupt("zip")
            if (end.centralSize > caps.maxEntries * CEN_RECORD_MAX) throw hostile("zip_entries")
        }
    }

    /**
     * Finds the END record `ZipFile.Source.findEND` would use — the last `PK\x05\x06` signature
     * in the tail window whose comment length lands on the file's end (or whose CEN and LOC
     * signatures verify when it does not) — and resolves the ZIP64 locator/END records with the
     * same all-or-nothing adoption rule, so the returned values are exactly the ones `initCEN`
     * would allocate from.
     */
    private fun scanEnd(raf: RandomAccessFile): EndRecords? {
        val length = raf.length()
        val tail = minOf(length, END_TAIL_BYTES).toInt()
        if (tail < ENDHDR) return null
        val buf = ByteArray(tail)
        raf.seek(length - tail)
        raf.readFully(buf)
        for (i in tail - ENDHDR downTo 0) {
            if (u32(buf, i) != ENDSIG) continue
            val endpos = length - tail + i
            val centot = u16(buf, i + END_TOT).toLong()
            val cenlen = u32(buf, i + END_SIZ)
            val cenoff = u32(buf, i + END_OFF)
            val comlen = u16(buf, i + END_COM).toLong()
            if (endpos + ENDHDR + comlen != length && !signaturesVerify(raf, endpos, cenlen, cenoff)) {
                continue
            }
            return resolveZip64(raf, endpos, centot, cenlen, cenoff)
        }
        return null
    }

    /** The fallback verification `findEND` applies when the comment length does not line up. */
    private fun signaturesVerify(
        raf: RandomAccessFile,
        endpos: Long,
        cenlen: Long,
        cenoff: Long,
    ): Boolean {
        val cenpos = endpos - cenlen
        val locpos = cenpos - cenoff
        return cenpos >= 0 && locpos >= 0 && sigAt(raf, cenpos) == CENSIG && sigAt(raf, locpos) == LOCSIG
    }

    /**
     * `findEND`'s ZIP64 resolution: the locator sits in the 20 bytes before the END record and
     * points at the ZIP64 END, whose values replace the classic ones only when every field
     * either matches or carries its 0xFFFF/0xFFFFFFFF sentinel. Any inconsistency or read
     * failure leaves the classic values in force, exactly as `findEND` returns them.
     */
    private fun resolveZip64(
        raf: RandomAccessFile,
        endpos: Long,
        centot: Long,
        cenlen: Long,
        cenoff: Long,
    ): EndRecords {
        if (endpos < ZIP64_LOCHDR) return EndRecords(centot, cenlen)
        val loc64 = ByteArray(ZIP64_LOCHDR)
        if (!readAt(raf, loc64, endpos - ZIP64_LOCHDR) || u32(loc64, 0) != ZIP64_LOCSIG) {
            return EndRecords(centot, cenlen)
        }
        val end64 = ByteArray(ZIP64_ENDHDR)
        if (!readAt(raf, end64, u64(loc64, ZIP64_LOCOFF)) || u32(end64, 0) != ZIP64_ENDSIG) {
            return EndRecords(centot, cenlen)
        }
        val centot64 = u64(end64, ZIP64_ENDTOT)
        val cenlen64 = u64(end64, ZIP64_ENDSIZ)
        val cenoff64 = u64(end64, ZIP64_ENDOFF)
        val adopted =
            (centot64 == centot || centot == ZIP64_MAGICCOUNT) &&
                (cenlen64 == cenlen || cenlen == ZIP64_MAGICVAL) &&
                (cenoff64 == cenoff || cenoff == ZIP64_MAGICVAL)
        if (!adopted) {
            return EndRecords(centot, cenlen)
        }
        return EndRecords(centot64, cenlen64)
    }

    private fun sigAt(
        raf: RandomAccessFile,
        pos: Long,
    ): Long {
        val buf = ByteArray(4)
        return if (readAt(raf, buf, pos)) u32(buf, 0) else -1L
    }

    private fun readAt(
        raf: RandomAccessFile,
        buf: ByteArray,
        pos: Long,
    ): Boolean {
        if (pos < 0 || pos + buf.size > raf.length()) return false
        raf.seek(pos)
        raf.readFully(buf)
        return true
    }

    private class Reader(
        private val zip: ZipFile,
        private val caps: ArchiveCaps,
        override val entryNames: List<String>,
    ) : ArchiveReader {
        /** Inflated bytes handed out by every open source, checked against `caps.maxTotalBytes`. */
        private val totalInflated = AtomicLong()

        override fun open(name: String): Source {
            // `getEntry` falls back to "name/" when the exact name is absent — only an exact
            // match is a valid read.
            val entry = zip.getEntry(name)
            if (entry == null || entry.name != name) throw corrupt("zip")
            val stream =
                try {
                    zip.getInputStream(entry)
                } catch (e: ZipException) {
                    throw corrupt("zip")
                }
            return CountingSource(stream.source(), entry.compressedSize, caps, totalInflated)
        }

        override fun close() {
            zip.close()
        }
    }

    /**
     * Wraps one entry's inflater stream and counts inflated bytes itself. The declared
     * uncompressed size is never consulted: the per-entry and total caps trip on the counted
     * bytes, and the ratio check divides by the declared *compressed* size — `ZipFile` bounds the
     * inflater's input to that size, so a bomb can only lie the ratio downward (it then still hits
     * the byte caps).
     */
    private class CountingSource(
        delegate: Source,
        private val compressedSize: Long,
        private val caps: ArchiveCaps,
        private val totalInflated: AtomicLong,
    ) : ForwardingSource(delegate) {
        private var inflated = 0L

        override fun read(
            sink: Buffer,
            byteCount: Long,
        ): Long {
            val n =
                try {
                    super.read(sink, byteCount)
                } catch (e: ZipException) {
                    // Damaged deflate data or a local header inconsistent with the central
                    // directory. `ZipFile` never verifies the CRC — bad bytes arrive silently,
                    // so content integrity is the manifest SHA-256 check's job (05 Validation).
                    throw corrupt("zip")
                } catch (e: EOFException) {
                    // The entry's deflate stream runs out before its declared end: damage inside
                    // a file that was readable, not an I/O failure.
                    throw corrupt("zip")
                }
            if (n < 0) return n
            inflated += n
            if (inflated > caps.maxEntryBytes || ratioTripped(inflated)) throw hostile("zip_bomb")
            if (totalInflated.addAndGet(n) > caps.maxTotalBytes) throw hostile("zip_bomb")
            return n
        }

        private fun ratioTripped(inflated: Long): Boolean {
            if (inflated <= caps.ratioFloorBytes || compressedSize <= 0) return false
            // An absurd declared size would overflow the multiply; such a declaration dwarfs any
            // possible inflation, so the check cannot legitimately trip for it.
            if (compressedSize > Long.MAX_VALUE / caps.maxRatio) return false
            return inflated > compressedSize * caps.maxRatio
        }
    }

    private companion object {
        /**
         * Android's zip path validator throws `ZipException("Invalid zip entry path: <name>")` from
         * the `ZipFile` constructor (SafeZipPathValidatorCallback, apps targeting 34+).
         */
        const val ANDROID_PATH_MESSAGE = "Invalid zip entry path"

        // The END record: 22 fixed bytes at the file tail, then a comment of up to 64 KiB.
        const val ENDHDR = 22
        const val ENDSIG = 0x06054B50L
        const val END_TOT = 10
        const val END_SIZ = 12
        const val END_OFF = 16
        const val END_COM = 20

        /**
         * `findEND` scans the END plus a maximum comment (0xFFFF + ENDHDR) in 128-byte blocks that
         * step 106 bytes, so candidates one block below the comment window are still reached.
         */
        const val END_TAIL_BYTES = 0xFFFFL + 128L

        // The ZIP64 end record (56 bytes) and locator (20 bytes) field offsets used.
        const val ZIP64_ENDHDR = 56
        const val ZIP64_LOCHDR = 20
        const val ZIP64_ENDSIG = 0x06064B50L
        const val ZIP64_LOCSIG = 0x07064B50L
        const val ZIP64_LOCOFF = 8
        const val ZIP64_ENDTOT = 32
        const val ZIP64_ENDSIZ = 40
        const val ZIP64_ENDOFF = 48
        const val ZIP64_MAGICVAL = 0xFFFFFFFFL
        const val ZIP64_MAGICCOUNT = 0xFFFFL

        const val CENSIG = 0x02014B50L
        const val LOCSIG = 0x04034B50L

        /**
         * A central-directory record is 46 fixed bytes plus a name, an extra field and a comment,
         * each bounded by a u16 length — so `CEN_RECORD_MIN` is the smallest possible record and
         * `maxEntries * CEN_RECORD_MAX` the largest directory the caps can legitimise.
         */
        const val CEN_RECORD_MIN = 46L
        const val CEN_RECORD_MAX = CEN_RECORD_MIN + 3L * 0xFFFF
    }
}

/** 05 Archive guard's name rule: no `..` anywhere, no leading `/`, no `\` at all. */
internal fun isSafeEntryName(name: String): Boolean =
    !name.contains("..") && !name.startsWith("/") && !name.contains('\\')

private fun hostile(reason: String): ArchiveException = ArchiveException(ArchiveException.Kind.HOSTILE, reason)

private fun corrupt(reason: String): ArchiveException = ArchiveException(ArchiveException.Kind.CORRUPT, reason)

private fun u16(
    bytes: ByteArray,
    at: Int,
): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

private fun u32(
    bytes: ByteArray,
    at: Int,
): Long = u16(bytes, at).toLong() or (u16(bytes, at + 2).toLong() shl 16)

private fun u64(
    bytes: ByteArray,
    at: Int,
): Long = u32(bytes, at) or (u32(bytes, at + 4) shl 32)
