// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.AddPodcastResolver
import ch.lkmc.neutrodyne.core.domain.AddResolution
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.feeds.discovery.AddInputNormalizer
import ch.lkmc.neutrodyne.feeds.discovery.NormalizedInput
import ch.lkmc.neutrodyne.feeds.identity.LocalDestination
import kotlinx.coroutines.CompletableDeferred

/**
 * In-memory [AddPodcastResolver] (09 `:core:testing` inventory): [nextResolution]/[nextPreview]
 * are the canned answers, [inputs] records what the UI resolved. [gate] parks a `resolve` after
 * recording it, for tests that need the lookup in flight (FakeSubscribeUseCase's pattern).
 */
class FakeAddPodcastResolver : AddPodcastResolver {
    var nextResolution: AddResolution = AddResolution.Failure(AddPodcastError.NotAFeed)
    var nextPreview: AddResolution = AddResolution.Failure(AddPodcastError.NotAFeed)

    /** Set to suspend resolves until completed; `null` passes through. */
    var gate: CompletableDeferred<Unit>? = null

    val inputs = mutableListOf<String>()
    val credentialInputs = mutableListOf<Pair<String, BasicCredentials>>()

    override suspend fun resolve(input: String): AddResolution {
        inputs += input
        gate?.await()
        return nextResolution
    }

    override suspend fun resolve(
        input: String,
        credentials: BasicCredentials,
    ): AddResolution {
        credentialInputs += input to credentials
        gate?.await()
        return nextResolution
    }

    val oneTimeInputs = mutableListOf<String>()

    override suspend fun lookupOnce(input: String): AddResolution {
        oneTimeInputs += input
        gate?.await()
        return nextResolution
    }

    override suspend fun preview(feedUrl: String): AddResolution {
        inputs += "preview:$feedUrl"
        return nextPreview
    }

    /** The real normalisation + predicate: the UI tests exercise the same classification. */
    override fun isLocalDestination(input: String): Boolean =
        (AddInputNormalizer.normalize(input) as? NormalizedInput.Url)
            ?.let { LocalDestination.isLocal(it.url) } == true
}
