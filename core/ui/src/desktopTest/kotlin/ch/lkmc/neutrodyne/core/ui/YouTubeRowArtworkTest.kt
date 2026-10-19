// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.settings.YouTubeRowArt
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class YouTubeRowArtworkTest {
    @Test fun appearanceChangesRowSourceAndWidth() =
        runDesktopComposeUiTest(width = 600, height = 300) {
            val requests = CopyOnWriteArrayList<ImageRequest>()
            SingletonImageLoader.setUnsafe { context ->
                ImageLoader
                    .Builder(context)
                    .components {
                        add(
                            Interceptor { chain ->
                                requests += chain.request
                                SuccessResult(
                                    ColorImage(0xff123456.toInt(), 320, 180),
                                    chain.request,
                                    DataSource.NETWORK,
                                )
                            },
                        )
                    }.build()
            }
            val thumbnail = ArtworkRef("u-video", "https://i.ytimg.com/vi/abcdefghijk/mqdefault.jpg", 0)
            val avatar = ArtworkRef("u-avatar", "https://yt3.googleusercontent.com/avatar=s900", 3)
            val row =
                testEpisodeRow(1).copy(
                    sourceType = SourceType.YOUTUBE_CHANNEL,
                    artwork = thumbnail,
                    podcastArtwork = avatar,
                )
            var choice by mutableStateOf(YouTubeRowArt.VIDEO_THUMBNAIL)
            var style by mutableStateOf(EpisodeRowStyle.FEED)
            setContent {
                CompositionLocalProvider(LocalYouTubeRowArt provides choice) {
                    EpisodeRow(row, null, style, RowCaps.FULL, false, null, {})
                }
            }
            waitUntil { requests.any { it.data == thumbnail } }
            val requestedSize =
                runBlocking {
                    requests.first { it.data == thumbnail }.sizeResolver.size()
                }
            assertEquals(Size(320, 180), requestedSize)
            runOnIdle { choice = YouTubeRowArt.CHANNEL_AVATAR }
            waitUntil { requests.any { it.data == avatar } }
            assertTrue(requests.last().data == avatar)
            runOnIdle { style = EpisodeRowStyle.PODCAST }
            onNodeWithTag(DATE_BLOCK_TAG, useUnmergedTree = true).assertDoesNotExist()
        }
}
