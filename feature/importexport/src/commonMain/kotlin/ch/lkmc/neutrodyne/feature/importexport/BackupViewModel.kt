// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.importexport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.domain.BackupRepository
import ch.lkmc.neutrodyne.core.domain.ImportRepository
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.PreparedBackup
import ch.lkmc.neutrodyne.core.model.SnapshotStatus
import ch.lkmc.neutrodyne.core.model.settings.BackupSettingKeys
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessages
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.backup_saved
import ch.lkmc.neutrodyne.core.ui.resources.backup_size
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

internal data class BackupUiState(
    val includePasswords: Boolean = false,
    val busy: Boolean = false,
    val warning: PreparedBackup? = null,
    val snapshot: SnapshotStatus? = null,
    val lastBackupAt: Long = 0,
    val acquisitionError: UiText? = null,
)

@ViewModelKey(BackupViewModel::class)
@ContributesIntoMap(AppScope::class)
@Inject
internal class BackupViewModel(
    private val backups: BackupRepository,
    private val imports: ImportRepository,
    private val settings: SettingsRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {
    private val state = MutableStateFlow(BackupUiState())
    val uiState = state.asStateFlow()
    private val userMessages = UserMessages()
    val messages = userMessages.flow

    init {
        viewModelScope.launch {
            backups.observeSnapshotStatus().collect { snapshot -> state.update { it.copy(snapshot = snapshot) } }
        }
        viewModelScope.launch {
            settings.observe(BackupSettingKeys.LAST_MANUAL_BACKUP_AT).collect { at ->
                state.update { it.copy(lastBackupAt = at) }
            }
        }
    }

    /** Opening the route again never reuses password consent. A prepared receipt keeps its own consent. */
    fun opened() {
        state.update { it.copy(includePasswords = false) }
    }

    fun setPasswords(include: Boolean) {
        if (state.value.busy || state.value.warning != null) return
        state.update { it.copy(includePasswords = include) }
    }

    suspend fun create(pick: suspend (PreparedBackup) -> String?) {
        if (!begin()) return
        val include = state.value.includePasswords
        val uiContext = coroutineContext
        runJob {
            when (val outcome = backups.prepareBackup(include)) {
                is Outcome.Failure -> {
                    userMessages.post(backupErrorText(outcome.error))
                }

                is Outcome.Success -> {
                    val prepared = outcome.value
                    if (prepared.privacy.privateLinks > 0 || prepared.privacy.passwords > 0) {
                        state.update { it.copy(warning = prepared) }
                        return@runJob
                    }
                    save(prepared, pick, uiContext)
                }
            }
        }.await()
    }

    suspend fun confirmWarning(pick: suspend (PreparedBackup) -> String?) {
        val prepared = state.value.warning ?: return
        if (!begin(expectedWarning = prepared)) return
        state.update { it.copy(warning = null) }
        val uiContext = coroutineContext
        runJob { save(prepared, pick, uiContext) }.await()
    }

    fun cancelWarning() {
        if (state.value.busy) return
        state.update { it.copy(warning = null) }
    }

    private suspend fun save(
        prepared: PreparedBackup,
        pick: suspend (PreparedBackup) -> String?,
        uiContext: CoroutineContext,
    ) {
        val uri = withContext(uiContext) { pick(prepared) } ?: return
        uiContext.ensureActive()
        when (val outcome = backups.savePreparedBackup(prepared, uri)) {
            is Outcome.Failure -> {
                userMessages.post(backupErrorText(outcome.error))
            }

            is Outcome.Success -> {
                userMessages.post(
                    UiText.Res(Res.string.backup_saved, listOf(backupSize(prepared.summary.bytes))),
                )
            }
        }
    }

    suspend fun chooseRestore(pick: suspend () -> String?): Long? {
        if (!begin()) return null
        return acquireBackup(imports, appScope, pick)
            .also { job ->
                job.invokeOnCompletion { state.update { it.copy(busy = false) } }
            }.await()
            .let { outcome ->
                when (outcome) {
                    is Outcome.Success -> {
                        outcome.value
                    }

                    is Outcome.Failure -> {
                        state.update { it.copy(acquisitionError = acquisitionErrorText(outcome.error)) }
                        null
                    }
                }
            }
    }

    fun dismissAcquisitionError() {
        state.update { it.copy(acquisitionError = null) }
    }

    fun setSnapshotEnabled(enabled: Boolean) = snapshotAction { backups.setSnapshotEnabled(enabled) }

    fun discardSnapshot() = snapshotAction { backups.discardAndroidBackup() }

    fun writeSnapshotNow() =
        snapshotAction {
            val outcome = backups.writeSnapshotNow()
            if (outcome is Outcome.Failure) userMessages.post(backupErrorText(outcome.error))
        }

    suspend fun restoreSnapshot(): Long? {
        if (!begin()) return null
        return runJob {
            when (val outcome = backups.restoreAndroidBackup()) {
                is Outcome.Success -> {
                    outcome.value
                }

                is Outcome.Failure -> {
                    userMessages.post(backupErrorText(outcome.error))
                    null
                }
            }
        }.await()
    }

    private fun snapshotAction(block: suspend () -> Unit) {
        if (!begin()) return
        runJob(block)
    }

    private fun begin(expectedWarning: PreparedBackup? = null): Boolean {
        if (state.value.busy || state.value.warning !== expectedWarning) return false
        if (!appScope.coroutineContext.isActive) {
            userMessages.post(backupErrorText(BackupError.Io))
            return false
        }
        state.update { it.copy(busy = true) }
        return true
    }

    private fun <T> runJob(block: suspend () -> T): Deferred<T?> =
        appScope
            .async {
                suspendRunCatching { block() }.getOrElse {
                    userMessages.post(backupErrorText(BackupError.Io))
                    null
                }
            }.also { job ->
                // Completion owns busy even when cancellation prevents the coroutine body from starting.
                job.invokeOnCompletion { state.update { it.copy(busy = false) } }
            }

    fun acknowledgeMessage(id: Long) = userMessages.shown(id)
}

private const val BYTES_PER_MB = 1_000_000L

internal fun backupSize(bytes: Long): UiText =
    UiText.Res(
        Res.string.backup_size,
        listOf(bytes / BYTES_PER_MB, bytes % BYTES_PER_MB / (BYTES_PER_MB / 10)),
    )
