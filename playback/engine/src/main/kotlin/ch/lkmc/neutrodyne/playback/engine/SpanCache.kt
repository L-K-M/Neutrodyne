// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.TreeMap

/** Device-local streaming bytes. A resource lease pins its files until the source closes. */
class SpanCache(
    private val directory: Path,
    private var maxBytes: Long = DEFAULT_BYTES,
) {
    private val resources = mutableMapOf<String, Resource>()
    private var access = System.currentTimeMillis()

    init {
        require(maxBytes >= 0)
        try {
            Files.createDirectories(directory)
            Files.list(directory).use { paths ->
                paths.filter { Files.isDirectory(it) && !Files.isSymbolicLink(it) }.forEach { path ->
                    try {
                        val index = Json.decodeFromString<Index>(Files.readString(path.resolve(INDEX)))
                        if (path.fileName.toString() != hash(index.key)) return@forEach
                        if (!index.key.startsWith("yt:")) {
                            deleteOwnedDirectory(path)
                            return@forEach
                        }
                        require(index.length == null || index.length >= 0)
                        require(index.spans.all { (start, size) -> start >= 0 && size > 0 && start <= Long.MAX_VALUE - size })
                        val resource = Resource(index.key, path, index)
                        resources[index.key] = resource
                        access = maxOf(access, index.lastAccess)
                    } catch (_: IOException) {
                        // An interrupted index write or absent span is a cache miss.
                    } catch (_: IllegalArgumentException) {
                        // Invalid cache metadata is never trusted as a byte offset.
                    }
                }
            }
        } catch (_: IOException) {
            // Each new lease falls back to its bounded memory window.
        }
    }

    /** A new RSS pin is always empty; only yt: keys may reuse persistent bytes. */
    @Synchronized
    fun open(key: String): Resource {
        require(key.startsWith("ep:") || key.startsWith("yt:"))
        if (!key.startsWith("yt:")) {
            check((resources[key]?.pins ?: 0) == 0) { "RSS resource is already pinned" }
            remove(key)
        }
        val resource = resources.getOrPut(key) { Resource(key, directory.resolve(hash(key)), Index(key)) }
        resource.pins++
        resource.touch()
        return resource
    }

    @Synchronized
    fun remove(key: String) {
        val resource = resources[key] ?: return
        check(resource.pins == 0) { "cannot remove a pinned resource" }
        resource.invalidate()
        resources.remove(key)
    }

    /** Called by the loader, never the device callback. */
    @Synchronized
    fun evict() {
        var bytes = sizeBytes()
        for (resource in resources.values.sortedBy { it.lastAccess }) {
            if (bytes <= maxBytes) break
            if (resource.pins != 0) continue
            bytes -= resource.sizeBytes()
            remove(resource.key)
        }
    }

    @Synchronized
    fun setMaxBytes(bytes: Long) {
        require(bytes >= 0)
        maxBytes = bytes
        evict()
    }

    @Synchronized
    fun sizeBytes(): Long = resources.values.sumOf { it.sizeBytes() }

    @Synchronized
    fun clear() {
        resources.values.filter { it.pins == 0 }.map { it.key }.forEach(::remove)
    }

    /** All methods share the cache lock; disk failure switches this lease to memory. */
    inner class Resource internal constructor(
        val key: String,
        private val path: Path,
        index: Index,
    ) : AutoCloseable {
        internal var pins = 0
        internal var lastAccess = index.lastAccess
        private var totalLength = index.length
        private var storedValidator = index.validator
        private val spans = TreeMap(index.spans)
        private val memory = TreeMap<Long, ByteArray>()
        private var persistent = true

        internal val isPersistent: Boolean get() = synchronized(this@SpanCache) { persistent }

        val length: Long? get() = synchronized(this@SpanCache) { totalLength }
        val validator: String? get() = synchronized(this@SpanCache) { storedValidator }

        internal fun touch() {
            lastAccess = ++access
        }

        fun setMetadata(length: Long?, validator: String?) = synchronized(this@SpanCache) {
            require(length == null || length >= 0)
            totalLength = length
            storedValidator = validator
            persist()
        }

        fun read(position: Long, count: Int): ByteArray? = synchronized(this@SpanCache) {
            require(position >= 0 && count > 0)
            touch()
            val inMemory = memory.floorEntry(position)
            if (inMemory != null && position - inMemory.key < inMemory.value.size) {
                val offset = (position - inMemory.key).toInt()
                return@synchronized inMemory.value.copyOfRange(offset, minOf(inMemory.value.size, offset + count))
            }
            if (!persistent) return@synchronized null
            val span = spans.floorEntry(position) ?: return@synchronized null
            val offset = position - span.key
            if (offset >= span.value) return@synchronized null
            try {
                java.nio.channels.FileChannel.open(path.resolve(span.key.toString()), StandardOpenOption.READ).use { channel ->
                    val buffer = java.nio.ByteBuffer.allocate(minOf(count.toLong(), span.value - offset).toInt())
                    val read = channel.read(buffer, offset)
                    if (read <= 0) return@synchronized null
                    buffer.array().copyOf(read)
                }
            } catch (_: IOException) {
                disablePersistence()
                null
            }
        }

        fun write(position: Long, bytes: ByteArray) = synchronized(this@SpanCache) {
            require(position >= 0 && position <= Long.MAX_VALUE - bytes.size)
            if (bytes.isEmpty()) return@synchronized
            touch()
            if (persistent) {
                try {
                    Files.createDirectories(path)
                    val previous = spans.floorEntry(position)
                    val start = if (previous != null && previous.key + previous.value == position) previous.key else position
                    Files.newOutputStream(
                        path.resolve(start.toString()),
                        StandardOpenOption.CREATE,
                        if (start == position) StandardOpenOption.TRUNCATE_EXISTING else StandardOpenOption.APPEND,
                    ).use { it.write(bytes) }
                    spans[start] = position + bytes.size - start
                    persist()
                } catch (_: IOException) {
                    disablePersistence()
                }
            }
            if (!persistent) {
                memory[position] = bytes.copyOf()
                while (memory.values.sumOf { it.size.toLong() } > MEMORY_BYTES) memory.pollFirstEntry()
            }
        }

        /** Discard stale content even while this lease remains pinned for error reporting. */
        fun invalidate() = synchronized(this@SpanCache) {
            deleteOwnedDirectory(path)
            spans.clear()
            memory.clear()
            totalLength = null
            storedValidator = null
        }

        internal fun sizeBytes(): Long = spans.values.sum() + memory.values.sumOf { it.size.toLong() }

        override fun close() = synchronized(this@SpanCache) {
            check(pins > 0) { "cache lease already closed" }
            pins--
            touch()
            persist()
        }

        private fun disablePersistence() {
            persistent = false
            spans.clear()
            deleteOwnedDirectory(path)
        }

        private fun persist() {
            if (!persistent) return
            try {
                Files.createDirectories(path)
                val temporary = path.resolve("index.tmp")
                Files.writeString(temporary, Json.encodeToString(Index(key, totalLength, storedValidator, spans.toMap(), lastAccess)))
                Files.move(temporary, path.resolve(INDEX), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: IOException) {
                disablePersistence()
            }
        }
    }

    @Serializable
    internal data class Index(
        val key: String,
        val length: Long? = null,
        val validator: String? = null,
        val spans: Map<Long, Long> = emptyMap(),
        val lastAccess: Long = 0,
    )

    private companion object {
        const val DEFAULT_BYTES = 500L * 1024 * 1024
        const val MEMORY_BYTES = 8L * 1024 * 1024
        const val INDEX = "index.json"

        fun hash(key: String): String =
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(16).joinToString("") { "%02x".format(it) }

        fun deleteOwnedDirectory(path: Path) {
            try {
                if (!Files.isDirectory(path) || Files.isSymbolicLink(path)) return
                Files.list(path).use { entries -> entries.forEach { Files.deleteIfExists(it) } }
                Files.deleteIfExists(path)
            } catch (_: IOException) {
                // A failed deletion must not turn best-effort caching into playback failure.
            }
        }
    }
}
