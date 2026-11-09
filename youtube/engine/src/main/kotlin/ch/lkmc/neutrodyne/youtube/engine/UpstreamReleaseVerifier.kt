// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import java.security.MessageDigest

/**
 * Checks 7–9 of the trust chain: `SHA2-256SUMS.sig` must be the pinned key's detached signature
 * over `SHA2-256SUMS`, the `yt-dlp` line names the archive's SHA-256, and the downloaded bytes
 * must match that line. Keeps the sums parsing strict so a mangled file cannot hide a line.
 */
object UpstreamReleaseVerifier {
    // sha256sum output: `<hash>  <name>` or `<hash> *<name>` for binary mode.
    private val linePattern = Regex("([0-9a-f]{64}) [ *]([A-Za-z0-9_.-]+)")

    /** name → sha256 for every sums line, or null when the detached signature fails. */
    fun verify(
        sums: ByteArray,
        signature: ByteArray,
        key: OpenPgpDetachedVerifier.PublicKey,
    ): Map<String, String>? {
        try {
            OpenPgpDetachedVerifier.verify(sums, signature, key)
        } catch (invalid: Exception) {
            return null
        }
        val lines = sums.decodeToString().lines().filter { it.isNotEmpty() }
        if (lines.any { linePattern.matchEntire(it) == null }) return null
        val entries =
            lines.associate {
                linePattern.matchEntire(it)!!.let { m ->
                    m.groupValues[2] to m.groupValues[1]
                }
            }
        return entries.takeIf { it.isNotEmpty() }
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
}
