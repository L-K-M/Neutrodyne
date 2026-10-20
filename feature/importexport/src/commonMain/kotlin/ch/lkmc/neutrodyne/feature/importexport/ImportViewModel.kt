// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.domain.ImportRepository
import ch.lkmc.neutrodyne.core.domain.ImportScreenVisibility
import ch.lkmc.neutrodyne.core.domain.ImportSessionOperations
import ch.lkmc.neutrodyne.core.domain.PodcastRepository
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.GroupProposal
import ch.lkmc.neutrodyne.core.model.IMPORT_DEFERRED_DETAIL
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportItemFilter
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportItemView
import ch.lkmc.neutrodyne.core.model.ImportReportSection
import ch.lkmc.neutrodyne.core.model.ImportSessionView
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.reportSection
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessages
import ch.lkmc.neutrodyne.core.ui.platform.NotificationPermissionRequester
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.export_err_storage
import ch.lkmc.neutrodyne.core.ui.resources.import_credentials_invalid
import ch.lkmc.neutrodyne.core.ui.resources.import_err_confirm
import ch.lkmc.neutrodyne.core.ui.resources.import_err_gone
import ch.lkmc.neutrodyne.core.ui.resources.import_err_network
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class ImportUiState(
    /** False until the first `observeSession` emission arrives; a null session is "gone". */
    val loaded: Boolean = false,
    val session: ImportSessionView? = null,
    val proposals: List<GroupProposal> = emptyList(),
    /** The existing groups offered by the "Put everything into group…" picker. */
    val existingGroups: List<Group> = emptyList(),
    /** A confirm, cancel or fix-up data job is in flight — edits and actions stay frozen meanwhile. */
    val busy: Boolean = false,
    /** Ephemeral chip state (08: ALL / Only new / Not imported; NEEDS_ATTENTION in the report). */
    val filter: ImportItemFilter = ImportItemFilter.ALL,
    /** Ephemeral title/host search, applied to the paged stream while in PREVIEW. */
    val query: String = "",
    val notificationsBlocked: Boolean = false,
)

/**
 * The `ImportKey` screen's controller (05 Preview contract): a validated session id, never the
 * raw key string. The session, proposals, persisted options/selections and counts all flow from
 * `ImportRepository`; `GroupRepository` supplies the target-group choices and the shared name
 * validation. The paged stream re-reads on every filter change, so a stale preview-only filter
 * cannot hide committed work: progress always uses ALL, while terminal reports retain their
 * filters. The preview-only search and "Only new" chip do not apply to reports.
 *
 * Preview edits (`setSelected`, `updateOptions`) are serialized in acceptance order;
 * accepted edits, [confirm] and [cancelSession] run on `@ApplicationScope`, so a disappearing
 * route cannot take them down and the job itself owns `busy`. A `NEUTRODYNE_BACKUP` PREVIEW
 * session renders the unavailable body (the restore preview is D4) and no path here calls the
 * normal import [ImportRepository.confirm] for it — the committer would reject it anyway.
 */
internal class ImportViewModel
    @AssistedInject
    constructor(
        @Assisted private val sessionId: Long,
        private val imports: ImportRepository,
        private val groups: GroupRepository,
        private val visibility: ImportScreenVisibility,
        @ApplicationScope private val appScope: CoroutineScope,
        private val podcasts: PodcastRepository,
        private val operations: ImportSessionOperations,
    ) : ViewModel() {
        private val state = MutableStateFlow(ImportUiState())

        // The UI dispatcher owns this tail; accepted edits survive leaving the route.
        private var pendingEdit: Job? = null
        private val localBusy = MutableStateFlow(false)
        val uiState = state.asStateFlow()
        private val userMessages = UserMessages()
        val messages = userMessages.flow

        init {
            viewModelScope.launch {
                operations.busySessions.collect { updateBusy() }
            }
            // 05 §8: the done notification is suppressed while this session's screen is open.
            visibility.enter(sessionId)
            viewModelScope.launch {
                imports.observeSession(sessionId).collect { session ->
                    state.update { it.copy(loaded = true, session = session) }
                }
            }
            viewModelScope.launch {
                imports.observeGroupProposals(sessionId).collect { proposals ->
                    state.update { it.copy(proposals = proposals) }
                }
            }
            viewModelScope.launch {
                groups.observeGroups().collect { list ->
                    state.update { it.copy(existingGroups = list) }
                }
            }
        }

        /**
         * The session's rows for the current effective filter. "Only new" and the search text
         * exist for the preview. Progress pages every row; terminal reports allow their own
         * filters over the same session's items.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        val items: Flow<PagingData<ImportItemView>> =
            state
                .map {
                    when (it.session?.state) {
                        ImportState.PREVIEW -> it.filter to it.query
                        ImportState.COMMITTED, ImportState.FETCHING -> ImportItemFilter.ALL to ""
                        else -> effectiveFilter(it.filter) to ""
                    }
                }.distinctUntilChanged()
                .flatMapLatest { (filter, query) ->
                    imports.pagedItems(sessionId, filter).map { data ->
                        if (query.isBlank()) {
                            data
                        } else {
                            data.filter { item -> item.matchesQuery(query) }
                        }
                    }
                }.cachedIn(viewModelScope)

        override fun onCleared() {
            visibility.leave(sessionId)
        }

        fun setFilter(filter: ImportItemFilter) {
            state.update { it.copy(filter = filter) }
        }

        fun setQuery(query: String) {
            state.update { it.copy(query = query) }
        }

        fun toggleItem(
            ordinal: Int,
            selected: Boolean,
        ) = edit { imports.setSelected(sessionId, setOf(ordinal), selected) }

        fun selectAll() = edit { imports.setSelected(sessionId, null, true) }

        fun selectNone() = edit { imports.setSelected(sessionId, null, false) }

        fun setImportGroups(enabled: Boolean) =
            edit { imports.updateOptions(sessionId) { it.copy(importGroups = enabled) } }

        fun setGroupIncluded(
            nameKey: String,
            included: Boolean,
        ) = edit {
            imports.updateOptions(sessionId) {
                it.copy(
                    excludedGroupKeys =
                        if (included) it.excludedGroupKeys - nameKey else it.excludedGroupKeys + nameKey,
                )
            }
        }

        fun setTreatExistingAsPlayed(enabled: Boolean) =
            edit { imports.updateOptions(sessionId) { it.copy(treatExistingAsPlayed = enabled) } }

        /** The existing-group choice; [groupId] of `null` clears the target. */
        fun setTargetGroup(groupId: Long?) =
            edit {
                imports.updateOptions(sessionId) {
                    it.copy(targetGroupId = groupId, targetGroupName = null)
                }
            }

        /** "Notify me" (03's permission rule): the write lands first, the prompt follows. */
        fun setNotifyNewEpisodes(
            enabled: Boolean,
            requester: NotificationPermissionRequester?,
        ) {
            if (!editable()) return
            val write =
                enqueueEdit {
                    suspendRunCatching {
                        imports.updateOptions(sessionId) { it.copy(notifyNewEpisodes = enabled) }
                    }.onFailure { postWriteFailed() }.isSuccess
                }
            viewModelScope.launch {
                if (!write.await()) return@launch

                state.update { it.copy(notificationsBlocked = enabled && requester?.granted == false) }
                if (enabled && requester != null && !requester.granted) {
                    requester.request { allowed ->
                        state.update { it.copy(notificationsBlocked = !allowed) }
                    }
                }
            }
        }

        /**
         * Validated proposal rename (05's GroupNames rules through `checkName`): a name already
         * held by another group is not an error — the commit resolves renames by nameKey, so the
         * typed name lands on that existing group (reuse, not a fork or a failure). Returns the
         * [GroupError] the dialog shows inline, or `null` once the rename is stored.
         */
        suspend fun renameGroup(
            nameKey: String,
            name: String,
        ): GroupError? =
            validateName(name) { validated ->
                imports.updateOptions(sessionId) {
                    it.copy(
                        renamedGroups =
                            it.renamedGroups + (nameKey to validated),
                    )
                }
            }

        /**
         * "New group…" in the target picker. `NameTaken` reuses the typed name, clearing any
         * [setTargetGroup] id. The commit resolves that nameKey onto the existing group, as
         * [renameGroup] does, instead of creating a duplicate.
         */
        suspend fun setTargetGroupName(name: String): GroupError? =
            validateName(name) { validated ->
                imports.updateOptions(sessionId) {
                    it.copy(targetGroupId = null, targetGroupName = validated)
                }
            }

        private suspend fun validateName(
            name: String,
            store: suspend (String) -> Unit,
        ): GroupError? {
            if (!editable()) return null
            return enqueueEdit {
                val outcome =
                    suspendRunCatching { groups.checkName(name) }.getOrElse {
                        Log.w(TAG, it) { "import group name check failed" }
                        postWriteFailed()
                        throw it
                    }
                val validated =
                    when (outcome) {
                        is Outcome.Success -> {
                            outcome.value
                        }

                        is Outcome.Failure -> {
                            if (outcome.error !is GroupError.NameTaken) return@enqueueEdit outcome.error

                            name.trim()
                        }
                    }
                writeEdit { store(validated) }.getOrThrow()
                null
            }.await()
        }

        /**
         * 05 §6 commit: the write runs on an [appScope] job the route only awaits — leaving the
         * screen never cancels an accepted commit, and `busy` clears on job completion
         * so a recreated route cannot double-confirm while an orphaned job still writes. Only a
         * non-backup PREVIEW session may confirm; `selectedCount = 0` stays confirmable because
         * member-only (ALREADY_SUBSCRIBED) items still apply their groups.
         */
        suspend fun confirm() {
            val session = state.value.session ?: return
            if (
                session.state != ImportState.PREVIEW ||
                session.format == ImportFormat.NEUTRODYNE_BACKUP ||
                busy()
            ) {
                return
            }
            setLocalBusy(true)
            // Report an unavailable application scope before accepting the operation.
            if (!appScope.coroutineContext.isActive) {
                setLocalBusy(false)
                userMessages.post(UiText.Res(Res.string.import_err_confirm))
                return
            }
            val edits = pendingEdit
            appScope
                .async {
                    edits?.join()
                    val outcome =
                        suspendRunCatching { imports.confirm(sessionId) }.getOrElse {
                            Log.w(TAG, it) { "import confirm failed" }
                            userMessages.post(UiText.Res(Res.string.import_err_confirm))
                            return@async
                        }
                    if (outcome is Outcome.Failure) {
                        userMessages.post(errorText(outcome.error))
                    }
                }.also { job ->
                    // Completion also runs if cancellation prevents dispatching the body.
                    job.invokeOnCompletion { setLocalBusy(false) }
                }.await()
        }

        /**
         * 05: PREVIEW cancel deletes the session; only success permits the route to pop.
         * COMMITTED/FETCHING cancellation stops the fetch and the report shows CANCELLED.
         */
        suspend fun cancelSession(): Boolean {
            val session = state.value.session ?: return false
            if (
                busy() ||
                (
                    session.state != ImportState.PREVIEW &&
                        session.state != ImportState.COMMITTED &&
                        session.state != ImportState.FETCHING
                )
            ) {
                return false
            }
            setLocalBusy(true)
            if (!appScope.coroutineContext.isActive) {
                setLocalBusy(false)
                postWriteFailed()
                return false
            }
            val edits = pendingEdit
            return appScope
                .async {
                    edits?.join()
                    suspendRunCatching { imports.cancel(sessionId) }
                        .onFailure {
                            Log.w(TAG, it) { "import cancel failed" }
                            postWriteFailed()
                        }.isSuccess
                }.also { job ->
                    job.invokeOnCompletion { setLocalBusy(false) }
                }.await()
        }

        /** Repository re-checks session ownership and status before every write. */
        suspend fun retry(item: ImportItemView): Boolean {
            if (!item.canFixUp || item.status == ImportItemStatus.QUEUED) return false
            return performWrite { imports.retry(sessionId, setOf(item.ordinal)) }
        }

        suspend fun remove(item: ImportItemView): Boolean {
            if (!item.canFixUp) return false
            return performWrite { imports.remove(sessionId, setOf(item.ordinal)) }
        }

        suspend fun removeAllNoMedia(): Boolean {
            val ordinals =
                state.value.session
                    ?.noMediaOrdinals
                    .orEmpty()
            if (ordinals.isEmpty()) return false
            return performWrite { imports.remove(sessionId, ordinals) }
        }

        suspend fun editUrl(
            item: ImportItemView,
            input: String,
        ): UiText? {
            if (!item.canFixUp) return UiText.Res(Res.string.write_failed)

            return performFixUp {
                when (val result = imports.editUrl(sessionId, item.ordinal, input)) {
                    is Outcome.Success -> null
                    is Outcome.Failure -> addPodcastErrorText(result.error)
                }
            }
        }

        suspend fun enterPassword(
            item: ImportItemView,
            credentials: BasicCredentials,
        ): UiText? {
            if (!item.canFixUp || item.status != ImportItemStatus.AUTH_REQUIRED) {
                return UiText.Res(Res.string.write_failed)
            }
            if ((credentials.username.isEmpty() && credentials.password.isEmpty()) ||
                ':' in credentials.username || credentials.username.any { it < ' ' || it == '\u007f' } ||
                credentials.password.any { it < ' ' || it == '\u007f' }
            ) {
                return UiText.Res(Res.string.import_credentials_invalid)
            }

            return performFixUp {
                when (val result = imports.enterPassword(sessionId, item.ordinal, credentials)) {
                    is Outcome.Success -> null
                    is Outcome.Failure -> addPodcastErrorText(result.error)
                }
            }
        }

        /** The row observes persisted artwork; previews never invoke this stream. */
        fun artworkFor(podcastId: Long): Flow<ArtworkRef?> =
            podcasts.observePodcast(podcastId).map { it?.artwork }.catch { emit(null) }

        private val ImportItemView.canFixUp: Boolean
            get() =
                podcastId != null &&
                    reportSection() in FIXABLE_SECTIONS &&
                    !(status == ImportItemStatus.FETCH_FAILED && errorDetail == IMPORT_DEFERRED_DETAIL)

        private suspend fun performWrite(write: suspend () -> Unit): Boolean {
            val error =
                performFixUp {
                    write()
                    null
                }
            if (error != null) userMessages.post(error)
            return error == null
        }

        private suspend fun performFixUp(block: suspend () -> UiText?): UiText? {
            val session = state.value.session ?: return UiText.Res(Res.string.write_failed)
            if (busy() || session.state !in FIXUP_STATES || !appScope.coroutineContext.isActive) {
                return UiText.Res(Res.string.write_failed)
            }
            val operation =
                operations.start(sessionId) {
                    // Exceptions can contain a literal password, even without a URL to redact.
                    suspendRunCatching { block() }.getOrElse { UiText.Res(Res.string.write_failed) }
                } ?: return UiText.Res(Res.string.write_failed)
            updateBusy()
            operation.invokeOnCompletion { updateBusy() }
            return operation.await()
        }

        private fun setLocalBusy(value: Boolean) {
            localBusy.value = value
            updateBusy()
        }

        private fun updateBusy() {
            state.update { it.copy(busy = localBusy.value || sessionId in operations.busySessions.value) }
        }

        private fun busy(): Boolean = state.value.busy || sessionId in operations.busySessions.value

        fun acknowledgeMessage(id: Long) = userMessages.shown(id)

        /** Editable means a real non-backup PREVIEW session with no confirm/cancel in flight. */
        private fun editable(): Boolean {
            val session = state.value.session ?: return false
            return session.state == ImportState.PREVIEW &&
                session.format != ImportFormat.NEUTRODYNE_BACKUP &&
                !busy()
        }

        private fun edit(block: suspend () -> Unit) {
            if (!editable()) return
            enqueueEdit { writeEdit(block) }
        }

        private suspend fun writeEdit(block: suspend () -> Unit) =
            suspendRunCatching { block() }.onFailure {
                Log.w(TAG, it) { "import edit failed" }
                postWriteFailed()
            }

        private fun <T> enqueueEdit(block: suspend () -> T): Deferred<T> {
            val previous = pendingEdit
            return appScope
                .async {
                    previous?.join()
                    block()
                }.also { pendingEdit = it }
        }

        private fun postWriteFailed() {
            userMessages.post(UiText.Res(Res.string.write_failed))
        }

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey(Factory::class)
        @ContributesIntoMap(AppScope::class)
        fun interface Factory : ManualViewModelAssistedFactory {
            fun create(sessionId: Long): ImportViewModel
        }

        private companion object {
            const val TAG = "ImportViewModel"
            val FIXABLE_SECTIONS =
                setOf(
                    ImportReportSection.QUEUED,
                    ImportReportSection.ATTENTION,
                    ImportReportSection.NO_MEDIA,
                )
            val FIXUP_STATES = setOf(ImportState.COMMITTED, ImportState.FETCHING, ImportState.DONE)
        }
    }

/** Past PREVIEW the "Only new" chip is meaningless (its rows are the unselected leftovers). */
private fun effectiveFilter(filter: ImportItemFilter): ImportItemFilter =
    if (filter == ImportItemFilter.NEW_ONLY) ImportItemFilter.ALL else filter

private fun ImportItemView.matchesQuery(query: String): Boolean =
    title.contains(query, ignoreCase = true) || host.contains(query, ignoreCase = true)

/** Typed import errors for the confirm snackbar (05 Error handling and failure modes). */
private fun errorText(error: ImportError): UiText =
    when (error) {
        ImportError.SessionGone -> UiText.Res(Res.string.import_err_gone)
        ImportError.Storage -> UiText.Res(Res.string.export_err_storage)
        is ImportError.Network -> UiText.Res(Res.string.import_err_network)
        else -> UiText.Res(Res.string.import_err_confirm)
    }
