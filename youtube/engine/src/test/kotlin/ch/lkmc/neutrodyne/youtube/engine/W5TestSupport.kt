// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.model.IpFamily
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeHealth
import ch.lkmc.neutrodyne.core.testing.FakeYtxTransport
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.youtube.api.AudioPref
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilities
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json

internal const val VIDEO = "aaaaaaaaaaA"
internal const val OTHER_VIDEO = "bbbbbbbbbbB"
internal const val THIRD_VIDEO = "ccccccccccC"

internal class MemoryHints : IpFamilyHints {
    var family: IpFamily? = null
    val writes = mutableListOf<IpFamily?>()

    override fun current() = family

    override fun set(family: IpFamily?) {
        this.family = family
        writes += family
    }
}

internal class ResolverFixture(
    scope: CoroutineScope,
    val clock: Clock,
) {
    val transport = FakeYtxTransport()
    val health = FakeYouTubeHealth()
    val hints = MemoryHints()
    val capabilities = MutableStateFlow(YouTubeCapabilities(true, true, true, true, true, null))
    val caps =
        object : YouTubeCapabilitiesSource {
            override val capabilities = this@ResolverFixture.capabilities
        }
    val cache = ResolvedUrlCache(clock)
    val client = YtDlpClient(transport, clock, scope)
    val resolver =
        YtDlpStreamResolver(client, caps, health, cache, YtDlpErrorMapper(clock), hints, {
            YtxContext("en", "US", "test-agent")
        }, clock, scope)

    init {
        transport.answer = { recordedResolve() }
    }
}

internal fun recordedResolve(): String =
    checkNotNull(ResolverFixture::class.java.getResource("/recorded/resolve/result.json")).readText()

internal fun wire(
    format: YtxFormat = wireFormat(),
    video: String = VIDEO,
): YtxResolve = YtxResolve(video, duration = 120.25, formats = listOf(format))

internal fun wireFormat(
    itag: Int = 140,
    suffix: String = "",
    tags: String? = null,
    extra: String = "",
): YtxFormat =
    YtxFormat(
        "$itag$suffix",
        "https://media.example.invalid/audio?itag=$itag&expire=4102444800&clen=1000&lmt=123&ip=192.0.2.1&sig=private$extra",
        "m4a",
        "mp4a.40.2",
        abr = 128.0,
        xtags = tags,
        protocol = "https",
    )

internal fun YtxResolve.json(): String = Json.encodeToString(YtxResolve.serializer(), this)

internal fun resolved(
    clock: Clock = TestClock(),
    itag: Int = 140,
) = YtDlpAudioMapper.map(wire(wireFormat(itag)), VIDEO, AudioPref(), clock.now())
