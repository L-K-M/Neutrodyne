// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.update.AppUpdateChecker
import ch.lkmc.neutrodyne.core.domain.update.UpdateNotices
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckState
import ch.lkmc.neutrodyne.core.model.update.UpdateDisabledReason
import ch.lkmc.neutrodyne.core.model.update.UpdateInfo
import ch.lkmc.neutrodyne.core.model.update.UpdateNotice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.assertNull

/** Public checker behavior reusable by coordinator implementations with equivalent seeded durable state. */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class AppUpdateCheckerContract {
    protected abstract fun TestScope.createChecker(initial: UpdateCheckState): AppUpdateChecker

    @Test fun debugRefusesManualCheck() =
        runTest {
            val disabled = UpdateCheckState.Disabled(UpdateDisabledReason.DEV_BUILD)
            assertEquals(disabled, createChecker(disabled).checkNow())
        }

    @Test fun skipKeepsLastCheckTime() =
        runTest {
            val checker = createChecker(UpdateCheckState.Available(updateContractInfo(), 123L))
            checker.skip(1000095L)
            advanceUntilIdle()
            assertEquals(UpdateCheckState.Idle(123L), checker.state.value)
            assertNull(checker.writeError.value)
        }

    @Test fun staleSkipCannotHideAnotherVersion() =
        runTest {
            val available = UpdateCheckState.Available(updateContractInfo(), 123L)
            val checker = createChecker(available)
            checker.skip(999L)
            advanceUntilIdle()
            assertEquals(available, checker.state.value)
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
abstract class UpdateNoticesContract {
    protected abstract fun TestScope.createNotices(notices: List<UpdateNotice>): UpdateNotices

    @Test fun dismissAdvancesOneNotice() =
        runTest {
            val notices = createNotices(UpdateNotice.entries)
            assertEquals(UpdateNotice.FIRST_RUN_CHOICE, notices.pending.value)
            notices.dismiss(UpdateNotice.FIRST_RUN_CHOICE)
            advanceUntilIdle()
            assertEquals(UpdateNotice.VERIFICATION_ENFORCEMENT, notices.pending.value)
            notices.dismiss(UpdateNotice.FIRST_RUN_CHOICE)
            advanceUntilIdle()
            assertEquals(UpdateNotice.VERIFICATION_ENFORCEMENT, notices.pending.value)
            notices.dismiss(UpdateNotice.VERIFICATION_ENFORCEMENT)
            advanceUntilIdle()
            assertNull(notices.pending.value)
            assertNull(notices.writeError.value)
        }

    @Test fun noNoticeIsInert() =
        runTest {
            val notices = createNotices(emptyList())
            notices.dismiss(UpdateNotice.FIRST_RUN_CHOICE)
            advanceUntilIdle()
            assertNull(notices.pending.value)
        }
}

fun updateContractInfo(): UpdateInfo =
    UpdateInfo(
        versionName = "1.0.0",
        versionCode = 1000095L,
        minSdk = 26,
        publishedAt = "2026-10-19T00:00:00Z",
        notes = "Release notes",
        releaseUrl = "https://github.com/L-K-M/Neutrodyne/releases/tag/v1.0.0",
        apk = null,
        desktopSelection = null,
        server = null,
    )
