// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScreenshotCaseTest {
    @Test fun stableIdentityIsSharedAcrossPlatformRoots() {
        val id = ScreenshotCaseId("episode-row__unplayed__dark__f200__rtl-ar-xb__compact__colourful")
        val android = ScreenshotCase(id, ScreenshotPlatform.ANDROID, ScreenshotTier.PR)
        val desktop = android.copy(platform = ScreenshotPlatform.DESKTOP)
        assertEquals(android.id, desktop.id)
        assertTrue(ScreenshotTier.FULL >= android.requiredTier)
    }

    @Test fun invalidAndPathLikeIdentitiesFailClosed() {
        for (id in listOf("row", "../row", "a__b__c__d__e__f__G", "a__b__c__d__e__f__g/../x")) {
            assertFailsWith<IllegalArgumentException> { ScreenshotCaseId(id) }
        }
    }
}
