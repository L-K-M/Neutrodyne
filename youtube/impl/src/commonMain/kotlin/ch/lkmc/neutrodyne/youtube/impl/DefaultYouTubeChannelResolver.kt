// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.impl

import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.network.NeutrodyneHttpClients
import ch.lkmc.neutrodyne.youtube.api.ChannelResolution
import ch.lkmc.neutrodyne.youtube.api.ExtractorChannelLookup
import ch.lkmc.neutrodyne.youtube.api.FailReason
import ch.lkmc.neutrodyne.youtube.api.FeedChannelMetadata
import ch.lkmc.neutrodyne.youtube.api.MetadataDepth
import ch.lkmc.neutrodyne.youtube.api.ResolvedVia
import ch.lkmc.neutrodyne.youtube.api.YouTubeChannelResolver
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedMetadataReader
import ch.lkmc.neutrodyne.youtube.api.YouTubeIds
import ch.lkmc.neutrodyne.youtube.api.YtRef
import io.ktor.http.URLBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Resolves only identities and channel metadata; no engine, media or feed ingestion is started. */
class DefaultYouTubeChannelResolver(
    clients: NeutrodyneHttpClients,
    private val feedReader: YouTubeFeedMetadataReader,
    private val extractor: ExtractorChannelLookup,
    appLocale: () -> String,
) : YouTubeChannelResolver {
    private val html = HtmlAutodiscoveryChannelResolver(clients, appLocale)
    private val oembed = OEmbedClient(clients, appLocale)
    private val http = YouTubeHttp(clients, appLocale)

    override suspend fun resolve(
        ref: YtRef,
        depth: MetadataDepth,
    ): ChannelResolution {
        require(ref !is YtRef.Query) { "Channel search uses YouTubeChannelSearch" }
        if (ref is YtRef.Channel) require(YouTubeIds.CHANNEL.matches(ref.id))
        if (ref is YtRef.Video) require(YouTubeIds.VIDEO.matches(ref.id))
        var known: ChannelResolution.Resolved? = null
        return try {
            val result =
                withTimeoutOrNull(OVERALL_BUDGET_MS) {
                    resolveInput(ref, depth) { known = it }
                }
            if (result == null || result is ChannelResolution.Failed) {
                known ?: result ?: failed(FailReason.NETWORK)
            } else {
                result
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            known ?: failed(FailReason.NETWORK, e)
        }
    }

    private suspend fun resolveInput(
        ref: YtRef,
        depth: MetadataDepth,
        remember: (ChannelResolution.Resolved) -> Unit,
    ): ChannelResolution =
        when (ref) {
            is YtRef.Channel -> {
                metadata(static(ref.id, ResolvedVia.STATIC), depth, remember)
            }

            is YtRef.Handle -> {
                lookup(ref, depth, remember)
            }

            is YtRef.LegacyPath -> {
                if (ref.path.startsWith("user/")) {
                    val reply = feed("user", ref.path.removePrefix("user/"))
                    val data = reply.body
                    val id =
                        data?.channelId?.takeIf(YouTubeIds.CHANNEL::matches)
                            ?: data?.authorUrl?.let(ChannelPageParser::channelFromUrl)
                    when {
                        data != null && data.entryCount > 0 && id != null -> {
                            metadata(static(id, ResolvedVia.USER_FEED).copy(title = data.authorName), depth, remember)
                        }

                        reply.code == 404 || (reply.code == 200 && data != null && data.entryCount == 0) -> {
                            lookup(ref, depth, remember)
                        }

                        else -> {
                            if (reply.code == 200) failed(FailReason.PAGE_UNREADABLE) else reply.failure()
                        }
                    }
                } else {
                    lookup(ref, depth, remember)
                }
            }

            is YtRef.Video -> {
                when (val author = oembed.author(ref.id)) {
                    is OEmbedClient.Result.Author -> {
                        val result = resolveInput(author.ref, depth, remember)
                        if (result is ChannelResolution.Resolved) {
                            result.copy(title = result.title ?: author.name, via = ResolvedVia.OEMBED)
                        } else {
                            result
                        }
                    }

                    OEmbedClient.Result.WatchFallback -> {
                        val result = html.watch(ref.id)
                        if (result is ChannelResolution.Resolved) metadata(result, depth, remember) else result
                    }

                    is OEmbedClient.Result.Failure -> {
                        author.resolution
                    }
                }
            }

            is YtRef.Playlist -> {
                val reply = feed("playlist_id", ref.id)
                val data = reply.body
                // Playlist entries can have many uploaders; only the feed author owns the playlist.
                ChannelResolution.PlaylistUnsupported(
                    ref.id,
                    data?.authorUrl?.let(ChannelPageParser::channelFromUrl),
                    data?.authorName,
                )
            }

            is YtRef.Query -> {
                error("Query was rejected at entry")
            }
        }

    private suspend fun metadata(
        identity: ChannelResolution.Resolved,
        depth: MetadataDepth,
        remember: (ChannelResolution.Resolved) -> Unit,
    ): ChannelResolution.Resolved {
        remember(identity)
        if (depth == MetadataDepth.ID_ONLY) return identity
        // Once identity is known even a metadata 404 cannot prevent subscription.
        val result = lookup(YtRef.Channel(identity.channelId), depth)
        return if (result is ChannelResolution.Resolved && result.channelId == identity.channelId) {
            result.copy(title = result.title ?: identity.title)
        } else {
            identity
        }
    }

    private suspend fun lookup(
        ref: YtRef,
        depth: MetadataDepth,
        remember: (ChannelResolution.Resolved) -> Unit = {},
    ): ChannelResolution {
        val result =
            try {
                extractor.lookup(ref, depth)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        if (result is ChannelResolution.Resolved || result == ChannelResolution.NotFound) return result
        return html.resolve(ref, depth, remember)
    }

    private suspend fun feed(
        parameter: String,
        value: String,
    ): YouTubeHttp.Reply<FeedChannelMetadata> {
        val url =
            URLBuilder("https://www.youtube.com/feeds/videos.xml")
                .apply {
                    parameters.append(parameter, value)
                }.buildString()
        return http
            .get(url, HttpClientKind.FEED, "application/atom+xml") { channel, _ ->
                YouTubeHttp.bounded(channel, YouTubeHttp.FEED_CAP)?.let { feedReader.read(it) }
            }.let { YouTubeHttp.Reply(it.code, it.body, it.consent) }
    }

    private fun static(
        id: String,
        via: ResolvedVia,
    ) = ChannelResolution.Resolved(id, null, null, null, null, via)

    private companion object {
        const val OVERALL_BUDGET_MS = 20_000L
    }
}
