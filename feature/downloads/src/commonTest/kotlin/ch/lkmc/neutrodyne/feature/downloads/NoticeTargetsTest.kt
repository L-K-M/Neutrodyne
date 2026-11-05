// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.downloads

import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import kotlin.test.Test
import kotlin.test.assertEquals

class NoticeTargetsTest {
    @Test
    fun anUnavailableRootOpensThePageThatHoldsTheStorageChoice() {
        // Android's SD-card choice lives on Settings › Downloads; the desktop folder on Settings › Desktop.
        assertEquals(SettingsPage.DOWNLOADS, rootUnavailableTarget(PlatformKind.ANDROID))
        assertEquals(SettingsPage.DESKTOP, rootUnavailableTarget(PlatformKind.DESKTOP))
    }
}
