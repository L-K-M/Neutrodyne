// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

/**
 * The prolog guard (03 Parser setup and charset step 1): scans the first 64 KiB up to the first element
 * start tag; `<!ENTITY` anywhere in that window (case-insensitive) means the document is hostile.
 * `FEATURE_PROCESS_DOCDECL` stays off and no external DTD is ever fetched (N9).
 */
internal object PrologGuard {
    private const val ENTITY_MARKER = "<!entity"

    /** Whether the prolog of [bytes] declares an entity, which would enable entity expansion. */
    fun isHostile(
        bytes: ByteArray,
        scanLimit: Int,
    ): Boolean {
        val prologEnd = firstElementStart(bytes, scanLimit)
        var i = 0
        while (i + ENTITY_MARKER.length <= prologEnd) {
            if (matchesEntityMarker(bytes, i)) return true
            i++
        }
        return false
    }

    /** The first element start tag: a `<` followed by a name-start character, within [scanLimit]. */
    private fun firstElementStart(
        bytes: ByteArray,
        scanLimit: Int,
    ): Int {
        val end = minOf(bytes.size, scanLimit)
        for (i in 0 until end - 1) {
            if (bytes[i] == '<'.code.toByte() && isNameStartByte(bytes[i + 1])) return i
        }
        return end
    }

    private fun matchesEntityMarker(
        bytes: ByteArray,
        at: Int,
    ): Boolean {
        for (i in ENTITY_MARKER.indices) {
            if (bytes[at + i].lowercaseAscii() != ENTITY_MARKER[i].code.toByte()) return false
        }
        return true
    }

    private fun Byte.lowercaseAscii(): Byte {
        val c = toInt() and 0xFF
        val lowered = if (c in 'A'.code..'Z'.code) c + 32 else c
        return lowered.toByte()
    }

    private fun isNameStartByte(byte: Byte): Boolean {
        val c = byte.toInt() and 0xFF
        return c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code ||
            c == '_'.code || c == ':'.code
    }
}
