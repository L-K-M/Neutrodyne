// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.impl

import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.network.NeutrodyneHttpClients
import ch.lkmc.neutrodyne.youtube.api.ChannelResolution
import ch.lkmc.neutrodyne.youtube.api.FailReason
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedUrls
import ch.lkmc.neutrodyne.youtube.api.YouTubeUrlClassifier
import ch.lkmc.neutrodyne.youtube.api.YtRef
import io.ktor.http.URLBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal class OEmbedClient(
    clients: NeutrodyneHttpClients,
    appLocale: () -> String,
) {
    private val http = YouTubeHttp(clients, appLocale)
    private val classifier = YouTubeUrlClassifier()

    internal sealed interface Result {
        data class Author(
            val ref: YtRef,
            val name: String?,
        ) : Result

        data object WatchFallback : Result

        data class Failure(
            val resolution: ChannelResolution,
        ) : Result
    }

    internal suspend fun author(videoId: String): Result {
        return try {
            val url =
                URLBuilder("https://www.youtube.com/oembed")
                    .apply {
                        parameters.append("url", YouTubeFeedUrls.watch(videoId))
                        parameters.append("format", "json")
                    }.buildString()
            val reply =
                http.get(url, HttpClientKind.API, "application/json") { channel, _ ->
                    YouTubeHttp.bounded(channel, MAX_JSON_BYTES)
                }
            when {
                reply.consent -> {
                    Result.Failure(reply.failure())
                }

                reply.code in setOf(401, 403, 404) -> {
                    Result.WatchFallback
                }

                reply.code != 200 -> {
                    Result.Failure(reply.failure())
                }

                else -> {
                    val body = reply.body ?: return Result.Failure(failed(FailReason.PAGE_UNREADABLE))
                    val obj = runCatching { Json.parseToJsonElement(body.decodeToString()) as? JsonObject }.getOrNull()
                    val authorUrl = (obj?.get("author_url") as? JsonPrimitive)?.contentOrNull
                    val ref =
                        authorUrl
                            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                            ?.let(classifier::classify)
                    // A second video/playlist is not an author. Never recurse back into oEmbed.
                    if (ref !is YtRef.Channel && ref !is YtRef.Handle && ref !is YtRef.LegacyPath) {
                        Result.Failure(failed(FailReason.PAGE_UNREADABLE))
                    } else {
                        Result.Author(ref, (obj?.get("author_name") as? JsonPrimitive)?.contentOrNull)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Failure(failed(FailReason.NETWORK, e))
        }
    }

    private companion object {
        const val MAX_JSON_BYTES = 64 * 1024
    }
}
