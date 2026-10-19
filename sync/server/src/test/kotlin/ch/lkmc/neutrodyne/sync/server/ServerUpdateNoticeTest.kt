// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import kotlinx.coroutines.runBlocking
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerUpdateNoticeTest {
    private val repo = "https://github.com/L-K-M/Neutrodyne"
    private val manifest = """{"schema":1,"versionName":"1.2.3","releaseUrl":"$repo/releases/tag/v1.2.3","server":{"jar":{"file":"neutrodyne-server-1.2.3.jar","url":"$repo/releases/download/v1.2.3/neutrodyne-server-1.2.3.jar","size":123,"sha256":"${"a".repeat(
        64,
    )}"},"image":"ghcr.io/l-k-m/neutrodyne-server@sha256:${"b".repeat(64)}","minJava":21}}"""

    @Test fun `parser reads additive server info and rebuilds safe release links`() {
        val notice = assertNotNull(ServerUpdateNotice.parse(manifest.toByteArray(), repo))
        assertEquals("1.2.3", notice.version)
        assertEquals("$repo/releases/tag/v1.2.3", notice.releaseUrl)
        assertEquals(21, notice.minJava)
        assertTrue(notice.jar.endsWith("neutrodyne-server-1.2.3.jar"))
        assertNotNull(ServerUpdateNotice.parse(manifest.replace("/tag/v", "/tag/%76").toByteArray(), repo))
    }

    @Test fun `parser rejects hostile links schema images files and missing server fields`() {
        for (changed in listOf(
            manifest.replace("github.com", "github.com.evil.test"),
            manifest.replace("github.com/", "secret@github.com/"),
            manifest.replace("/tag/v1.2.3", "/tag/v1.2.3?secret"),
            manifest.replace("/tag/v1.2.3", "/tag/v1.2.3#fragment"),
            manifest.replace("/tag/v1.2.3", "/tag/%2e%2e/v1.2.3"),
            manifest.replace("/tag/v1.2.3", "/tag/%2fv1.2.3"),
            manifest.replace("/tag/v1.2.3", "/tag/%5cv1.2.3"),
            manifest.replace("\"schema\":1", "\"schema\":2"),
            manifest.replace("ghcr.io/l-k-m/", "ghcr.io/other/"),
            manifest.replace("@sha256:", ":latest@sha256:"),
            manifest.replace("\"minJava\":21", "\"minJava\":17"),
            manifest.replace("\"size\":123", "\"size\":0"),
            manifest.replace("\"server\"", "\"unknown\""),
            manifest.replace("\"versionName\":\"1.2.3\"", "\"versionName\":\"<script>\""),
        )) {
            assertNull(ServerUpdateNotice.parse(changed.toByteArray(), repo), changed)
        }
        assertFailsWith<IllegalArgumentException> { ServerUpdateNotice.parse(ByteArray(65_537), repo) }
    }

    @Test fun `redirect policy rejects every destination except HTTPS Github release hosts`() {
        for (url in listOf(
            "http://github.com/L-K-M/Neutrodyne/releases/latest/download/neutrodyne-update.json",
            "https://api" + ".github.com/repos/x",
            "https://github.com/other/Neutrodyne/releases/latest/download/neutrodyne-update.json",
            "https://release-assets.githubusercontent.com.evil.test/x",
            "https://secret@release-assets.githubusercontent.com/x",
            "https://release-assets.githubusercontent.com:8443/x",
            "https://127.0.0.1/x",
            "file:///tmp/x",
        )) {
            assertFalse(ServerUpdateNotice.allowed(URI(url), repo), url)
        }
        assertTrue(
            ServerUpdateNotice.allowed(
                URI("https://release-assets.githubusercontent.com/github-production-release-asset/x?signature=private"),
                repo,
            ),
        )
    }

    @Test fun `fetch follows allowed redirects only and enforces byte redirect caps and deadline`() {
        val inputs = ServerInputs({ 0 }, { ByteArray(it) })
        val visited = mutableListOf<URI>()
        val service =
            ServerUpdateNotice(UpdateCheckMode.ENABLED, inputs, "1.0.0", repo) { uri, deadline ->
                assertTrue(deadline > System.nanoTime())
                visited += uri
                when (visited.size) {
                    1 -> ManifestResponse(302, "$repo/releases/download/v1.2.3/neutrodyne-update.json")
                    2 -> ManifestResponse(302, "https://release-assets.githubusercontent.com/x?private")
                    else -> ManifestResponse(200, body = manifest.toByteArray())
                }
            }
        assertEquals(manifest, service.download().toString(Charsets.UTF_8))
        assertEquals(3, visited.size)
        var count = 0
        val blocked =
            ServerUpdateNotice(UpdateCheckMode.ENABLED, inputs, "1.0.0", repo) { _, _ ->
                count++
                ManifestResponse(302, "http://127.0.0.1/private")
            }
        assertFailsWith<IllegalArgumentException> { blocked.download() }
        assertEquals(1, count)
        count = 0
        val loop =
            ServerUpdateNotice(UpdateCheckMode.ENABLED, inputs, "1.0.0", repo) { _, _ ->
                count++
                ManifestResponse(302, "$repo/releases/latest/download/neutrodyne-update.json")
            }
        assertFailsWith<IllegalArgumentException> { loop.download() }
        assertEquals(4, count)
        val oversized =
            ServerUpdateNotice(
                UpdateCheckMode.ENABLED,
                inputs,
                "1.0.0",
                repo,
            ) { _, _ -> ManifestResponse(200, body = ByteArray(65_537)) }
        assertFailsWith<IllegalArgumentException> { oversized.download() }
    }

    @Test fun `off never requests enabled scheduler attempts at most daily and retains notice on failure`() =
        runBlocking {
            var now = 0L
            var count = 0
            var failed = false
            val inputs = ServerInputs({ now }, { ByteArray(it) })
            val fetch: (
                URI,
                Long,
            ) -> ManifestResponse = {
                _,
                _,
                ->
                count++
                if (failed) error("private signed URL") else ManifestResponse(200, body = manifest.toByteArray())
            }
            val off = ServerUpdateNotice(UpdateCheckMode.DISABLED, inputs, "1.0.0", repo, fetch)
            assertNull(off.start(this))
            assertFailsWith<IllegalStateException> { off.download() }
            repeat(3) {
                off.checkDue()
                now += DAY_MS
            }
            assertEquals(0, count)
            val enabled = ServerUpdateNotice(UpdateCheckMode.ENABLED, inputs, "1.0.0", repo, fetch)
            enabled.checkDue()
            enabled.checkDue()
            assertEquals(1, count)
            assertNotNull(enabled.notice)
            now += DAY_MS - 1
            enabled.checkDue()
            assertEquals(1, count)
            now++
            failed = true
            enabled.checkDue()
            assertEquals(2, count)
            assertNotNull(enabled.notice)
            enabled.checkDue()
            assertEquals(2, count)
        }

    @Test fun `same or older version produces no notice and scheduling has initial jitter`() =
        runBlocking {
            var now = 0L
            var requests = 0
            val inputs = ServerInputs({ now }, { ByteArray(it) { 1 } })
            val service =
                ServerUpdateNotice(UpdateCheckMode.ENABLED, inputs, "1.2.3", repo) { _, _ ->
                    requests++
                    ManifestResponse(200, body = manifest.toByteArray())
                }
            service.checkDue()
            service.checkDue()
            assertEquals(0, requests)
            now += 3_600_000
            service.checkDue()
            assertEquals(1, requests)
            assertNull(service.notice)
        }
}
