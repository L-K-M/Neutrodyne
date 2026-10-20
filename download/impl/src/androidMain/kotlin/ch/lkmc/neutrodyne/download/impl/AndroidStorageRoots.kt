// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.download.impl

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import ch.lkmc.neutrodyne.core.datastore.SettingStore
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import ch.lkmc.neutrodyne.download.api.RootKind
import ch.lkmc.neutrodyne.download.api.StorageRootInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal class AndroidStorageRoots(
    private val context: Context,
    private val settings: SettingStore,
) : StorageRoots {
    private val manager = context.getSystemService(StorageManager::class.java)

    override suspend fun current(): String =
        withContext(Dispatchers.IO) {
            val selected = settings.get(DownloadSettingKeys.ROOT_ID)
            if (selected == PRIMARY &&
                context.getExternalFilesDir(Environment.DIRECTORY_PODCASTS) == null
            ) {
                INTERNAL
            } else {
                selected
            }
        }

    override suspend fun list(): List<StorageRootInfo> =
        withContext(Dispatchers.IO) {
            locations().map { (id, dir) ->
                val resolved = resolve(id)
                val volume = if (id == INTERNAL) null else manager.getStorageVolume(dir)
                StorageRootInfo(
                    id,
                    when (id) {
                        PRIMARY -> RootKind.PRIMARY_EXTERNAL
                        INTERNAL -> RootKind.INTERNAL
                        else -> RootKind.REMOVABLE
                    },
                    volume?.getDescription(context),
                    resolved?.let { StatFs(it.absolutePath).availableBytes },
                    resolved != null,
                )
            }
        }

    override suspend fun resolve(rootId: String): ResolvedRoot? =
        withContext(Dispatchers.IO) {
            val dir = locations()[rootId] ?: return@withContext null
            if (rootId != INTERNAL &&
                Environment.getExternalStorageState(dir) != Environment.MEDIA_MOUNTED
            ) {
                return@withContext null
            }
            try {
                if ((!dir.isDirectory && !dir.mkdirs()) || !dir.canWrite()) return@withContext null
                File(dir, ".nomedia").createNewFile()
                ResolvedRoot(rootId, dir.canonicalPath)
            } catch (_: IOException) {
                null
            }
        }

    override suspend fun ensureSpace(
        rootId: String,
        requiredBytes: Long,
    ): StorageFailure? =
        withContext(Dispatchers.IO) {
            require(requiredBytes >= 0)
            val root = resolve(rootId) ?: return@withContext StorageFailure.UNAVAILABLE
            val dir = File(root.absolutePath)
            try {
                val uuid = manager.getUuidForPath(dir)
                if (requiredBytes > manager.getAllocatableBytes(uuid)) return@withContext StorageFailure.FULL
                if (StatFs(root.absolutePath).availableBytes < requiredBytes) manager.allocateBytes(uuid, requiredBytes)
                if (StatFs(root.absolutePath).availableBytes < requiredBytes) StorageFailure.FULL else null
            } catch (_: IOException) {
                fallbackSpace(root, requiredBytes)
            } catch (_: IllegalArgumentException) {
                // Some removable public volumes have no allocatable UUID; do not allocate by FD.
                fallbackSpace(root, requiredBytes)
            } catch (_: UnsupportedOperationException) {
                fallbackSpace(root, requiredBytes)
            }
        }

    private fun fallbackSpace(
        root: ResolvedRoot,
        requiredBytes: Long,
    ): StorageFailure? =
        try {
            if (StatFs(root.absolutePath).availableBytes < requiredBytes) StorageFailure.FULL else null
        } catch (_: IllegalArgumentException) {
            StorageFailure.UNAVAILABLE
        }

    override suspend fun classify(
        error: Throwable,
        rootId: String,
    ): StorageFailure =
        withContext(Dispatchers.IO) {
            var cause: Throwable? = error
            while (cause != null) {
                if (cause is ErrnoException) {
                    when (cause.errno) {
                        OsConstants.ENOSPC -> return@withContext StorageFailure.FULL
                        OsConstants.EFBIG -> return@withContext StorageFailure.TOO_LARGE
                        OsConstants.EROFS, OsConstants.EIO -> return@withContext StorageFailure.UNAVAILABLE
                    }
                }
                cause = cause.cause
            }
            if (resolve(rootId) == null) StorageFailure.UNAVAILABLE else StorageFailure.IO
        }

    private fun locations(): Map<String, File> =
        buildMap {
            put(INTERNAL, File(context.filesDir, "downloads"))
            context.getExternalFilesDirs(Environment.DIRECTORY_PODCASTS).forEachIndexed { index, dir ->
                if (dir == null) return@forEachIndexed
                if (index == 0) {
                    put(PRIMARY, dir)
                } else {
                    manager.getStorageVolume(dir)?.uuid?.let { put("ext:$it", dir) }
                }
            }
        }

    private companion object {
        const val PRIMARY = "ext:primary"
        const val INTERNAL = "int"
    }
}

internal actual class PlatformLocalUris actual constructor() : LocalUris {
    actual override fun fileUri(absolutePath: String): String = Uri.fromFile(File(absolutePath)).toString()
}

internal actual fun platformPartialDirectory(path: String) = Unit

internal actual fun pathPlatform(): PathPlatform = PathPlatform.PORTABLE

internal actual fun platformSameStore(
    source: String,
    target: String,
): Boolean = false
