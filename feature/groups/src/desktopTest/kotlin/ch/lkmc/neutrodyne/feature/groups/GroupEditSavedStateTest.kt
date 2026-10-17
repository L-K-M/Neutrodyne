// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.groups

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import ch.lkmc.neutrodyne.core.navigation.GroupEditKey
import kotlin.test.Test
import kotlin.test.assertNotNull

/** Use the same entry decorators as the app, rather than constructing a stand-alone handle. */
@OptIn(ExperimentalTestApi::class)
class GroupEditSavedStateTest {
    @Test fun entryFactoryCanCreateSavedStateHandleOnDesktop() =
        runComposeUiTest {
            val root =
                object : ViewModelStoreOwner {
                    override val viewModelStore = ViewModelStore()
                }
            var handle: SavedStateHandle? = null
            val factory = viewModelFactory { initializer { HandleProbe(createSavedStateHandle()) } }
            setContent {
                val entries =
                    rememberDecoratedNavEntries(
                        backStack = listOf<NavKey>(GroupEditKey(null)),
                        entryDecorators =
                            listOf(
                                rememberSaveableStateHolderNavEntryDecorator(),
                                rememberViewModelStoreNavEntryDecorator(root),
                            ),
                        entryProvider =
                            entryProvider<NavKey> {
                                entry<GroupEditKey> { handle = viewModel<HandleProbe>(factory = factory).handle }
                            },
                    )
                entries.single().Content()
            }
            runOnIdle { assertNotNull(handle) }
        }

    private class HandleProbe(
        val handle: SavedStateHandle,
    ) : ViewModel()
}
