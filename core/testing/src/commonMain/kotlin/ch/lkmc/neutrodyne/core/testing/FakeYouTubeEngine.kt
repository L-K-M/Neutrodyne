// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.DescriptionCompleteness
import ch.lkmc.neutrodyne.core.domain.YouTubeAvailabilityRecorder
import ch.lkmc.neutrodyne.core.domain.YouTubeFactsWriter
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.youtube.api.EngineAvailability
import ch.lkmc.neutrodyne.youtube.api.EngineStatus
import ch.lkmc.neutrodyne.youtube.api.PrewarmReason
import ch.lkmc.neutrodyne.youtube.api.VideoFacts
import ch.lkmc.neutrodyne.youtube.api.YouTubeEngine
import kotlinx.coroutines.flow.MutableStateFlow

class FakeYouTubeEngine : YouTubeEngine {
    override val status = MutableStateFlow(EngineStatus(EngineAvailability.NOT_IN_THIS_APK))
    val prewarms = mutableListOf<PrewarmReason>()
    var retries = 0

    override fun prewarm(reason: PrewarmReason) {
        prewarms += reason
    }

    override fun retryStart() {
        retries++
    }
}

class FakeYouTubeAvailabilityRecorder : YouTubeAvailabilityRecorder {
    val records = mutableListOf<Pair<Long, Availability>>()
    var record: suspend (Long, Availability) -> Unit = { _, _ -> }

    override suspend fun record(
        episodeId: Long,
        reason: Availability,
    ) {
        records += episodeId to reason
        record.invoke(episodeId, reason)
    }
}

class FakeYouTubeFactsWriter : YouTubeFactsWriter {
    val facts = mutableListOf<Pair<Long, List<VideoFacts>>>()
    val descriptions = mutableListOf<Triple<Long, String, DescriptionCompleteness>>()
    var apply: suspend () -> Unit = {}

    override suspend fun applyFacts(
        podcastId: Long,
        facts: List<VideoFacts>,
    ) {
        this.facts += podcastId to facts.toList()
        apply()
    }

    override suspend fun fillDescriptionIfAbsent(
        episodeId: Long,
        description: String,
        completeness: DescriptionCompleteness,
    ) {
        descriptions += Triple(episodeId, description, completeness)
        apply()
    }
}
