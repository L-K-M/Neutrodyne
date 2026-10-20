// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.download.impl

import android.system.ErrnoException
import android.system.OsConstants
import ch.lkmc.neutrodyne.core.datastore.SettingStore
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.model.settings.SettingsFile
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28, 34])
class AndroidStorageRootsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val roots get() = AndroidStorageRoots(context, DefaultSettings())

    @Test
    fun internalRootCreatesNomediaAndUnknownVolumeIsUnavailable() =
        runBlocking {
            val root = assertNotNull(roots.resolve("int"))
            assertTrue(File(root.absolutePath, ".nomedia").isFile)
            assertNull(roots.resolve("ext:missing-volume"))
            assertTrue(roots.list().any { it.rootId == "int" && it.available })
        }

    @Test
    fun errnoCauseChainDistinguishesFullOversizedAndUnmountedStorage() =
        runBlocking {
            for ((errno, failure) in mapOf(
                OsConstants.ENOSPC to StorageFailure.FULL,
                OsConstants.EFBIG to StorageFailure.TOO_LARGE,
                OsConstants.EIO to StorageFailure.UNAVAILABLE,
                OsConstants.EROFS to StorageFailure.UNAVAILABLE,
            )) {
                assertEquals(failure, roots.classify(IOException("write", ErrnoException("write", errno)), "int"))
            }
            assertEquals(StorageFailure.IO, roots.classify(IOException("other"), "int"))
        }

    @Test
    fun androidFileUriEncodesUnicodeSpacesAndFragments() {
        val uri = PlatformLocalUris().fileUri("/storage/é #name.mp3")
        assertTrue(uri.contains("%C3%A9%20%23name.mp3"))
    }

    private class DefaultSettings : SettingStore {
        override val file = SettingsFile.DEVICE

        override fun <T : Any> observe(key: SettingKey<T>) = flowOf(key.default)

        override suspend fun <T : Any> get(key: SettingKey<T>) = key.default

        override suspend fun <T : Any> set(
            key: SettingKey<T>,
            value: T,
        ) = error("unused")

        override suspend fun <T : Any> update(
            key: SettingKey<T>,
            transform: (T) -> T,
        ) = error("unused")

        override suspend fun reset(key: SettingKey<*>) = error("unused")
    }
}
