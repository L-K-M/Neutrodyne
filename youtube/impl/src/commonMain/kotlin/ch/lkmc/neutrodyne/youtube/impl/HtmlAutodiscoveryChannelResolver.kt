// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.impl

import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.network.NeutrodyneHttpClients
import ch.lkmc.neutrodyne.youtube.api.ChannelResolution
import ch.lkmc.neutrodyne.youtube.api.FailReason
import ch.lkmc.neutrodyne.youtube.api.MetadataDepth
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedUrls
import ch.lkmc.neutrodyne.youtube.api.YtRef
import io.ktor.http.encodeURLPathPart
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8

/** Head-first Layer A page reads. The default resolver owns the complete 20-second deadline. */
internal class HtmlAutodiscoveryChannelResolver(
    clients: NeutrodyneHttpClients,
    appLocale: () -> String,
) {
    private val http = YouTubeHttp(clients, appLocale)

    suspend fun resolve(
        ref: YtRef,
        depth: MetadataDepth,
        remember: (ChannelResolution.Resolved) -> Unit = {},
    ): ChannelResolution {
        val url =
            when (ref) {
                is YtRef.Channel -> YouTubeFeedUrls.channelPage(ref.id)

                is YtRef.Handle -> "https://www.youtube.com/@${ref.handle.removePrefix("@").encodeURLPathPart()}"

                is YtRef.LegacyPath -> "https://www.youtube.com/${ref.path.split(
                    '/',
                ).joinToString("/") { it.encodeURLPathPart() }}"

                else -> return failed(FailReason.NOT_A_CHANNEL_LINK)
            }
        return page(url, depth, PageKind.CHANNEL, remember)
    }

    internal suspend fun watch(videoId: String): ChannelResolution =
        page(YouTubeFeedUrls.watch(videoId), MetadataDepth.ID_ONLY, PageKind.WATCH)

    private suspend fun page(
        url: String,
        depth: MetadataDepth,
        kind: PageKind,
        remember: (ChannelResolution.Resolved) -> Unit = {},
    ): ChannelResolution =
        try {
            val reply =
                http.get(
                    url,
                    HttpClientKind.FEED,
                    "text/html",
                ) { channel, base -> scan(channel, base, depth, kind, remember) }
            reply.body ?: reply.failure()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(FailReason.NETWORK, e)
        }

    internal enum class PageKind { CHANNEL, WATCH }

    internal companion object {
        const val HEAD_CAP = 1536 * 1024
        const val PAGE_CAP = 3 * 1024 * 1024
        private const val WINDOW = ChannelPageParser.BANNER_WINDOW
        private const val OVERLAP = 2 * 1024

        internal suspend fun scan(
            channel: ByteReadChannel,
            base: String,
            depth: MetadataDepth,
            kind: PageKind = PageKind.CHANNEL,
            remember: (ChannelResolution.Resolved) -> Unit = {},
        ): ChannelResolution {
            val head = Buffer()
            val chunk = ByteArray(8192)
            val boundary = HeadBoundary()
            var total = 0
            var ended = false
            var pending = ByteArray(0)
            while (total < HEAD_CAP && !ended) {
                val count = channel.readAvailable(chunk, 0, minOf(chunk.size, HEAD_CAP - total))
                if (count < 0) break
                if (count == 0) {
                    channel.awaitContent()
                    continue
                }
                total += count
                var used = count
                for (i in 0 until count) {
                    if (boundary.accept(chunk[i])) {
                        ended = true
                        used = i + 1
                        break
                    }
                }
                head.write(chunk, 0, used)
                if (used < count) pending = chunk.copyOfRange(used, count)
            }
            val headBytes = head.size
            val prefix = head.readUtf8()
            // A video's og:title/image describe the video, never its channel.
            val page =
                if (kind == PageKind.WATCH) {
                    ChannelPageParser.Page(ChannelPageParser.watchId(prefix), null, null, null)
                } else {
                    ChannelPageParser.parseHead(prefix, base, depth)
                }
            var id = page.channelId
            id?.let { remember(page.resolved(it)) }
            var banner = if (depth == MetadataDepth.FULL) ChannelPageParser.banner(prefix) else null
            val mayFindId = ended && headBytes < HEAD_CAP && id == null
            val totalCap = if (kind == PageKind.WATCH) HEAD_CAP else PAGE_CAP
            if (!mayFindId && (id == null || depth != MetadataDepth.FULL || banner != null)) {
                return id?.let { page.resolved(it, banner) } ?: failed(FailReason.PAGE_UNREADABLE)
            }

            // Retain enough overlap for an externalId or a banner array spanning a window boundary.
            val window = Buffer().writeUtf8(prefix.takeLast(OVERLAP)).write(pending)
            while (true) {
                val text = window.snapshot().utf8()
                if (mayFindId && id == null) {
                    id =
                        if (kind ==
                            PageKind.WATCH
                        ) {
                            ChannelPageParser.watchId(text)
                        } else {
                            ChannelPageParser.externalId(text)
                        }
                    id?.let { remember(page.resolved(it)) }
                }
                if (depth == MetadataDepth.FULL && banner == null) banner = ChannelPageParser.banner(text)
                if (id != null && (depth != MetadataDepth.FULL || banner != null)) break
                if (total >= totalCap) break
                val count = channel.readAvailable(chunk, 0, minOf(chunk.size, totalCap - total))
                if (count < 0) break
                if (count == 0) {
                    channel.awaitContent()
                    continue
                }
                total += count
                if (window.size + count > WINDOW) {
                    // Preserve an in-progress array, including sources longer than the normal overlap.
                    val start =
                        if (depth == MetadataDepth.FULL && ChannelPageParser.bannerStart(text) != null) {
                            // Byte offsets must not depend on preceding multibyte UTF-8 text.
                            window.snapshot().indexOf(BANNER_KEY).takeIf { it >= 0 }
                        } else {
                            null
                        }
                    val keep = start?.takeIf { window.size - it + count <= WINDOW }
                    window.skip(keep?.toLong() ?: (window.size - OVERLAP))
                }
                window.write(chunk, 0, count)
            }
            return id?.let { page.resolved(it, banner) } ?: failed(FailReason.PAGE_UNREADABLE)
        }

        private val BANNER_KEY = "\"imageBannerViewModel\"".encodeUtf8()
    }
}
