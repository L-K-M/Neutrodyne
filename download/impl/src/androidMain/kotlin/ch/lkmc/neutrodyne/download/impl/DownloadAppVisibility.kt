// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.concurrent.atomic.AtomicBoolean

@SingleIn(AppScope::class)
@Inject
internal class DownloadAppVisibility {
    private val visible = AtomicBoolean(false)

    fun isVisible(): Boolean = visible.get()

    fun update(visible: Boolean) {
        this.visible.set(visible)
    }
}
