// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.AutoDownloadPlanner
import ch.lkmc.neutrodyne.core.domain.CleanupPlanner
import ch.lkmc.neutrodyne.core.domain.CleanupReason
import ch.lkmc.neutrodyne.core.domain.DownloadMover
import ch.lkmc.neutrodyne.core.domain.DownloadRootRegistration
import ch.lkmc.neutrodyne.core.domain.ExitReasonProbe
import ch.lkmc.neutrodyne.core.domain.MoveResult
import ch.lkmc.neutrodyne.core.domain.ProcessExit
import ch.lkmc.neutrodyne.download.api.FolderRejectReason
import ch.lkmc.neutrodyne.download.api.FolderRootResult
import ch.lkmc.neutrodyne.download.api.RootChangeMode
import ch.lkmc.neutrodyne.download.api.RootChangeResult

/** Recording fakes expose fixture hooks; no production binding or implicit successful move. */
class FakeAutoDownloadPlanner : AutoDownloadPlanner {
    val calls = mutableListOf<List<Long>>()
    var onPlan: suspend (Collection<Long>) -> Unit = {}

    override suspend fun plan(podcastIds: Collection<Long>) {
        calls += podcastIds.toList()
        onPlan(podcastIds)
    }
}

class FakeCleanupPlanner : CleanupPlanner {
    val calls = mutableListOf<CleanupReason>()
    var onRun: suspend (CleanupReason) -> Unit = {}

    override suspend fun run(reason: CleanupReason) {
        calls += reason
        onRun(reason)
    }
}

class FakeDownloadMover : DownloadMover {
    val calls = mutableListOf<Pair<String, Long?>>()
    var result = MoveResult.PENDING

    override suspend fun run(
        target: String,
        deadline: Long?,
    ): MoveResult {
        calls += target to deadline
        return result
    }
}

class FakeDownloadRootRegistration : DownloadRootRegistration {
    val registrations = mutableListOf<String>()
    val changes = mutableListOf<Pair<String, RootChangeMode>>()
    var registrationResult: FolderRootResult = FolderRootResult.Rejected(FolderRejectReason.UNSUPPORTED)
    var changeResult: RootChangeResult = RootChangeResult.Unsupported

    override suspend fun registerFolderRoot(absolutePath: String): FolderRootResult {
        registrations += absolutePath
        return registrationResult
    }

    override suspend fun changeRoot(
        rootId: String,
        mode: RootChangeMode,
    ): RootChangeResult {
        changes += rootId to mode
        return changeResult
    }
}

class FakeExitReasonProbe : ExitReasonProbe {
    var exit: ProcessExit? = null

    override fun latest(): ProcessExit? = exit
}
