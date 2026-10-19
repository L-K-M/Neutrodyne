// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.os.Bundle
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import ch.lkmc.neutrodyne.core.domain.StartMembership
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.UnplayableReason
import ch.lkmc.neutrodyne.playback.core.StartRequest

/** One wire codec for app and service. Reject malformed external bundles before any mutation. */
internal object CustomCommands {
    const val PLAY_CONTEXT = "nd.PLAY_CONTEXT"
    const val SPEED_SET_SCOPE = "nd.SPEED_SET_SCOPE"
    const val SKIP_SILENCE = "nd.SKIP_SILENCE"
    const val SPEED_CYCLE = "nd.SPEED_CYCLE"
    const val DISMISS = "nd.DISMISS"
    const val RESULT = "nd.result"
    const val EPISODE_ID = "nd.episodeId"
    const val REASON = "nd.reason"
    val names = listOf(PLAY_CONTEXT, SPEED_SET_SCOPE, SKIP_SILENCE, SPEED_CYCLE, DISMISS)

    fun command(name: String): SessionCommand = SessionCommand(name, Bundle.EMPTY)

    fun encode(request: StartRequest): Bundle =
        Bundle().apply {
            when (request) {
                StartRequest.Resume -> {
                    putString("mode", "RESUME")
                }

                is StartRequest.Episode -> {
                    require(request.externalQueue == null) { "External lists enter through Media3" }
                    putLong("startEpisodeId", request.episodeId)
                    request.positionMs?.let { putLong("startPositionMs", it) }
                }

                is StartRequest.Feed -> {
                    require(request.membership == StartMembership.ANY) {
                        "Browse validation enters through Media3"
                    }
                    val type =
                        when (val source = request.source) {
                            FeedSource.All -> {
                                "ALL"
                            }

                            FeedSource.Ungrouped -> {
                                "UNGROUPED"
                            }

                            is FeedSource.Group -> {
                                putLong("contextId", source.groupId)
                                "GROUP"
                            }

                            is FeedSource.Podcast -> {
                                putLong("contextId", source.podcastId)
                                "PODCAST"
                            }
                        }
                    putString("contextType", type)
                    putString("order", request.order.name)
                    putInt("filterFlags", request.filters.toFlags())
                    putString("mediaFilter", request.filters.media.name)
                    request.filters.minSortDate?.let { putLong("minSortDate", it) }
                    request.startEpisodeId?.let { putLong("startEpisodeId", it) }
                    request.positionMs?.let { putLong("startPositionMs", it) }
                }
            }
        }

    fun decode(args: Bundle): StartRequest {
        val position =
            if (args.containsKey("startPositionMs")) {
                args.getLong("startPositionMs").also {
                    require(it >= 0)
                }
            } else {
                null
            }
        val episode =
            if (args.containsKey(
                    "startEpisodeId",
                )
            ) {
                args.getLong("startEpisodeId").also { require(it > 0) }
            } else {
                null
            }
        if (args.getString("mode") == "RESUME") return StartRequest.Resume
        val type = args.getString("contextType") ?: return StartRequest.Episode(requireNotNull(episode), position)
        val source =
            when (type) {
                "ALL" -> FeedSource.All
                "UNGROUPED" -> FeedSource.Ungrouped
                "GROUP" -> FeedSource.Group(args.getLong("contextId").also { require(it > 0) })
                "PODCAST" -> FeedSource.Podcast(args.getLong("contextId").also { require(it > 0) })
                else -> throw IllegalArgumentException("Unsupported play context")
            }
        val order = FeedOrder.valueOf(args.getString("order") ?: FeedOrder.NEWEST_FIRST.name)
        val media = MediaFilter.valueOf(args.getString("mediaFilter") ?: MediaFilter.ALL.name)
        val min = if (args.containsKey("minSortDate")) args.getLong("minSortDate").also { require(it >= 0) } else null
        val filters = FeedFilters.fromFlags(args.getInt("filterFlags"), media).copy(minSortDate = min)
        return StartRequest.Feed(source, filters, order, episode, position)
    }

    fun result(result: PlayResult): SessionResult {
        val extras = Bundle()
        val name =
            when (result) {
                PlayResult.Started -> {
                    "STARTED"
                }

                is PlayResult.NothingToPlay -> {
                    if (result.externalOnly) "NOTHING_TO_PLAY_EXTERNAL" else "NOTHING_TO_PLAY"
                }

                is PlayResult.NeedsMeteredConsent -> {
                    extras.putLong(EPISODE_ID, result.episodeId)
                    "NEEDS_METERED_CONSENT"
                }

                PlayResult.MeteredBlocked -> {
                    "METERED_BLOCKED"
                }

                PlayResult.Offline -> {
                    "OFFLINE"
                }

                PlayResult.ServiceUnavailable -> {
                    "SERVICE_UNAVAILABLE"
                }

                is PlayResult.NotPlayable -> {
                    extras.putLong(EPISODE_ID, result.episodeId)
                    extras.putString(REASON, encodeReason(result.reason))
                    "NOT_PLAYABLE"
                }
            }
        extras.putString(RESULT, name)
        return SessionResult(SessionResult.RESULT_SUCCESS, extras)
    }

    fun playResult(result: SessionResult): PlayResult {
        if (result.resultCode != SessionResult.RESULT_SUCCESS) return PlayResult.ServiceUnavailable
        val id = result.extras.getLong(EPISODE_ID)
        return when (result.extras.getString(RESULT)) {
            "STARTED" -> {
                PlayResult.Started
            }

            "NOTHING_TO_PLAY" -> {
                PlayResult.NothingToPlay()
            }

            "NOTHING_TO_PLAY_EXTERNAL" -> {
                PlayResult.NothingToPlay(true)
            }

            "NEEDS_METERED_CONSENT" -> {
                PlayResult.NeedsMeteredConsent(id)
            }

            "METERED_BLOCKED" -> {
                PlayResult.MeteredBlocked
            }

            "OFFLINE" -> {
                PlayResult.Offline
            }

            "NOT_PLAYABLE" -> {
                decodeReason(result.extras.getString(REASON))?.let { PlayResult.NotPlayable(id, it) }
                    ?: PlayResult.ServiceUnavailable
            }

            else -> {
                PlayResult.ServiceUnavailable
            }
        }
    }

    private fun encodeReason(reason: UnplayableReason): String =
        when (reason) {
            is UnplayableReason.Http -> "Http:${reason.status}"
            is UnplayableReason.YouTube -> "YouTube:${reason.availability.name}"
            is UnplayableReason.YouTubeExternal -> "YouTubeExternal:${reason.reason.name}"
            UnplayableReason.AuthRequired -> "AuthRequired"
            UnplayableReason.UnsupportedFormat -> "UnsupportedFormat"
            UnplayableReason.NoMedia -> "NoMedia"
            UnplayableReason.YouTubeExtraction -> "YouTubeExtraction"
        }

    private fun decodeReason(text: String?): UnplayableReason? =
        runCatching {
            when (text?.substringBefore(':')) {
                "Http" -> UnplayableReason.Http(requireNotNull(text.substringAfter(':').toIntOrNull()))
                "YouTube" -> UnplayableReason.YouTube(Availability.valueOf(text.substringAfter(':')))
                "YouTubeExternal" -> UnplayableReason.YouTubeExternal(ExternalReason.valueOf(text.substringAfter(':')))
                "AuthRequired" -> UnplayableReason.AuthRequired
                "UnsupportedFormat" -> UnplayableReason.UnsupportedFormat
                "NoMedia" -> UnplayableReason.NoMedia
                "YouTubeExtraction" -> UnplayableReason.YouTubeExtraction
                else -> null
            }
        }.getOrNull()
}
