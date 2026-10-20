// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertFalse

class BundledZipTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun rejectsSlipAbsoluteBackslashAndUnapprovedRoots() {
        for (name in listOf(
            "../escape",
            "/escape",
            "yt_dlp/../escape",
            "yt_dlp//escape",
            "yt_dlp/./escape",
            "yt_dlp\\escape",
            "plugins/evil.py",
            "yt_dlp/evil.so",
            "yt_dlp/evil.pyc",
        )) {
            reject(fixtureZip(mapOf(name to byteArrayOf(1))))
        }
    }

    @Test fun rejectsSymlinkAndSpecialUnixEntries() {
        for (mode in listOf(0xa1ffL, 0x21ffL, 0x11ffL)) {
            val bytes = fixtureZip()
            val at = central(bytes)
            put(bytes, at + 38, mode shl 16)
            reject(bytes)
        }
    }

    @Test fun rejectsBombAndOversizedZipEntryTotalAndCount() {
        reject(fixtureZip(mapOf("yt_dlp/bomb.py" to ByteArray(1024 * 1024))))
        reject(
            fixtureZip().let { bytes ->
                put(bytes, central(bytes) + 24, 9L * 1024 * 1024)
                bytes
            },
        )
        reject(fixtureZip() + ByteArray(10 * 1024 * 1024))
        reject(fixtureZip((0..4096).associate { "yt_dlp/$it.py" to byteArrayOf(1) }))
        val pattern = kotlin.random.Random(7).nextBytes(16 * 1024)
        val large = ByteArray(7 * 1024 * 1024) { pattern[it % pattern.size] }
        val many = fixtureZip((0..5).associate { "yt_dlp/$it.py" to large })
        val failure = reject(many)
        kotlin.test.assertTrue(failure.message.orEmpty().contains("total limit"))
    }

    @Test fun rejectsDuplicateCollisionLocalMismatchEncryptionAndTruncation() {
        reject(fixtureZip(mapOf("yt_dlp" to byteArrayOf(1))))
        reject(fixtureZip(mapOf("yt_dlp/collision" to byteArrayOf(1), "yt_dlp/collision/file.py" to byteArrayOf(2))))
        val mismatch = fixtureZip()
        mismatch[30] = 'x'.code.toByte()
        reject(mismatch)
        val encrypted = fixtureZip()
        encrypted[central(encrypted) + 8] = 1
        reject(encrypted)
        reject(fixtureZip().dropLast(5).toByteArray())
        val duplicate = fixtureZip(mapOf("yt_dlp/aaaa.py" to byteArrayOf(1), "yt_dlp/bbbb.py" to byteArrayOf(2)))
        val needle = "yt_dlp/bbbb.py".toByteArray()
        for (i in 0..duplicate.size - needle.size) {
            if (duplicate
                    .copyOfRange(
                        i,
                        i + needle.size,
                    ).contentEquals(needle)
            ) {
                "yt_dlp/aaaa.py".toByteArray().copyInto(duplicate, i)
            }
        }
        reject(duplicate)
    }

    @Test fun rejectsWrongVersionOriginEjsAndCorruptBody() {
        reject(fixtureZip(version = "2026.09.01"))
        reject(fixtureZip(mapOf("yt_dlp/version.py" to "ORIGIN = 'evil'".toByteArray())))
        reject(fixtureZip(mapOf("yt_dlp_ejs/_version.py" to "__version__ = version = '9.0.0'".toByteArray())))
        val corrupt = fixtureZip()
        corrupt[45] = (corrupt[45].toInt() xor 127).toByte()
        reject(corrupt)
    }

    private fun reject(bytes: ByteArray): Throwable {
        val root = temporary.newFolder()
        val archive = File(root, "yt-dlp").apply { writeBytes(bytes) }
        val output = File(root, "lib")
        val failure = assertFails { BundledZip.extract(archive, output, "2026.08.19", "0.8.0") }
        assertFalse(File(root, "escape").exists())
        return failure
    }

    private fun central(bytes: ByteArray): Int = (0..bytes.size - 4).first { signature(bytes, it) == 0x02014b50L }

    private fun u16(
        bytes: ByteArray,
        at: Int,
    ): Int = (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)

    private fun signature(
        bytes: ByteArray,
        at: Int,
    ): Long = u16(bytes, at).toLong() or (u16(bytes, at + 2).toLong() shl 16)

    private fun put(
        bytes: ByteArray,
        at: Int,
        value: Long,
    ) {
        repeat(4) { bytes[at + it] = (value shr (it * 8)).toByte() }
    }
}
