// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.YouTubeVariantBits
import ch.lkmc.neutrodyne.youtube.api.EnrichResult
import ch.lkmc.neutrodyne.youtube.api.ExtractionAdmission
import ch.lkmc.neutrodyne.youtube.api.ExtractionOutcome
import ch.lkmc.neutrodyne.youtube.api.ResolveResult
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import ch.lkmc.neutrodyne.youtube.api.UploadsCursor
import ch.lkmc.neutrodyne.youtube.api.UploadsPageResult
import ch.lkmc.neutrodyne.youtube.api.VideoFacts
import ch.lkmc.neutrodyne.youtube.api.VideoFactsResult
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeEnricher
import ch.lkmc.neutrodyne.youtube.api.YouTubeExtractionHealth
import ch.lkmc.neutrodyne.youtube.api.YouTubeIds
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/** Portable facts/tab adapter. Refresh pacing and candidate selection belong to the data owner. */
class YtDlpEnricher(
    private val client: YtDlpClient,
    private val capabilities: YouTubeCapabilitiesSource,
    private val health: YouTubeExtractionHealth,
    private val errors: YtDlpErrorMapper,
    private val context: () -> YtxContext,
    private val clock: Clock,
) : YouTubeEnricher {
    override suspend fun facts(videoId: String): VideoFactsResult {
        if (!YouTubeIds.VIDEO.matches(videoId)) return VideoFactsResult.Failed(TransientKind.EXTRACTION, null)
        if (!capabilities.capabilities.value.enrichment) return VideoFactsResult.Unsupported
        return try {
            operation(45_000, videoId) {
                val raw =
                    call(
                        YtxMethod.FACTS,
                        Json.encodeToString(YtxVideoRequest.serializer(), YtxVideoRequest(context(), videoId)),
                    )
                val wire = YtDlpClient.decode(raw, YtxFacts.serializer())
                if (wire.description != null) {
                    // The frozen DTO default is not producer evidence. The real shim emits this
                    // boolean explicitly; missing or string-valued provenance is a malformed reply.
                    val flag = Json.parseToJsonElement(raw).jsonObject["descriptionTruncated"] as? JsonPrimitive
                    if (flag == null || flag.isString ||
                        flag.booleanOrNull == null
                    ) {
                        throw YtxCallException("EXTRACTION")
                    }
                }
                if (wire.videoId != videoId) throw YtxCallException("EXTRACTION")
                checkDeadline()
                VideoFactsResult.Ok(map(wire))
            }
        } catch (e: YtxCallException) {
            Availability.entries.firstOrNull { it.name == e.code }?.let {
                VideoFactsResult.Ok(VideoFacts(videoId, null, null, false, null, it, null, null))
            } ?: VideoFactsResult.Failed(transient(e), null)
        }
    }

    override suspend fun enrich(
        channelId: String,
        videoIds: Set<String>,
        variants: Int,
    ): EnrichResult {
        if (!capabilities.capabilities.value.enrichment) return EnrichResult.Unsupported
        if (!YouTubeIds.CHANNEL.matches(channelId) || !YouTubeVariantBits.isValid(variants) ||
            videoIds.size > 90 || videoIds.any { !YouTubeIds.VIDEO.matches(it) }
        ) {
            return EnrichResult.Failed(TransientKind.EXTRACTION, null)
        }
        if (videoIds.isEmpty()) return EnrichResult.Ok(emptyList())
        return try {
            operation(20_000, null) {
                val found = linkedMapOf<String, VideoFacts>()
                for (variant in listOf(1, 2, 4).filter { variants and it != 0 }) {
                    if (found.keys.containsAll(videoIds)) break
                    val page = page(channelId, variant, null)
                    checkDeadline()
                    page.items.filter { it.videoId in videoIds }.forEach { fact ->
                        val old = found[fact.videoId]
                        found[fact.videoId] =
                            fact.copy(
                                durationMs = fact.durationMs ?: old?.durationMs,
                                availability = fact.availability ?: old?.availability,
                                isShort = old?.isShort == true || fact.isShort == true,
                            )
                    }
                }
                EnrichResult.Ok(found.values.toList())
            }
        } catch (e: YtxCallException) {
            EnrichResult.Failed(transient(e), null)
        }
    }

    override suspend fun uploadsPage(
        channelId: String,
        variant: Int,
        cursor: UploadsCursor?,
    ): UploadsPageResult {
        if (!capabilities.capabilities.value.backCatalogue) return UploadsPageResult.Unsupported
        if (!YouTubeIds.CHANNEL.matches(channelId) || variant !in listOf(1, 2, 4)) {
            return UploadsPageResult.Failed(TransientKind.EXTRACTION)
        }
        return try {
            operation(45_000, null) {
                try {
                    page(channelId, variant, cursor)
                } catch (e: YtxCallException) {
                    if (e.code != "CURSOR_EXPIRED" || cursor == null) throw e
                    // A restart returns page one. The repository skips already known pages under
                    // its process cap; no cursor or URL becomes persistent state.
                    page(channelId, variant, null)
                }
            }
        } catch (e: YtxCallException) {
            UploadsPageResult.Failed(transient(e))
        }
    }

    private suspend fun YtDlpClient.Call.page(
        channelId: String,
        variant: Int,
        cursor: UploadsCursor?,
    ): UploadsPageResult.Ok {
        val text =
            if (cursor == null) {
                val tab =
                    when (variant) {
                        1 -> "videos"
                        2 -> "shorts"
                        else -> "streams"
                    }
                call(
                    YtxMethod.TAB_OPEN,
                    Json.encodeToString(YtxTabRequest.serializer(), YtxTabRequest(context(), channelId, tab)),
                )
            } else {
                call(
                    YtxMethod.TAB_NEXT,
                    Json.encodeToString(YtxCursorRequest.serializer(), YtxCursorRequest(context(), cursor.token)),
                )
            }
        val wire = YtDlpClient.decode(text, YtxTabPage.serializer())
        if (wire.items.size > YtxLimits.TAB_PAGE_SIZE || wire.items.any { !YouTubeIds.VIDEO.matches(it.id) } ||
            (wire.hasMore && wire.cursor.isNullOrBlank())
        ) {
            throw YtxCallException("EXTRACTION")
        }
        checkDeadline()
        val items =
            wire.items.map {
                VideoFacts(
                    it.id,
                    it.title,
                    seconds(it.timestamp)?.let { ts -> ts / DAY * DAY },
                    true,
                    duration(it.duration),
                    availability(it.liveStatus, it.availability),
                    variant == 2,
                    null,
                )
            }
        return UploadsPageResult.Ok(items, wire.cursor?.takeIf { wire.hasMore }?.let(::UploadsCursor))
    }

    private suspend fun <T : Any> operation(
        budget: Long,
        videoId: String?,
        block: suspend YtDlpClient.Call.() -> T,
    ): T =
        client.operation(YtxPriority.BACKGROUND, YtxDeadline(clock.elapsedRealtime() + budget)) {
            if (!capabilities.capabilities.value.enrichment) throw YtxCallException("ENGINE_UNAVAILABLE")
            health.awaitLoaded()
            checkDeadline()
            val admission = health.reserveExtraction(clock.now())
            if (admission is ExtractionAdmission.Denied) throw YtxCallException(admission.gate.kind.name)
            admission as ExtractionAdmission.Admitted
            var outcome: ExtractionOutcome = ExtractionOutcome.Inconclusive
            try {
                val result = block()
                checkDeadline()
                if (!capabilities.capabilities.value.enrichment) throw YtxCallException("ENGINE_UNAVAILABLE")
                outcome = ExtractionOutcome.Success
                result
            } catch (e: CancellationException) {
                throw e
            } catch (e: YtxCallException) {
                checkDeadline()
                val mapped = if (e.code == "BREAKER_OPEN") null else errors.map(e.code, videoId)
                checkDeadline()
                if (mapped is ResolveResult.Transient && mapped.kind == TransientKind.EXTRACTION) {
                    outcome = ExtractionOutcome.ParseFailure(videoId)
                }
                if (e.code == "RATE_LIMITED") health.reportRateLimited(clock.now())
                throw YtxCallException(
                    when (mapped) {
                        is ResolveResult.Unavailable -> mapped.reason.name
                        is ResolveResult.Transient -> mapped.kind.name
                        else -> e.code
                    },
                )
            } finally {
                health.completeExtraction(admission.reservation, outcome)
            }
        }

    private fun transient(e: YtxCallException): TransientKind =
        TransientKind.entries.firstOrNull { it.name == e.code } ?: TransientKind.EXTRACTION

    private fun map(wire: YtxFacts): VideoFacts {
        if (wire.description
                ?.toByteArray(Charsets.UTF_8)
                ?.size
                ?.let { it > YtxLimits.DESCRIPTION_UTF8_BYTES } ==
            true
        ) {
            throw YtxCallException("EXTRACTION")
        }
        return VideoFacts(
            wire.videoId,
            wire.title,
            seconds(wire.releaseTs),
            false,
            duration(wire.duration),
            availability(wire.liveStatus, wire.availability),
            null,
            wire.description,
            wire.descriptionTruncated,
        )
    }

    private fun availability(
        live: String?,
        access: String?,
    ): Availability? =
        when {
            access == "private" -> Availability.PRIVATE

            access == "subscriber_only" || access == "premium_only" -> Availability.MEMBERS_ONLY

            live == "is_upcoming" -> Availability.UPCOMING

            live == "is_live" || live == "post_live" -> Availability.LIVE

            access == "public" || access == "unlisted" ||
                ((live == "not_live" || live == "was_live") && access == null) -> Availability.AVAILABLE

            else -> null
        }

    private fun duration(seconds: Double?): Long? =
        seconds
            ?.takeIf {
                it.isFinite() && it > 0 &&
                    it < Long.MAX_VALUE / 1000.0
            }?.let { (it * 1000).toLong().takeIf { ms -> ms > 0 } }

    private fun seconds(value: Long?): Long? = value?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 }?.times(1000)

    private companion object {
        const val DAY = 86_400_000L
    }
}
