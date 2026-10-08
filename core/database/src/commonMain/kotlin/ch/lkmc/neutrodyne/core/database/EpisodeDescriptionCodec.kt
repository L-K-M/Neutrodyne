// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

/**
 * The only way to read or write `episode_description.html` (02 episode_description): UTF-8 ≥ 512
 * bytes → `[0x01]` + raw DEFLATE (nowrap, level 6); else `[0x00]` + UTF-8. Decoding an unknown
 * header byte treats the whole payload as UTF-8 (corrupt or pre-codec data), never throws.
 */
expect object EpisodeDescriptionCodec {
    fun encode(text: String): ByteArray

    fun decode(bytes: ByteArray): String
}
