// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Origin
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.domain.StoredSecret
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64

/**
 * In-memory [SecretStore] for consumer tests (09 `:core:testing` inventory). Keeps 03's rules:
 * one entry per stored origin string, the published map read without blocking, `sync:` /
 * `podcastindex` origins never answering [basicAuthorization] (their keys are not HTTP
 * origins), and `put` returning a row ID — the row-backed (Android) behaviour; the desktop's
 * row-less `sync:` entries are not modelled.
 */
class FakeSecretStore(
    /**
     * The first-load barrier behind [awaitLoaded]: pre-completed, so tests see a loaded store.
     * A test proving a caller waits passes a fresh deferred.
     */
    private val loadBarrier: CompletableDeferred<Unit> = CompletableDeferred(Unit),
) : SecretStore {
    private class Row(
        val id: Long,
        val stored: StoredSecret,
    )

    private val mutex = Mutex()
    private var nextId = 1L

    /** Stored origin → row, published as one immutable map like the real stores' in-memory map. */
    private val rows = MutableStateFlow<Map<String, Row>>(emptyMap())

    /**
     * `podcast.credentialId` links the fake cannot read from a database: tests seed them here,
     * [forPodcast] resolves them.
     */
    val podcastCredentialIds = MutableStateFlow<Map<Long, Long>>(emptyMap())

    override suspend fun awaitLoaded() {
        loadBarrier.await()
    }

    override fun basicAuthorization(origin: Origin): String? {
        val stored = rows.value[origin.storedKey()]?.stored ?: return null
        return "Basic " +
            Base64.encode("${stored.username}:${stored.secret.concatToString()}".encodeToByteArray())
    }

    override suspend fun put(
        origin: String,
        username: String,
        secret: CharArray,
        attributes: Map<String, String>,
    ): Long? =
        mutex.withLock {
            val id = rows.value[origin]?.id ?: nextId++
            rows.value =
                rows.value + (origin to Row(id, StoredSecret(username, secret.copyOf(), attributes)))
            id
        }

    override suspend fun get(origin: String): StoredSecret? = rows.value[origin]?.stored

    override suspend fun remove(origin: String) {
        mutex.withLock {
            rows.value = rows.value - origin
        }
    }

    override suspend fun forPodcast(podcastId: Long): BasicCredentials? {
        val id = podcastCredentialIds.value[podcastId] ?: return null
        val stored =
            rows.value.values
                .firstOrNull { it.id == id }
                ?.stored ?: return null
        return BasicCredentials(stored.username, stored.secret.concatToString())
    }

    override suspend fun podcastIndexKey(): Pair<String, String>? =
        rows.value["podcastindex"]?.stored?.let { it.username to it.secret.concatToString() }
}

/** 03's stored form of an `Origin`: `scheme://host[:port]`, default port omitted (02 credential). */
private fun Origin.storedKey(): String {
    val defaultPort =
        (scheme == "http" && port == HTTP_DEFAULT_PORT) ||
            (scheme == "https" && port == HTTPS_DEFAULT_PORT)
    return if (defaultPort) "$scheme://$host" else "$scheme://$host:$port"
}

private const val HTTP_DEFAULT_PORT = 80
private const val HTTPS_DEFAULT_PORT = 443
