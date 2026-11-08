// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data.refresh

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Internal run metadata survives a committed-event fallback without extending extraction time. */
internal class PostIngestContext(
    val deadline: Long,
    val origin: RefreshOrigin,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PostIngestContext>
}
