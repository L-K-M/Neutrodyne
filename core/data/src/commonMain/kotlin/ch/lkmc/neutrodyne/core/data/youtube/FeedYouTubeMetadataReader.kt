// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data.youtube

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.feeds.model.FeedFormat
import ch.lkmc.neutrodyne.feeds.parse.FeedParser
import ch.lkmc.neutrodyne.feeds.parse.ParseLimits
import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import ch.lkmc.neutrodyne.youtube.api.FeedChannelMetadata
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedMetadataReader
import ch.lkmc.neutrodyne.youtube.api.YouTubeIds
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Buffer
import kotlin.coroutines.coroutineContext

/** Keeps Atom parsing in the feed stack; Layer A owns transport and supplies a bounded body. */
@ContributesBinding(AppScope::class)
@Inject
internal class FeedYouTubeMetadataReader(
    private val parser: FeedParser,
    @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
) : YouTubeFeedMetadataReader {
    override suspend fun read(body: ByteArray): FeedChannelMetadata? =
        withContext(io) {
            coroutineContext.ensureActive()
            if (body.size > MAX_BYTES) return@withContext null
            val result =
                parser.parse(
                    open = { Buffer().write(body) },
                    httpCharset = null,
                    baseUrl = "https://www.youtube.com/feeds/videos.xml",
                    limits = ParseLimits(maxDocumentBytes = MAX_BYTES.toLong()),
                ) as? ParseResult.Ok ?: return@withContext null
            coroutineContext.ensureActive()
            val feed = result.feed
            if (feed.format != FeedFormat.ATOM) return@withContext null
            val id =
                feed.items
                    .firstOrNull()
                    ?.ytChannelId
                    ?.takeIf(YouTubeIds.CHANNEL::matches)
            FeedChannelMetadata(id, feed.author, feed.authorUri, feed.items.size)
        }

    private companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
    }
}
