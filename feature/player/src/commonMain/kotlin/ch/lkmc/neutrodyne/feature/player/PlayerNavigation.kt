// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.model.Effective
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.SpeedKey
import ch.lkmc.neutrodyne.core.ui.resolve
import ch.lkmc.neutrodyne.core.ui.root.LocalRootViewModelStoreOwner
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.playback.api.ScopeWriteResult
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.metroViewModel

@BindingContainer
@ContributesTo(AppScope::class)
public object PlayerNavigation {
    @Provides
    @IntoSet
    public fun entries(): EntryProviderInstaller =
        {
            entry<SpeedKey>(metadata = NdSceneMetadata.bottomSheet()) { SpeedRoute() }
        }
}

@Composable
private fun SpeedRoute() {
    CompositionLocalProvider(
        LocalViewModelStoreOwner provides
            checkNotNull(LocalRootViewModelStoreOwner.current ?: LocalViewModelStoreOwner.current),
    ) {
        SpeedRouteContent()
    }
}

@Composable
private fun SpeedRouteContent(vm: PlayerViewModel = metroViewModel()) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val result by vm.scopeWriteResult.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    var resetScope by remember { mutableIntStateOf(0) }
    var resetPresentation by remember { mutableIntStateOf(0) }
    LaunchedEffect(result) {
        when (result) {
            ScopeWriteResult.NOTHING_PLAYING -> navigator.pop(SpeedKey)
            ScopeWriteResult.NO_CONTEXT_GROUP -> resetScope++
            ScopeWriteResult.FAILED -> resetPresentation++
            else -> Unit
        }
        if (result != null) vm.acknowledgeScopeWrite()
    }
    key(resetScope, resetPresentation, ui.now?.episodeId) {
        SpeedSheet(
            ui.now,
            ui.effective,
            ui.presets,
            vm::setSpeed,
            vm::setSkipSilence,
            vm::setPresets,
            onDismiss = { navigator.pop(SpeedKey) },
            resetScopeToken = resetScope,
        )
    }
}
