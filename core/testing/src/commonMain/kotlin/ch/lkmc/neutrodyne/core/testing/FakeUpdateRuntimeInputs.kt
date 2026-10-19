// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.update.UpdateRuntimeInputs
import ch.lkmc.neutrodyne.core.model.update.UpdateRuntimeTarget

class FakeUpdateRuntimeInputs(
    override val target: UpdateRuntimeTarget = UpdateRuntimeTarget.Android("arm64-v8a", 26),
) : UpdateRuntimeInputs
