// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.api

/** Source rules only; fetching, pinning and cropping belong to core:artwork. */
public object YouTubeThumbnails {
    public val THUMB = Regex("""^https?://i\d?\.ytimg\.com/vi(?:_webp)?/([A-Za-z0-9_-]{11})/(\w+)\.(?:jpg|webp)$""")

    public fun avatar(
        url: String,
        px: Int = AVATAR_PX,
    ): String {
        require(px > 0)
        return AVATAR_SIZE.replace(url) { "=s$px" }
    }

    public fun video(
        videoId: String,
        variant: ThumbVariant = ThumbVariant.MQ,
    ): String {
        require(YouTubeIds.VIDEO.matches(videoId))
        return "https://i.ytimg.com/vi/$videoId/${variant.fileName}.jpg"
    }

    public fun chainFor(widthPx: Int): List<ThumbVariant> =
        if (widthPx <=
            ROW_WIDTH_PX
        ) {
            listOf(ThumbVariant.MQ)
        } else {
            listOf(ThumbVariant.MAXRES, ThumbVariant.HQ720, ThumbVariant.MQ)
        }

    /** Banners are lazy, unpinned detail art; prefer the least bandwidth that meets the header. */
    public fun pickBanner(sources: List<BannerSource>): String? {
        val usable = sources.filter { it.width > 0 && it.url.isNotBlank() }
        return (usable.filter { it.width >= BANNER_MIN_PX }.minByOrNull { it.width } ?: usable.maxByOrNull { it.width })
            ?.url
    }

    public const val AVATAR_PX: Int = 900
    public const val ROW_WIDTH_PX: Int = 320
    private const val BANNER_MIN_PX = 1280
    private val AVATAR_SIZE = Regex("=s[0-9]+")
}
