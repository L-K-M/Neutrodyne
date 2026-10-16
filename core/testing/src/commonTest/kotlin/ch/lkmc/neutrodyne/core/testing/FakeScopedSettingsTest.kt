// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import app.cash.turbine.test
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.model.Effective
import ch.lkmc.neutrodyne.core.model.SettingOverrides
import ch.lkmc.neutrodyne.core.model.SettingSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FakeScopedSettingsTest {
    @Test fun fakeResolverSelectsIdsAndObservesUpdates() =
        runTest {
            val fake = FakeEffectiveSettingsResolver()
            fake.notificationValues.value = mapOf(1L to Effective(true, SettingSource.AppDefault))
            assertEquals(emptyMap(), fake.notifications(listOf(2L)))
            assertEquals(fake.notificationValues.value, fake.observeNotifications(listOf(1L)).first())
            fake.observeRefreshIntervals().test {
                assertEquals(emptyMap(), awaitItem())
                fake.refreshValues.value = mapOf(1L to Effective(null, SettingSource.PodcastOverride))
                assertEquals(fake.refreshIntervals(), awaitItem())
            }
        }

    @Test fun fakeScopedWritesValidateAndClearOverrides() =
        runTest {
            val fake = FakeScopeSettingsRepository()
            fake.seedPodcast(1)
            fake.seedGroup(2, "uuid", "news")
            fake.updatePodcast(1) { SettingOverrides(0, true) }
            assertEquals(SettingOverrides(null, true), fake.observePodcast(1).first()?.effective)
            fake.updateGroup(2) { SettingOverrides(60, false) }
            val before = fake.observeGroup(2).first()
            assertEquals(
                Outcome.Failure(SettingsError.OutOfRange("refreshIntervalMinutes")),
                fake.updateGroup(2) {
                    SettingOverrides(1)
                },
            )
            assertEquals(before, fake.observeGroup(2).first())
            fake.updatePodcast(1) { SettingOverrides() }
            assertEquals(SettingOverrides(240, false), fake.observePodcast(1).first()?.effective)
            fake.removePodcast(1)
            fake.removeGroup(2)
            assertNull(fake.observePodcast(1).first())
            assertNull(fake.observeGroup(2).first())
            assertEquals(Outcome.Success(Unit), fake.updatePodcast(1) { error("Missing scope") })
            assertEquals(Outcome.Success(Unit), fake.updateGroup(2) { error("Missing scope") })
        }
}
