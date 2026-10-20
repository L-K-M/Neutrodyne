// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.SourceKind
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.download.api.DownloadEntry
import ch.lkmc.neutrodyne.download.api.DownloadStatus

fun testDownloadStatus(
    id: Long = 1,
    state: DownloadState = DownloadState.QUEUED,
    wait: WaitReason = WaitReason.NONE,
): DownloadStatus =
    DownloadStatus(
        id,
        DownloadLane.MANUAL,
        state,
        wait,
        0,
        null,
        null,
        null,
        null,
        null,
        null,
        false,
        false,
        SourceKind.RSS_ENCLOSURE,
        null,
    )

fun testDownloadEntry(
    id: Long = 1,
    state: DownloadState = DownloadState.QUEUED,
    wait: WaitReason = WaitReason.NONE,
): DownloadEntry =
    DownloadEntry(
        testDownloadStatus(id, state, wait),
        1,
        "Podcast",
        "Episode $id",
        ArtworkRef("m-test", null, 0),
        id,
        false,
        false,
    )
