// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.youtube.api.ChannelResolution
import ch.lkmc.neutrodyne.youtube.api.ExtractionAdmission
import ch.lkmc.neutrodyne.youtube.api.ExtractionOutcome
import ch.lkmc.neutrodyne.youtube.api.ExtractorChannelLookup
import ch.lkmc.neutrodyne.youtube.api.FailReason
import ch.lkmc.neutrodyne.youtube.api.MetadataDepth
import ch.lkmc.neutrodyne.youtube.api.ResolvedVia
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeExtractionHealth
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedUrls
import ch.lkmc.neutrodyne.youtube.api.YouTubeIds
import ch.lkmc.neutrodyne.youtube.api.YtRef
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * Extractor channel lookup behind the [channelSearch][ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilities.channelSearch]
 * capability. It returns only `Resolved`/`Failed`/`null`: wire `UNAVAILABLE` is ambiguous (removed
 * or terminated text, URL rejection — see the shim's errors.py) and never proves a missing channel,
 * so ambiguous failures map to `Failed(PAGE_UNREADABLE)` and let Layer A decide; `NotFound` stays
 * Layer A's definite HTTP 404/410 answer. `null` means the extractor cannot help — unsupported refs
 * and an unavailable engine — and keeps the HTML fallback.
 */
class YtDlpChannelLookup(
    private val client: YtDlpClient,
    private val capabilities: YouTubeCapabilitiesSource,
    private val health: YouTubeExtractionHealth,
    private val context: () -> YtxContext,
    private val clock: Clock,
) : ExtractorChannelLookup {
    override suspend fun lookup(
        ref: YtRef,
        depth: MetadataDepth,
    ): ChannelResolution? {
        val url = channelUrl(ref) ?: return null
        if (!capabilities.capabilities.value.channelSearch) return null
        return try {
            operation {
                val raw =
                    call(
                        YtxMethod.LOOKUP,
                        Json.encodeToString(YtxLookupRequest.serializer(), YtxLookupRequest(context(), url)),
                    )
                val wire = YtDlpClient.decode(raw, YtxLookup.serializer())
                if (!YouTubeIds.CHANNEL.matches(wire.channelId)) throw YtxCallException("EXTRACTION")
                checkDeadline()
                map(wire, depth)
            }
        } catch (e: YtxCallException) {
            when (e.code) {
                // The extractor is gone or its breaker is open; Layer A HTML remains possible.
                "ENGINE_UNAVAILABLE", "BREAKER_OPEN" -> null

                "NETWORK", "TIMEOUT" -> failed(FailReason.NETWORK, e)

                "RATE_LIMITED" -> failed(FailReason.RATE_LIMITED, e)

                "CONSENT_WALL" -> failed(FailReason.CONSENT_WALL, e)

                // UNAVAILABLE is ambiguous and EXTRACTION means an unreadable payload; neither is
                // an authoritative missing-channel answer, so Layer A gets the final word.
                else -> failed(FailReason.PAGE_UNREADABLE, e)
            }
        }
    }

    private suspend fun <T : Any> operation(block: suspend YtDlpClient.Call.() -> T): T =
        client.operation(YtxPriority.INTERACTIVE, YtxDeadline(clock.elapsedRealtime() + BUDGET_MS)) {
            if (!capabilities.capabilities.value.channelSearch) throw YtxCallException("ENGINE_UNAVAILABLE")
            health.awaitLoaded()
            checkDeadline()
            val admission = health.reserveExtraction(clock.now())
            if (admission is ExtractionAdmission.Denied) throw YtxCallException(admission.gate.kind.name)
            admission as ExtractionAdmission.Admitted
            var outcome: ExtractionOutcome = ExtractionOutcome.Inconclusive
            try {
                val result = block()
                checkDeadline()
                if (!capabilities.capabilities.value.channelSearch) throw YtxCallException("ENGINE_UNAVAILABLE")
                outcome = ExtractionOutcome.Success
                result
            } catch (e: CancellationException) {
                throw e
            } catch (e: YtxCallException) {
                checkDeadline()
                if (e.code == "EXTRACTION") outcome = ExtractionOutcome.ParseFailure(null)
                if (e.code == "RATE_LIMITED") health.reportRateLimited(clock.now())
                throw e
            } finally {
                health.completeExtraction(admission.reservation, outcome)
            }
        }

    private fun map(
        wire: YtxLookup,
        depth: MetadataDepth,
    ): ChannelResolution {
        if (depth == MetadataDepth.ID_ONLY) {
            return ChannelResolution.Resolved(wire.channelId, null, null, null, null, ResolvedVia.EXTRACTOR)
        }
        val avatar = largest(wire.avatars)
        if (depth == MetadataDepth.AVATAR) {
            return ChannelResolution.Resolved(
                wire.channelId,
                wire.title,
                avatar,
                null,
                null,
                ResolvedVia.EXTRACTOR,
            )
        }
        if (wire.description != null &&
            wire.description.toByteArray(Charsets.UTF_8).size > YtxLimits.DESCRIPTION_UTF8_BYTES
        ) {
            throw YtxCallException("EXTRACTION")
        }
        return ChannelResolution.Resolved(
            wire.channelId,
            wire.title,
            avatar,
            largest(wire.banners),
            wire.description,
            ResolvedVia.EXTRACTOR,
        )
    }

    private fun largest(images: List<YtxImage>): String? =
        images
            .filter { validImageUrl(it.url) }
            .maxByOrNull { it.w.toLong() * it.h }
            ?.url

    private fun channelUrl(ref: YtRef): String? =
        when (ref) {
            is YtRef.Channel -> {
                YouTubeFeedUrls.channelPage(ref.id)
            }

            is YtRef.Handle -> {
                "https://www.youtube.com/@${ref.handle.removePrefix("@").encodePathPart()}"
            }

            is YtRef.LegacyPath -> {
                "https://www.youtube.com/" +
                    ref.path.split('/').joinToString("/") { it.encodePathPart() }
            }

            // Videos, playlists and queries have no channel page the shim accepts; Layer A handles them.
            else -> {
                null
            }
        }

    private fun failed(
        reason: FailReason,
        cause: Throwable,
    ): ChannelResolution =
        ChannelResolution.Failed(reason == FailReason.NETWORK || reason == FailReason.RATE_LIMITED, reason, cause)

    private companion object {
        const val BUDGET_MS = 20_000L
    }
}

internal fun validImageUrl(url: String): Boolean {
    if (!url.startsWith("https://")) return false
    val host = url.substringAfter("https://").substringBefore('/')
    return host.isNotEmpty() && host.none { it.isWhitespace() || it == '@' }
}

private val PATH_PART_SAFE =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$&'()*+,;=:@".toSet()

/** Path-segment percent-encoding matching `encodeURLPathPart` so lookup URLs equal Layer A's. */
internal fun String.encodePathPart(): String {
    val out = StringBuilder()
    for (byte in encodeToByteArray()) {
        val value = byte.toInt() and 0xFF
        if (value < 128 && value.toChar() in PATH_PART_SAFE) {
            out.append(value.toChar())
        } else {
            out.append('%')
            out.append("0123456789ABCDEF"[value shr 4])
            out.append("0123456789ABCDEF"[value and 0xF])
        }
    }
    return out.toString()
}
