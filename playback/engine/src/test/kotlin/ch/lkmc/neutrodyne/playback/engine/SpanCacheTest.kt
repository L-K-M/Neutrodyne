// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpanCacheTest {
    @Test
    fun rssPinsAreEmptyAndOnlyYoutubeSurvivesReopen() {
        val directory = Files.createTempDirectory("span-cache")
        try {
            val cache = SpanCache(directory, 100)
            cache.open("ep:1:pin").use { it.write(0, byteArrayOf(1, 2, 3)) }
            cache.open("ep:1:pin").use { assertNull(it.read(0, 3)) }
            cache.open("yt:video:140").use {
                it.setMetadata(3, "\"strong\"")
                it.write(0, byteArrayOf(4, 5, 6))
            }
            SpanCache(directory, 100).open("yt:video:140").use {
                assertContentEquals(byteArrayOf(4, 5, 6), it.read(0, 3))
                assertEquals(3L, it.length)
                assertEquals("\"strong\"", it.validator)
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun evictionAndClearRespectPins() {
        val directory = Files.createTempDirectory("span-cache")
        try {
            val cache = SpanCache(directory, 4)
            cache.open("yt:old:140").use { it.write(0, byteArrayOf(1, 2, 3)) }
            cache.open("yt:pinned:140").use { pinned ->
                pinned.write(0, byteArrayOf(4, 5, 6))
                cache.evict()
                cache.open("yt:old:140").use { assertNull(it.read(0, 3)) }
                cache.clear()
                assertContentEquals(byteArrayOf(4, 5, 6), pinned.read(0, 3))
            }
            cache.clear()
            assertEquals(0L, cache.sizeBytes())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun memoryWindowRetainsNewBytesAfterABackwardSeek() {
        val file = Files.createTempFile("unwritable-seek-cache", ".tmp")
        try {
            val cache = SpanCache(file)
            cache.open("yt:video:140").use { resource ->
                val chunk = ByteArray(1024 * 1024) { 1 }
                val firstOffset = 16L * chunk.size
                repeat(8) { resource.write(firstOffset + it * chunk.size, chunk) }
                resource.write(0, byteArrayOf(2, 3, 4))
                assertContentEquals(byteArrayOf(2, 3, 4), resource.read(0, 3))
                assertNull(resource.read(firstOffset, 1))
                assertTrue(cache.sizeBytes() <= 8L * chunk.size)
            }
        } finally {
            Files.delete(file)
        }
    }

    @Test
    fun unavailableDiskUsesBoundedMemory() {
        val file = Files.createTempFile("unwritable-cache", ".tmp")
        try {
            SpanCache(file).open("yt:video:140").use {
                it.write(10, byteArrayOf(1, 2, 3))
                assertContentEquals(byteArrayOf(1, 2, 3), it.read(10, 3))
            }
        } finally {
            Files.delete(file)
        }
    }
}
