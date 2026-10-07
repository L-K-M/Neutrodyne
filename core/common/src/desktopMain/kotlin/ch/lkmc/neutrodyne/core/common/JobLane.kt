// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Multibinds
import kotlin.time.Instant

/**
 * A desktop background lane (11 Runner contract): `DesktopJobRunner` (`:core:data` `desktopMain`)
 * ticks the `Set<JobLane>` multibinding every minute and each lane does what is due, then returns.
 * Lanes read their own persisted state; `now` is supplied so work is deterministic in tests.
 */
interface JobLane {
    /** Stable lane name — `refresh`, `downloads-manual`, … (the lane table of 11). */
    val name: String

    /** Does what is due at [now], then returns; cancellable. */
    suspend fun run(now: Instant)
}

/**
 * "Run soon" signal into `DesktopJobRunner` — coalesced; unknown lane names are a programming
 * error. The runner is bound as `JobLanePoker` so 07's download scheduler and 10's sync lane poke
 * it without depending on `:core:data`.
 */
fun interface JobLanePoker {
    fun poke(name: String)
}

/** Empty `Set<JobLane>` so `DesktopAppGraph` compiles before any lane lands (01 DI rule). */
@ContributesTo(AppScope::class)
@BindingContainer
interface JobLaneBindings {
    @Multibinds(allowEmpty = true)
    fun jobLanes(): Set<JobLane>
}
