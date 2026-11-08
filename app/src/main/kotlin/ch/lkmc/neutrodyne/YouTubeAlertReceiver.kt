// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Explicit, unexported action uses the main-process application service. */
class YouTubeAlertReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        (context.applicationContext as NeutrodyneApplication).graph.youtubeHealth.retryNow()
    }
}
