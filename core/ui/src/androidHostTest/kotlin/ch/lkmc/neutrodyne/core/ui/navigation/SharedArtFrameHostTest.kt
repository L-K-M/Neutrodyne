// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import ch.lkmc.neutrodyne.core.designsystem.components.CoverArt
import ch.lkmc.neutrodyne.core.designsystem.components.CoverTier
import ch.lkmc.neutrodyne.core.designsystem.components.Covers
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.EpisodeKey
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.LocalNavTab
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import ch.lkmc.neutrodyne.core.testing.testLibraryTile
import ch.lkmc.neutrodyne.core.testing.testPodcastDetail
import ch.lkmc.neutrodyne.core.ui.CoverTile
import ch.lkmc.neutrodyne.core.ui.EpisodeRow
import ch.lkmc.neutrodyne.core.ui.EpisodeRowStyle
import ch.lkmc.neutrodyne.core.ui.PodcastHeader
import ch.lkmc.neutrodyne.core.ui.RowCaps
import ch.lkmc.neutrodyne.core.ui.rememberMonogram
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.map.Mapper
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.Options
import coil3.request.SuccessResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Real Android Coil file decoding and native Skia frames, not FakeImageLoader or key inspection.
 * THUMB is decoded first; a gate stops HERO before transport for longer than the entire shared
 * transition. Each captured frame must contain an intact art interior. Geometry must interpolate
 * too: a plain scene cover with an unrelated image cannot satisfy the intermediate-width check.
 * Hardware bitmap/readback and physical status-strip evidence still require a device.
 */
@OptIn(coil3.annotation.DelicateCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class SharedArtFrameHostTest : NdNavHostHarness() {
    private val ref = mutableStateOf(ArtworkRef("frame-art", null, 1))
    private lateinit var loader: ImageLoader
    private lateinit var source: File
    private val heroGate = CompletableDeferred<Unit>()
    private val heroStarted = AtomicInteger()
    private val heroFinished = AtomicInteger()
    private val heroSucceeded = AtomicInteger()
    private var failHero = false
    private var frameNumber = 0
    private var caseName = ""
    private var scopePresent = false
    private var prefetched = false

    @After
    fun closeLoader() {
        heroGate.complete(Unit)
        if (::loader.isInitialized) loader.shutdown()
        if (::source.isInitialized) source.delete()
        SingletonImageLoader.reset()
    }

    @Test
    fun offlinePinnedTileKeepsDecodedThumbUntilDelayedHeroThenPops() {
        prepare("tile-delayed")
        install()
        val before = artFrame()
        rule.runOnUiThread { navigation.push(PodcastKey(1)) }
        val widths = watchFrames(24)
        assertTrue(widths.any { it > before.width + 8 && it < 155 }, "no shared intermediate bounds: $widths")
        assertTrue(heroStarted.get() > 0, "HERO never started")
        assertEquals(0, heroFinished.get(), "HERO was not delayed")
        heroGate.complete(Unit)
        rule.waitUntil(5_000) { heroSucceeded.get() > 0 }
        watchFrames(20)
        assertTrue(source.isFile)
        rule.runOnUiThread { navigation.pop() }
        watchFrames(24)
        assertTrue(abs(artFrame().width - before.width) < 3)
    }

    @Test
    fun failedHeroKeepsSameVersionThumbAfterTransition() {
        prepare("hero-failure")
        failHero = true
        install()
        rule.runOnUiThread { navigation.push(PodcastKey(1)) }
        watchFrames(24)
        heroGate.complete(Unit)
        rule.waitUntil(5_000) { heroFinished.get() > 0 }
        // Error delivery/recomposition is deliberately included, not only the loading interval.
        watchFrames(24)
    }

    @Test
    fun newByteVersionUsesItsOwnDecodedThumbThroughHeroFailure() {
        prepare("version-change")
        // A second byte version is really decoded, never an alias to v1's memory entry.
        writePng(android.graphics.Color.CYAN)
        val next = ref.value.copy(version = 2)
        decodeThumb(next)
        failHero = true
        install()
        rule.runOnUiThread { ref.value = next }
        watchFrames(4)
        assertTrue(artFrame().cyan, "new version still showed the old magenta bytes")
        rule.runOnUiThread { navigation.push(PodcastKey(1)) }
        watchFrames(24)
        heroGate.complete(Unit)
        rule.waitUntil(5_000) { heroFinished.get() > 0 }
        watchFrames(24)
        assertTrue(artFrame().cyan)
    }

    @Test
    fun rowToEpisodeUsesTheSameEntryClockAndCancelsPredictiveBack() {
        prepare("row-cancel")
        install(row = true)
        rule.runOnUiThread { navigation.push(EpisodeKey(1)) }
        watchFrames(24)
        val original = artFrame()
        val input = DirectNavigationEventInput()
        rule.runOnUiThread { assertNotNull(dispatcher).addInput(input) }
        rule.runOnUiThread {
            input.backStarted(NavigationEvent(NavigationEvent.EDGE_LEFT, 0f, 16f, 400f))
            input.backProgressed(NavigationEvent(NavigationEvent.EDGE_LEFT, .45f, 300f, 400f))
        }
        watchFrames(8)
        rule.runOnUiThread { input.backCancelled() }
        watchFrames(24)
        assertEquals(EpisodeKey(1), navigation.stack(FeedsKey).last())
        assertTrue(abs(original.width - artFrame().width) < 3)
    }

    @Test
    fun coexistingPanesDoNotRegisterSharedArt() {
        prepare("two-pane")
        install(partitions = 2)
        assertTrue(!scopePresent)
        rule.runOnUiThread { navigation.push(PodcastKey(1)) }
        advanceFrames(24)
        assertTrue(!scopePresent)
    }

    @Test
    fun reducedMotionHasNoSharedScopeOrIntermediateGeometry() {
        prepare("reduced")
        install(reduced = true)
        assertTrue(!scopePresent)
        rule.runOnUiThread { navigation.push(PodcastKey(1)) }
        val widths = watchFrames(24)
        assertTrue(widths.none { it in 75..150 }, "shared art animated despite reduced motion: $widths")
    }

    @Test
    fun prefetchedPageCannotRegisterSharedArt() {
        prepare("prefetch")
        prefetched = true
        install()
        assertTrue(scopePresent, "fixture must have the host scope to test page exclusion")
    }

    private fun prepare(name: String) {
        caseName = name
        source = File(rule.activity.cacheDir, "w5-$name.png")
        writePng(android.graphics.Color.MAGENTA)
        loader =
            ImageLoader
                .Builder(rule.activity)
                .components {
                    add(
                        object : Mapper<ArtworkRef, String> {
                            override fun map(
                                data: ArtworkRef,
                                options: Options,
                            ): String = source.toURI().toString()
                        },
                    )
                    add(
                        Interceptor { chain ->
                            if (chain.request.memoryCacheKey?.endsWith(":h") == true) {
                                heroStarted.incrementAndGet()
                                heroGate.await()
                                val result =
                                    if (failHero) {
                                        ErrorResult(null, chain.request, IOException("deliberate HERO failure"))
                                    } else {
                                        chain.proceed()
                                    }
                                if (result is SuccessResult) heroSucceeded.incrementAndGet()
                                heroFinished.incrementAndGet()
                                result
                            } else {
                                chain.proceed()
                            }
                        },
                    )
                }.build()
        SingletonImageLoader.setUnsafe { loader }
        decodeThumb(ref.value)
    }

    private fun decodeThumb(art: ArtworkRef) {
        val result = runBlocking { loader.execute(Covers.request(rule.activity, art, CoverTier.THUMB, Density(1f))) }
        assertTrue(result is SuccessResult, "real PNG THUMB decode failed: $result")
        assertNotNull(loader.memoryCache?.get(assertNotNull(result.memoryCacheKey)))
    }

    private fun writePng(color: Int) {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun install(
        row: Boolean = false,
        partitions: Int = 1,
        reduced: Boolean = false,
    ) {
        val installers =
            setOf<EntryProviderInstaller>(
                {
                    entry<FeedsKey>(metadata = NdSceneMetadata.paneList()) {
                        Page {
                            if (row) {
                                EpisodeRow(
                                    testEpisodeRow(1, artwork = ref.value),
                                    null,
                                    EpisodeRowStyle.FEED,
                                    RowCaps.FULL,
                                    false,
                                    null,
                                    {},
                                )
                            } else {
                                CoverTile(
                                    testLibraryTile(1, artwork = ref.value),
                                    false,
                                    null,
                                    emptyList(),
                                    {},
                                    {},
                                    Modifier.size(64.dp),
                                )
                            }
                        }
                    }
                    entry<LibraryKey> { Page {} }
                    entry<UpNextKey> { Page {} }
                    entry<DownloadsKey> { Page {} }
                    entry<DiscoverKey> { Page {} }
                    entry<PodcastKey>(metadata = NdSceneMetadata.paneDetail()) {
                        Page {
                            PodcastHeader(testPodcastDetail(artwork = ref.value), 0L, emptyList(), {}, {})
                        }
                    }
                    entry<EpisodeKey>(metadata = NdSceneMetadata.paneExtra()) {
                        Page {
                            CoverArt(
                                ref.value,
                                rememberMonogram("Episode"),
                                Modifier.size(160.dp).sharedArt(SharedKeys.episodeArt(LocalNavTab.current, 1)),
                                tier = CoverTier.HERO,
                            )
                        }
                    }
                },
            )
        setFixedHost(
            installers,
            PaneScaffoldDirective.Default.copy(maxHorizontalPartitions = partitions),
            reducedMotion = reduced,
        )
        artFrame()
    }

    @Composable
    private fun Page(content: @Composable () -> Unit) {
        val shared = LocalSharedTransitionScope.current
        SideEffect { scopePresent = shared != null }
        CompositionLocalProvider(LocalSharedArtEnabled provides !prefetched) {
            if (prefetched) assertSame(Modifier, Modifier.sharedArt("prefetch-probe"))
            NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag("art-page"),
                ) { content() }
            }
        }
    }

    private fun watchFrames(count: Int): List<Int> =
        (0 until count).map {
            advanceFrames(1)
            artFrame().width
        }

    private data class ArtFrame(
        val width: Int,
        val cyan: Boolean,
    )

    private fun artFrame(): ArtFrame {
        val image = rule.onRoot().captureToImage()
        val out = File("build/reports/w5-frames/$caseName/${frameNumber++.toString().padStart(3, '0')}.png")
        checkNotNull(out.parentFile).mkdirs()
        out.outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        val pixels = image.toPixelMap()
        val points =
            buildList {
                for (y in 0 until pixels.height step 2) {
                    for (x in 0 until pixels.width step 2) {
                        if (pixels[x, y].isArt()) add(x to y)
                    }
                }
            }
        assertTrue(points.isNotEmpty(), "$caseName frame ${frameNumber - 1}: no decoded artwork; see $out")
        val left = points.minOf { it.first }
        val right = points.maxOf { it.first }
        val top = points.minOf { it.second }
        val bottom = points.maxOf { it.second }
        assertArtInterior(pixels, left, top, right, bottom, out)
        val center = pixels[(left + right) / 2, (top + bottom) / 2]
        return ArtFrame(right - left + 1, center.green > .8f && center.red < .2f)
    }

    private fun assertArtInterior(
        pixels: PixelMap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        out: File,
    ) {
        // Ignore round corners and edge antialiasing; every interior sample must remain art.
        val insetX = (right - left) / 4
        val insetY = (bottom - top) / 4
        for (y in top + insetY..bottom - insetY step 3) {
            for (x in left + insetX..right - insetX step 3) {
                assertTrue(pixels[x, y].isArt(), "placeholder/hole at $x,$y in $out")
            }
        }
    }

    private fun Color.isArt(): Boolean = blue > .8f && red + green in .8f..1.2f
}
