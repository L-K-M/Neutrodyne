// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The fake against the shared contract plus the fake-only surface (09):
 * [FakeSettingsRepository.failNextSet] and the [FakeSettingsRepository.calls] log.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeSettingsRepositoryTest : SettingsRepositoryContract() {
    override fun TestScope.createRepository(): SettingsRepository = FakeSettingsRepository()

    @Test
    fun failNextSetFailsExactlyOneSetWithoutStoring() =
        runTest {
            val fake = FakeSettingsRepository()
            fake.failNextSet = SettingsError.WriteFailed

            assertEquals(Outcome.Failure(SettingsError.WriteFailed), fake.set(portableBool, true))
            assertEquals(false, fake.get(portableBool))

            assertEquals(Outcome.Success(Unit), fake.set(portableBool, true))
            assertEquals(true, fake.get(portableBool))
        }

    @Test
    fun everyCallIsLoggedInOrder() =
        runTest {
            val fake = FakeSettingsRepository()

            fake.get(portableBool)
            fake.set(portableBool, true)
            fake.reset(portableBool)

            assertEquals(
                listOf(
                    "get(feeds.notify_new_episodes)",
                    "set(feeds.notify_new_episodes)",
                    "reset(feeds.notify_new_episodes)",
                ),
                fake.calls,
            )
        }
}
