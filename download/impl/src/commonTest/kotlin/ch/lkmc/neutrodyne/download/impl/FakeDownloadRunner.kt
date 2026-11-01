// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.model.WaitReason

/** Platform runner tests configure exit outcomes without claiming or transferring files. */
internal class FakeDownloadRunner : DownloadRunner {
    val requests = mutableListOf<DrainRequest>()
    var outcome: DrainOutcome = DrainOutcome.Blocked(WaitReason.SYSTEM)
    var failure: Throwable? = null

    override suspend fun drain(request: DrainRequest): DrainOutcome {
        requests += request
        failure?.let { throw it }
        return outcome
    }
}
