// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.archive

import okio.Path
import okio.Path.Companion.toOkioPath
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Hand-built archives for the guard tests, including shapes no compliant writer emits. */
internal object ZipTestFiles {
    /** A valid ZIP holding [entries] (name → content) in order, DEFLATEd. */
    fun zipBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** `totalBytes` of NULs, which deflate to roughly a thousandth — a bomb fixture. */
    fun zeroBombBytes(
        name: String,
        totalBytes: Long,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            val chunk = ByteArray(CHUNK_BYTES)
            var remaining = totalBytes
            while (remaining > 0) {
                val n = minOf(chunk.size.toLong(), remaining).toInt()
                zip.write(chunk, 0, n)
                remaining -= n
            }
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    /**
     * A ZIP whose central directory names [name] twice. `ZipOutputStream` refuses to write a
     * duplicate name, so a valid two-entry archive is patched byte-for-byte: [other] must have
     * [name]'s length so every offset survives; the name bytes appear exactly twice (the local
     * header and the central-directory record) and CRCs cover data, never names.
     */
    fun duplicateNameZip(
        name: String,
        other: String,
        content: ByteArray,
    ): ByteArray {
        require(name.length == other.length) { "the patch needs same-length names" }
        val zip = zipBytes(listOf(name to content, other to content))
        val needle = other.encodeToByteArray()
        val patch = name.encodeToByteArray()
        var from = 0
        var found = 0
        while (true) {
            val at = zip.indexOf(needle, from) ?: break
            needle.indices.forEach { zip[at + it] = patch[it] }
            found += 1
            from = at + needle.size
        }
        check(found == 2) { "expected the name twice (local header + central directory), got $found" }
        return zip
    }

    /**
     * [zip] with the first byte of the entry's deflate body rewritten to an illegal block type:
     * the central directory stays readable so the archive opens, then the entry's own read fails
     * inside the inflater. (Only the entry-data corruption is deterministic — mutating the body or
     * the central-directory CRC further in is tolerated by `java.util.zip.ZipFile` and yields
     * wrong bytes instead of an error.)
     */
    fun deflateDamagedZip(zip: ByteArray): ByteArray {
        val nameLen = u16(zip, LOCAL_NAME_LEN_OFFSET)
        val extraLen = u16(zip, LOCAL_EXTRA_LEN_OFFSET)
        zip[LOCAL_HEADER_BYTES + nameLen + extraLen] = ILLEGAL_DEFLATE_BLOCK
        return zip
    }

    /** A valid ZIP holding [entries] (name → content) in order, STORED (no inflater involved). */
    fun storedZipBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                val entry = ZipEntry(name)
                entry.method = ZipEntry.STORED
                entry.size = content.size.toLong()
                entry.crc = CRC32().also { it.update(content) }.value
                zip.putNextEntry(entry)
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * A one-entry archive followed by a ZIP64 END declaring [centot64] entries: the classic END
     * carries the 0xFFFF/0xFFFFFFFF sentinels, so `java.util.zip.ZipFile` adopts the 64-bit count
     * into the `int[centot * 3]` allocation its constructor performs unchecked. The guard must
     * reject the claim before the file ever reaches `ZipFile`. Layout: LOC + CEN + ZIP64 END +
     * ZIP64 locator + classic END — 176 bytes for the one-byte name.
     */
    fun zip64EndBomb(
        centot64: Long,
        declaredCenlen: Long? = null,
        locatorOffset: Long? = null,
    ): ByteArray {
        val name = byteArrayOf('x'.code.toByte())
        val loc = ByteArray(LOCHDR + name.size)
        put32(loc, 0, LOCSIG)
        put16(loc, 4, 20)
        put16(loc, LOCAL_NAME_LEN_OFFSET, name.size)
        name.copyInto(loc, LOCHDR)
        val cen = ByteArray(CENHDR + name.size)
        put32(cen, 0, CENSIG)
        put16(cen, 4, 20)
        put16(cen, 6, 20)
        put16(cen, CEN_NAME_LEN_OFFSET, name.size)
        name.copyInto(cen, CENHDR)
        val end64 = ByteArray(ZIP64_ENDHDR)
        put32(end64, 0, ZIP64_ENDSIG)
        put64(end64, 4, (ZIP64_ENDHDR - 12).toLong())
        put16(end64, 12, 45)
        put16(end64, 14, 45)
        put64(end64, ZIP64_END_TOT_DISK, centot64)
        put64(end64, ZIP64_ENDTOT, centot64)
        put64(end64, ZIP64_ENDSIZ, declaredCenlen ?: cen.size.toLong())
        put64(end64, ZIP64_ENDOFF, loc.size.toLong())
        val loc64 = ByteArray(ZIP64_LOCHDR)
        put32(loc64, 0, ZIP64_LOCSIG)
        put64(loc64, ZIP64_LOCOFF, locatorOffset ?: (loc.size + cen.size).toLong())
        put32(loc64, 16, 1)
        val end = ByteArray(ENDHDR)
        put32(end, 0, ENDSIG)
        put16(end, END_TOT_DISK, 0xFFFF)
        put16(end, END_TOT, 0xFFFF)
        put32(end, END_SIZ, ZIP64_MAGICVAL)
        put32(end, END_OFF, ZIP64_MAGICVAL)
        return loc + cen + end64 + loc64 + end
    }

    /**
     * A valid one-entry archive whose END record is rewritten to declare [declaredEntries]
     * entries and [declaredCenlen] central-directory bytes, to drive the guard's end-record
     * plausibility checks. (The real directory is left untouched; only the declaration lies.)
     */
    fun endLyingZip(
        declaredEntries: Int,
        declaredCenlen: Long,
    ): ByteArray {
        val zip = zipBytes(listOf("a" to "x".encodeToByteArray()))
        val at = zip.size - ENDHDR
        check(u32(zip, at) == ENDSIG) { "fixture drift: the END record is not at the tail" }
        put16(zip, at + END_TOT_DISK, declaredEntries)
        put16(zip, at + END_TOT, declaredEntries)
        put32(zip, at + END_SIZ, declaredCenlen)
        return zip
    }

    /**
     * [zip] with the central-directory CRC of its entry made wrong. `java.util.zip` never
     * verifies it: the read delivers the bytes silently.
     */
    fun corruptCenCrc(zip: ByteArray): ByteArray {
        var at = zip.size - CEN_NAME_LEN_OFFSET
        while (u32(zip, at) != CENSIG) at--
        zip[at + CEN_CRC_OFFSET] = (zip[at + CEN_CRC_OFFSET].toInt() xor 0xFF).toByte()
        return zip
    }

    /** [bytes] as a fresh temporary file. */
    fun tempZip(bytes: ByteArray): Path =
        Files.createTempFile("zip-guard", ".zip").also { Files.write(it, bytes) }.toOkioPath()

    private fun ByteArray.indexOf(
        needle: ByteArray,
        from: Int,
    ): Int? {
        outer@ for (i in from..size - needle.size) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return null
    }

    private fun u16(
        bytes: ByteArray,
        at: Int,
    ): Int = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)

    private fun u32(
        bytes: ByteArray,
        at: Int,
    ): Long = u16(bytes, at).toLong() or (u16(bytes, at + 2).toLong() shl 16)

    private fun put16(
        bytes: ByteArray,
        at: Int,
        value: Int,
    ) {
        bytes[at] = value.toByte()
        bytes[at + 1] = (value ushr 8).toByte()
    }

    private fun put32(
        bytes: ByteArray,
        at: Int,
        value: Long,
    ) {
        put16(bytes, at, value.toInt())
        put16(bytes, at + 2, (value ushr 16).toInt())
    }

    private fun put64(
        bytes: ByteArray,
        at: Int,
        value: Long,
    ) {
        put32(bytes, at, value)
        put32(bytes, at + 4, value ushr 32)
    }

    private const val CHUNK_BYTES = 1024 * 1024

    // Local file header: 30 fixed bytes, then the name and extra fields, then the data.
    private const val LOCAL_HEADER_BYTES = 30
    private const val LOCHDR = 30
    private const val LOCSIG = 0x04034B50L
    private const val LOCAL_NAME_LEN_OFFSET = 26
    private const val LOCAL_EXTRA_LEN_OFFSET = 28

    // Central-directory record: 46 fixed bytes before name, extra and comment.
    private const val CENHDR = 46
    private const val CENSIG = 0x02014B50L
    private const val CEN_CRC_OFFSET = 16
    private const val CEN_NAME_LEN_OFFSET = 28

    // End-of-central-directory record: 22 fixed bytes, then the comment.
    private const val ENDHDR = 22
    private const val ENDSIG = 0x06054B50L
    private const val END_TOT_DISK = 8
    private const val END_TOT = 10
    private const val END_SIZ = 12
    private const val END_OFF = 16

    // ZIP64 end record (56 bytes) and locator (20 bytes).
    private const val ZIP64_ENDHDR = 56
    private const val ZIP64_LOCHDR = 20
    private const val ZIP64_ENDSIG = 0x06064B50L
    private const val ZIP64_LOCSIG = 0x07064B50L
    private const val ZIP64_LOCOFF = 8
    private const val ZIP64_END_TOT_DISK = 24
    private const val ZIP64_ENDTOT = 32
    private const val ZIP64_ENDSIZ = 40
    private const val ZIP64_ENDOFF = 48
    private const val ZIP64_MAGICVAL = 0xFFFFFFFFL

    /** BFINAL=0 + BTYPE=0b11 — a reserved block type every inflater must reject. */
    private const val ILLEGAL_DEFLATE_BLOCK: Byte = 0b110
}
