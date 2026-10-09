// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/**
 * `import_session.sourceFormat` (02 import_session; the 05 import sources; `URL_LIST` is appended
 * at M8, `TEXT` storage needs no migration). Unknown values read back as [OPML].
 */
enum class ImportFormat { OPML, NEWPIPE_JSON, LIBRETUBE_JSON, TAKEOUT_CSV, NEUTRODYNE_BACKUP }

/** `import_session.state` (02; the 05 import lifecycle). Unknown values read back as [DONE]. */
enum class ImportState { PREVIEW, COMMITTED, FETCHING, DONE, CANCELLED }

/**
 * `import_item.status` (02; the 05 preview/commit per-item outcomes). Unknown values read back as
 * [FETCH_FAILED].
 */
enum class ImportItemStatus {
    PREVIEW,
    QUEUED,
    SUBSCRIBED,
    ALREADY_SUBSCRIBED,
    MERGED,
    DUPLICATE_IN_FILE,
    INVALID_URL,
    NOT_A_FEED,
    NO_MEDIA,
    AUTH_REQUIRED,
    GONE,
    FETCH_FAILED,
    YOUTUBE_UNSUPPORTED_YET,
}

/** What an `import_item` row refers to (02 New names). Unknown values read back as [RSS]. */
enum class ImportItemKind { RSS, YOUTUBE }
