// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckState
import ch.lkmc.neutrodyne.core.model.update.UpdateDisabledReason
import ch.lkmc.neutrodyne.core.model.update.UpdateNotice
import ch.lkmc.neutrodyne.core.model.update.UpdateRuntimeTarget
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FakeAppUpdateCheckerTest : AppUpdateCheckerContract() {
    override fun TestScope.createChecker(initial: UpdateCheckState) = FakeAppUpdateChecker(initial)

    @Test fun failedSkipRetainsCardAndRetryClearsError() {
        val available = UpdateCheckState.Available(updateContractInfo(), 123L)
        val checker = FakeAppUpdateChecker(available)
        checker.failNextWrite = SettingsError.WriteFailed
        checker.skip(available.info.versionCode)
        assertEquals(available, checker.state.value)
        assertEquals(SettingsError.WriteFailed, checker.writeError.value)
        checker.skip(available.info.versionCode)
        assertEquals(UpdateCheckState.Idle(123L), checker.state.value)
        assertNull(checker.writeError.value)
    }

    @Test fun manualResultIsControllableWhileChecksAreOff() =
        runTest {
            val checker = FakeAppUpdateChecker(UpdateCheckState.Disabled(UpdateDisabledReason.CHECKS_OFF))
            val available = UpdateCheckState.Available(updateContractInfo(), 123L)
            checker.nextCheckState = available
            assertEquals(available, checker.checkNow())
            assertEquals(listOf("checkNow"), checker.calls)
        }

    @Test fun debugWinsOverRestoredOrScriptedResult() =
        runTest {
            val disabled = UpdateCheckState.Disabled(UpdateDisabledReason.DEV_BUILD)
            val checker = FakeAppUpdateChecker(disabled)
            checker.nextCheckState = UpdateCheckState.Available(updateContractInfo(), 123L)
            assertEquals(disabled, checker.checkNow())
        }
}

class FakeUpdateNoticesTest : UpdateNoticesContract() {
    override fun TestScope.createNotices(notices: List<UpdateNotice>) = FakeUpdateNotices(notices)

    @Test fun failedDismissRetainsNoticeAndRetryClearsError() {
        val notices = FakeUpdateNotices(UpdateNotice.entries)
        notices.failNextWrite = SettingsError.WriteFailed
        notices.dismiss(UpdateNotice.FIRST_RUN_CHOICE)
        assertEquals(UpdateNotice.FIRST_RUN_CHOICE, notices.pending.value)
        assertEquals(SettingsError.WriteFailed, notices.writeError.value)
        assertEquals(emptyList(), notices.dismissals)
        notices.dismiss(UpdateNotice.FIRST_RUN_CHOICE)
        assertEquals(UpdateNotice.VERIFICATION_ENFORCEMENT, notices.pending.value)
        assertNull(notices.writeError.value)
    }
}

class FakeUpdateRuntimeInputsTest {
    @Test fun runtimeTargetDoesNotInventDeviceFacts() {
        val target = UpdateRuntimeTarget.Desktop("10.0")
        assertEquals(target, FakeUpdateRuntimeInputs(target).target)
        assertEquals(UpdateRuntimeTarget.Android("arm64-v8a", 26), FakeUpdateRuntimeInputs().target)
    }
}
