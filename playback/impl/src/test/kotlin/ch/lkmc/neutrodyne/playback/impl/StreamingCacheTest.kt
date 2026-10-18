// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.SimpleCache
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamingCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun clearRetainsActivePinAndDownloadsButDropsOtherStreamingBytes() =
        runBlocking {
            val directory = temporary.newFolder()
            val download = File(temporary.root, "download.mp3").apply { writeText("download") }
            SourceHarness(directory).use { h ->
                h.index.putAll(listOf(media()))
                val active = checkNotNull(h.resolve().key)
                h.owner.cache().write(active, "current")
                h.owner.cache().write("ep:2:old", "unused")
                h.owner.cache().write("yt:old:140", "youtube")
                assertEquals(20L, h.owner.streamingCacheBytes())
                h.owner.clearStreamingCache()
                assertEquals(setOf(active), h.owner.cache().keys)
                assertEquals(7L, h.owner.streamingCacheBytes())
                assertEquals("download", download.readText())
                h.resolver.clearPins()
                h.owner.clearStreamingCache()
                assertEquals(0L, h.owner.streamingCacheBytes())
            }
        }

    @Test fun oneLazyCacheOwnerAndValidatedResizeChoices() =
        runBlocking {
            val directory = File(temporary.root, "not-created")
            SourceHarness(directory).use { h ->
                assertFalse(directory.exists())
                h.owner.resize(100)
                assertFalse(directory.exists())
                val first = h.owner.cache()
                assertSame(first, h.owner.cache())
                assertThrows(IllegalStateException::class.java) {
                    SimpleCache(
                        directory,
                        AdjustableLruCacheEvictor(8) {
                            false
                        },
                        StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()),
                    )
                }
                for (mb in PlaybackSettingKeys.STREAM_CACHE_MB_VALUES) h.owner.resize(mb)
                assertThrows(IllegalArgumentException::class.java) { runBlocking { h.owner.resize(0) } }
                assertEquals(500, PlaybackSettingKeys.STREAM_CACHE_MB.default)
            }
        }

    @Test fun adjustableLruHonorsTouchesAndDefersPinnedEvictionUntilRelease() {
        val pinned = mutableSetOf<String>()
        val evictor = AdjustableLruCacheEvictor(12, pinned::contains)
        val cache =
            SimpleCache(temporary.newFolder(), evictor, StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()))
        try {
            cache.write("old", "aaaa")
            cache.write("recent", "bbbb")
            // startReadWrite touches the span and returns it without locking a cached region.
            cache.startReadWrite("old", 0, 4)
            pinned.add("old")
            evictor.maxBytes = 4
            evictor.trim(cache)
            assertEquals(setOf("old"), cache.keys)
            cache.write("discard", "cccc")
            assertEquals(setOf("old"), cache.keys)
            evictor.maxBytes = 0
            evictor.trim(cache)
            assertEquals(4, cache.cacheSpace)
            pinned.clear()
            evictor.trim(cache)
            assertEquals(0, cache.cacheSpace)
        } finally {
            cache.release()
        }
    }

    @Test fun liveLimitChangesEvictOnIoButRetainActiveResourcesUntilUnpin() =
        runBlocking {
            val prefs = kotlinx.coroutines.flow.MutableStateFlow(PlaybackPrefsSnapshot.DEFAULT)
            SourceHarness(temporary.newFolder(), prefs).use { h ->
                h.index.putAll(listOf(media()))
                val active = checkNotNull(h.resolve().key)
                val cache = h.owner.cache()

                fun sparse(key: String) {
                    val length = 101_000_000L
                    val hole = checkNotNull(cache.startReadWrite(key, 0, length))
                    try {
                        val file = cache.startFile(key, 0, length)
                        java.io.RandomAccessFile(file, "rw").use { it.setLength(length) }
                        cache.commitFile(file, length)
                    } finally {
                        cache.releaseHoleSpan(hole)
                    }
                }
                sparse(active)
                sparse("unused")
                assertEquals(202_000_000L, cache.cacheSpace)
                prefs.value = prefs.value.copy(streamCacheMb = 100)
                assertEquals(101_000_000L, cache.cacheSpace)
                assertTrue(cache.getCachedSpans("unused").isEmpty())
                h.resolver.unpin(1)
                assertEquals(0L, cache.cacheSpace)
            }
        }

    @Test fun backupRulesIncludeOnlySnapshotsAndSettingsAcrossApiLevels() {
        val root = File(System.getProperty("neutrodyne.rootDir"))
        for (path in listOf("xml/backup_rules.xml", "xml-v28/backup_rules.xml", "xml/data_extraction_rules.xml")) {
            val factory =
                javax.xml.parsers.DocumentBuilderFactory
                    .newInstance()
            val document = factory.newDocumentBuilder().parse(File(root, "app/src/main/res/$path"))
            val includes = document.getElementsByTagName("include")
            assertTrue(includes.length > 0)
            for (i in 0 until includes.length) {
                val element = includes.item(i) as org.w3c.dom.Element
                assertEquals("file", element.getAttribute("domain"))
                assertTrue(
                    element.getAttribute("path") in
                        setOf("backup/.none", "backup/auto-snapshot.zip", "datastore/settings.preferences_pb"),
                )
            }
        }
    }
}
