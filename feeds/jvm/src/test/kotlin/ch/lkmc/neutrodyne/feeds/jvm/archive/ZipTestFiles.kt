// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.archive

import okio.Path
import okio.Path.Companion.toOkioPath
import java.io.ByteArrayOutputStream
import java.nio.file.Files
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

    private const val CHUNK_BYTES = 1024 * 1024

    // Local file header: 30 fixed bytes, then the name and extra fields, then the data.
    private const val LOCAL_HEADER_BYTES = 30
    private const val LOCAL_NAME_LEN_OFFSET = 26
    private const val LOCAL_EXTRA_LEN_OFFSET = 28

    /** BFINAL=0 + BTYPE=0b11 — a reserved block type every inflater must reject. */
    private const val ILLEGAL_DEFLATE_BLOCK: Byte = 0b110
}
