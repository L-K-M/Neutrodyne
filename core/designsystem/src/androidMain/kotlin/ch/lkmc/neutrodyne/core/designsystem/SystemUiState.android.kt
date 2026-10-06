// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem

import android.app.UiModeManager
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState

/**
 * The Android read of [SystemUiState] (08 App scheme), called by `MainActivity`: night mode,
 * `UiModeManager.getContrast()` on API 34+ (0.0 elsewhere) and "Remove animations"
 * (`Settings.Global.ANIMATOR_DURATION_SCALE == 0`), kept current by a `ContentObserver`.
 */
@Composable
public fun rememberAndroidSystemUiState(): SystemUiState {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()

    var reducedMotion by remember { mutableStateOf(readAnimatorOff(context)) }
    DisposableEffect(context) {
        val uri: Uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        val observer =
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    reducedMotion = readAnimatorOff(context)
                }
            }
        context.contentResolver.registerContentObserver(uri, false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

    var contrast by remember { mutableDoubleStateOf(readContrast(context)) }
    DisposableEffect(context, dark) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return@DisposableEffect onDispose { }
        }
        val uiModeManager = context.getSystemService(UiModeManager::class.java)
        val listener = UiModeManager.ContrastChangeListener { c -> contrast = c.toDouble() }
        uiModeManager?.addContrastChangeListener(context.mainExecutor, listener)
        contrast = readContrast(context)
        onDispose { if (uiModeManager != null) uiModeManager.removeContrastChangeListener(listener) }
    }

    return SystemUiState(dark = dark, contrast = contrast, reducedMotion = reducedMotion)
}

private fun readAnimatorOff(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

private fun readContrast(context: Context): Double {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return 0.0
    return context.getSystemService(UiModeManager::class.java)?.contrast?.toDouble() ?: 0.0
}
