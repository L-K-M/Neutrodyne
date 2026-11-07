// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.SyncActivity

/** A settable [SyncActivity] for tests. */
class FakeSyncActivity : SyncActivity {
    override var active: Boolean = false
}
