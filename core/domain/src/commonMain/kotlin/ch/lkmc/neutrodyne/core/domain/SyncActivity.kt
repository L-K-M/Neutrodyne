// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

/**
 * The truthful "a sync round or link merge is running" flag (02 step 7's desktop `VACUUM`
 * guard). `:sync:impl` marks every round — the periodic lane tick, an event-driven poke and
 * a manual "Sync now" — active for its duration and clears it on completion, failure or
 * cancellation, so [DbMaintenance.run]'s vacuum blocker never reports a stale round.
 * `:core:data` reads this port only; it never sees the sync engine.
 */
interface SyncActivity {
    /** True while a sync round or link merge holds the write path. */
    val active: Boolean
}
