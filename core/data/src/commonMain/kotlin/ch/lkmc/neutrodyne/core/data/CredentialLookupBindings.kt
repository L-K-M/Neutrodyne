// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.CredentialLookup
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * The M0a credential stub (01 Components and scopes): no feed credentials exist until M1b's
 * `KeystoreCredentialStore` (Android) and `DesktopSecretStore` (desktop) replace this binding —
 * lookups answer `null` and never block.
 */
@BindingContainer
@ContributesTo(AppScope::class)
object CredentialLookupBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun credentialLookup(): CredentialLookup = CredentialLookup.None
}
