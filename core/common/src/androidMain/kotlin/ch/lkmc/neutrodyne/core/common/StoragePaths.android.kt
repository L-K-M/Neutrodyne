// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import android.content.Context
import java.io.File

/**
 * Android storage roots (01): the app-private dirs the platform gives us — `filesDir`,
 * `noBackupFilesDir`, `cacheDir`. The shells construct this once with `applicationContext` and
 * bind it; DataStore files land under `filesDir/datastore/` like every Android app.
 */
actual class StoragePaths(
    context: Context,
) {
    actual val filesDir: String = context.filesDir.absolutePath
    actual val noBackupDir: String = context.noBackupFilesDir.absolutePath
    actual val cacheDir: String = context.cacheDir.absolutePath

    actual fun dataStoreFile(name: String): String = File(filesDir, "datastore/$name.preferences_pb").absolutePath
}
