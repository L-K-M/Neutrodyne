// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.model.settings.SettingsFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * "Reads never throw" (01 DataStore files and typed setting keys): a settings file that cannot be
 * read — an I/O error, or a corrupt file whose replacement failed (DataStore rethrows then) —
 * reads as the key's default instead of crashing the collector (review of `main`, 2026-10-07:
 * `MainActivity` collects `appearance.*` at start-up). Bugs and cancellation still propagate.
 */
class SettingStoreReadFailureTest {
    private val key = SettingKey.Int32("playback.sleep_last_minutes", DEFAULT, SettingsFile.PORTABLE)

    @Test
    fun anUnreadableFileObservesTheDefault() =
        runTest {
            val store = SettingStoreImpl(SettingsFile.PORTABLE, failingStore(IOException("disk full")))

            assertEquals(listOf(DEFAULT), store.observe(key).toList())
        }

    @Test
    fun anUnreadableFileGetsTheDefault() =
        runTest {
            val store = SettingStoreImpl(SettingsFile.PORTABLE, failingStore(IOException("disk full")))

            assertEquals(DEFAULT, store.get(key))
        }

    @Test
    fun aProgrammingErrorStillPropagates() =
        runTest {
            val store = SettingStoreImpl(SettingsFile.PORTABLE, failingStore(IllegalStateException("bug")))

            assertFailsWith<IllegalStateException> { store.observe(key).first() }
            assertFailsWith<IllegalStateException> { store.get(key) }
        }

    @Test
    fun cancellationStillPropagates() =
        runTest {
            val store = SettingStoreImpl(SettingsFile.PORTABLE, failingStore(CancellationException("stop")))

            assertFailsWith<CancellationException> { store.get(key) }
        }

    /** A store whose every read fails with [failure]; writes are never reached by these tests. */
    private fun failingStore(failure: Throwable): DataStore<Preferences> =
        object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw failure }

            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                error("not used")
        }

    private companion object {
        const val DEFAULT = 15
    }
}
