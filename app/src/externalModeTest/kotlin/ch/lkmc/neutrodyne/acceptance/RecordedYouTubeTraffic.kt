// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.acceptance

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Test APK/JVM only. Reconstructed HTML and synthetic Atom are replay, never live captures. */
internal class RecordedYouTubeTraffic(
    private val load: (String) -> String,
) : Interceptor {
    val requests = CopyOnWriteArrayList<Request>()

    @Volatile var feedsUnavailable = false
    private val unexpected = CopyOnWriteArrayList<String>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        requests += request
        val (type, body) =
            when {
                request.url.host == "www.youtube.com" && request.url.encodedPath.startsWith("/@") -> {
                    "text/html" to load("recorded/channel-head.html")
                }

                request.url.host == "www.youtube.com" && request.url.encodedPath == "/channel/$IMPORTED" -> {
                    "text/html" to
                        load("recorded/channel-head.html")
                            .replace(CHANNEL, IMPORTED)
                            .replace("MKBHD &amp; Studio", "Imported channel")
                }

                request.url.host == "www.youtube.com" && request.url.encodedPath.startsWith("/channel/") -> {
                    "text/html" to load("recorded/channel-head.html")
                }

                request.url.host == "www.youtube.com" && request.url.encodedPath == "/feeds/videos.xml" -> {
                    val imported =
                        request.url.query
                            .orEmpty()
                            .contains(IMPORTED.removePrefix("UC"))
                    val id = if (imported) IMPORTED else CHANNEL
                    "application/atom+xml" to
                        load("youtube/m8-atom.xml")
                            .replace("UCaaaaaaaaaaaaaaaaaaaaaA", id)
                            .replace("aaaaaaaaaaaaaaaaaaaaaA", id.removePrefix("UC"))
                            .replace("Channel &amp; Team", if (imported) "Imported channel" else "MKBHD &amp; Studio")
                }

                request.url.host == "fixtures.neutrodyne.invalid" -> {
                    "application/json" to
                        """{"localSubscriptions":[{"channelId":"$IMPORTED","name":"Imported channel"}],
                    "groups":[{"groupName":"Imported group","channels":["$IMPORTED"],"index":0}]}"""
                }

                request.url.host == "itunes.apple.com" && request.url.encodedPath == "/search" -> {
                    "application/json" to """{"resultCount":0,"results":[]}"""
                }

                request.url.host == "api.fyyd.de" && request.url.encodedPath == "/0.2/search/podcast" -> {
                    "application/json" to """{"status":200,"data":[]}"""
                }

                request.url.host in setOf("i.ytimg.com", "yt3.ggpht.com", "yt3.googleusercontent.com") -> {
                    "image/png" to ""
                }

                else -> {
                    val url = "${request.url.host}${request.url.encodedPath}"
                    unexpected += url
                    throw java.io.IOException("Unexpected application request: $url")
                }
            }
        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(
                if (type == "image/png" || (feedsUnavailable && type == "application/atom+xml")) 404 else 200,
            ).message("Replay")
            .body(body.toResponseBody(type.toMediaType()))
            .build()
    }

    fun assertPageAndFeedEvidence() {
        assertTrue(unexpected.isEmpty(), "Unplanned replay requests: $unexpected")
        requests.forEach { println("E8 application-client replay: ${it.url.host}${it.url.encodedPath}") }
        val page = requests.first { it.url.encodedPath.startsWith("/@") }
        assertEquals("SOCS=CAE=", page.header("Cookie"))
        assertTrue(page.header("User-Agent").orEmpty().startsWith("Neutrodyne/"))
        assertTrue(page.header("Accept-Language").orEmpty().isNotBlank())
        assertTrue(requests.any { it.url.encodedPath == "/feeds/videos.xml" })
        assertTrue(requests.none { it.url.host.endsWith("googlevideo.com") || it.url.host.contains("github") })
    }

    companion object {
        const val CHANNEL = "UCBJycsmduvYEL83R_U4JriQ"
        const val IMPORTED = "UCK8sQmJBp8GCxrOtXWBpyEA"
    }
}
