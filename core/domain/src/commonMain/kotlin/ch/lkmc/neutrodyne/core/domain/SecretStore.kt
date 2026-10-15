// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.Origin
import ch.lkmc.neutrodyne.core.model.BasicCredentials

/**
 * The common credential contract of 03 Basic auth and CredentialStore — Basic-auth feed
 * credentials, the user's Podcast Index key and 10's sync token. Users: 03, 05's opted-in
 * exports, 10's `SyncTokenStore` and opted-in `auth` field.
 *
 * Implements 01's [CredentialLookup], so `AuthInterceptor` reads it; replaces
 * `CredentialLookup.None` in M1b. Implemented by `KeystoreCredentialStore`
 * (`:core:data androidMain`) and `DesktopSecretStore` (`:core:data desktopMain`).
 */
interface SecretStore : CredentialLookup {
    /** Suspends until the first load is decrypted into memory. */
    override suspend fun awaitLoaded()

    /** `"Basic base64(user:pass)"` — a non-blocking read of the in-memory map. */
    override fun basicAuthorization(origin: Origin): String?

    /**
     * Replaces the entry for [origin]. Returns the `credential` row ID for feed and
     * `"podcastindex"` origins (`podcast.credentialId`); `null` for `"sync:<host>"` origins
     * on the desktop, which have no row (02 credential).
     */
    suspend fun put(
        origin: String,
        username: String,
        secret: CharArray,
        attributes: Map<String, String> = emptyMap(),
    ): Long?

    /** The stored entry for [origin] — 10: the sync token and its desktop fingerprint. */
    suspend fun get(origin: String): StoredSecret?

    suspend fun remove(origin: String)

    /** The feed credential of [podcastId] — 05's opted-in exports, 10's opted-in `auth`. */
    suspend fun forPodcast(podcastId: Long): BasicCredentials?

    /** The user's Podcast Index key pair (origin `"podcastindex"`), or `null`. */
    suspend fun podcastIndexKey(): Pair<String, String>?
}

/** A decrypted `SecretStore` entry (03). */
class StoredSecret(
    val username: String,
    val secret: CharArray,
    val attributes: Map<String, String>,
)
