// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.youtube.api.ChannelHit
import ch.lkmc.neutrodyne.youtube.api.ChannelSearchResult
import ch.lkmc.neutrodyne.youtube.api.ExtractionAdmission
import ch.lkmc.neutrodyne.youtube.api.ExtractionOutcome
import ch.lkmc.neutrodyne.youtube.api.SearchCursor
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeChannelSearch
import ch.lkmc.neutrodyne.youtube.api.YouTubeExtractionHealth
import ch.lkmc.neutrodyne.youtube.api.YouTubeIds
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * Extractor channel search behind the
 * [channelSearch][ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilities.channelSearch] capability.
 * The first page is `SEARCH_OPEN` with a bounded nonblank query; further pages are `SEARCH_NEXT`
 * against the opaque host-owned cursor. An expired cursor ends paging with an empty terminal page —
 * the caller keeps already loaded hits — and malformed hits fail the page rather than corrupting
 * identity.
 */
class YtDlpChannelSearch(
    private val client: YtDlpClient,
    private val capabilities: YouTubeCapabilitiesSource,
    private val health: YouTubeExtractionHealth,
    private val context: () -> YtxContext,
    private val clock: Clock,
) : YouTubeChannelSearch {
    override suspend fun search(
        query: String,
        cursor: SearchCursor?,
    ): ChannelSearchResult {
        if (!capabilities.capabilities.value.channelSearch) return ChannelSearchResult.Unsupported
        if (cursor == null && (query.isBlank() || query.length > MAX_QUERY_CHARS)) {
            return ChannelSearchResult.Failed(TransientKind.EXTRACTION)
        }
        if (cursor != null && cursor.token.isBlank()) {
            return ChannelSearchResult.Failed(TransientKind.EXTRACTION)
        }
        return try {
            operation {
                val raw =
                    if (cursor == null) {
                        call(
                            YtxMethod.SEARCH_OPEN,
                            Json.encodeToString(
                                YtxSearchRequest.serializer(),
                                YtxSearchRequest(context(), query.trim()),
                            ),
                        )
                    } else {
                        call(
                            YtxMethod.SEARCH_NEXT,
                            Json.encodeToString(
                                YtxCursorRequest.serializer(),
                                YtxCursorRequest(context(), cursor.token),
                            ),
                        )
                    }
                val wire = YtDlpClient.decode(raw, YtxSearchPage.serializer())
                map(wire)
            }
        } catch (e: YtxCallException) {
            // A dead cursor is an ended result set, not a failure: keep paging state intact.
            if (e.code == "CURSOR_EXPIRED" && cursor != null) return ChannelSearchResult.Ok(emptyList(), null)
            ChannelSearchResult.Failed(transient(e))
        }
    }

    private fun map(wire: YtxSearchPage): ChannelSearchResult {
        if (wire.hits.size > YtxLimits.SEARCH_PAGE_SIZE ||
            wire.hits.any { !YouTubeIds.CHANNEL.matches(it.channelId) } ||
            (wire.hasMore && wire.cursor.isNullOrBlank())
        ) {
            throw YtxCallException("EXTRACTION")
        }
        val hits =
            wire.hits.map { hit ->
                if (hit.avatar != null && !validImageUrl(hit.avatar)) {
                    throw YtxCallException("EXTRACTION")
                }
                if (hit.description != null &&
                    hit.description.toByteArray(Charsets.UTF_8).size > YtxLimits.DESCRIPTION_UTF8_BYTES
                ) {
                    throw YtxCallException("EXTRACTION")
                }
                ChannelHit(
                    hit.channelId,
                    hit.title ?: throw YtxCallException("EXTRACTION"),
                    hit.avatar,
                    hit.followerCount?.takeIf { it >= 0 },
                    hit.description,
                    hit.verified,
                )
            }
        return ChannelSearchResult.Ok(hits, wire.cursor?.takeIf { wire.hasMore }?.let(::SearchCursor))
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

    private fun transient(e: YtxCallException): TransientKind =
        TransientKind.entries.firstOrNull { it.name == e.code } ?: TransientKind.EXTRACTION

    private companion object {
        const val BUDGET_MS = 20_000L
        const val MAX_QUERY_CHARS = 200
    }
}
