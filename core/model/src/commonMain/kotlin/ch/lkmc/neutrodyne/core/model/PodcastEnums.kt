// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/**
 * Where a podcast comes from (02 Naming and types). Stored in `podcast.sourceType`; an unknown
 * stored name reads back as [RSS] — a source a newer build added is treated as RSS, not dropped.
 */
enum class SourceType { RSS, YOUTUBE_CHANNEL, YOUTUBE_PLAYLIST }

/**
 * Lifecycle of a `podcast` row (02 podcast): `PENDING_FIRST_FETCH` until the first successful
 * fetch, then `ACTIVE`. Unknown stored names read back as [ACTIVE].
 */
enum class PodcastStatus { PENDING_FIRST_FETCH, ACTIVE }

/**
 * Why a fetch failed, persisted on `podcast.lastErrorKind` (02 Naming and types; the 24-value list
 * owned by 03 Fetch state). Unknown values read back as [UNKNOWN].
 */
enum class FeedErrorKind {
    OFFLINE,
    TIMEOUT,
    DNS,
    CONNECTION,
    LOCAL_NETWORK_UNSUPPORTED,
    TLS_UNTRUSTED,
    TLS_CERTIFICATE_TRANSPARENCY,
    TLS_HANDSHAKE,
    HTTP_AUTH,
    HTTP_FORBIDDEN,
    HTTP_NOT_FOUND,
    HTTP_GONE,
    HTTP_RATE_LIMITED,
    HTTP_SERVER,
    HTTP_CLIENT,
    REDIRECT_LOOP,
    TOO_LARGE,
    NOT_A_FEED,
    PARSE_ERROR,
    NO_MEDIA,
    UNSUPPORTED_LIST_FEED,
    IDENTITY_CONFLICT,
    STORAGE,
    UNKNOWN,
}

/** RSS `<itunes:type>` of a show (02 New names: `podcast.showType`; null = episodic). */
enum class ShowType { EPISODIC, SERIAL }

/**
 * Why a `podcast_url_alias` row exists (02 New names: the scope revision's canonical list;
 * [SYNC] = an alias received in a podcast record's `feedKeys`, written from MS2).
 */
enum class AliasReason { SUBSCRIBE_INPUT, REDIRECT, NEW_FEED_URL, IMPORT, RESTORE, MERGE, RENORMALISED, SYNC }
