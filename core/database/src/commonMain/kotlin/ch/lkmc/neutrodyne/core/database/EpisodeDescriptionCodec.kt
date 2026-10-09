// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

/**
 * The only way to read or write `episode_description.html` (02 episode_description): UTF-8 ≥ 512
 * bytes → `[0x01]` + raw DEFLATE (nowrap, level 6); else `[0x00]` + UTF-8. Decoding an unknown
 * header byte treats the whole payload as UTF-8 (corrupt or pre-codec data), never throws — that
 * clause covers only the legacy/unknown-header fallback. `[0x00]`/`[0x01]` are codec-owned:
 * a corrupt or truncated deflate body, or decoded output past the producer bound (03's 512 Ki
 * text chars per element, so at most 2 MiB of UTF-8), is a corruption signal and fails with
 * `IllegalStateException` — a stored blob can never exhaust the heap or yield partial notes.
 */
expect object EpisodeDescriptionCodec {
    fun encode(text: String): ByteArray

    fun decode(bytes: ByteArray): String
}
