// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.domain.StoredSecret

/** The `SecretStore` origin of a device token (10 Token storage). */
fun syncOrigin(host: String): String = "sync:$host"

/**
 * The device token in [SecretStore] under `sync:<host>` (10 Token storage). The token never
 * appears in logs, diagnostics or backups; `SecretStore` is the only writer.
 */
class SyncTokenStore(
    private val secrets: SecretStore,
) {
    suspend fun save(
        host: String,
        token: String,
        attributes: Map<String, String> = emptyMap(),
    ) {
        secrets.put(syncOrigin(host), USERNAME, token.toCharArray(), attributes)
    }

    suspend fun load(host: String): String? = secrets.get(syncOrigin(host))?.secret?.concatToString()

    suspend fun clear(host: String) = secrets.remove(syncOrigin(host))

    private companion object {
        const val USERNAME = "sync"
    }
}

/** The stored token's attributes (the desktop fingerprint rides here, 11 Secrets). */
fun StoredSecret.tokenAttributes(): Map<String, String> = attributes
