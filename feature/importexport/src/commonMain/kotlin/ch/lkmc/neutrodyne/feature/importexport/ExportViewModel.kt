// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.ExportError
import ch.lkmc.neutrodyne.core.domain.ExportRepository
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

/** The export the warning was raised for, kept so Continue delivers that exact artifact. */
private data class PendingExport(
    val export: PreparedExport,
    val action: ExportAction,
    val destinations: ExportDestinations,
)

/**
 * The shared export dialog's controller (05 OPML export): `ExportKey(null)` is the library, a
 * group id shares that group. `prepare` runs exactly once per action; the returned
 * `PreparedExport` is the immutable artifact every later step works on — the warning counts
 * come from it and [deliver] never rebuilds it after consent (R1.9/AC10).
 */
internal class ExportViewModel
    @AssistedInject
    constructor(
        @Assisted private val groupId: Long?,
        private val exports: ExportRepository,
        private val settings: SettingsRepository,
        private val groups: GroupRepository,
    ) : ViewModel() {
        private val state = MutableStateFlow(ExportUiState())
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

        fun export(
            action: ExportAction,
            destinations: ExportDestinations,
        ) {
            // The busy flag is the duplicate/stale guard: while a flow runs, further taps and
            // any late-arriving second launch are ignored; viewModelScope drops the rest on pop.
            if (!state.value.ready || state.value.busy) return
            state.update { it.copy(busy = true, warning = null) }
            viewModelScope.launch {
                val options = state.value
                val request =
                    ExportRequest(
                        groupId = groupId,
                        layout = options.layout,
                        includeYouTube = options.includeYouTube,
                        includePasswords = options.includePasswords,
                    )
                when (val outcome = exports.prepare(request)) {
                    is Outcome.Failure -> {
                        userMessages.post(errorText(outcome.error))
                    }

                    is Outcome.Success -> {
                        // 05: the dialog's layout/YouTube memories write back on export. A failed
                        // memory write must not fail the export itself.
                        settings.set(BackupSettingKeys.OPML_LAYOUT, options.layout)
                        settings.set(BackupSettingKeys.OPML_INCLUDE_YOUTUBE, options.includeYouTube)
                        val export = outcome.value
                        if (export.privateLinks > 0 || export.passwords > 0) {
                            pending = PendingExport(export, action, destinations)
                            state.update { it.copy(warning = export) }
                        } else {
                            deliver(export, action, destinations)
                        }
                    }
                }
                state.update { it.copy(busy = false) }
            }
        }

        /** Continue on the warning: delivers the artifact the counts were read from. */
        fun confirmWarning() {
            val warned = pending ?: return
            if (state.value.busy) return
            pending = null
            state.update { it.copy(warning = null, busy = true) }
            viewModelScope.launch {
                deliver(warned.export, warned.action, warned.destinations)
                state.update { it.copy(busy = false) }
            }
        }

        /** Cancel on the warning: no destination is asked for, nothing is saved or shared. */
        fun cancelWarning() {
            if (state.value.busy) return
            pending = null
            state.update { it.copy(warning = null) }
        }

        private suspend fun deliver(
            export: PreparedExport,
            action: ExportAction,
            destinations: ExportDestinations,
        ) {
            when (action) {
                ExportAction.SHARE -> {
                    if (!destinations.canShare || export.shareUri == null) {
                        userMessages.post(UiText.Res(Res.string.export_err_unavailable))
                        return
                    }
                    destinations.share(export)
                    state.update { it.copy(finished = FinishedExport(export, null, shared = true)) }
                }

                ExportAction.SAVE -> {
                    // A dismissed destination picker stays silent — nothing was accepted, so
                    // there is neither a write nor a message.
                    val uri = destinations.save(export) ?: return
                    when (val outcome = exports.saveTo(export, uri)) {
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
