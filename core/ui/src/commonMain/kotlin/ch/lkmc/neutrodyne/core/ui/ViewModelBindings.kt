// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.lifecycle.ViewModel
import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactory
import kotlin.reflect.KClass

/**
 * The multibinding maps `NeutrodyneViewModelFactory` injects (01 ViewModels and UI state).
 * `allowEmpty` keeps the shells' graphs compiling while no feature contributes a given kind —
 * `ViewModelAssistedFactory` is the metrox assisted-injection seam no M1a VM uses yet.
 */
@ContributesTo(AppScope::class)
@BindingContainer
public interface ViewModelBindings {
    @Multibinds(allowEmpty = true)
    public fun viewModelProviders(): Map<KClass<out ViewModel>, ViewModel>

    @Multibinds(allowEmpty = true)
    public fun viewModelAssistedFactoryProviders(): Map<KClass<out ViewModel>, ViewModelAssistedFactory>

    @Multibinds(allowEmpty = true)
    public fun manualViewModelAssistedFactoryProviders():
        Map<KClass<out ManualViewModelAssistedFactory>, ManualViewModelAssistedFactory>
}
