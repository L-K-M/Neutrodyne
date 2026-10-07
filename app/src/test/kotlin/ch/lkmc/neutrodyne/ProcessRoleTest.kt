// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProcessRoleTest {
    @Test
    fun classifiesReleaseAndDebugProcessNames() {
        assertThat(ProcessRole.fromProcessName("ch.lkmc.neutrodyne")).isEqualTo(ProcessRole.MAIN)
        assertThat(ProcessRole.fromProcessName("ch.lkmc.neutrodyne:ytx")).isEqualTo(ProcessRole.YTX)
        assertThat(ProcessRole.fromProcessName("ch.lkmc.neutrodyne:acra")).isEqualTo(ProcessRole.ACRA)
        assertThat(ProcessRole.fromProcessName("ch.lkmc.neutrodyne.debug:ytx")).isEqualTo(ProcessRole.YTX)
        assertThat(ProcessRole.fromProcessName("ch.lkmc.neutrodyne.debug")).isEqualTo(ProcessRole.MAIN)
    }

    @Test
    fun unknownSuffixIsMain() {
        assertThat(ProcessRole.fromProcessName("ch.lkmc.neutrodyne:other")).isEqualTo(ProcessRole.MAIN)
    }
}
