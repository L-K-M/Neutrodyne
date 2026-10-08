// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/**
 * `download.state` (02 Naming and types; the 07 download state machine). Unknown values read back as
 * [FAILED] — an unknown download is not resumed silently.
 */
enum class DownloadState {
    QUEUED,
    RESOLVING,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    COMPLETED,
    MISSING,
    FAILED,
}

/** Which concurrency lane a download occupies (02 `download.lane`; the 07 lanes). */
enum class DownloadLane { MANUAL, AUTO }

/** Why a non-active download is waiting (02 `download.waitReason`; [NONE] while it runs). */
enum class WaitReason {
    NONE,
    NETWORK,
    UNMETERED_NETWORK,
    CHARGING,
    STORAGE,
    BACKOFF,
    SYSTEM,
    NEEDS_FOREGROUND,
    SLOT,
}

/**
 * Why a download failed (02 `download.error`; the 07 error vocabulary). Unknown values read back as
 * [UNKNOWN].
 */
enum class DownloadError {
    HTTP_NOT_FOUND,
    HTTP_GONE,
    HTTP_AUTH,
    HTTP_CLIENT,
    HTTP_SERVER,
    HTTP_RATE_LIMITED,
    NETWORK_IO,
    NOT_MEDIA,
    SIZE_MISMATCH,
    STORAGE_FULL,
    STORAGE_UNAVAILABLE,
    YT_UNAVAILABLE,
    YT_EXTRACTION,
    YT_FORBIDDEN,
    UNSUPPORTED_STREAM,
    CANCELLED_BY_SYSTEM,
    UNKNOWN,
}

/** What a `download` row downloads (02 `download.sourceKind`). */
enum class SourceKind { RSS_ENCLOSURE, YOUTUBE }
