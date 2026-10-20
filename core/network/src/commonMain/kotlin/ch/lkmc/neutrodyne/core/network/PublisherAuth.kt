// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.network

import ch.lkmc.neutrodyne.core.common.PublisherAuthPolicy
import io.ktor.client.request.HttpRequestBuilder

/** Apply to a publisher-authenticated purpose client; anonymous requests never resolve credentials. */
fun HttpRequestBuilder.publisherAuth(policy: PublisherAuthPolicy) {
    headers[PublisherAuthPolicy.HEADER] = policy.name
}
