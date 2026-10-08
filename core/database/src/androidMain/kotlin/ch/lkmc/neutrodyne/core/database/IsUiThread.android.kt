// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import android.os.Looper

internal actual fun isUiThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()
