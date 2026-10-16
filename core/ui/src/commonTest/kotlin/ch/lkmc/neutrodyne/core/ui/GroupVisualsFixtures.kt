// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.GroupMosaicMember
import ch.lkmc.neutrodyne.core.model.GroupPalette
import ch.lkmc.neutrodyne.core.model.MediaFilter

/** Screenshot/semantics fixtures shared by the desktop and Android golden sets. */
internal fun groupFixture(
    name: String = "Tech",
    colorArgb: Int? = GroupPalette.COLORS[6],
    iconKey: String? = "memory",
    showAsTab: Boolean = true,
    memberCount: Int = 4,
): Group =
    Group(
        id = 1,
        uuid = "3d0a6d4a-7a6f-4a4f-9d2d-0f3f4c2c0a1a",
        name = name,
        orderKey = "a",
        colorArgb = colorArgb,
        iconKey = iconKey,
        feedOrder = FeedOrder.NEWEST_FIRST,
        playOrder = FeedOrder.NEWEST_FIRST,
        filterFlags = 0,
        mediaFilter = MediaFilter.ALL,
        hideOlderThanDays = null,
        showAsTab = showAsTab,
        lastViewedAt = null,
        createdAt = 1_700_000_000_000L,
        memberCount = memberCount,
    )

/** [n] mosaic members with deterministic cover refs (matching [mosaicImages]). */
internal fun mosaicMembers(n: Int): List<GroupMosaicMember> =
    (1..n).map { i ->
        GroupMosaicMember(
            groupId = 1,
            podcastId = i.toLong(),
            displayTitle = "Show $i",
            artwork = ArtworkRef("u-mosaic-$i", "https://x.test/mosaic-$i.jpg", 1),
            artworkAvgArgb = null,
        )
    }
