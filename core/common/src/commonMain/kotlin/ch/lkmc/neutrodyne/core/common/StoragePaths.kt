// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

/**
 * Where app files live, small per-platform shim (01 Source sets and JVM islands): the Android
 * `actual` reads `Context` (`filesDir`/`noBackupFilesDir`/`cacheDir`), the desktop `actual` is
 * built from [AppDirs]. Paths are absolute. `dataStoreFile(name)` yields the DataStore file path —
 * `:core:datastore` passes `SettingsFile.storeName` and wraps the result in `okio.Path`.
 */
expect class StoragePaths {
    /** Durable app-private files root (`filesDir` on Android; the data dir on desktop). */
    val filesDir: String

    /** Files excluded from backup (`noBackupFilesDir` on Android; the data dir on desktop). */
    val noBackupDir: String

    /** Disposable caches (`cacheDir` on Android; the cache dir on desktop). */
    val cacheDir: String

    /** Absolute path of the preferences DataStore file `<name>.preferences_pb`. */
    fun dataStoreFile(name: String): String
}
