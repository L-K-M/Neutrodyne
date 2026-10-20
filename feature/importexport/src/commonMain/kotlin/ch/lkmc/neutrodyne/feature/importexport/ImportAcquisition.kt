// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.importexport

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.domain.ImportRepository
import ch.lkmc.neutrodyne.core.model.ImportSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** The picker belongs to the live route; copying/parsing an accepted file belongs to the app. */
internal suspend fun acquireBackup(
    imports: ImportRepository,
    appScope: CoroutineScope,
    pick: suspend () -> String?,
): Deferred<Outcome<Long?, ImportError>> {
    val uiContext = coroutineContext
    return appScope.async {
        suspendRunCatching {
            val uri = withContext(uiContext) { pick() } ?: return@async Outcome.Success(null)
            uiContext.ensureActive()
            imports.create(ImportSource(uri, displayName = null))
        }.getOrElse { Outcome.Failure(ImportError.Unreadable) }
    }
}
