// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.desktop.platform

import ch.lkmc.neutrodyne.core.domain.update.UpdateRuntimeInputs
import ch.lkmc.neutrodyne.core.model.update.UpdateRuntimeTarget

internal class DesktopUpdateRuntimeInputs(
    osVersion: String = System.getProperty("os.version"),
) : UpdateRuntimeInputs {
    override val target = UpdateRuntimeTarget.Desktop(osVersion)
}
