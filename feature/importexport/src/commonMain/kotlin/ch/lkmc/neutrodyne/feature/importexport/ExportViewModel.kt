// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.ExportError
import ch.lkmc.neutrodyne.core.domain.ExportRepository
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.ExportFormat
import ch.lkmc.neutrodyne.core.model.ExportLayout
import ch.lkmc.neutrodyne.core.model.ExportRequest
import ch.lkmc.neutrodyne.core.model.PreparedExport
import ch.lkmc.neutrodyne.core.model.settings.BackupSettingKeys
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessages
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.export_err_destination
import ch.lkmc.neutrodyne.core.ui.resources.export_err_io
import ch.lkmc.neutrodyne.core.ui.resources.export_err_storage
import ch.lkmc.neutrodyne.core.ui.resources.export_err_unavailable
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** The dialog's two delivery choices (05 OPML export): Android's sheet or "Save to file". */
internal enum class ExportAction {
    SHARE,
    SAVE,
}

internal data class ExportUiState(
    /** False until the remembered layout/YouTube options arrive; actions stay disabled. */
    val ready: Boolean = false,
    /** A prepare or delivery is in flight — options and actions are frozen meanwhile. */
    val busy: Boolean = false,
    val format: ExportFormat = ExportFormat.OPML,
    val fullExport: Boolean = true,
    val layout: ExportLayout = ExportLayout.GROUPED,
    val includeYouTube: Boolean = true,
    /** Off on every open (05): password inclusion is never a remembered default. */
    val includePasswords: Boolean = false,
    /** Null for a library export (and while a group name is still loading). */
    val groupName: String? = null,
    /** The prepared artifact whose counts the warning quotes — `null` shows the options. */
    val warning: PreparedExport? = null,
    /** One-shot delivery result the route consumes to pop and snackbar. */
    val finished: FinishedExport? = null,
)

/**
 * A completed delivery (05 Destinations): [destinationUri] is set for a save, [shared] marks a
 * hand-off to the Android sheet. The route shows "Saved" only for a real save — a cancelled or
 * failed pick never reaches this state.
 */
internal data class FinishedExport(
    val export: PreparedExport,
    val destinationUri: String?,
    val shared: Boolean,
)

/**
 * The platform seams behind the dialog's actions, built by the route from `LocalPlatformActions`:
 * [save] is `FileSaver.create` over the artifact's name/MIME, [share] is `ShareSheet.shareFile`
 * over its share URI. [canShare] is `share != null` — the desktop hides the Share action.
 */
internal class ExportDestinations(
    val canShare: Boolean,
    val save: suspend (PreparedExport) -> String?,
    val share: (PreparedExport) -> Unit,
)

/**
 * The export the warning was raised for: immutable artifact + chosen action only — never the
 * route's platform seams, which die with the composition that built them. Continue passes the
 * live route's destinations instead.
 */
private data class PendingExport(
    val export: PreparedExport,
    val action: ExportAction,
)

/**
 * The shared export dialog's controller (05 OPML export): `ExportKey(null)` is the library, a
 * group id shares that group. `prepare` runs exactly once per action; the returned
 * `PreparedExport` is the immutable artifact every later step works on — the warning counts
 * come from it and [deliver] never rebuilds it after consent (R1.9/AC10).
 *
 * [export] and [confirmWarning] are `suspend` operations the route launches on its own
 * composition scope, but each flow's data work — prepare and the accepted destination copy —
 * rides one `@ApplicationScope` job the caller only awaits (05: leaving the dialog does not
 * cancel an accepted save). `busy` clears in that job's own `finally`, so a recreated route
 * cannot start a second same-name run while an orphaned one still writes. The UI half stays
 * route-owned: the retained VM never holds a dead Activity's launchers — the pending warning
 * keeps only the immutable `PreparedExport` plus its action, Continue receives the live
 * route's destinations, and the pick/share calls run inside the captured caller context, so a
 * disposed route's seams are never invoked.
 */
internal class ExportViewModel
    @AssistedInject
    constructor(
        @Assisted private val groupId: Long?,
        private val exports: ExportRepository,
        private val settings: SettingsRepository,
        private val groups: GroupRepository,
        @ApplicationScope private val appScope: CoroutineScope,
    ) : ViewModel() {
        private val state = MutableStateFlow(ExportUiState(fullExport = groupId == null))
        val uiState = state.asStateFlow()
        private val userMessages = UserMessages()
        val messages = userMessages.flow
        private var pending: PendingExport? = null

        init {
            viewModelScope.launch {
                combine(
                    settings.observe(BackupSettingKeys.OPML_LAYOUT),
                    settings.observe(BackupSettingKeys.OPML_INCLUDE_YOUTUBE),
                ) { layout, youtube -> layout to youtube }.collect { (layout, youtube) ->
                    state.update { it.copy(layout = layout, includeYouTube = youtube, ready = true) }
                }
            }
            if (groupId != null) {
                viewModelScope.launch {
                    groups.observeGroup(groupId).collect { group ->
                        state.update { it.copy(groupName = group?.name) }
                    }
                }
            }
        }

        fun setFormat(format: ExportFormat) {
            if (state.value.busy || (format == ExportFormat.NEWPIPE_JSON && groupId != null)) return
            state.update { it.copy(format = format) }
        }

        fun setLayout(layout: ExportLayout) {
            if (state.value.busy) return
            state.update { it.copy(layout = layout) }
        }

        fun setYouTube(include: Boolean) {
            if (state.value.busy) return
            state.update { it.copy(includeYouTube = include) }
        }

        fun setPasswords(include: Boolean) {
            if (state.value.busy) return
            state.update { it.copy(includePasswords = include) }
        }

        /**
         * Runs the interactive export — prepare, optional warning, destination pick, delivery —
         * on an [appScope] job this caller only awaits: caller cancellation never cancels it, so
         * the private prepare and an accepted save finish after the dialog is gone (05's
         * "leaving the dialog does not cancel"). The picker's and the share's platform calls run
         * inside the caller's captured context instead — a dead route's seams are never invoked.
         * A platform or repository throw that is not a typed [Outcome.Failure] becomes
         * `export_err_io`; cancellation still propagates.
         */
        suspend fun export(
            action: ExportAction,
            destinations: ExportDestinations,
        ) {
            // The busy flag is the duplicate/stale guard: while a flow runs, further taps and
            // any late-arriving second launch are ignored.
            if (!state.value.ready || state.value.busy) return
            pending = null
            state.update { it.copy(busy = true, warning = null) }
            val uiContext = coroutineContext
            appScope
                .async {
                    try {
                        val options = state.value
                        val request =
                            ExportRequest(
                                groupId = groupId,
                                format = options.format,
                                layout = options.layout,
                                includeYouTube = options.includeYouTube,
                                includePasswords = options.includePasswords,
                            )
                        val outcome =
                            suspendRunCatching { exports.prepare(request) }.getOrElse {
                                userMessages.post(UiText.Res(Res.string.export_err_io))
                                return@async
                            }
                        when (outcome) {
                            is Outcome.Failure -> {
                                userMessages.post(errorText(outcome.error))
                            }

                            is Outcome.Success -> {
                                // 05: the dialog's layout/YouTube memories write back on export. A failed
                                // or thrown memory write must not fail the export itself.
                                suspendRunCatching {
                                    settings.set(BackupSettingKeys.OPML_LAYOUT, options.layout)
                                    settings.set(BackupSettingKeys.OPML_INCLUDE_YOUTUBE, options.includeYouTube)
                                }
                                val export = outcome.value
                                if (export.privateLinks > 0 || export.passwords > 0) {
                                    pending = PendingExport(export, action)
                                    state.update { it.copy(warning = export) }
                                } else {
                                    deliver(export, action, destinations, uiContext)
                                }
                            }
                        }
                    } finally {
                        // The job — not the caller — owns busy: it stays set while an orphaned
                        // flow still writes, so a recreated route cannot double-prepare.
                        state.update { it.copy(busy = false) }
                    }
                }.await()
        }

        /**
         * Continue on the warning through the caller's live [destinations]: delivers the artifact
         * the counts were read from on an [appScope] job the caller only awaits. If the owner
         * dies mid-delivery, the artifact goes back to pending so a recreated route can finish
         * it — never a dead route's seams.
         */
        suspend fun confirmWarning(destinations: ExportDestinations) {
            val warned = pending ?: return
            if (state.value.busy) return
            pending = null
            state.update { it.copy(warning = null, busy = true) }
            val uiContext = coroutineContext
            appScope
                .async {
                    try {
                        deliver(warned.export, warned.action, destinations, uiContext)
                    } catch (c: CancellationException) {
                        // The delivery died with its UI owner before finishing: return the exact
                        // artifact to pending — a recreated route finishes the same export.
                        pending = warned
                        state.update { it.copy(warning = warned.export) }
                        throw c
                    } finally {
                        state.update { it.copy(busy = false) }
                    }
                }.await()
        }

        /** Cancel on the warning: no destination is asked for, nothing is saved or shared. */
        fun cancelWarning() {
            if (state.value.busy) return
            pending = null
            state.update { it.copy(warning = null) }
        }

        /**
         * The delivery half, inside the flow's [appScope] job. The platform calls run on
         * [uiContext] — the live context of the route that launched this flow — so a disposed
         * route's seams are never invoked; an accepted save destination then copies here, on the
         * job, independent of the dialog's lifetime.
         */
        private suspend fun deliver(
            export: PreparedExport,
            action: ExportAction,
            destinations: ExportDestinations,
            uiContext: CoroutineContext,
        ) {
            when (action) {
                ExportAction.SHARE -> {
                    if (!destinations.canShare || export.shareUri == null) {
                        userMessages.post(UiText.Res(Res.string.export_err_unavailable))
                        return
                    }
                    suspendRunCatching { withContext(uiContext) { destinations.share(export) } }
                        .getOrElse {
                            userMessages.post(UiText.Res(Res.string.export_err_unavailable))
                            return
                        }
                    state.update { it.copy(finished = FinishedExport(export, null, shared = true)) }
                }

                ExportAction.SAVE -> {
                    // A dismissed destination picker stays silent — nothing was accepted, so
                    // there is neither a write nor a message. A thrown picker is a real failure.
                    val uri =
                        suspendRunCatching { withContext(uiContext) { destinations.save(export) } }
                            .getOrElse {
                                userMessages.post(UiText.Res(Res.string.export_err_io))
                                return
                            } ?: return
                    // A picker answer that only reached an already-dead owner is no acceptance:
                    // the captured context is checked once more after the await. An accepted
                    // URI's copy runs below — on this job, outliving the dialog.
                    uiContext.ensureActive()
                    val outcome =
                        suspendRunCatching { exports.saveTo(export, uri) }.getOrElse {
                            userMessages.post(UiText.Res(Res.string.export_err_io))
                            return
                        }
                    when (outcome) {
                        is Outcome.Success -> {
                            state.update {
                                it.copy(finished = FinishedExport(export, uri, shared = false))
                            }
                        }

                        is Outcome.Failure -> {
                            userMessages.post(errorText(outcome.error))
                        }
                    }
                }
            }
        }

        fun consumeFinished(): FinishedExport? = state.value.finished.also { state.update { it.copy(finished = null) } }

        fun acknowledgeMessage(id: Long) = userMessages.shown(id)

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey(Factory::class)
        @ContributesIntoMap(AppScope::class)
        fun interface Factory : ManualViewModelAssistedFactory {
            fun create(groupId: Long?): ExportViewModel
        }
    }

private fun errorText(error: ExportError): UiText =
    when (error) {
        ExportError.Io -> UiText.Res(Res.string.export_err_io)
        ExportError.Storage -> UiText.Res(Res.string.export_err_storage)
        ExportError.DestinationUnavailable -> UiText.Res(Res.string.export_err_destination)
    }
