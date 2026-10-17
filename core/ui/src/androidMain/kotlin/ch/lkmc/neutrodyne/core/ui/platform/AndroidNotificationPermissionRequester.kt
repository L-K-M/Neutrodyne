// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/** Keeps denial across screen changes and restarts; only system settings can undo a denial. */
internal class AndroidNotificationPermissionRequester(
    private val context: Context,
    private val launch: (onResult: (Boolean) -> Unit) -> Unit,
) : NotificationPermissionRequester {
    private val preferences = context.getSharedPreferences("notification_permission", Context.MODE_PRIVATE)

    override var granted: Boolean by mutableStateOf(hasPermission())
        private set

    fun refresh() {
        granted = hasPermission()
    }

    override fun request(onResult: (Boolean) -> Unit) {
        refresh()
        if (granted || preferences.getBoolean(REQUESTED, false)) {
            onResult(granted)
            return
        }

        // Save before launch so recreation cannot open a second dialog.
        preferences.edit().putBoolean(REQUESTED, true).apply()
        launch { allowed ->
            granted = allowed
            onResult(allowed)
        }
    }

    override fun openSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val REQUESTED = "requested"
    }
}
