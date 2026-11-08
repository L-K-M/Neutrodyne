// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.runtime.Composable

/**
 * The platform's local-network runtime-permission prompt (08 Permission prompts, 10
 * Local-network gate): `null` where the OS has no such prompt — the desktop and Android below 37
 * — so the explicit "Continue" tap answers the link flow directly there. On Android 17+ the
 * prompt asks the OS for `ACCESS_LOCAL_NETWORK` and only its real result answers the flow.
 */
internal fun interface LocalNetworkPermissionPrompt {
    /**
     * Launches the OS prompt; [onResult] receives the granted flag exactly once. A call while a
     * prompt is already out is dropped, so a double tap can neither stack dialogs nor answers.
     */
    fun request(onResult: (Boolean) -> Unit)
}

/**
 * The bridge the sync setup asks on its explicit "Continue" tap. Registering the launcher in
 * composition never shows the prompt itself; only [LocalNetworkPermissionPrompt.request] does.
 */
@Composable
internal expect fun rememberLocalNetworkPermissionPrompt(): LocalNetworkPermissionPrompt?
