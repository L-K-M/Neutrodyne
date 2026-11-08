// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wire v1: camelCase names, duration/releaseTs/timestamp/availableAt in seconds, sizes in bytes.
 * Mappers convert to API epoch milliseconds/duration milliseconds; lmt stays microseconds. All
 * payloads include ctx; credentials, cookies and database IDs never cross this boundary.
 */
@Serializable
data class YtxContext(
    val hl: String,
    val gl: String,
    val ua: String,
    val ipFamily: String? = null,
) {
    init {
        require(ipFamily == null || ipFamily == "V4" || ipFamily == "V6")
    }

    override fun toString(): String = "YtxContext(redacted)"
}

@Serializable
data class YtxVideoRequest(
    val ctx: YtxContext,
    val videoId: String,
)

@Serializable
data class YtxLookupRequest(
    val ctx: YtxContext,
    val url: String,
) {
    override fun toString(): String = "YtxLookupRequest(redacted)"
}

@Serializable
data class YtxTabRequest(
    val ctx: YtxContext,
    val channelId: String,
    val tab: String,
    val pageSize: Int = YtxLimits.TAB_PAGE_SIZE,
)

@Serializable
data class YtxSearchRequest(
    val ctx: YtxContext,
    val query: String,
    val pageSize: Int = YtxLimits.SEARCH_PAGE_SIZE,
) {
    override fun toString(): String = "YtxSearchRequest(redacted)"
}

@Serializable
data class YtxCursorRequest(
    val ctx: YtxContext,
    val cursor: String,
) {
    override fun toString(): String = "YtxCursorRequest(redacted)"
}

@Serializable
data class YtxControlRequest(
    val ctx: YtxContext,
) {
    fun toJson(): String = Json.encodeToString(serializer(), this)
}

@Serializable
data class YtxPing(
    val ready: Boolean,
)

@Serializable
data class YtxVersion(
    val ytDlp: String,
    val ejs: String?,
    val shimApi: Int,
    val python: String,
    val jsc: Boolean,
    val host: String,
)

@Serializable
data class YtxSelftest(
    val ok: Boolean,
    val missing: List<String>,
)

/** Raw format identity includes DRC/dub suffixes. No reduced itag is used for playback pins. */
@Serializable
data class YtxFormat(
    val formatId: String,
    val url: String,
    val ext: String?,
    val acodec: String?,
    val abr: Double? = null,
    val tbr: Double? = null,
    val filesize: Long? = null,
    val language: String? = null,
    val languagePreference: Int? = null,
    val formatNote: String? = null,
    val protocol: String? = null,
    val availableAt: Double? = null,
    val xtags: String? = null,
) {
    override fun toString(): String = "YtxFormat(redacted)"
}

@Serializable
data class YtxResolve(
    val videoId: String,
    val duration: Double? = null,
    val liveStatus: String? = null,
    val availability: String? = null,
    val ageLimit: Int? = null,
    val releaseTs: Long? = null,
    val formats: List<YtxFormat> = emptyList(),
) {
    override fun toString(): String = "YtxResolve(redacted)"
}

/** Description is plain UTF-8 bounded before JSON encoding; truncated chapters are not complete. */
@Serializable
data class YtxFacts(
    val videoId: String,
    val title: String? = null,
    val duration: Double? = null,
    val liveStatus: String? = null,
    val availability: String? = null,
    val releaseTs: Long? = null,
    val description: String? = null,
    val descriptionTruncated: Boolean = false,
) {
    override fun toString(): String = "YtxFacts(redacted)"
}

@Serializable
data class YtxImage(
    val url: String,
    val w: Int,
    val h: Int,
) {
    override fun toString(): String = "YtxImage(redacted)"
}

@Serializable
data class YtxLookup(
    val channelId: String,
    val title: String?,
    val handle: String?,
    val description: String?,
    val followerCount: Long?,
    val avatars: List<YtxImage>,
    val banners: List<YtxImage>,
) {
    override fun toString(): String = "YtxLookup(redacted)"
}

@Serializable
data class YtxTabItem(
    val id: String,
    val title: String?,
    val duration: Double?,
    val liveStatus: String?,
    val availability: String?,
    val timestamp: Long?,
)

@Serializable
data class YtxTabPage(
    val cursor: String?,
    val items: List<YtxTabItem>,
    val hasMore: Boolean,
) {
    override fun toString(): String = "YtxTabPage(items=${items.size}, hasMore=$hasMore)"
}

@Serializable
data class YtxSearchHit(
    val channelId: String,
    val title: String?,
    val handle: String?,
    val followerCount: Long?,
    val avatar: String?,
    val description: String?,
    val verified: Boolean,
) {
    override fun toString(): String = "YtxSearchHit(redacted)"
}

@Serializable
data class YtxSearchPage(
    val cursor: String?,
    val hits: List<YtxSearchHit>,
    val hasMore: Boolean,
) {
    override fun toString(): String = "YtxSearchPage(hits=${hits.size}, hasMore=$hasMore)"
}

/** Test arming is inert in production. W4 implements explicit before-first-bind instrumentation
 * injection, including release; no intent extra/environment variable from external callers arms it.
 * W3/W12 own scrubbing and strict fail-closed replay. Synthetic data is never labelled live proof.
 */
interface YtxTestArming {
    fun arm(replayDirectory: okio.Path)

    fun clear()
}
