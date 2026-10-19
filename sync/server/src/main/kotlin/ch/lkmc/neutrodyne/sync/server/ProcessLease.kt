// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions

internal enum class ProcessMode { SERVE, LIVE_ADMIN, RESTORE }

/** All processes take the maintenance lease; only serve also takes the singleton lease.
 * Live administration never touches the clean marker. Restore requires every other process to exit.
 */
internal class ProcessLease private constructor(
    private val channels: List<FileChannel>,
    private val locks: List<FileLock>,
) : AutoCloseable {
    override fun close() {
        locks.reversed().forEach { it.release() }
        channels.reversed().forEach { it.close() }
    }

    companion object {
        fun acquire(
            dataDir: Path,
            mode: ProcessMode,
        ): ProcessLease {
            privateDirectory(dataDir)
            val channels = mutableListOf<FileChannel>()
            val locks = mutableListOf<FileLock>()
            try {
                fun lock(
                    name: String,
                    shared: Boolean,
                ) {
                    val channel = FileChannel.open(dataDir.resolve(name), CREATE, READ, WRITE)
                    channels += channel
                    locks += checkNotNull(channel.tryLock(0, Long.MAX_VALUE, shared)) { "Data directory is in use" }
                }
                lock("maintenance.lock", mode != ProcessMode.RESTORE)
                if (mode == ProcessMode.SERVE) lock("serve.lock", false)
                return ProcessLease(channels, locks)
            } catch (failure: Throwable) {
                locks.reversed().forEach { it.release() }
                channels.reversed().forEach { it.close() }
                throw failure
            }
        }
    }
}

internal fun privateDirectory(directory: Path) {
    Files.createDirectories(directory)
    try {
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
    } catch (_: UnsupportedOperationException) {
        // Non-POSIX platforms rely on the administrator's directory ACL.
    }
}
