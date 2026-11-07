// SPDX-License-Identifier: Unlicense
@file:Suppress("ktlint:standard:max-line-length")

package ch.lkmc.neutrodyne.youtube.impl

import ch.lkmc.neutrodyne.youtube.api.ChannelResolution
import ch.lkmc.neutrodyne.youtube.api.FailReason
import ch.lkmc.neutrodyne.youtube.api.MetadataDepth
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChannelPageParserTest {
    private val id = "UCBJycsmduvYEL83R_U4JriQ"
    private val other = "UCLA_DiR1FfKNvjuUpBHmylQ"
    private val base = "https://www.youtube.com/@example"

    @Test fun entitiesAndAttributes() {
        val page =
            parse(
                """
            <HEAD><META PROPERTY=OG:TITLE CONTENT='A &amp; B &lt;C&gt; &quot;D&quot; &#39;E&#39; &#x1F680; &#169; &unknown;'>
            <meta property='og:description' content="plain > text">
            <meta property=og:image content=//yt3.ggpht.com/avatar>
            <LINK TYPE=application/rss+xml REL='alternate something' HREF='/feeds/videos.xml?x=1&amp;channel_id=$id'>
            </HEAD><meta property=og:title content=Ignored>
        """,
            )
        assertEquals(id, page.channelId)
        assertEquals("A & B <C> \"D\" 'E' 🚀 © &unknown;", page.title)
        assertEquals("plain > text", page.description)
        assertEquals("https://yt3.ggpht.com/avatar", page.avatar)
    }

    @Test fun selectorPriorityAndInvalidIds() {
        val alternatives = """<link rel=canonical href=/channel/$other><meta itemprop=identifier content=$other>"externalId":"$other""""
        assertEquals(
            id,
            parse(
                "$alternatives<link rel=alternate type=application/rss+xml href='/feeds/videos.xml?channel_id=$id'>",
            ).channelId,
        )
        assertEquals(
            other,
            parse("<link rel=alternate type=application/rss+xml href='?channel_id=UCinvalid'>$alternatives").channelId,
        )
        assertEquals(
            other,
            parse("<link rel=canonical href=/channel/invalid><meta ITEMPROP=identifier CONTENT=$other>").channelId,
        )
        assertEquals(other, parse("<meta itemprop=identifier content=UCbad>\"externalId\": \"$other\"").channelId)
        assertNull(parse("\"externalId\":\"UCBJycsmduvYEL83R_U4JriZ\"").channelId)
    }

    @Test fun scannerIgnoresCommentsScriptsAndDuplicateAttributes() {
        val page =
            parse(
                """
            <!-- <meta itemprop=identifier content=$other> -->
            <script>var fake = '<meta itemprop=identifier content=$other>'; var end = '</head>';</script>
            <style>/* <meta itemprop=identifier content=$other> */</style>
            <meta itemprop=identifier content=$id content=$other>
            <title>A &amp; B - YouTube</title>
        """,
            )
        assertEquals(id, page.channelId)
        assertEquals("A & B", page.title)
    }

    @Test fun metadataDepthAndUnsafeArt() {
        val html = "<meta itemprop=identifier content=$id><meta property=og:title content=Name><meta property=og:image content=javascript:alert(1)>"
        assertNull(parse(html).avatar)
        val idOnly = ChannelPageParser.parseHead(html, base, MetadataDepth.ID_ONLY)
        assertEquals(id, idOnly.channelId)
        assertNull(idOnly.title)
        assertNull(idOnly.avatar)
    }

    @Test fun malformedAndUnknownEntitiesStayLiteral() {
        assertEquals(
            "&#0; &#xD800; &#1114112; &copy; &amp",
            HeadTagScanner.decode("&#0; &#xD800; &#1114112; &copy; &amp"),
        )
        assertNull(parse("<meta content='unfinished").channelId)
        assertNull(parse("<link rel=canonical href=https://evil.example/channel/$id>").channelId)
    }

    @Test fun bannerPicksLargestValidSourceAndDecodesJsonEscapes() {
        val sources = """[{"url":"https:\/\/yt3.ggpht.com/small","width":100,"height":20},{"url":"https://yt3.ggpht.com/large\u003dx","width":900,"height":200},{"url":"javascript:no","width":1000,"height":200}]"""
        assertEquals("https://yt3.ggpht.com/large=x", ChannelPageParser.banner(banner(sources)))
        assertNull(ChannelPageParser.banner(banner("[{\"width\":\"bad\",\"height\":4}]")))
        assertNull(ChannelPageParser.banner(banner("[incomplete")))
    }

    @Test fun headStopsBeforeBodyMetadataAndScriptCannotCloseIt() =
        runTest {
            val text = "<head><script>var x = '</head>';</script><meta itemprop=identifier content=$id></HEAD><meta property=og:title content=Body>"
            val result = scan(text, MetadataDepth.AVATAR)
            assertEquals(id, result.channelId)
            assertNull(result.title)
        }

    @Test fun noHeadCloseCannotScanPastHeadCapForIdentity() =
        runTest {
            val text = "<head>" + " ".repeat(HtmlAutodiscoveryChannelResolver.HEAD_CAP) + "\"externalId\":\"$id\""
            val result = rawScan(text, MetadataDepth.AVATAR)
            assertEquals(FailReason.PAGE_UNREADABLE, assertIs<ChannelResolution.Failed>(result).reason)
        }

    @Test fun capCountsUtf8BytesAndIncludesExactBoundary() =
        runTest {
            val tag = "<meta itemprop=identifier content=$id>"
            val padding = " ".repeat(HtmlAutodiscoveryChannelResolver.HEAD_CAP - tag.encodeToByteArray().size)
            assertEquals(id, scan(padding + tag, MetadataDepth.ID_ONLY).channelId)
            val beyond = "é".repeat(HtmlAutodiscoveryChannelResolver.HEAD_CAP / 2) + tag
            assertIs<ChannelResolution.Failed>(rawScan(beyond, MetadataDepth.ID_ONLY))
        }

    @Test fun externalIdCanBeInBodyUpToTotalCap() =
        runTest {
            val marker = "\"externalId\":\"$id\""
            val start = "<head><title>Head - YouTube</title></head>"
            val text =
                start + " ".repeat(HtmlAutodiscoveryChannelResolver.PAGE_CAP - start.length - marker.length) + marker
            val result = scan(text, MetadataDepth.AVATAR)
            assertEquals(id, result.channelId)
            assertEquals("Head", result.title)
            assertIs<ChannelResolution.Failed>(
                rawScan(start + " ".repeat(HtmlAutodiscoveryChannelResolver.PAGE_CAP) + marker, MetadataDepth.ID_ONLY),
            )
        }

    @Test fun fullAloneSearchesBannerAndStopsAtThreeMiB() =
        runTest {
            val head = "<head><meta itemprop=identifier content=$id></head>"
            val art = banner("""[{"url":"https://yt3.ggpht.com/banner","width":900,"height":200}]""")
            val text = head + " ".repeat(70000) + art
            assertNull(scan(text, MetadataDepth.AVATAR).bannerUrl)
            assertNull(scan(text, MetadataDepth.ID_ONLY).bannerUrl)
            assertEquals("https://yt3.ggpht.com/banner", scan(text, MetadataDepth.FULL).bannerUrl)
            assertNull(
                scan(head + " ".repeat(HtmlAutodiscoveryChannelResolver.PAGE_CAP) + art, MetadataDepth.FULL).bannerUrl,
            )
        }

    @Test fun windowBoundaryPreservesIdentityAndBannerTokens() =
        runTest {
            val head = "<head></head>"
            val marker = "\"externalId\":\"$id\""
            for (offset in listOf(8190, 65520, 65535, 129000)) {
                val text =
                    head + " ".repeat(offset - head.length) + marker +
                        banner("""[{"url":"https://yt3.ggpht.com/b","width":900,"height":200}]""")
                assertEquals("https://yt3.ggpht.com/b", scan(text, MetadataDepth.FULL).bannerUrl, "offset $offset")
            }
            val large =
                banner("[" + " ".repeat(12000) + """{"url":"https://yt3.ggpht.com/large","width":900,"height":200}]""")
            val unicode = "<head><meta itemprop=identifier content=$id></head>" + "é".repeat(29000)
            assertEquals("https://yt3.ggpht.com/large", scan(unicode + large, MetadataDepth.FULL).bannerUrl)
        }

    @Test fun watchUsesChannelMetadataAndNeverVideoTitleOrImage() =
        runTest {
            val html = "<head><meta itemprop=channelId content=$id><meta property=og:title content=Video><meta property=og:image content=https://i.ytimg.com/video></head>"
            val result =
                HtmlAutodiscoveryChannelResolver.scan(
                    ByteReadChannel(html.encodeToByteArray()),
                    base,
                    MetadataDepth.ID_ONLY,
                    HtmlAutodiscoveryChannelResolver.PageKind.WATCH,
                )
            assertEquals(id, assertIs<ChannelResolution.Resolved>(result).channelId)
            assertNull(result.title)
            assertNull(result.avatarUrl)
            assertEquals(id, ChannelPageParser.watchId("\"channelId\": \"$id\""))
        }

    @Test fun fullPublishesIdentityBeforeWaitingForOptionalBanner() =
        runTest {
            val channel = ByteChannel(autoFlush = true)
            val identified = CompletableDeferred<ChannelResolution.Resolved>()
            val scan =
                async {
                    HtmlAutodiscoveryChannelResolver.scan(
                        channel,
                        base,
                        MetadataDepth.FULL,
                        remember = { identified.complete(it) },
                    )
                }
            channel.writeStringUtf8("<head><meta itemprop=identifier content=$id></head>")
            assertEquals(id, identified.await().channelId)
            assertTrue(scan.isActive)
            scan.cancelAndJoin()
            channel.cancel(null)
        }

    private fun parse(text: String) = ChannelPageParser.parseHead(text, base, MetadataDepth.AVATAR)

    private fun banner(array: String) = "\"imageBannerViewModel\":{\"image\":{\"sources\":$array}}"

    private suspend fun rawScan(
        text: String,
        depth: MetadataDepth,
    ) = HtmlAutodiscoveryChannelResolver.scan(ByteReadChannel(text.encodeToByteArray()), base, depth)

    private suspend fun scan(
        text: String,
        depth: MetadataDepth,
    ): ChannelResolution.Resolved = assertIs(rawScan(text, depth))
}
