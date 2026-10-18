// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import androidx.paging.PagingData
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.CreateImportOptions
import ch.lkmc.neutrodyne.core.model.GroupProposal
import ch.lkmc.neutrodyne.core.model.ImportItemFilter
import ch.lkmc.neutrodyne.core.model.ImportItemView
import ch.lkmc.neutrodyne.core.model.ImportOptions
import ch.lkmc.neutrodyne.core.model.ImportSessionView
import ch.lkmc.neutrodyne.core.model.ImportSource
import ch.lkmc.neutrodyne.core.model.NetError
import kotlinx.coroutines.flow.Flow

/**
 * Import sessions — OPML, the other file formats, and `NEUTRODYNE_BACKUP` restore sessions
 * (05 OPML import). Implemented by `:core:data` in M3.
 */
interface ImportRepository {
    /**
     * Copies the payload, parses and classifies it, and inserts the `PREVIEW` session
     * (05 1–3); the value is the new session id.
     */
    suspend fun create(
        source: ImportSource,
        options: CreateImportOptions = CreateImportOptions(),
    ): Outcome<Long, ImportError>

    fun observeSession(sessionId: Long): Flow<ImportSessionView?>

    fun pagedItems(
        sessionId: Long,
        filter: ImportItemFilter,
    ): Flow<PagingData<ImportItemView>>

    fun observeGroupProposals(sessionId: Long): Flow<List<GroupProposal>>

    /** `ordinals = null` toggles every selectable (PREVIEW) item (05). */
    suspend fun setSelected(
        sessionId: Long,
        ordinals: Set<Int>?,
        selected: Boolean,
    )

    /**
     * Edits PREVIEW options against the latest row in one transaction; later states are
     * unchanged. Use [ImportOptions.withUserChanges] to preserve parser/worker fields and
     * freeze a requested restore. Only [BackupRepository.restore] may request a restore.
     */
    suspend fun updateOptions(
        sessionId: Long,
        change: (ImportOptions) -> ImportOptions,
    )

    /** Commits the selected items in chunks and schedules the fetch (05 6. Commit). */
    suspend fun confirm(sessionId: Long): Outcome<Unit, ImportError>

    /** PREVIEW: deletes the session; COMMITTED/FETCHING: stops the worker, state CANCELLED. */
    suspend fun cancel(sessionId: Long)

    /** Atomically deletes only an owned PREVIEW, keeping active imports and restore intents. */
    suspend fun discardPreview(sessionId: Long): DiscardPreviewResult

    suspend fun retry(
        sessionId: Long,
        ordinals: Set<Int>,
    )

    suspend fun editUrl(
        sessionId: Long,
        ordinal: Int,
        input: String,
    ): Outcome<Unit, AddPodcastError>

    suspend fun enterPassword(
        sessionId: Long,
        ordinal: Int,
        credentials: BasicCredentials,
    ): Outcome<Unit, AddPodcastError>

    /** Unsubscribes podcasts this session created; never ALREADY_SUBSCRIBED/MERGED items (05). */
    suspend fun remove(
        sessionId: Long,
        ordinals: Set<Int>,
    )

    /** PREVIEW, COMMITTED and FETCHING sessions, for 08's resume banner. */
    fun observeOpenSessions(): Flow<List<ImportSessionView>>
}

/** Why an import call failed (05 Error handling and failure modes). */
sealed interface ImportError {
    /** Payload over 50 MiB. */
    data object TooLarge : ImportError

    /** Grant lost, provider failure, unreadable file. */
    data object Unreadable : ImportError

    /** Import-from-URL failed; carries the feed client's error. */
    data class Network(
        val error: NetError,
    ) : ImportError

    /** Recognised but not an import list. */
    data class Unsupported(
        val kind: UnsupportedSource,
    ) : ImportError

    /** Rejected by the safety caps (05 Sniff and parse, Archive guard). */
    data class Hostile(
        val cap: HostileInput,
    ) : ImportError

    /** No feed entries found. */
    data object Empty : ImportError

    data object Storage : ImportError

    data object SessionGone : ImportError
}

/** What an unsupported import payload turned out to be (05's `Unsupported` reasons). */
enum class UnsupportedSource { ZIP, TGZ, JSON, SINGLE_FEED, UNKNOWN }

/** The safety cap an import payload broke (05's `Hostile` reasons). */
enum class HostileInput {
    ENTITY,
    TAG_TOO_LONG,
    TOO_DEEP,
    TOO_MANY_FEEDS,
    TOO_MANY_OUTLINES,
    ZIP_BOMB,
    ZIP_PATH,
    ZIP_ENTRIES,
}

/** A preview discard must never be mistaken for an active-import stop or a failed delete. */
enum class DiscardPreviewResult { DISCARDED, NOT_PREVIEW, FAILED }
