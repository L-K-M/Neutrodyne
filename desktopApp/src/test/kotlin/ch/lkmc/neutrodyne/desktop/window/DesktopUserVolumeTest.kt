// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.DesktopSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals

class DesktopUserVolumeTest {
    @Test
    fun aStoredVolumeOutsideTheRangeIsClamped() =
        runBlocking {
            val settings = FakeSettingsRepository()
            settings.set(DesktopSettingKeys.VOLUME, 1.7f)
            val applied = mutableListOf<Float>()
            val volume = DesktopUserVolume({ 1f }, { applied += it }, settings, this)

            volume.restore()

            assertEquals(listOf(1f), applied)
            assertEquals(1f, volume.volume)
        }

    @Test
    fun aFailedReadKeepsTheDefaultVolume() =
        runBlocking {
            val settings =
                object : SettingsRepository by FakeSettingsRepository() {
                    override suspend fun <T : Any> get(key: SettingKey<T>): T = throw IOException("disk")
                }
            val applied = mutableListOf<Float>()
            val volume = DesktopUserVolume({ 1f }, { applied += it }, settings, this)

            volume.restore()

            assertEquals(listOf(DesktopSettingKeys.VOLUME.default), applied)
        }

    @Test
    fun rapidChangesPersistTheLastValue() =
        runBlocking {
            val store = FakeSettingsRepository()
            val release = CompletableDeferred<Unit>()
            // The first write (0.9) is held until both changes were made, so a write that
            // overtakes it would leave the older value stored last.
            val settings =
                object : SettingsRepository by store {
                    override suspend fun <T : Any> set(
                        key: SettingKey<T>,
                        value: T,
                    ) = run {
                        if (value == 0.9f) release.await()
                        store.set(key, value)
                    }
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val volume = DesktopUserVolume({ 1f }, {}, settings, scope)

            volume.volumeDown()
            volume.volumeDown()
            release.complete(Unit)
            scope.coroutineContext.job.children
                .forEach { it.join() }

            assertEquals(0.8f, store.get(DesktopSettingKeys.VOLUME), 0.0001f)
        }
}
