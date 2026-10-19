// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import androidx.paging.PagingData
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.DiscardPreviewResult
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.domain.ImportRepository
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.CreateImportOptions
import ch.lkmc.neutrodyne.core.model.GroupProposal
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportItemFilter
import ch.lkmc.neutrodyne.core.model.ImportItemKind
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportItemView
import ch.lkmc.neutrodyne.core.model.ImportOptions
import ch.lkmc.neutrodyne.core.model.ImportSessionView
import ch.lkmc.neutrodyne.core.model.ImportSource
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.ImportWarning
import ch.lkmc.neutrodyne.core.model.reportSection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-memory [ImportRepository] for ViewModel/UI tests. [sessions], [items] and [proposals]
 * are the state; [create] seeds them from the `create*` fixtures, and the mutating members
 * apply the session rules of 05 (PREVIEW selection, commit to QUEUED, cancel semantics,
 * fix-ups) so observed views always derive their counts from the current items.
 */
class FakeImportRepository(
    private val clock: Clock = TestClock(),
) : ImportRepository {
    val sessions = MutableStateFlow<Map<Long, ImportSessionView>>(emptyMap())
    val items = MutableStateFlow<Map<Long, List<ImportItemView>>>(emptyMap())
    val proposals = MutableStateFlow<Map<Long, List<GroupProposal>>>(emptyMap())

    var nextSessionId = 1L

    /** null → create seeds a session and returns Success(id); a Failure short-circuits it. */
    var createOutcome: Outcome<Long, ImportError>? = null
    var confirmOutcome: Outcome<Unit, ImportError> = Outcome.Success(Unit)
    var editUrlOutcome: Outcome<Unit, AddPodcastError> = Outcome.Success(Unit)
    var enterPasswordOutcome: Outcome<Unit, AddPodcastError> = Outcome.Success(Unit)

    /** Fixtures [create] seeds the new session with. */
    var createFormat = ImportFormat.OPML
    var createItems: List<ImportItemView> = emptyList()
    var createProposals: List<GroupProposal> = emptyList()
    var createWarnings: List<ImportWarning> = emptyList()
    var createOptions: (CreateImportOptions) -> ImportOptions = { ImportOptions(targetGroupId = it.targetGroupId) }

    val calls = mutableListOf<String>()

    override suspend fun create(
        source: ImportSource,
        options: CreateImportOptions,
    ): Outcome<Long, ImportError> {
        calls += "create(${source.uri})"
        createOutcome?.let { return it }
        val id = nextSessionId++
        val session =
            ImportSessionView(
                id = id,
                sourceName = source.displayName,
                format = createFormat,
                state = ImportState.PREVIEW,
                recoveredBySalvage = false,
                warnings = createWarnings,
                options = createOptions(options),
                counts = emptyMap(),
                selectedCount = 0,
                youtubeCount = 0,
                createdAt = clock.now(),
            )
        sessions.update { it + (id to session) }
        items.update { it + (id to createItems) }
        proposals.update { it + (id to createProposals) }
        return Outcome.Success(id)
    }

    override fun observeSession(sessionId: Long): Flow<ImportSessionView?> =
        combine(sessions, items) { bySession, byItem ->
            bySession[sessionId]?.withCounts(byItem[sessionId].orEmpty())
        }

    override fun observeOpenSessions(): Flow<List<ImportSessionView>> =
        combine(sessions, items) { bySession, byItem ->
            bySession.values
                .filter { it.state in OPEN_STATES }
                .sortedWith(compareBy(ImportSessionView::createdAt, ImportSessionView::id))
                .map { it.withCounts(byItem[it.id].orEmpty()) }
        }

    override fun observeGroupProposals(sessionId: Long): Flow<List<GroupProposal>> =
        proposals.map { it[sessionId].orEmpty() }

    /** The 05 Session-and-item-states filter table, including the past-PREVIEW row. */
    override fun pagedItems(
        sessionId: Long,
        filter: ImportItemFilter,
    ): Flow<PagingData<ImportItemView>> =
        combine(sessions, items) { bySession, byItem ->
            val statuses = filterStatuses(filter, bySession[sessionId]?.state)
            val rows = byItem[sessionId].orEmpty().filter { it.status in statuses }
            PagingData.from(
                if (bySession[sessionId]?.state == ImportState.PREVIEW) {
                    rows
                } else {
                    rows.sortedWith(compareBy<ImportItemView> { it.reportSection().ordinal }.thenBy { it.ordinal })
                },
            )
        }

    /** Only PREVIEW items may toggle (05's status table); `ordinals = null` means all. */
    override suspend fun setSelected(
        sessionId: Long,
        ordinals: Set<Int>?,
        selected: Boolean,
    ) {
        calls += "setSelected($sessionId, ${ordinals ?: "all"}, $selected)"
        mutateItems(sessionId) { item ->
            if (item.status == ImportItemStatus.PREVIEW &&
                (ordinals == null || item.ordinal in ordinals)
            ) {
                item.copy(selected = selected)
            } else {
                item
            }
        }
    }

    override suspend fun updateOptions(
        sessionId: Long,
        change: (ImportOptions) -> ImportOptions,
    ) {
        calls += "updateOptions($sessionId)"
        sessions.update { bySession ->
            val session = bySession[sessionId] ?: return@update bySession
            if (session.state != ImportState.PREVIEW) return@update bySession

            bySession + (sessionId to session.copy(options = session.options.withUserChanges(change)))
        }
    }

    override suspend fun confirm(sessionId: Long): Outcome<Unit, ImportError> {
        calls += "confirm($sessionId)"
        if (confirmOutcome is Outcome.Failure) return confirmOutcome
        val committedAt = clock.now()
        while (true) {
            val current = sessions.value
            val session = current[sessionId] ?: return Outcome.Failure(ImportError.SessionGone)
            if (session.state != ImportState.PREVIEW) return confirmOutcome
            val committed =
                session.copy(
                    state = ImportState.COMMITTED,
                    options = session.options.copy(committedAt = committedAt),
                )
            if (sessions.compareAndSet(current, current + (sessionId to committed))) break
        }
        mutateItems(sessionId) { item ->
            if (item.status == ImportItemStatus.PREVIEW && item.selected) {
                item.copy(status = ImportItemStatus.QUEUED)
            } else {
                item
            }
        }
        return confirmOutcome
    }

    /** 05: cancel deletes a PREVIEW session (payload and all); later states go CANCELLED. */
    override suspend fun cancel(sessionId: Long) {
        calls += "cancel($sessionId)"
        val session = sessions.value[sessionId] ?: return
        if (session.state == ImportState.PREVIEW) {
            sessions.update { it - sessionId }
            items.update { it - sessionId }
            proposals.update { it - sessionId }
            return
        }
        sessions.update { it + (sessionId to session.copy(state = ImportState.CANCELLED)) }
    }

    override suspend fun discardPreview(sessionId: Long): DiscardPreviewResult {
        calls += "discardPreview($sessionId)"
        // Claim the session atomically, just as the production write transaction does.
        while (true) {
            val current = sessions.value
            val session = current[sessionId] ?: return DiscardPreviewResult.NOT_PREVIEW
            if (session.state != ImportState.PREVIEW || session.options.restoreRequestedAt != null) {
                return DiscardPreviewResult.NOT_PREVIEW
            }
            if (!sessions.compareAndSet(current, current - sessionId)) continue

            items.update { it - sessionId }
            proposals.update { it - sessionId }
            return DiscardPreviewResult.DISCARDED
        }
    }

    /** Re-queues the need-attention statuses; a DONE session goes back to FETCHING (05). */
    override suspend fun retry(
        sessionId: Long,
        ordinals: Set<Int>,
    ) {
        calls += "retry($sessionId, $ordinals)"
        mutateItems(sessionId) { item ->
            if (item.ordinal in ordinals && item.status in NEEDS_ATTENTION_STATUSES) {
                item.copy(status = ImportItemStatus.QUEUED)
            } else {
                item
            }
        }
        sessions.update { bySession ->
            val session = bySession[sessionId] ?: return@update bySession
            if (session.state != ImportState.DONE) return@update bySession
            bySession + (sessionId to session.copy(state = ImportState.FETCHING))
        }
    }

    override suspend fun editUrl(
        sessionId: Long,
        ordinal: Int,
        input: String,
    ): Outcome<Unit, AddPodcastError> {
        calls += "editUrl($sessionId, $ordinal)"
        return editUrlOutcome
    }

    override suspend fun enterPassword(
        sessionId: Long,
        ordinal: Int,
        credentials: BasicCredentials,
    ): Outcome<Unit, AddPodcastError> {
        calls += "enterPassword($sessionId, $ordinal)"
        return enterPasswordOutcome
    }

    /** Removes only items this session created — QUEUED and the need-attention statuses (05). */
    override suspend fun remove(
        sessionId: Long,
        ordinals: Set<Int>,
    ) {
        calls += "remove($sessionId, $ordinals)"
        mutateItems(sessionId) { item ->
            if (item.ordinal in ordinals && item.status in REMOVABLE_STATUSES) null else item
        }
    }

    /** Applies [transform] to the session's items; returning null drops the item. */
    private fun mutateItems(
        sessionId: Long,
        transform: (ImportItemView) -> ImportItemView?,
    ) {
        items.update { byItem ->
            byItem + (sessionId to byItem[sessionId].orEmpty().mapNotNull(transform))
        }
    }

    private fun ImportSessionView.withCounts(sessionItems: List<ImportItemView>): ImportSessionView =
        copy(
            counts = sessionItems.groupingBy { it.status }.eachCount(),
            selectedCount = sessionItems.count { it.selected },
            youtubeCount = sessionItems.count { it.selected && it.kind == ImportItemKind.YOUTUBE },
            deferredCount =
                sessionItems.count {
                    it.status == ImportItemStatus.FETCH_FAILED &&
                        it.errorDetail == "DEFERRED"
                },
            noMediaOrdinals =
                sessionItems
                    .filter { it.status == ImportItemStatus.NO_MEDIA }
                    .map { it.ordinal }
                    .toSet(),
        )

    private companion object {
        /** 05 Session and item states: open = not yet DONE or CANCELLED. */
        val OPEN_STATES: Set<ImportState> =
            setOf(ImportState.PREVIEW, ImportState.COMMITTED, ImportState.FETCHING)

        /** 05's NEEDS_ATTENTION report set — also the statuses retry re-queues. */
        val NEEDS_ATTENTION_STATUSES: Set<ImportItemStatus> =
            setOf(
                ImportItemStatus.NOT_A_FEED,
                ImportItemStatus.NO_MEDIA,
                ImportItemStatus.AUTH_REQUIRED,
                ImportItemStatus.GONE,
                ImportItemStatus.FETCH_FAILED,
            )

        /** remove() reaches only podcasts this session created (05). */
        val REMOVABLE_STATUSES: Set<ImportItemStatus> = NEEDS_ATTENTION_STATUSES + ImportItemStatus.QUEUED

        /** 05's filter → status mapping (Session and item states). */
        fun filterStatuses(
            filter: ImportItemFilter,
            state: ImportState?,
        ): Set<ImportItemStatus> =
            when (filter) {
                ImportItemFilter.ALL -> {
                    ImportItemStatus.entries.toSet()
                }

                ImportItemFilter.NEW_ONLY -> {
                    setOf(ImportItemStatus.PREVIEW)
                }

                ImportItemFilter.NOT_IMPORTED -> {
                    setOf(
                        ImportItemStatus.DUPLICATE_IN_FILE,
                        ImportItemStatus.INVALID_URL,
                        ImportItemStatus.YOUTUBE_UNSUPPORTED_YET,
                    ) +
                        // Past PREVIEW, unselected items also count as not imported.
                        if (state != null && state != ImportState.PREVIEW) {
                            setOf(ImportItemStatus.PREVIEW)
                        } else {
                            emptySet()
                        }
                }

                ImportItemFilter.NEEDS_ATTENTION -> {
                    NEEDS_ATTENTION_STATUSES
                }
            }
    }
}
