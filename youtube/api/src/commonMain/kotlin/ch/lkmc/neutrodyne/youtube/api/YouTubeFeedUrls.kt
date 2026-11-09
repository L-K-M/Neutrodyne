// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.youtube.api

import ch.lkmc.neutrodyne.core.model.YouTubeVariantBits

object YouTubeFeedUrls {
    const val NASA_CONTROL_CHANNEL_ID = "UCLA_DiR1FfKNvjuUpBHmylQ"
    const val MKBHD_CONTROL_CHANNEL_ID = "UCBJycsmduvYEL83R_U4JriQ"
    val CONTROL_CHANNEL_IDS: List<String> = listOf(NASA_CONTROL_CHANNEL_ID, MKBHD_CONTROL_CHANNEL_ID)

    fun canonical(channelId: String): String = "$FEED?channel_id=${channel(channelId)}"

    fun channelPage(channelId: String): String = "https://www.youtube.com/channel/${channel(channelId)}"

    fun watch(videoId: String): String {
        require(YouTubeIds.VIDEO.matches(videoId))
        return "https://www.youtube.com/watch?v=$videoId"
    }

    /** Poll URLs are ephemeral; only canonical is an identity or export URL. */
    fun pollUrls(
        channelId: String,
        variants: Int,
    ): List<VariantUrl> {
        require(YouTubeVariantBits.isValid(variants))
        val suffix = channel(channelId).removePrefix("UC")
        return listOf(
            YouTubeVariantBits.LONG_FORM to "UULF",
            YouTubeVariantBits.SHORTS to "UUSH",
            YouTubeVariantBits.LIVE to "UULV",
        ).filter { (bit, _) -> variants and bit != 0 }
            .map { (bit, prefix) -> VariantUrl(bit, "$FEED?playlist_id=$prefix$suffix") }
    }

    private fun channel(id: String): String {
        require(YouTubeIds.CHANNEL.matches(id))
        return id
    }

    private const val FEED = "https://www.youtube.com/feeds/videos.xml"
}

data class VariantUrl(
    val bit: Int,
    val url: String,
)
