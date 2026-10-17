// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.youtube.api

/**
 * Validators and small pure helpers for YouTube's ID shapes (04 Input grammar). Everything here is
 * synchronous and side-effect-free; `YouTubeUrlClassifier` and the M8 feed builders share it.
 */
object YouTubeIds {
    /** A channel ID: "UC" plus 22 base64url characters encoding 128 bits; the last char carries padding. */
    val CHANNEL = Regex("^UC[0-9A-Za-z_-]{21}[AQgw]$")

    /** A video ID. Lenient per 04: the stricter last-character rule is not enforced. */
    val VIDEO = Regex("^[0-9A-Za-z_-]{11}$")

    /**
     * A playlist ID. YouTube does not document the grammar; real IDs are opaque base64url tokens and
     * range from 13-character `RD…` mixes to 41-character `OLAK5uy_…` Music albums. This is a sanity
     * bound, not a prefix whitelist; the classifier keeps any non-uploads `list=` value per 04.
     */
    val PLAYLIST = Regex("^[0-9A-Za-z_-]{13,64}$")

    /** [YtRef.Channel.variantsHint] when every uploads variant is on. */
    const val VARIANTS_ALL: Int = 7

    private const val PLAIN_UPLOADS_LENGTH = 24
    private const val VARIANT_UPLOADS_LENGTH = 26

    /** Long-form uploads only (`UULF`). */
    private const val VARIANTS_LONG_FORM = 1

    /** Shorts only (`UUSH`). */
    private const val VARIANTS_SHORTS = 2

    /** Live streams only (`UULV`). */
    private const val VARIANTS_LIVE = 4

    private val VARIANT_HINTS =
        mapOf(
            "UULF" to VARIANTS_LONG_FORM,
            "UUSH" to VARIANTS_SHORTS,
            "UULV" to VARIANTS_LIVE,
        )

    /**
     * Maps an uploads-playlist ID to its channel ID and variant hint, or null when [playlistId] is
     * not an uploads list or does not yield a valid channel. The prefix length comes from the total
     * LENGTH, never from the text: 24 chars is `UU` + the 22-char channel suffix (hint [VARIANTS_ALL]);
     * 26 chars is a 4-char variant prefix + the same suffix (`UULF`→1, `UUSH`→2, `UULV`→4, others no
     * hint). "UULF…" with 24 chars is therefore the plain uploads list of a channel named "UCLF…".
     */
    fun uploadsToChannel(playlistId: String): Pair<String, Int?>? {
        val prefixLength =
            when (playlistId.length) {
                PLAIN_UPLOADS_LENGTH -> 2
                VARIANT_UPLOADS_LENGTH -> 4
                else -> return null
            }
        val channelId = "UC" + playlistId.substring(prefixLength)
        if (!CHANNEL.matches(channelId)) return null

        val hint = if (prefixLength == 2) VARIANTS_ALL else VARIANT_HINTS[playlistId.take(prefixLength)]
        return channelId to hint
    }

    /** A channel ID from a feed-level `yt:channelId`, which omits the "UC" prefix. */
    fun channelFromFeedLevel(id: String): String = if (id.length == 22) "UC$id" else id
}
