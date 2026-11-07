// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.model.DownloadState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The E10 fixture row's startup policy: the reset-and-park pass exists for an interrupted transfer.
 * A row that finished, lost its file or failed is the test's evidence; a later process start must not
 * rewrite the recorded reset or stamp a retry deadline on it (GLM review finding d).
 */
class SystemTestDownloadInitializerPolicyTest {
    @Test
    fun startupParksInterruptedFixtureRows() {
        assertThat(parksFixtureRowOnStartup(DownloadState.QUEUED)).isTrue()
        assertThat(parksFixtureRowOnStartup(DownloadState.RESOLVING)).isTrue()
        assertThat(parksFixtureRowOnStartup(DownloadState.DOWNLOADING)).isTrue()
        assertThat(parksFixtureRowOnStartup(DownloadState.PAUSED)).isTrue()
        assertThat(parksFixtureRowOnStartup(DownloadState.VERIFYING)).isTrue()
    }

    @Test
    fun startupLeavesTerminalFixtureRowsUntouched() {
        assertThat(parksFixtureRowOnStartup(DownloadState.COMPLETED)).isFalse()
        assertThat(parksFixtureRowOnStartup(DownloadState.MISSING)).isFalse()
        assertThat(parksFixtureRowOnStartup(DownloadState.FAILED)).isFalse()
    }
}
