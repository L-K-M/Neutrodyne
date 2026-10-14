// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.lifecycle.ViewModel
import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactory
import kotlin.reflect.KClass

/**
 * The one contributed `MetroViewModelFactory` every `ViewModelGraph` needs (01 Feature entry
 * installers, S8 2026-10-06): the three maps are filled by the feature modules'
 * `@ContributesIntoMap(AppScope::class)` ViewModel and assisted-factory bindings. The shells hand
 * it to `LocalMetroViewModelFactory` at the root so `metroViewModel()`/`assistedMetroViewModel()`
 * resolve per nav entry.
 */
@Inject
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
public class NeutrodyneViewModelFactory(
    override val viewModelProviders: Map<KClass<out ViewModel>, () -> ViewModel>,
    override val assistedFactoryProviders: Map<KClass<out ViewModel>, () -> ViewModelAssistedFactory>,
    override val manualAssistedFactoryProviders:
        Map<KClass<out ManualViewModelAssistedFactory>, () -> ManualViewModelAssistedFactory>,
) : MetroViewModelFactory()
