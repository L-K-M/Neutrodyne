// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.importexport

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.domain.ImportRepository
import ch.lkmc.neutrodyne.core.navigation.ImportFileKey
import ch.lkmc.neutrodyne.core.navigation.ImportKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.ui.platform.FilePicker
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Inject
@ViewModelKey(ImportFileViewModel::class)
@ContributesIntoMap(AppScope::class)
internal class ImportFileViewModel(
    imports: ImportRepository,
) : ViewModel() {
    private val controller = ImportFileController(imports)
    private val state = MutableStateFlow<FileImportResult?>(null)
    val result = state.asStateFlow()
    private var started = false

    fun open(picker: FilePicker) {
        if (started) return
        started = true
        viewModelScope.launch { state.value = controller.open(picker) }
    }
}

/** A retained picker request, shared by the File menu and the in-app Import buttons. */
@Composable
internal fun ImportFileRoute(viewModel: ImportFileViewModel = metroViewModel()) {
    val picker = LocalPlatformActions.current.files
    val navigator = LocalAppNavigator.current
    val result by viewModel.result.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel, picker) { viewModel.open(picker) }
    LaunchedEffect(result) {
        when (val current = result) {
            FileImportResult.Cancelled -> {
                navigator.pop(ImportFileKey)
            }

            is FileImportResult.Opened -> {
                navigator.pop(ImportFileKey)
                navigator.push(ImportKey(current.sessionId.toString()))
            }

            else -> {
                Unit
            }
        }
    }
    (result as? FileImportResult.Failed)?.let {
        ImportFailureDialog(it.error, onDismiss = { navigator.pop(ImportFileKey) })
    }
}
