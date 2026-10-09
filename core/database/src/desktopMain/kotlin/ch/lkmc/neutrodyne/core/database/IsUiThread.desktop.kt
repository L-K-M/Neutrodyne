// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import javax.swing.SwingUtilities

internal actual fun isUiThread(): Boolean = SwingUtilities.isEventDispatchThread()
