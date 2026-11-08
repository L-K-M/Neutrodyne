// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlp.ytx

/** Reserve space for the interface token, IDs, callback handle, string terminator and alignment. */
internal object BinderPayloads {
    private const val FRAMING_BYTES = 1024

    fun fits(
        text: String,
        limit: Int,
    ): Boolean = text.length.toLong() * Char.SIZE_BYTES + FRAMING_BYTES <= limit
}
