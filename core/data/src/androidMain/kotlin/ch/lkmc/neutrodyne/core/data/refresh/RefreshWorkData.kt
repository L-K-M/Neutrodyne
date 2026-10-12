// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.refresh

import androidx.work.Data
import ch.lkmc.neutrodyne.core.domain.RefreshScope

/**
 * The `refresh-*` work requests' input encoding (03 Work requests): scope is `"all"`, `"group:<id>"`,
 * or a `LongArray` of podcast ids — `Data` is capped at 10 KB, so `WorkManagerRefreshScheduler` never
 * serializes more than [MAX_SCOPE_IDS] ids.
 */
internal object RefreshWorkData {
    const val MAX_SCOPE_IDS = 500

    private const val KEY_SCOPE = "scope"
    private const val KEY_IDS = "ids"
    private const val KEY_FORCE = "force"
    private const val KEY_PAGES_ONLY = "pagesOnly"
    private const val KEY_ORIGIN = "origin"

    private const val SCOPE_ALL = "all"
    private const val SCOPE_GROUP = "group:"

    fun of(
        scope: RefreshScope,
        force: Boolean,
        pagesOnly: Boolean,
        origin: RefreshOrigin,
    ): Data =
        Data
            .Builder()
            .apply {
                when (scope) {
                    RefreshScope.All -> putString(KEY_SCOPE, SCOPE_ALL)
                    is RefreshScope.Group -> putString(KEY_SCOPE, "$SCOPE_GROUP${scope.groupId}")
                    is RefreshScope.Podcasts -> putLongArray(KEY_IDS, scope.ids.toLongArray())
                }
            }.putBoolean(KEY_FORCE, force)
            .putBoolean(KEY_PAGES_ONLY, pagesOnly)
            .putString(KEY_ORIGIN, origin.name)
            .build()

    /** Decodes the input data back into the engine's request (03 Worker). */
    fun request(
        data: Data,
        deadlineElapsedMs: Long,
        dueSlackMs: Long,
        pagingBudgetMs: Long,
    ): RefreshRequest {
        val scopeValue = data.getString(KEY_SCOPE)
        val ids = data.getLongArray(KEY_IDS)
        val scope =
            when {
                ids != null -> {
                    RefreshScope.Podcasts(ids.toList())
                }

                scopeValue?.startsWith(SCOPE_GROUP) == true -> {
                    RefreshScope.Group(scopeValue.removePrefix(SCOPE_GROUP).toLong())
                }

                else -> {
                    RefreshScope.All
                }
            }
        return RefreshRequest(
            scope = scope,
            force = data.getBoolean(KEY_FORCE, false),
            pagesOnly = data.getBoolean(KEY_PAGES_ONLY, false),
            origin =
                data.getString(KEY_ORIGIN)?.let { runCatching { RefreshOrigin.valueOf(it) }.getOrNull() }
                    ?: RefreshOrigin.PERIODIC,
            deadlineElapsedMs = deadlineElapsedMs,
            dueSlackMs = dueSlackMs,
            pagingBudgetMs = pagingBudgetMs,
        )
    }
}
