// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.AddPodcastResolver
import ch.lkmc.neutrodyne.core.domain.AddResolution
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.FeedPreview

/**
 * In-memory [AddPodcastResolver] (09 `:core:testing` inventory): [nextResolution]/[nextPreview]
 * are the canned answers, [inputs] records what the UI resolved.
 */
class FakeAddPodcastResolver : AddPodcastResolver {
    var nextResolution: AddResolution = AddResolution.Failure(AddPodcastError.NotAFeed)
    var nextPreview: Outcome<FeedPreview, AddPodcastError> =
        Outcome.Failure(AddPodcastError.NotAFeed)

    val inputs = mutableListOf<String>()
    val credentialInputs = mutableListOf<Pair<String, BasicCredentials>>()

    override suspend fun resolve(input: String): AddResolution {
        inputs += input
        return nextResolution
    }

    override suspend fun resolve(
        input: String,
        credentials: BasicCredentials,
    ): AddResolution {
        credentialInputs += input to credentials
        return nextResolution
    }

    override suspend fun preview(feedUrl: String): Outcome<FeedPreview, AddPodcastError> {
        inputs += "preview:$feedUrl"
        return nextPreview
    }
}
