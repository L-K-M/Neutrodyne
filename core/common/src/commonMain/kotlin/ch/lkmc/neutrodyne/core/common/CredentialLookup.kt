// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

/**
 * An origin with an explicit port (01 Networking baseline): "same origin" means lowercase
 * `scheme` + lowercase `host` + `port` (defaulted for http/https) all equal.
 * Constructed by `Origin.of(url)` inside `:core:network:okhttp` — keep construction there so the
 * lowercase/port-default rules live next to the client islands.
 */
data class Origin(
    val scheme: String,
    val host: String,
    val port: Int,
) {
    companion object
}

/**
 * Feed-credential source injected into `:core:network:okhttp`'s `AuthInterceptor` (01 Networking
 * baseline). Implemented by 03's `SecretStore` implementations (`:core:data`:
 * `KeystoreCredentialStore` on Android, `DesktopSecretStore` on the desktop, M1b), which keep the
 * decrypted lookup map in memory; [awaitLoaded] lets callers wait for that load before a request.
 * [None] is the M0a binding until then. The sync token's `sync:<host>` origin never matches an
 * HTTP origin, so a sync credential is never sent by the interceptor (10).
 */
fun interface CredentialLookup {
    /** `Authorization: Basic …` header value for [origin], or `null` when none is stored. */
    fun basicAuthorization(origin: Origin): String?

    /** Waits for credential stores to finish loading; a no-op for [None]. */
    suspend fun awaitLoaded() {}

    companion object {
        /** No credentials — bound for `:sync:api`. */
        val None = CredentialLookup { null }
    }
}
