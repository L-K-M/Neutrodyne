// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import org.junit.Test

/**
 * The shell must compile against the lifecycle it ships. `metrox-viewmodel` 1.4.5 pulls
 * `lifecycle-viewmodel` 2.10.0 onto the compile classpath while the features ship the catalog's
 * 2.11.0, and `ViewModelStore.put` changed its JVM signature between them: compiled against the
 * older one, this call fails with `NoSuchMethodError` at run time.
 */
class LifecycleLinkageTest {
    @Test
    fun `view model store calls link against the shipped lifecycle`() {
        val store = ViewModelStore()
        store.put("probe", object : ViewModel() {})
        store.clear()
    }
}
