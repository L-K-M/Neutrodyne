// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.credentials

import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.domain.SecretStore
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider

/**
 * The `credential-load` start-up initializer (03 Basic auth and CredentialStore: order 120, after
 * the database at 100 and before refresh work): `awaitLoaded()` drives the platform store's first
 * `credential` emission through reconcile, so the decrypted map is published before any feed
 * request can need it. `FeedFetcher` also awaits the load before every request — this band only
 * brings the wait forward. When the database open failed, band 100's own retry wait keeps this
 * initializer queued until the store can resolve.
 */
@ContributesIntoSet(AppScope::class)
internal class CredentialLoadInitializer
    @Inject
    constructor(
        // Lazy (01 DI rule 7): the store reaches the database, which band 100 opens first.
        private val secrets: Provider<SecretStore>,
    ) : AppInitializer {
        override val order: Int = 120

        override suspend fun run() {
            secrets().awaitLoaded()
        }
    }
