// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.shell

import ch.lkmc.neutrodyne.core.common.AppDirs
import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Exactly one process per user (11 Single instance and handshake; Room has no multi-instance
 * invalidation off Android): `FileChannel.tryLock()` on `<state>/instance.lock` — never blocking.
 * The OS releases the lock when the process ends, also after a crash, so a stale file never
 * blocks a start. The lock holder writes `{pid, startedAt, versionName}` into the file for
 * diagnostics; the content is never trusted for decisions.
 *
 * The extra constructor parameters feed only that diagnostics content.
 */
class SingleInstanceLock(
    private val dirs: AppDirs,
    private val pid: Long = ProcessHandle.current().pid(),
    private val startedAtMs: Long = 0L,
    private val versionName: String = "",
) : AutoCloseable {
    private val lockFile: Path = dirs.state.resolve(LOCK_FILE_NAME)

    private var channel: FileChannel? = null
    private var lock: java.nio.channels.FileLock? = null

    /** The outcome of [tryAcquire]. */
    sealed interface Acquire {
        data object Acquired : Acquire

        /** Another process (or an earlier instance in this JVM) holds the lock. */
        data object HeldByOther : Acquire
    }

    @Serializable
    private data class OwnerInfo(
        val pid: Long,
        val startedAt: Long,
        val versionName: String,
    )

    fun tryAcquire(): Acquire {
        Files.createDirectories(dirs.state)
        val channel =
            FileChannel.open(
                lockFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.READ,
            )
        val lock =
            try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                // A holder inside this JVM: not us (each instance uses its own channel).
                null
            } catch (e: IOException) {
                runCatching { channel.close() }.exceptionOrNull()?.let(e::addSuppressed)
                throw e
            }
        if (lock == null) {
            channel.close()
            return Acquire.HeldByOther
        }

        this.channel = channel
        this.lock = lock
        writeOwnerInfo(channel)
        return Acquire.Acquired
    }

    private fun writeOwnerInfo(channel: FileChannel) {
        try {
            val info =
                SHELL_JSON.encodeToString(
                    OwnerInfo.serializer(),
                    OwnerInfo(pid = pid, startedAt = startedAtMs, versionName = versionName),
                )
            channel.truncate(0)
            channel.write(java.nio.ByteBuffer.wrap(info.toByteArray(Charsets.UTF_8)), 0)
            channel.force(false)
        } catch (_: java.io.IOException) {
            // Diagnostics only: a failing write never blocks the start.
        }
    }

    /** Releases the lock; the OS would also release it when the process ends. */
    override fun close() {
        try {
            lock?.release()
        } finally {
            channel?.close()
            lock = null
            channel = null
        }
    }

    companion object {
        const val LOCK_FILE_NAME = "instance.lock"
    }
}
