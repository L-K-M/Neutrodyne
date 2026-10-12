// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.DownloadError
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.EpisodeRow
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.TlsKind
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.availability_age_restricted
import ch.lkmc.neutrodyne.core.ui.resources.availability_kids
import ch.lkmc.neutrodyne.core.ui.resources.availability_live
import ch.lkmc.neutrodyne.core.ui.resources.availability_members_only
import ch.lkmc.neutrodyne.core.ui.resources.availability_private
import ch.lkmc.neutrodyne.core.ui.resources.availability_region_blocked
import ch.lkmc.neutrodyne.core.ui.resources.availability_unavailable
import ch.lkmc.neutrodyne.core.ui.resources.availability_upcoming
import ch.lkmc.neutrodyne.core.ui.resources.dl_err_auth
import ch.lkmc.neutrodyne.core.ui.resources.dl_err_failed
import ch.lkmc.neutrodyne.core.ui.resources.dl_err_io
import ch.lkmc.neutrodyne.core.ui.resources.dl_err_no_longer
import ch.lkmc.neutrodyne.core.ui.resources.dl_err_not_media
import ch.lkmc.neutrodyne.core.ui.resources.dl_err_ratelimit
import ch.lkmc.neutrodyne.core.ui.resources.dl_err_server
import ch.lkmc.neutrodyne.core.ui.resources.dl_err_size
import ch.lkmc.neutrodyne.core.ui.resources.dl_err_storage
import ch.lkmc.neutrodyne.core.ui.resources.dl_err_stream
import ch.lkmc.neutrodyne.core.ui.resources.dl_err_yt
import ch.lkmc.neutrodyne.core.ui.resources.dl_missing
import ch.lkmc.neutrodyne.core.ui.resources.dl_paused
import ch.lkmc.neutrodyne.core.ui.resources.dl_paused_system
import ch.lkmc.neutrodyne.core.ui.resources.dl_percent
import ch.lkmc.neutrodyne.core.ui.resources.dl_queued
import ch.lkmc.neutrodyne.core.ui.resources.dl_retry_in
import ch.lkmc.neutrodyne.core.ui.resources.dl_retry_soon
import ch.lkmc.neutrodyne.core.ui.resources.dl_storage_unavailable
import ch.lkmc.neutrodyne.core.ui.resources.dl_tap_resume
import ch.lkmc.neutrodyne.core.ui.resources.dl_wait_charging
import ch.lkmc.neutrodyne.core.ui.resources.dl_wait_network
import ch.lkmc.neutrodyne.core.ui.resources.dl_wait_storage
import ch.lkmc.neutrodyne.core.ui.resources.dl_wait_storage_android
import ch.lkmc.neutrodyne.core.ui.resources.dl_wait_storage_desktop
import ch.lkmc.neutrodyne.core.ui.resources.dl_wait_wifi
import ch.lkmc.neutrodyne.core.ui.resources.episode_opens_youtube
import ch.lkmc.neutrodyne.core.ui.resources.feed_err_auth
import ch.lkmc.neutrodyne.core.ui.resources.feed_err_forbidden
import ch.lkmc.neutrodyne.core.ui.resources.feed_err_gone
import ch.lkmc.neutrodyne.core.ui.resources.feed_err_no_media
import ch.lkmc.neutrodyne.core.ui.resources.feed_err_not_a_feed
import ch.lkmc.neutrodyne.core.ui.resources.feed_err_not_found
import ch.lkmc.neutrodyne.core.ui.resources.feed_err_ratelimit
import ch.lkmc.neutrodyne.core.ui.resources.feed_err_redirects
import ch.lkmc.neutrodyne.core.ui.resources.feed_err_server
import ch.lkmc.neutrodyne.core.ui.resources.feed_err_storage
import ch.lkmc.neutrodyne.core.ui.resources.feed_err_too_large
import ch.lkmc.neutrodyne.core.ui.resources.net_cancelled
import ch.lkmc.neutrodyne.core.ui.resources.net_connection
import ch.lkmc.neutrodyne.core.ui.resources.net_dns
import ch.lkmc.neutrodyne.core.ui.resources.net_lan
import ch.lkmc.neutrodyne.core.ui.resources.net_offline
import ch.lkmc.neutrodyne.core.ui.resources.net_other
import ch.lkmc.neutrodyne.core.ui.resources.net_timeout
import ch.lkmc.neutrodyne.core.ui.resources.net_tls_handshake
import ch.lkmc.neutrodyne.core.ui.resources.net_tls_untrusted
import ch.lkmc.neutrodyne.core.ui.resources.summary_downloaded
import ch.lkmc.neutrodyne.core.ui.resources.summary_downloading
import ch.lkmc.neutrodyne.core.ui.resources.summary_minutes_left
import ch.lkmc.neutrodyne.core.ui.resources.summary_new
import ch.lkmc.neutrodyne.core.ui.resources.summary_now_playing
import ch.lkmc.neutrodyne.core.ui.resources.summary_played
import ch.lkmc.neutrodyne.core.ui.resources.summary_video

/**
 * 08 "Row summary" — the row's merged-node `contentDescription`: "{title}. {podcast}. {date}.
 * {duration}[, {n} minutes left][. Played][. New][. Downloaded | …][. Video][. Opens in YouTube]
 * [. Unavailable: {reason}][. Now playing]".
 */
public object EpisodeRowSummary {
    /**
     * [caps] is needed only for the "Opens in YouTube" wording of external-mode YouTube rows;
     * `null` treats every row as in-app playable.
     */
    public fun describe(
        row: EpisodeRow,
        live: RowLive?,
        nowMs: Long,
        caps: RowCaps? = null,
    ): UiText {
        val parts = mutableListOf<UiText>()
        parts += UiText.Raw(row.title)
        parts += UiText.Raw(row.podcastTitle)
        parts += FeedDates.dayLabel(row.pubDate ?: row.sortDate, nowMs)

        val duration = live?.durationMs ?: row.durationMs
        parts += FeedDates.duration(duration)

        val position = live?.positionMs
        if (row.startedAt != null && row.playedAt == null && position != null && duration != null) {
            val left = ((duration - position).coerceAtLeast(0) + MS_PER_MINUTE - 1) / MS_PER_MINUTE
            parts += UiText.Plural(Res.plurals.summary_minutes_left, left.toInt(), listOf(left))
        }
        if (row.playedAt != null) parts += UiText.Res(Res.string.summary_played)
        if (row.isNew) parts += UiText.Res(Res.string.summary_new)

        val downloadState = live?.downloadState ?: row.downloadState
        when (downloadState) {
            DownloadState.COMPLETED -> {
                parts += UiText.Res(Res.string.summary_downloaded)
            }

            DownloadState.DOWNLOADING -> {
                val percent = live?.downloadProgress()?.let { (it * 100).toInt() } ?: 0
                parts += UiText.Res(Res.string.summary_downloading, listOf(percent))
            }

            else -> {}
        }
        // QUEUED waits, failures and the missing file read their wording via DownloadStatusText.
        if (downloadState != DownloadState.COMPLETED && downloadState != DownloadState.DOWNLOADING) {
            val downloadText =
                DownloadStatusText.describe(
                    state = downloadState,
                    waitReason = live?.waitReason,
                    progress = live?.downloadProgress(),
                    error = live?.lastError,
                    nextAttemptAt = live?.nextAttemptAt,
                    nowMs = nowMs,
                    platform = PlatformKind.ANDROID,
                )
            if (downloadText != null) parts += downloadText
        }

        if (row.isVideo) parts += UiText.Res(Res.string.summary_video)
        if (row.sourceType != SourceType.RSS && caps?.inAppPlayback == false) {
            parts += UiText.Res(Res.string.episode_opens_youtube)
        }
        if (row.availability != Availability.AVAILABLE) {
            parts += AvailabilityText.describe(row.availability)
        }
        if (live?.isNowPlaying == true) parts += UiText.Res(Res.string.summary_now_playing)

        return UiText.Joined(parts)
    }

    private const val MS_PER_MINUTE = 60_000L
}

/** 08 "Download status text" — wait reasons, active/failed states and [DownloadError] wording. */
public object DownloadStatusText {
    /**
     * The status line for [state]/`waitReason` (08's table). `null` = the state renders no text.
     * [platform] picks the STORAGE_UNAVAILABLE wording (07: desktop reads "download folder").
     * `STORAGE` waits of a YouTube row name the engine, which M9a adds; rows at M1a are RSS.
     */
    public fun describe(
        state: DownloadState?,
        waitReason: WaitReason?,
        progress: Float?,
        error: DownloadError?,
        nextAttemptAt: Long?,
        nowMs: Long,
        platform: PlatformKind,
    ): UiText? =
        when (state) {
            null -> {
                null
            }

            DownloadState.QUEUED -> {
                when (waitReason) {
                    null, WaitReason.NONE, WaitReason.SLOT -> {
                        UiText.Res(Res.string.dl_queued)
                    }

                    WaitReason.NETWORK -> {
                        UiText.Res(Res.string.dl_wait_network)
                    }

                    WaitReason.UNMETERED_NETWORK -> {
                        UiText.Res(Res.string.dl_wait_wifi)
                    }

                    WaitReason.CHARGING -> {
                        UiText.Res(Res.string.dl_wait_charging)
                    }

                    WaitReason.STORAGE -> {
                        if (error == DownloadError.STORAGE_UNAVAILABLE) {
                            UiText.Res(
                                if (platform == PlatformKind.ANDROID) {
                                    Res.string.dl_wait_storage_android
                                } else {
                                    Res.string.dl_wait_storage_desktop
                                },
                            )
                        } else {
                            UiText.Res(Res.string.dl_wait_storage)
                        }
                    }

                    WaitReason.BACKOFF -> {
                        retryText(nextAttemptAt, nowMs)
                    }

                    WaitReason.SYSTEM -> {
                        UiText.Res(Res.string.dl_paused_system)
                    }

                    WaitReason.NEEDS_FOREGROUND -> {
                        UiText.Res(Res.string.dl_tap_resume)
                    }
                }
            }

            DownloadState.RESOLVING, DownloadState.VERIFYING -> {
                null
            }

            DownloadState.DOWNLOADING -> {
                progress?.let { UiText.Res(Res.string.dl_percent, listOf((it * 100).toInt())) }
            }

            DownloadState.PAUSED -> {
                UiText.Res(Res.string.dl_paused)
            }

            DownloadState.COMPLETED -> {
                null
            }

            DownloadState.MISSING -> {
                if (error == DownloadError.STORAGE_UNAVAILABLE) {
                    UiText.Res(Res.string.dl_storage_unavailable)
                } else {
                    UiText.Res(Res.string.dl_missing)
                }
            }

            DownloadState.FAILED -> {
                errorText(error)
            }
        }

    /** 07's "Retrying in {relative time}" — "Retrying soon" when absent or past. */
    private fun retryText(
        nextAttemptAt: Long?,
        nowMs: Long,
    ): UiText {
        if (nextAttemptAt == null || nextAttemptAt <= nowMs) {
            return UiText.Res(Res.string.dl_retry_soon)
        }
        return UiText.Res(Res.string.dl_retry_in, listOf(FeedDates.relativeIn(nextAttemptAt, nowMs)))
    }

    /** 08's `DownloadError` table (`SERVER` has no status code at row level — 07's `DownloadStatus`). */
    public fun errorText(error: DownloadError?): UiText =
        when (error) {
            DownloadError.HTTP_NOT_FOUND, DownloadError.HTTP_GONE -> {
                UiText.Res(Res.string.dl_err_no_longer)
            }

            DownloadError.HTTP_AUTH -> {
                UiText.Res(Res.string.dl_err_auth)
            }

            DownloadError.HTTP_CLIENT,
            DownloadError.HTTP_SERVER,
            -> {
                UiText.Res(Res.string.dl_err_server)
            }

            DownloadError.HTTP_RATE_LIMITED -> {
                UiText.Res(Res.string.dl_err_ratelimit)
            }

            DownloadError.NETWORK_IO -> {
                UiText.Res(Res.string.dl_err_io)
            }

            DownloadError.NOT_MEDIA -> {
                UiText.Res(Res.string.dl_err_not_media)
            }

            DownloadError.SIZE_MISMATCH -> {
                UiText.Res(Res.string.dl_err_size)
            }

            DownloadError.STORAGE_FULL -> {
                UiText.Res(Res.string.dl_err_storage)
            }

            DownloadError.STORAGE_UNAVAILABLE -> {
                UiText.Res(Res.string.dl_storage_unavailable)
            }

            DownloadError.YT_UNAVAILABLE -> {
                UiText.Res(Res.string.availability_unavailable)
            }

            DownloadError.YT_EXTRACTION, DownloadError.YT_FORBIDDEN -> {
                UiText.Res(Res.string.dl_err_yt)
            }

            DownloadError.UNSUPPORTED_STREAM -> {
                UiText.Res(Res.string.dl_err_stream)
            }

            DownloadError.CANCELLED_BY_SYSTEM,
            DownloadError.UNKNOWN,
            null,
            -> {
                UiText.Res(Res.string.dl_err_failed)
            }
        }
}

/** 08 "Availability text" — 04's reason strings, verbatim. `AVAILABLE` maps to no text. */
public object AvailabilityText {
    public fun describe(availability: Availability): UiText =
        when (availability) {
            Availability.AVAILABLE -> UiText.Raw("")
            Availability.UPCOMING -> UiText.Res(Res.string.availability_upcoming)
            Availability.LIVE -> UiText.Res(Res.string.availability_live)
            Availability.MEMBERS_ONLY -> UiText.Res(Res.string.availability_members_only)
            Availability.AGE_RESTRICTED -> UiText.Res(Res.string.availability_age_restricted)
            Availability.REGION_BLOCKED -> UiText.Res(Res.string.availability_region_blocked)
            Availability.PRIVATE -> UiText.Res(Res.string.availability_private)
            Availability.KIDS_ONLY -> UiText.Res(Res.string.availability_kids)
            Availability.UNAVAILABLE -> UiText.Res(Res.string.availability_unavailable)
        }
}

/** 08 "Feed error text" — `FeedErrorKind` → `UiText` for banners, badges and settings rows. */
public object FeedErrorText {
    public fun describe(kind: FeedErrorKind): UiText =
        when (kind) {
            FeedErrorKind.OFFLINE -> {
                UiText.Res(Res.string.net_offline)
            }

            FeedErrorKind.TIMEOUT -> {
                UiText.Res(Res.string.net_timeout)
            }

            FeedErrorKind.DNS -> {
                UiText.Res(Res.string.net_dns)
            }

            FeedErrorKind.CONNECTION -> {
                UiText.Res(Res.string.net_connection)
            }

            FeedErrorKind.LOCAL_NETWORK_UNSUPPORTED -> {
                UiText.Res(Res.string.net_lan)
            }

            FeedErrorKind.TLS_UNTRUSTED, FeedErrorKind.TLS_CERTIFICATE_TRANSPARENCY -> {
                UiText.Res(Res.string.net_tls_untrusted)
            }

            FeedErrorKind.TLS_HANDSHAKE -> {
                UiText.Res(Res.string.net_tls_handshake)
            }

            FeedErrorKind.HTTP_AUTH -> {
                UiText.Res(Res.string.feed_err_auth)
            }

            FeedErrorKind.HTTP_FORBIDDEN -> {
                UiText.Res(Res.string.feed_err_forbidden)
            }

            FeedErrorKind.HTTP_NOT_FOUND -> {
                UiText.Res(Res.string.feed_err_not_found)
            }

            FeedErrorKind.HTTP_GONE -> {
                UiText.Res(Res.string.feed_err_gone)
            }

            FeedErrorKind.HTTP_RATE_LIMITED -> {
                UiText.Res(Res.string.feed_err_ratelimit)
            }

            FeedErrorKind.HTTP_SERVER, FeedErrorKind.HTTP_CLIENT -> {
                UiText.Res(Res.string.feed_err_server)
            }

            FeedErrorKind.REDIRECT_LOOP -> {
                UiText.Res(Res.string.feed_err_redirects)
            }

            FeedErrorKind.TOO_LARGE -> {
                UiText.Res(Res.string.feed_err_too_large)
            }

            FeedErrorKind.NOT_A_FEED,
            FeedErrorKind.PARSE_ERROR,
            FeedErrorKind.UNSUPPORTED_LIST_FEED,
            -> {
                UiText.Res(Res.string.feed_err_not_a_feed)
            }

            FeedErrorKind.NO_MEDIA -> {
                UiText.Res(Res.string.feed_err_no_media)
            }

            FeedErrorKind.STORAGE -> {
                UiText.Res(Res.string.feed_err_storage)
            }

            FeedErrorKind.IDENTITY_CONFLICT, FeedErrorKind.UNKNOWN -> {
                UiText.Res(Res.string.net_other)
            }
        }
}

/** `NetError` → `UiText` for callers that surface the raw transport kind (add sheet, sync). */
public object NetErrorText {
    public fun describe(error: NetError): UiText =
        when (error) {
            NetError.Offline -> {
                UiText.Res(Res.string.net_offline)
            }

            NetError.Timeout -> {
                UiText.Res(Res.string.net_timeout)
            }

            NetError.DnsFailure -> {
                UiText.Res(Res.string.net_dns)
            }

            NetError.ConnectionFailed -> {
                UiText.Res(Res.string.net_connection)
            }

            NetError.LocalNetworkUnsupported -> {
                UiText.Res(Res.string.net_lan)
            }

            is NetError.Tls -> {
                when (error.kind) {
                    TlsKind.UNTRUSTED_CERTIFICATE, TlsKind.CERTIFICATE_TRANSPARENCY -> {
                        UiText.Res(Res.string.net_tls_untrusted)
                    }

                    TlsKind.HANDSHAKE -> {
                        UiText.Res(Res.string.net_tls_handshake)
                    }
                }
            }

            NetError.Cancelled -> {
                UiText.Res(Res.string.net_cancelled)
            }

            is NetError.Other -> {
                UiText.Res(Res.string.net_other)
            }
        }
}
