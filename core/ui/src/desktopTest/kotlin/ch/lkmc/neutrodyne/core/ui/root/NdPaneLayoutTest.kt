// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.ui.root

import kotlin.test.Test
import kotlin.test.assertEquals

class NdPaneLayoutTest {
    @Test fun contentThresholdsAreIndependentOfWindowClasses() {
        for (chrome in listOf(0, 96)) {
            for ((content, partitions) in listOf(599 to 1, 600 to 2, 1199 to 2, 1200 to 3)) {
                val layout = ndPaneLayout(content + chrome, chrome, false, false)
                assertEquals(content, layout.contentWidthDp)
                assertEquals(partitions, layout.partitions, "content=$content chrome=$chrome")
                assertEquals(false, layout.playerPanel)
            }
        }
    }

    @Test fun acceptedWindowBoundariesWithActualRailChrome() {
        data class Case(
            val width: Int,
            val playing: Boolean,
            val content: Int,
            val panes: Int,
            val panel: Boolean,
        )
        val cases =
            listOf(
                Case(695, false, 599, 1, false),
                Case(696, false, 600, 2, false),
                Case(839, true, 743, 2, false),
                Case(840, true, 384, 1, true),
                Case(1055, true, 599, 1, true),
                Case(1056, true, 600, 2, true),
                Case(1199, true, 743, 2, true),
                Case(1200, true, 692, 2, true),
                Case(1295, false, 1199, 2, false),
                Case(1296, false, 1200, 3, false),
                Case(1707, true, 1199, 2, true),
                Case(1708, true, 1200, 3, true),
            )
        for (case in cases) {
            val actual = ndPaneLayout(case.width, 96, case.playing, false)
            assertEquals(case.content, actual.contentWidthDp, "$case")
            assertEquals(case.panes, actual.partitions, "$case")
            assertEquals(case.panel, actual.playerPanel, "$case")
            assertEquals(
                case.width,
                actual.contentWidthDp + 96 +
                    if (case.panel) {
                        if (case.width >= 1200) 412 else 360
                    } else {
                        0
                    },
            )
        }
    }

    @Test fun shortHeightAndTabletopBarReserveNoRail() {
        for ((width, content, panes) in listOf(
            Triple(839, 839, 2),
            Triple(840, 480, 1),
            Triple(959, 599, 1),
            Triple(960, 600, 2),
            Triple(1199, 839, 2),
            Triple(1200, 788, 2),
            Triple(1611, 1199, 2),
            Triple(1612, 1200, 3),
        )) {
            val actual = ndPaneLayout(width, 0, true, false)
            assertEquals(content, actual.contentWidthDp)
            assertEquals(panes, actual.partitions)
        }
    }

    @Test fun hiddenSettingSurvivesIdleAndNewPlaybackWithoutChangingArithmetic() {
        for (playing in listOf(true, false, true)) {
            val hidden = ndPaneLayout(1708, 96, playing, true)
            assertEquals(false, hidden.playerPanel)
            assertEquals(1612, hidden.contentWidthDp)
            assertEquals(3, hidden.partitions)
        }
    }
}
