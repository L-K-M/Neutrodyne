// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.platform

import android.os.Build
import ch.lkmc.neutrodyne.core.domain.update.UpdateRuntimeInputs
import ch.lkmc.neutrodyne.core.model.update.UpdateRuntimeTarget

internal class AndroidUpdateRuntimeInputs(
    supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
    sdkInt: Int = Build.VERSION.SDK_INT,
) : UpdateRuntimeInputs {
    override val target = UpdateRuntimeTarget.Android(supportedAbis.firstOrNull(), sdkInt)
}
