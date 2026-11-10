// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessages
import ch.lkmc.neutrodyne.feature.sync.resources.Res
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_copied
import ch.lkmc.neutrodyne.sync.api.SyncController
import ch.lkmc.neutrodyne.sync.api.SyncDiagnosticsSnapshot
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class SyncDiagnosticsUiState(
    val loading: Boolean = true,
    val snapshot: SyncDiagnosticsSnapshot? = null,
    val failed: Boolean = false,
)

/**
 * Settings › Sync › Diagnostics (08 Sync diagnostics): a local, read-only snapshot through
 * [SyncController.diagnostics] — never a request, an RPC or `syncNow`. Every read belongs to the
 * [SyncIdentity] it started under: an unlink or relink cancels the in-flight read and clears the
 * published snapshot, and a completion still compares the live identity before publishing, so one
 * profile's lines can never appear — or be copied — under another's. `viewModelScope` ends with
 * the nav entry, so leaving the screen cancels a pending read.
 */
@ViewModelKey(SyncDiagnosticsViewModel::class)
@ContributesIntoMap(AppScope::class)
@Inject
internal class SyncDiagnosticsViewModel(
    private val controller: SyncController,
    private val platformInfo: PlatformInfo,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SyncDiagnosticsUiState())
    val uiState = mutableState.asStateFlow()
    private val userMessages = UserMessages()
    val messages = userMessages.flow

    /** The local-network gate is Android's only (10 Diagnostics); the desktop has none to show. */
    val showLanGate: Boolean = platformInfo.kind == PlatformKind.ANDROID

    private var readJob: Job? = null
    private var identity: SyncIdentity? = null
    private var identitySeen = false

    /** The identity that produced [SyncDiagnosticsUiState.snapshot]; Copy compares against it. */
    private var snapshotIdentity: SyncIdentity? = null

    init {
        viewModelScope.launch {
            controller.status.collect { status ->
                val next = status.identity()
                if (!identitySeen || next != identity) {
                    identitySeen = true
                    identity = next
                    refresh()
                }
            }
        }
    }

    /**
     * (Re)reads the local snapshot. A new read cancels the old one so responses never overlap, and
     * the stale-completion check catches what cancellation missed.
     */
    fun refresh() {
        readJob?.cancel()
        val owner = controller.status.value.identity()
        identity = owner
        mutableState.value = SyncDiagnosticsUiState(loading = true)
        readJob =
            viewModelScope.launch {
                val outcome = controller.diagnostics()
                // The status collector can lag the flow's value: a read issued for one profile
                // must never publish another's lines.
                if (controller.status.value.identity() != owner) return@launch
                mutableState.value =
                    when (outcome) {
                        is Outcome.Success -> {
                            snapshotIdentity = owner
                            SyncDiagnosticsUiState(loading = false, snapshot = outcome.value)
                        }

                        is Outcome.Failure -> {
                            SyncDiagnosticsUiState(loading = false, failed = true)
                        }
                    }
            }
    }

    /** The copy text, or `null` when no current snapshot is shown — Copy can never leak a stale profile. */
    fun plainText(): String? {
        val snapshot = mutableState.value.snapshot ?: return null
        if (controller.status.value.identity() != snapshotIdentity) return null
        return snapshot.plainText()
    }

    fun copied() {
        // 08's clipboard rule: Android 13+ shows its own copy confirmation.
        val sdk = platformInfo.androidSdkInt
        if (sdk != null && sdk > 32) return
        viewModelScope.launch { userMessages.post(UiText.Res(Res.string.sync_diag_copied)) }
    }

    fun onMessageShown(id: Long) = userMessages.shown(id)
}
