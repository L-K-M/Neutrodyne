// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom

/** Serve owns crash recovery and the marker; closing a store or an admin command never marks clean. */
internal class ServerStorage private constructor(
    val store: SqliteSyncStore,
    private val lease: ProcessLease,
    private val dataDir: Path,
) : AutoCloseable {
    private var stopped = false

    @Synchronized
    fun finishGracefully() {
        if (stopped) return
        store.close()
        val temporary = dataDir.resolve(".clean-shutdown.tmp")
        Files.writeString(temporary, "clean\n")
        Files.move(
            temporary,
            dataDir.resolve(CLEAN_MARKER),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        stopped = true
        lease.close()
    }

    @Synchronized
    override fun close() {
        if (stopped) return
        try {
            store.close()
        } finally {
            stopped = true
            lease.close()
        }
    }

    companion object {
        const val CLEAN_MARKER = "clean-shutdown"

        fun open(
            dataDir: Path,
            publish: (ServerEvent) -> Unit = {},
        ): ServerStorage {
            val lease = ProcessLease.acquire(dataDir, ProcessMode.SERVE)
            var store: SqliteSyncStore? = null
            try {
                val unclean =
                    Files.exists(dataDir.resolve(SqliteSyncStore.DATABASE_FILE)) &&
                        !Files.exists(dataDir.resolve(CLEAN_MARKER))
                Files.deleteIfExists(dataDir.resolve(CLEAN_MARKER))
                store = SqliteSyncStore(dataDir, publish)
                if (unclean) {
                    val random = SecureRandom()
                    runBlocking {
                        store.recoverUncleanStart(
                            {
                                ByteArray(EPOCH_BYTES).also(random::nextBytes).joinToString("") {
                                    it.toUByte().toString(16).padStart(2, '0')
                                }
                            },
                            System.currentTimeMillis(),
                        )
                    }
                }
                return ServerStorage(store, lease, dataDir)
            } catch (failure: Throwable) {
                store?.close()
                lease.close()
                throw failure
            }
        }

        private const val EPOCH_BYTES = 4
    }
}
