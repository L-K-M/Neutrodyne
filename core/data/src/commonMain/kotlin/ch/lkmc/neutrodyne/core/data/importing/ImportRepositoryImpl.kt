// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.importing

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.database.GroupRow
import ch.lkmc.neutrodyne.core.database.ImportItemEntity
import ch.lkmc.neutrodyne.core.database.ImportItemTallies
import ch.lkmc.neutrodyne.core.database.ImportSessionEntity
import ch.lkmc.neutrodyne.core.database.ImportStatusCount
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.domain.ImportRepository
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.CreateImportOptions
import ch.lkmc.neutrodyne.core.model.GroupProposal
import ch.lkmc.neutrodyne.core.model.ImportItemFilter
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportItemView
import ch.lkmc.neutrodyne.core.model.ImportOptions
import ch.lkmc.neutrodyne.core.model.ImportSessionView
import ch.lkmc.neutrodyne.core.model.ImportSource
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.feeds.groups.GroupNames
import ch.lkmc.neutrodyne.feeds.identity.PrivateFeedUrls
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * `ImportRepository` (05 OPML import) over the real pipeline: [create] and [confirm] delegate
 * to [ImportSessions]/[ImportCommitter]; everything else is a mapping or guarded transaction
 * over `ImportDao`, with the §8 fix-ups in [ImportFixUps].
 *
 * - `setSelected`/`updateOptions` are `PREVIEW`-only write transactions — the options edit is
 *   a read-modify-write through [ImportOptions.withUserChanges] against the latest stored row,
 *   so a stale screen can never overwrite parser/worker fields or a requested restore.
 * - `pagedItems` maps the filter to 05's status table and pages the DAO's `PagingSource`; the
 *   session state feeds `NOT_IMPORTED`'s "unselected rows count too" half. Search stays in the
 *   UI layer (`PagingData.filter` on title/host, 05 §5).
 * - `observeGroupProposals` joins `options.sourceGroups` (which carries empty folders) with
 *   per-item `groupNamesJson` counts and the existing groups — in Kotlin, since 02 forbids SQL
 *   inside the JSON column. Item-table changes arrive debounced on [cpu] (05 Threading).
 * - `cancel` deletes a `PREVIEW` session payload-then-row, stops the host and records
 *   `CANCELLED` for `COMMITTED`/`FETCHING` (podcasts stay), and leaves `DONE` alone.
 */
@SingleIn(AppScope::class)
@Inject
internal class ImportRepositoryImpl(
    private val db: NeutrodyneDatabase,
    private val sessions: ImportSessions,
    private val committer: ImportCommitter,
    private val fixUps: ImportFixUps,
    private val payloads: PayloadStore,
    private val workScheduler: ImportWorkScheduler,
    @Dispatcher(NeutrodyneDispatchers.Default) private val cpu: CoroutineDispatcher,
    @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
) : ImportRepository {
    override suspend fun create(
        source: ImportSource,
        options: CreateImportOptions,
    ): Outcome<Long, ImportError> = sessions.create(source, options)

    override fun observeSession(sessionId: Long): Flow<ImportSessionView?> = sessionViewFlow(sessionId)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeOpenSessions(): Flow<List<ImportSessionView>> =
        db.importDao().observeOpenSessions().flatMapLatest { rows ->
            if (rows.isEmpty()) {
                flowOf(emptyList())
            } else {
                // A session deleted between the outer emission and its view's first read drops
                // out of the list rather than failing the flow.
                combine(rows.map { sessionViewFlow(it.id) }) { views -> views.filterNotNull() }
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun pagedItems(
        sessionId: Long,
        filter: ImportItemFilter,
    ): Flow<PagingData<ImportItemView>> =
        db
            .importDao()
            .observeSession(sessionId)
            .map { it?.state }
            .distinctUntilChanged()
            .flatMapLatest { state ->
                Pager(PAGING_CONFIG) {
                    db.importDao().pagedItems(sessionId, filterStatuses(filter, state))
                }.flow.map { data -> data.map(::itemView) }
            }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    override fun observeGroupProposals(sessionId: Long): Flow<List<GroupProposal>> =
        combine(
            db.importDao().observeSession(sessionId),
            // The item table is the high-frequency writer here; 05's debounce applies to it.
            db.importDao().observeItemTallies(sessionId).debounce(PROPOSAL_DEBOUNCE_MS),
            db.groupDao().observeGroups(),
        ) { session, _, groups -> session to groups }
            .mapLatest { (session, groups) ->
                session ?: return@mapLatest emptyList()
                withContext(cpu) { proposalsOf(session, groups) }
            }

    override suspend fun setSelected(
        sessionId: Long,
        ordinals: Set<Int>?,
        selected: Boolean,
    ) {
        db.withWriteTransaction {
            val dao = db.importDao()
            val session = dao.sessionById(sessionId) ?: return@withWriteTransaction Unit
            if (session.state != ImportState.PREVIEW) return@withWriteTransaction Unit
            if (ordinals == null) {
                dao.setSelectedByStatus(sessionId, ImportItemStatus.entries, selected)
            } else {
                dao.setSelectedOrdinals(sessionId, ordinals, selected)
            }
        }
    }

    override suspend fun updateOptions(
        sessionId: Long,
        change: (ImportOptions) -> ImportOptions,
    ) {
        db.withWriteTransaction {
            val dao = db.importDao()
            val session = dao.sessionById(sessionId) ?: return@withWriteTransaction Unit
            if (session.state != ImportState.PREVIEW) return@withWriteTransaction Unit
            val options = session.optionsJson?.let(ImportOptionsJson::decodeOrNull)
            if (session.optionsJson != null && options == null) {
                // The edit must not write defaults over an unreadable row — that could
                // erase a restore intent hidden inside it.
                Log.w(TAG) { "import options update skipped: unreadable options for session $sessionId" }
                return@withWriteTransaction Unit
            }
            dao.setOptionsJson(
                sessionId,
                ImportOptionsJson.encode((options ?: ImportOptions()).withUserChanges(change)),
            )
        }
    }

    override suspend fun confirm(sessionId: Long): Outcome<Unit, ImportError> = committer.confirm(sessionId)

    /**
     * 05 §9 / the state table: `PREVIEW` loses payload then row — the ownership re-check,
     * the payload delete and the row delete inside ONE write transaction, so a confirm or
     * a restore request can only land before it (the row is left with its payload) or
     * after it (the row is gone). `COMMITTED`/`FETCHING` stop the host and record
     * `CANCELLED` without touching the subscriptions; `DONE` is terminal — cancel never
     * resurrects it. A `PREVIEW` session with a requested restore is the restore host's
     * and is left alone.
     */
    override suspend fun cancel(sessionId: Long) {
        val dao = db.importDao()
        val session = dao.sessionById(sessionId) ?: return
        when (session.state) {
            ImportState.PREVIEW -> {
                // The early read is a fast path — the transaction re-claims under the lock.
                if (!session.previewOwned()) return
                // Non-cancellable so a caller cancelled mid-delete cannot strand a row
                // whose payload is gone; a failed file delete keeps the row so a later
                // cancel or sweep retries the file first.
                withContext(NonCancellable) {
                    db.withWriteTransaction {
                        val latest = dao.sessionById(sessionId) ?: return@withWriteTransaction
                        if (latest.state != ImportState.PREVIEW || !latest.previewOwned()) {
                            return@withWriteTransaction
                        }
                        val cleared =
                            latest.payloadPath?.let {
                                withContext(io) { payloads.deleteIfPresent(payloads.payloadFile(it)) }
                            } != false
                        if (!cleared) return@withWriteTransaction
                        dao.deleteSession(sessionId)
                    }
                }
            }

            ImportState.COMMITTED, ImportState.FETCHING -> {
                suspendRunCatching { workScheduler.cancel(sessionId) }
                    .onFailure { Log.w(TAG, it) { "import work cancellation failed for session $sessionId" } }
                withContext(NonCancellable) {
                    db.withWriteTransaction {
                        val latest = dao.sessionById(sessionId) ?: return@withWriteTransaction
                        if (latest.state == ImportState.COMMITTED || latest.state == ImportState.FETCHING) {
                            dao.setState(sessionId, ImportState.CANCELLED)
                        }
                    }
                }
            }

            ImportState.DONE, ImportState.CANCELLED -> {}
        }
    }

    override suspend fun retry(
        sessionId: Long,
        ordinals: Set<Int>,
    ) = fixUps.retry(sessionId, ordinals)

    override suspend fun editUrl(
        sessionId: Long,
        ordinal: Int,
        input: String,
    ): Outcome<Unit, AddPodcastError> = fixUps.editUrl(sessionId, ordinal, input)

    override suspend fun enterPassword(
        sessionId: Long,
        ordinal: Int,
        credentials: BasicCredentials,
    ): Outcome<Unit, AddPodcastError> = fixUps.enterPassword(sessionId, ordinal, credentials)

    override suspend fun remove(
        sessionId: Long,
        ordinals: Set<Int>,
    ) = fixUps.remove(sessionId, ordinals)

    /**
     * A `PREVIEW` session is cancel's only while it carries no restore intent — the shared
     * fail-closed predicate keeps an unreadable `optionsJson` (which may hide one).
     */
    private fun ImportSessionEntity.previewOwned(): Boolean = !hasRestoreIntent()

    // --- Mapping -------------------------------------------------------------------------------

    private fun sessionViewFlow(sessionId: Long): Flow<ImportSessionView?> =
        combine(
            db.importDao().observeSession(sessionId),
            db.importDao().observeProgress(sessionId),
            db.importDao().observeItemTallies(sessionId),
        ) { session, progress, tallies -> session?.toView(progress, tallies) }

    private fun ImportSessionEntity.toView(
        progress: List<ImportStatusCount>,
        tallies: ImportItemTallies,
    ): ImportSessionView =
        ImportSessionView(
            id = id,
            sourceName = sourceName,
            format = sourceFormat,
            state = state,
            recoveredBySalvage = recoveredBySalvage,
            warnings = warningsJson?.let(ImportWarningJson::decodeAll).orEmpty(),
            options = optionsJson?.let(ImportOptionsJson::decodeOrNull) ?: ImportOptions(),
            counts = progress.associate { it.status to it.count },
            selectedCount = tallies.selectedCount,
            youtubeCount = tallies.youtubeCount,
            createdAt = createdAt,
        )

    /** Rows show monogram, title and host — never the URL (05 §5). */
    private fun itemView(item: ImportItemEntity): ImportItemView =
        ImportItemView(
            ordinal = item.ordinal,
            title = item.title ?: hostOf(item.normalizedUrl ?: item.originalUrl).orEmpty(),
            host = hostOf(item.normalizedUrl ?: item.originalUrl).orEmpty(),
            kind = item.kind,
            status = item.status,
            selected = item.selected,
            selectable = item.status == ImportItemStatus.PREVIEW,
            groupNames = groupNames(item.groupNamesJson),
            // The original URL keeps userinfo until commit redacts it; a token in the
            // normalised form flags private too (05's private-feed chip).
            isPrivate =
                PrivateFeedUrls.looksPrivate(item.originalUrl) ||
                    item.normalizedUrl?.let(PrivateFeedUrls::looksPrivate) == true,
            errorDetail = item.errorDetail,
            podcastId = item.podcastId,
        )

    /**
     * The proposal list of 05 §4–§5: `sourceGroups` order (empty folders included), member
     * counts resolved in Kotlin over `groupNamesJson`, the existing group's id and name when
     * the nameKey matches (its name wins — a user rename still wins over both).
     */
    private suspend fun proposalsOf(
        session: ImportSessionEntity,
        groups: List<GroupRow>,
    ): List<GroupProposal> {
        val options = session.optionsJson?.let(ImportOptionsJson::decodeOrNull) ?: ImportOptions()
        if (options.sourceGroups.isEmpty()) return emptyList()

        val memberCounts = HashMap<String, Int>()
        for (item in db.importDao().itemsOf(session.id)) {
            for (name in groupNames(item.groupNamesJson)) {
                // `truncate` — the same mapping `sourceGroups`' nameKeys went through at parse.
                memberCounts.merge(GroupNames.truncate(name)?.nameKey ?: continue, 1, Int::plus)
            }
        }
        val existing = groups.associateBy { it.group.nameKey }

        return options.sourceGroups.map { source ->
            val group = existing[source.nameKey]?.group
            val renamed = options.renamedGroups[source.nameKey]?.let { GroupNames.truncate(it)?.name }
            GroupProposal(
                nameKey = source.nameKey,
                name = renamed ?: group?.name ?: source.name,
                memberCount = memberCounts[source.nameKey] ?: 0,
                existingGroupId = group?.id,
                included = options.importGroups && source.nameKey !in options.excludedGroupKeys,
                excludedReason = source.excludedReason,
            )
        }
    }

    /** `groupNamesJson` — our own writers keep it valid; a corrupt row just shows no groups. */
    private fun groupNames(encoded: String): List<String> =
        try {
            itemJson.decodeFromString<List<String>>(encoded)
        } catch (_: Exception) {
            emptyList()
        }

    /** `://host/…` slice of a maybe-URL — userinfo and port are display noise, so they drop. */
    private fun hostOf(url: String?): String? =
        url
            ?.substringAfter("://", url)
            ?.substringBefore('/')
            ?.substringBefore('?')
            ?.substringBefore('#')
            ?.substringAfterLast('@')
            ?.substringBefore(':')
            ?.takeIf(String::isNotEmpty)

    private companion object {
        const val TAG = "ImportRepository"

        /** 05 Threading: group proposals of a 10,000-item preview debounce 100 ms. */
        const val PROPOSAL_DEBOUNCE_MS = 100L

        /** 05's filter → status table (Session and item states); state feeds NOT_IMPORTED. */
        fun filterStatuses(
            filter: ImportItemFilter,
            state: ImportState?,
        ): List<ImportItemStatus> =
            when (filter) {
                ImportItemFilter.ALL -> {
                    ImportItemStatus.entries
                }

                ImportItemFilter.NEW_ONLY -> {
                    listOf(ImportItemStatus.PREVIEW)
                }

                ImportItemFilter.NOT_IMPORTED -> {
                    listOf(
                        ImportItemStatus.DUPLICATE_IN_FILE,
                        ImportItemStatus.INVALID_URL,
                        ImportItemStatus.YOUTUBE_UNSUPPORTED_YET,
                    ) +
                        // Past PREVIEW, unselected items also count as not imported.
                        if (state != null && state != ImportState.PREVIEW) {
                            listOf(ImportItemStatus.PREVIEW)
                        } else {
                            emptyList()
                        }
                }

                ImportItemFilter.NEEDS_ATTENTION -> {
                    listOf(
                        ImportItemStatus.NOT_A_FEED,
                        ImportItemStatus.NO_MEDIA,
                        ImportItemStatus.AUTH_REQUIRED,
                        ImportItemStatus.GONE,
                        ImportItemStatus.FETCH_FAILED,
                    )
                }
            }

        /** The feed list's paging shape (08 Paging hand-off) applies to import rows too. */
        val PAGING_CONFIG =
            PagingConfig(
                pageSize = 40,
                prefetchDistance = 40,
                initialLoadSize = 80,
                enablePlaceholders = true,
                maxSize = 400,
            )

        val itemJson = Json
    }
}
