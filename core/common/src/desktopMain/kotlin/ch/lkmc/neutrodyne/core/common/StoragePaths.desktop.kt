// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

/**
 * Desktop storage roots (01), built from [AppDirs]: files → the data dir, cache → the cache dir,
 * DataStore files → the config dir. Desktop has no backup exclusion — `noBackupDir` aliases the
 * data dir so excluded content still lands somewhere durable.
 */
actual class StoragePaths(
    private val dirs: AppDirs,
) {
    actual val filesDir: String = dirs.data.toString()
    actual val noBackupDir: String = dirs.data.toString()
    actual val cacheDir: String = dirs.cache.toString()

    actual fun dataStoreFile(name: String): String = dirs.config.resolve("$name.preferences_pb").toString()
}
