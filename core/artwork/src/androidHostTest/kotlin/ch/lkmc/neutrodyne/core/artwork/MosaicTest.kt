// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import android.content.pm.ProviderInfo
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import ch.lkmc.neutrodyne.core.common.art.Monogram
import ch.lkmc.neutrodyne.core.database.ArtworkEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupMemberEntity
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.GroupMosaicMember
import ch.lkmc.neutrodyne.core.model.MosaicSnapshot
import ch.lkmc.neutrodyne.core.model.art.ArtColors
import ch.lkmc.neutrodyne.core.model.art.MonogramMode
import ch.lkmc.neutrodyne.core.model.art.MonogramSpec
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import coil3.Image
import dev.zacsweers.metro.Provider
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ArtworkTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MosaicTest {
    private val f = ArtworkHostFixture(RuntimeEnvironment.getApplication())
    private val artwork get() = f.db.artworkDao()
    private val groups get() = f.db.groupDao()

    @Before fun open() = runBlocking { f.open() }

    @After fun close() = f.close()

    @Test fun zeroToFourMembersUseOrderedCellsFourPixelGapsAndJpeg() =
        runBlocking {
            val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
            val members =
                colors.mapIndexed { i, color ->
                    val key = "m-" + (i + 1).toString().repeat(40)
                    val id = f.podcast(key)
                    f.store.applyBatch(
                        listOf(
                            ArtworkProduction(
                                key,
                                "fixture",
                                EncodedArtwork(jpegBytes(800, 600, color).toByteString(), 800, 600, "jpg"),
                            ),
                        ),
                        0,
                    )
                    GroupMosaicMember(1, id, "Show $i", ArtworkRef(key, null, 1), null)
                }
            for (count in 0..4) {
                val input = snapshot().copy(members = members.take(count).toImmutableList())
                val encoded = renderer().render(input)
                assertEquals("jpg", encoded.extension)
                assertEquals(512, encoded.width)
                assertEquals(512, encoded.height)
                val bitmap = BitmapFactory.decodeByteArray(encoded.bytes.toByteArray(), 0, encoded.bytes.size)
                try {
                    for (i in 0..3) {
                        val expected = colors.getOrNull(i)?.takeIf { i < count } ?: tone
                        assertColor(expected, bitmap.getPixel((i % 2) * 258 + 100, (i / 2) * 258 + 100))
                    }
                    assertColor(tone, bitmap.getPixel(255, 100), tolerance = 35)
                    assertColor(tone, bitmap.getPixel(100, 255), tolerance = 35)
                } finally {
                    bitmap.recycle()
                }
            }
        }

    @Test fun corruptOrMissingFileUsesMonogramThenGroupTone() =
        runBlocking {
            val key = "m-" + "a".repeat(40)
            val id = f.podcast(key)
            f.store.applyBatch(
                listOf(
                    ArtworkProduction(
                        key,
                        "fixture",
                        EncodedArtwork("invalid".encodeToByteArray().toByteString(), 10, 10, "jpg"),
                    ),
                ),
                0,
            )
            val input =
                snapshot().copy(
                    members = persistentListOf(GroupMosaicMember(1, id, "Alpha", ArtworkRef(key, null, 1), null)),
                )

            fun pixel(rendered: EncodedArtwork): Int {
                val bitmap = BitmapFactory.decodeByteArray(rendered.bytes.toByteArray(), 0, rendered.bytes.size)
                return try {
                    bitmap.getPixel(20, 20)
                } finally {
                    bitmap.recycle()
                }
            }
            val expected = Monogram.spec("Alpha").colors(MonogramMode.RASTER).first
            assertColor(expected, pixel(renderer().render(input)))
            File(f.store.pinnedFile(key).toString()).delete()
            assertColor(expected, pixel(renderer().render(input)))
            val failing =
                object : MonogramRenderer {
                    override fun render(
                        spec: MonogramSpec,
                        size: Int,
                    ): Image = error("render unavailable")
                }
            assertColor(tone, pixel(MosaicRenderer(f.store, failing, f.codec).render(input)))
        }

    @Test fun snapshotsMatchInAppOrderIncludeEmptyGroupsAndObserveSourceChurn() =
        runBlocking {
            val id = groups.insert(groupEntity(orderKey = "a"))
            val source = MosaicSources(f.db)
            assertEquals(emptyList(), source.snapshots().single().members)
            val emissions = Channel<List<MosaicSnapshot>>(Channel.UNLIMITED)
            val observation = launch(Dispatchers.IO) { source.observe().collect { emissions.send(it) } }
            try {
                withTimeout(5_000) { emissions.receive() }
                for (i in 0..4) {
                    val podcast = f.podcast("m-" + (i + 1).toString().repeat(40))
                    groups.insertMember(PodcastGroupMemberEntity(id, podcast, i.toString(), 0))
                }
                val current = source.snapshots().single()
                assertEquals(
                    f.db
                        .groupDao()
                        .mosaicMembers()
                        .map { it.podcastId },
                    current.members.map { it.podcastId },
                )
                assertEquals(4, current.members.size)
                withTimeout(5_000) {
                    while (emissions
                            .receive()
                            .single()
                            .members.size != 4
                    ) {
                        Unit
                    }
                }
                val member = current.members.first()
                artwork.upsert(ArtworkEntity(member.artwork.key, version = 3))
                withTimeout(5_000) {
                    while (emissions
                            .receive()
                            .single()
                            .members
                            .first()
                            .artwork.version != 3
                    ) {
                        Unit
                    }
                }
                assertNotEquals(mosaicDescriptor(current), mosaicDescriptor(source.snapshots().single()))
                groups.setLook(id, Color.RED, "star", 1)
                assertEquals(Color.RED, source.snapshots().single().groupColorArgb)
                groups.deleteGroup(id)
                assertTrue(source.snapshots().isEmpty())
            } finally {
                observation.cancel()
            }
        }

    @Test fun guardedPublicationRejectsChangedOrDeletedSourcesAndGcRemovesOldUuid() =
        runBlocking {
            val id = groups.insert(groupEntity(orderKey = "a"))
            val sources = MosaicSources(f.db)
            val original = sources.snapshots().single()
            val encoded = renderer().render(original)
            val key = ArtworkKeys.mosaic(original.groupUuid)
            val result = ArtworkProduction(key, mosaicDescriptor(original), encoded)
            groups.rename(id, "Changed", "changed", 1)
            assertFalse(f.store.applyMosaic(original, result, 0))
            assertNull(artwork.byKey(result.key))
            val racing =
                object : ArtworkCodec {
                    override fun encode(
                        image: Image,
                        encoding: ArtworkEncoding,
                    ): EncodedArtwork {
                        runBlocking { groups.rename(id, "Raced", "raced", 2) }
                        return f.codec.encode(image, encoding)
                    }
                }
            sync(MosaicRenderer(f.store, f.monograms, racing)).runOnce(null)
            assertEquals(1, f.enqueues)
            assertNull(artwork.byKey(key))
            val changed = sources.snapshots().single()
            sync().runOnce(null)
            assertNotNull(f.store.pinnedFile(result.key))
            groups.deleteGroup(id)
            assertFalse(f.store.applyMosaic(changed, result.copy(descriptor = mosaicDescriptor(changed)), 2))
            groups.insert(groupEntity(name = "Restored", orderKey = "a"))
            assertTrue(f.store.collectGarbage() >= 1)
            assertNull(f.store.pinnedFile(result.key))
            assertFalse(File(f.paths.filesDir, "artwork/${result.key}.jpg").exists())
        }

    @Test fun syncPublishesOfflineBytesAndRetainsByteVersions() =
        runBlocking {
            groups.insert(groupEntity(orderKey = "a"))
            val sync =
                sync()
            sync.runOnce(null)
            val snapshot = MosaicSources(f.db).snapshots().single()
            val key = ArtworkKeys.mosaic(snapshot.groupUuid)
            val row = artwork.byKey(key)!!
            assertEquals(1, row.version)
            assertNull(row.seedArgb)
            assertTrue(f.store.contentUri(key, row.version).endsWith("/$key?v=1"))
            val bytes = File(f.store.pinnedFile(key).toString()).readBytes()
            val app = RuntimeEnvironment.getApplication() as ArtworkTestApplication
            app.graph =
                object : ArtworkProviderGraph {
                    override val artworkProviderAccess = Provider { f.access }
                }
            val provider =
                ArtworkProvider().apply {
                    attachInfo(
                        app,
                        ProviderInfo().apply {
                            authority = "${app.packageName}.artwork"
                            exported = true
                        },
                    )
                }
            val uri = Uri.parse(f.store.contentUri(key, row.version))
            val pfd = assertNotNull(provider.openFile(uri, "r"))
            assertEquals("image/jpeg", provider.getType(uri))
            assertTrue(ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }.contentEquals(bytes))
            assertEquals("image/jpeg", f.access.metadata(key)?.mime)
            sync.runOnce(null)
            assertEquals(row, artwork.byKey(key))
            // Name affects the descriptor but these empty-group bytes remain identical.
            groups.rename(snapshot.groupId, "Renamed", "renamed", 2)
            sync.runOnce(null)
            val refreshed = artwork.byKey(key)!!
            assertEquals(row.version, refreshed.version)
            assertNotEquals(row.url, refreshed.url)
            assertTrue(File(f.store.pinnedFile(key).toString()).readBytes().contentEquals(bytes))
        }

    @Test fun failedRefreshPreservesExistingFileColorsAndRetryMetadataThroughProvider() =
        runBlocking {
            groups.insert(groupEntity(orderKey = "a"))
            val source = MosaicSources(f.db).snapshots().single()
            val key = ArtworkKeys.mosaic(source.groupUuid)
            val image = renderer().render(source)
            val result = ArtworkProduction(key, mosaicDescriptor(source), image)
            f.store.applyMosaic(source, result, 1)
            val old = artwork.byKey(key)!!
            artwork.upsert(old.copy(avgArgb = Color.RED))
            f.store.applyMosaic(source, result.copy(image = null, error = "DECODE"), 2)
            val failed = artwork.byKey(key)!!
            assertEquals("DECODE:1", failed.lastError)
            assertEquals(old.localPath, failed.localPath)
            assertEquals(old.version, failed.version)
            assertEquals(Color.RED, failed.avgArgb)
            f.access.open(key)!!.close()
            assertEquals(failed, artwork.byKey(key))
            f.store.applyMosaic(source, result, 3)
            assertNull(
                artwork
                    .byKey(key)
                    ?.lastError,
            )
            assertEquals(
                old.version,
                artwork
                    .byKey(key)
                    ?.version,
            )
        }

    @Test fun deadlineContinuesAndRenderCancellationNeverPublishesFailure() =
        runBlocking {
            groups.insert(groupEntity(orderKey = "a"))
            val source = MosaicSources(f.db)
            val sync = sync()
            sync.runOnce(0.milliseconds)
            assertEquals(1, f.enqueues)
            val key = ArtworkKeys.mosaic(source.snapshots().single().groupUuid)
            assertNull(artwork.byKey(key))
            val cancelled =
                object : ArtworkCodec {
                    override fun encode(
                        image: Image,
                        encoding: ArtworkEncoding,
                    ): EncodedArtwork = throw CancellationException("stopped")
                }
            val cancelling = sync(MosaicRenderer(f.store, f.monograms, cancelled))
            assertFailsWith<CancellationException> { cancelling.runOnce(null) }
            assertNull(artwork.byKey(key))
        }

    private fun sync(raster: MosaicRenderer = renderer()) =
        MosaicSync(f.db, f.store, MosaicSources(f.db), raster, f.scheduler, f.clock, Dispatchers.Default)

    private fun renderer() = MosaicRenderer(f.store, f.monograms, f.codec)

    private fun snapshot() =
        MosaicSnapshot(1, "00000000-0000-0000-0000-000000000001", "Group", Color.RED, null, persistentListOf())

    private val tone get() = ArtColors.tone(Color.RED, 90.0)

    private fun assertColor(
        expected: Int,
        actual: Int,
        tolerance: Int = 12,
    ) {
        for (channel in listOf(16, 8, 0)) {
            assertTrue(
                kotlin.math.abs(((expected shr channel) and 255) - ((actual shr channel) and 255)) <= tolerance,
                "Expected ${Integer.toHexString(expected)}, got ${Integer.toHexString(actual)}",
            )
        }
    }
}
