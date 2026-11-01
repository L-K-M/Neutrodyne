// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.update

import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ch.lkmc.neutrodyne.core.model.settings.UpdateSettingKeys as Keys
import ch.lkmc.neutrodyne.core.model.update.UpdateNotice as Notice
import ch.lkmc.neutrodyne.core.model.update.VerificationPhase as Phase

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateNoticesTest {
    private fun TestScope.notices(
        settings: FakeSettingsRepository,
        platform: BuildInfo.Platform = BuildInfo.Platform.ANDROID,
        now: Long = TestClock.DEFAULT_NOW,
        debug: Boolean = false,
        timeline: VerificationTimeline = VerificationTimeline(),
    ): UpdateNoticesImpl =
        UpdateNoticesImpl(
            updateBuild(platform, debug),
            settings,
            TestClock.from(testScheduler, now),
            timeline,
            backgroundScope,
        )

    @Test fun firstM11aLaunchShowsChoiceOnBothPlatforms() =
        runTest {
            BuildInfo.Platform.entries.forEach { platform ->
                val settings = FakeSettingsRepository()
                val notices = notices(settings, platform)
                runCurrent()
                assertEquals(Notice.FIRST_RUN_CHOICE, notices.pending.value)
                notices.dismiss(Notice.FIRST_RUN_CHOICE)
                runCurrent()
                assertNull(notices.pending.value)
                assertTrue(settings.get(Keys.FIRST_RUN_CHOICE_DONE))
                val restarted = notices(settings, platform)
                runCurrent()
                assertNull(restarted.pending.value)
            }
        }

    @Test fun debugBuildShowsNothingAndDoesNotRecordDismissals() =
        runTest {
            val settings = FakeSettingsRepository()
            val notices = notices(settings, now = VerificationTimeline.NOTICE_FROM, debug = true)
            runCurrent()
            assertNull(notices.pending.value)
            notices.dismiss(Notice.FIRST_RUN_CHOICE)
            notices.dismiss(Notice.VERIFICATION_ENFORCEMENT)
            runCurrent()
            assertEquals(false, settings.get(Keys.FIRST_RUN_CHOICE_DONE))
            assertEquals(Keys.ABSENT, settings.get(Keys.VERIFICATION_NOTICE_SHOWN_AT))
        }

    @Test fun restoredOffSuppressesAndDurablyCompletesFirstRunOnBothPlatforms() =
        runTest {
            BuildInfo.Platform.entries.forEach { platform ->
                val settings = FakeSettingsRepository().also { it.set(Keys.CHECK_ENABLED, false) }
                val notices = notices(settings, platform)
                runCurrent()
                assertNull(notices.pending.value)
                assertTrue(settings.get(Keys.FIRST_RUN_CHOICE_DONE))
                settings.set(Keys.CHECK_ENABLED, true)
                runCurrent()
                assertNull(notices.pending.value)
            }
        }

    @Test fun switchingOffWhileChoiceIsPendingCompletesIt() =
        runTest {
            val settings = FakeSettingsRepository()
            val notices = notices(settings)
            runCurrent()
            settings.set(Keys.CHECK_ENABLED, false)
            runCurrent()
            assertNull(notices.pending.value)
            assertTrue(settings.get(Keys.FIRST_RUN_CHOICE_DONE))
        }

    @Test fun firstChoicePrecedesVerificationAndBothAdvanceDurably() =
        runTest {
            val settings = FakeSettingsRepository()
            val notices = notices(settings, now = VerificationTimeline.NOTICE_FROM)
            runCurrent()
            assertEquals(Notice.FIRST_RUN_CHOICE, notices.pending.value)
            notices.dismiss(Notice.FIRST_RUN_CHOICE)
            runCurrent()
            assertEquals(Notice.VERIFICATION_ENFORCEMENT, notices.pending.value)
            notices.dismiss(Notice.VERIFICATION_ENFORCEMENT)
            runCurrent()
            assertNull(notices.pending.value)
            assertEquals(VerificationTimeline.NOTICE_FROM, settings.get(Keys.VERIFICATION_NOTICE_SHOWN_AT))
            val restarted = notices(settings, now = VerificationTimeline.NOTICE_FROM + DAY)
            runCurrent()
            assertNull(restarted.pending.value)
        }

    @Test fun desktopNeverRaisesAndroidVerificationNotice() =
        runTest {
            val settings = FakeSettingsRepository().also { it.set(Keys.FIRST_RUN_CHOICE_DONE, true) }
            val notices = notices(settings, BuildInfo.Platform.DESKTOP, VerificationTimeline.NOTICE_FROM + DAY)
            runCurrent()
            assertNull(notices.pending.value)
        }

    @Test fun verificationBoundaryUsesDefaultDecemberDateAndUnknownGlobalDate() =
        runTest {
            val settings = FakeSettingsRepository().also { it.set(Keys.FIRST_RUN_CHOICE_DONE, true) }
            val notices = notices(settings, now = VerificationTimeline.NOTICE_FROM - 1)
            runCurrent()
            assertNull(notices.pending.value)
            assertEquals(Phase.PRE_ENFORCEMENT, notices.verificationPhase.value)
            advanceTimeBy(1)
            notices.refresh()
            assertEquals(Notice.VERIFICATION_ENFORCEMENT, notices.pending.value)
            assertEquals(Phase.PRE_ENFORCEMENT, notices.verificationPhase.value)
        }

    @Test fun keptAliveProcessReevaluatesDateWithoutRestart() =
        runTest {
            val settings = FakeSettingsRepository().also { it.set(Keys.FIRST_RUN_CHOICE_DONE, true) }
            val notices = notices(settings, now = VerificationTimeline.NOTICE_FROM - 30_000)
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(Notice.VERIFICATION_ENFORCEMENT, notices.pending.value)
        }

    @Test fun knownGlobalBoundaryChangesCopyForPendingAndFreshInstalls() =
        runTest {
            val timeline = VerificationTimeline(0, VerificationTimeline.NOTICE_FROM)
            val settings = FakeSettingsRepository().also { it.set(Keys.FIRST_RUN_CHOICE_DONE, true) }
            val notices = notices(settings, now = VerificationTimeline.NOTICE_FROM - 1, timeline = timeline)
            runCurrent()
            assertEquals(Phase.PRE_ENFORCEMENT, notices.verificationPhase.value)
            advanceTimeBy(1)
            notices.refresh()
            assertEquals(Phase.POST_ENFORCEMENT, notices.verificationPhase.value)
            assertEquals(Notice.VERIFICATION_ENFORCEMENT, notices.pending.value)
            val fresh = notices(settings, now = VerificationTimeline.NOTICE_FROM, timeline = timeline)
            runCurrent()
            assertEquals(Phase.POST_ENFORCEMENT, fresh.verificationPhase.value)
        }

    @Test fun automaticChecksOffDoesNotSuppressAndroidVerification() =
        runTest {
            val settings = FakeSettingsRepository().also { it.set(Keys.CHECK_ENABLED, false) }
            val notices = notices(settings, now = VerificationTimeline.NOTICE_FROM)
            runCurrent()
            assertEquals(Notice.VERIFICATION_ENFORCEMENT, notices.pending.value)
            assertTrue(settings.get(Keys.FIRST_RUN_CHOICE_DONE))
        }

    @Test fun staleDismissalDoesNotWriteOrAdvanceThePendingNotice() =
        runTest {
            val settings = FakeSettingsRepository()
            val notices = notices(settings, now = VerificationTimeline.NOTICE_FROM)
            runCurrent()
            notices.dismiss(Notice.VERIFICATION_ENFORCEMENT)
            runCurrent()
            assertEquals(Notice.FIRST_RUN_CHOICE, notices.pending.value)
            assertEquals(Keys.ABSENT, settings.get(Keys.VERIFICATION_NOTICE_SHOWN_AT))
        }

    @Test fun failedDismissalRetainsNoticeAndRetryClearsError() =
        runTest {
            val settings = FakeSettingsRepository()
            val notices = notices(settings)
            runCurrent()
            settings.failNextSet = SettingsError.WriteFailed
            notices.dismiss(Notice.FIRST_RUN_CHOICE)
            runCurrent()
            assertEquals(Notice.FIRST_RUN_CHOICE, notices.pending.value)
            assertEquals(SettingsError.WriteFailed, notices.writeError.value)
            assertEquals(false, settings.get(Keys.FIRST_RUN_CHOICE_DONE))
            notices.dismiss(Notice.FIRST_RUN_CHOICE)
            runCurrent()
            assertNull(notices.pending.value)
            assertNull(notices.writeError.value)
        }

    @Test fun failedVerificationDismissalDoesNotClaimShown() =
        runTest {
            val settings = FakeSettingsRepository().also { it.set(Keys.FIRST_RUN_CHOICE_DONE, true) }
            val notices = notices(settings, now = VerificationTimeline.NOTICE_FROM)
            runCurrent()
            settings.failNextSet = SettingsError.WriteFailed
            notices.dismiss(Notice.VERIFICATION_ENFORCEMENT)
            runCurrent()
            assertEquals(Notice.VERIFICATION_ENFORCEMENT, notices.pending.value)
            assertEquals(Keys.ABSENT, settings.get(Keys.VERIFICATION_NOTICE_SHOWN_AT))
            notices.dismiss(Notice.VERIFICATION_ENFORCEMENT)
            runCurrent()
            assertNull(notices.pending.value)
        }

    @Test fun restoredOffCompletionFailureIsExposedAndRetried() =
        runTest {
            val settings = FakeSettingsRepository().also { it.set(Keys.CHECK_ENABLED, false) }
            settings.failNextSet = SettingsError.WriteFailed
            val notices = notices(settings)
            runCurrent()
            assertNull(notices.pending.value)
            assertEquals(SettingsError.WriteFailed, notices.writeError.value)
            assertEquals(false, settings.get(Keys.FIRST_RUN_CHOICE_DONE))
            notices.refresh()
            assertTrue(settings.get(Keys.FIRST_RUN_CHOICE_DONE))
            assertNull(notices.writeError.value)
        }
}
