// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** The permission the OS prompt asks for; a literal so it cannot drift from the manifest. */
internal const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

/** `ACCESS_LOCAL_NETWORK` exists from API 37 (Android 17; 10 Local-network gate). */
private const val LOCAL_NETWORK_PERMISSION_SDK = 37

/**
 * Host-test seam: Robolectric tops out at SDK 36, so tests pin the level the prompt check reads.
 * Production code never sets it and always sees `Build.VERSION.SDK_INT`.
 */
internal var testLocalNetworkSdkInt: Int? = null

/**
 * Android's bridge (10 Local-network gate): registers the `RequestPermission` launcher while the
 * rationale dialog is composed — never launches on its own — and delivers the OS answer verbatim.
 */
@Composable
internal actual fun rememberLocalNetworkPermissionPrompt(): LocalNetworkPermissionPrompt? {
    if ((testLocalNetworkSdkInt ?: Build.VERSION.SDK_INT) < LOCAL_NETWORK_PERMISSION_SDK) return null
    val pending = remember { PendingLocalNetworkPermission() }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pending.deliver(granted)
        }
    return remember(launcher) {
        LocalNetworkPermissionPrompt { onResult ->
            if (pending.start(onResult)) launcher.launch(LOCAL_NETWORK_PERMISSION)
        }
    }
}

/**
 * The open OS request's callback. One prompt maps to one [deliver]: a [start] while a request is
 * out is refused, so a second tap stacks neither dialogs nor answers. Disposing the dialog
 * unregisters the launcher, and this remembered holder goes with it — a late OS answer has no
 * callback left to reach.
 */
private class PendingLocalNetworkPermission {
    private var onResult: ((Boolean) -> Unit)? = null

    fun start(result: (Boolean) -> Unit): Boolean {
        if (onResult != null) return false
        onResult = result
        return true
    }

    fun deliver(granted: Boolean) {
        val result = onResult
        onResult = null
        result?.invoke(granted)
    }
}
