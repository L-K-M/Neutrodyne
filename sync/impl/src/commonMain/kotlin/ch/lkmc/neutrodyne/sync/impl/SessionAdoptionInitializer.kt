// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider

/**
 * 10 Now playing and handoff: arms the adopter's collectors and the restart evaluation —
 * band 170, after the durable-effects drain at 160 and before the scheduling band's rounds
 * at 200/210. `start()` only launches coroutines; the stored-session read itself runs on the
 * application scope, so start-up is not blocked on Room.
 */
@ContributesIntoSet(AppScope::class)
class SessionAdoptionInitializer
    @Inject
    constructor(
        // Lazy (01 DI rule 7): the adopter reaches the database, which band 100 opens first.
        private val adopter: Provider<SessionAdopter>,
    ) : AppInitializer {
        override val order: Int = 170

        override suspend fun run() {
            adopter().start()
        }
    }
