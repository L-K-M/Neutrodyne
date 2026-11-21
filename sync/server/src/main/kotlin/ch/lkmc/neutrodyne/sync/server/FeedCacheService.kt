// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits
import ch.lkmc.neutrodyne.sync.protocol.dto.CachedFeed
import ch.lkmc.neutrodyne.sync.protocol.dto.FeedCacheQuery
import ch.lkmc.neutrodyne.sync.protocol.dto.FeedCacheRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.FeedCacheResponse
import io.ktor.server.application.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

/** One subscribed feed the scheduler may fetch. [authorization] is a ready `Basic …` header value. */
internal data class FeedSubscription(
    val url: String,
    val sourceType: String,
    val authorization: String?,
    val key: RecordKey,
    val sequence: Long,
) {
    override fun toString(): String = "FeedSubscription(sourceType=$sourceType)"
}

internal data class FeedCacheConfig(
    val refreshIntervalMs: Long = DEFAULT_REFRESH_INTERVAL_MS,
    val ttlMs: Long = DEFAULT_TTL_MS,
    val maxQueries: Int = DEFAULT_MAX_QUERIES,
    val maxEntryBytes: Int = SqliteSyncStore.FEED_CACHE_MAX_ENTRY_BYTES.toInt(),
    val maxResponseBodyBytes: Long = DEFAULT_MAX_RESPONSE_BODY_BYTES,
) {
    init {
        require(refreshIntervalMs > 0)
        require(ttlMs > 0)
        require(maxQueries > 0)
        require(maxEntryBytes > 0)
        require(maxResponseBodyBytes > 0)
    }

    internal companion object {
        const val DEFAULT_REFRESH_INTERVAL_MS = 15 * 60_000L
        const val DEFAULT_TTL_MS = 30 * 60_000L
        const val DEFAULT_MAX_QUERIES = ProtocolLimits.MAX_FEED_BATCH
        const val DEFAULT_MAX_RESPONSE_BODY_BYTES = 8L shl 20
    }
}

/**
 * The server-side feed cache: subscription enumeration, the authenticated batch endpoint's read
 * path, the periodic refresh and invalidation. Every read and write is scoped to one account, so
 * one account's bodies are never visible to another.
 */
internal class FeedCacheService(
    private val store: SyncStore,
    private val auth: AuthStore,
    private val inputs: ServerInputs,
    private val fetcher: ServerFeedFetcher,
    private val config: FeedCacheConfig = FeedCacheConfig(),
) {
    private val log = LoggerFactory.getLogger("ch.lkmc.neutrodyne.sync.server.feed-cache")

    /**
     * Returns the account's fresh cached bodies for the requested URLs. A body is omitted only
     * when the caller already holds the same SHA-256; a miss (unknown URL or stale entry) is left
     * to the client's own fetch.
     */
    suspend fun batch(
        principal: AccountPrincipal,
        request: FeedCacheRequest,
    ): FeedCacheResponse {
        val queries = validate(request)
        val now = inputs.wallMs()
        val freshBefore = now - config.ttlMs
        return store.read(principal.accountId) {
            requirePrincipal(principal, now)
            var remainingBytes = config.maxResponseBodyBytes
            val feeds =
                queries.distinctBy { it.url }.mapNotNull { query ->
                    val head = feedCacheHead(query.url) ?: return@mapNotNull null
                    if (head.checkedAt !in freshBefore..now) return@mapNotNull null
                    val body =
                        if (query.sha256 == head.sha256 && head.permanentUrl == null) {
                            null
                        } else {
                            if (head.bodyBytes > remainingBytes) return@mapNotNull null
                            val entry = feedCache(listOf(query.url)).singleOrNull() ?: return@mapNotNull null
                            remainingBytes -= entry.body.size
                            Base64.getEncoder().encodeToString(entry.body)
                        }
                    CachedFeed(
                        query.url,
                        head.finalUrl,
                        head.sha256,
                        head.checkedAt,
                        body,
                        head.permanentUrl,
                        head.httpCharset,
                    )
                }
            FeedCacheResponse(feeds)
        }
    }

    /** Every subscribed feed of one account, as the scheduler sees it. */
    internal suspend fun enumerate(accountId: Long): List<FeedSubscription> =
        store.read(accountId) {
            livePodcasts()
                .flatMap { row ->
                    val url = row.text("feedUrl") ?: return@flatMap emptyList()
                    if (!validUrl(url)) return@flatMap emptyList()
                    val authorization = basicAuthorization(row, url)
                    // A feed that still needs credentials cannot be fetched without them; the
                    // client resolves the challenge and syncs the credential later.
                    val requiresAuth = row.text("credentialOrigin") != null || row.bool("needsCredentials") == true
                    if (authorization == null && requiresAuth) {
                        return@flatMap emptyList()
                    }
                    val source = row.text("sourceType") ?: SOURCE_RSS
                    val urls = if (source == "YOUTUBE_CHANNEL") channelFeedUrls(row) else listOf(url)
                    val header = authorization.takeIf { source == SOURCE_RSS }
                    urls.map { FeedSubscription(it, source, header, row.key, row.seq) }
                }.distinctBy { it.url }
                .toList()
        }

    /** One refresh pass over one account; a failed fetch leaves the previous entry untouched. */
    suspend fun refreshAccount(accountId: Long) {
        val slots = Semaphore(2)
        // Keep a slow publisher from delaying unrelated feeds. Serialize each host and bound
        // simultaneous decompressed bodies to fit the server's reference 96 MiB heap.
        coroutineScope {
            for (subscriptions in enumerate(accountId).groupBy { UrlNormalizer.origin(it.url) }.values) {
                launch {
                    for (subscription in subscriptions) slots.withPermit { refresh(accountId, subscription) }
                }
            }
        }
    }

    private suspend fun refresh(
        accountId: Long,
        subscription: FeedSubscription,
    ) {
        val prior = store.read(accountId) { feedCacheHead(subscription.url) }
        val result =
            try {
                fetcher.fetch(subscription.url, subscription.authorization, prior?.etag, prior?.lastModified)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                null
            }
        val checkedAtMs = inputs.wallMs()
        when (result) {
            null -> {
                Unit
            }

            ServerFeedFetcher.ServerFeedFetch.Unchanged -> {
                if (prior == null) return
                store.write(accountId) {
                    if (currentSubscription(subscription)) {
                        touchFeedCache(subscription.url, checkedAtMs)
                    }
                }
            }

            is ServerFeedFetcher.ServerFeedFetch.Changed -> {
                if (result.body.size > config.maxEntryBytes) return
                val finalUrl = result.finalUrl.takeIf(::validUrl) ?: return
                val digest = sha256Hex(result.body)
                val etag = boundedHeader(result.etag)
                val lastModified = boundedHeader(result.lastModified)
                store.write(accountId) {
                    if (!currentSubscription(subscription)) return@write
                    if (prior != null && prior.sha256 == digest && prior.etag == etag &&
                        prior.lastModified == lastModified && prior.finalUrl == finalUrl &&
                        prior.permanentUrl == result.permanentUrl && prior.httpCharset == result.httpCharset
                    ) {
                        touchFeedCache(subscription.url, checkedAtMs)
                        return@write
                    }
                    putFeedCache(
                        StoredFeedCache(
                            url = subscription.url,
                            finalUrl = finalUrl,
                            sha256 = digest,
                            etag = etag,
                            lastModified = lastModified,
                            body = result.body,
                            checkedAtMs = checkedAtMs,
                            podcastRid = subscription.key.id.rid,
                            permanentUrl = result.permanentUrl,
                            httpCharset = result.httpCharset,
                        ),
                    )
                }
            }
        }
    }

    /**
     * The cache write is a compare-and-swap on the record version observed while enumerating: a
     * late fetch may only touch the cache while its podcast is still live and unchanged. This
     * keeps a fetch that outlived an unsubscribe, a feed-URL change or a merge from restoring
     * withdrawn access.
     */
    private fun SyncWrite.currentSubscription(subscription: FeedSubscription): Boolean {
        val current = record(subscription.key) ?: return false
        return current.seq == subscription.sequence && current.live()
    }

    /** One refresh pass over every enabled account. */
    suspend fun refreshAll() {
        val accounts =
            auth.readAuth { accounts().filter { it.disabledAt == null }.map { it.id } }
        accounts.forEach { accountId ->
            try {
                refreshAccount(accountId)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                // One unhealthy account must not starve the accounts refreshed after it. Log the
                // failure class only: a message can carry feed URLs or credentials.
                log.error("Feed cache refresh failed for one account: ${failure::class.simpleName}")
            }
        }
    }

    /** Runs a pass immediately, then every [FeedCacheConfig.refreshIntervalMs]; stops with the application. */
    fun start(application: Application) {
        application.launch(Dispatchers.IO) {
            while (true) {
                try {
                    refreshAll()
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    application.environment.log.error(
                        "Scheduled feed cache refresh failed: ${failure::class.simpleName}",
                    )
                }
                delay(config.refreshIntervalMs)
            }
        }
    }

    private fun validate(request: FeedCacheRequest): List<FeedCacheQuery> {
        if (request.feeds.isEmpty() || request.feeds.size > config.maxQueries) invalidAuth()
        return request.feeds.map { query ->
            if (!validUrl(query.url)) invalidAuth()
            FeedCacheQuery(query.url, query.sha256?.let { normalizeSha256(it) ?: invalidAuth() })
        }
    }

    private fun validUrl(url: String): Boolean =
        url.length <= ProtocolLimits.MAX_URL && UrlNormalizer.forIdentity(url) != null

    private fun normalizeSha256(value: String): String? {
        if (value.length != SHA256_HEX_LENGTH) return null
        if (value.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
        return value.lowercase()
    }

    private fun basicAuthorization(
        row: StoredRecord,
        url: String,
    ): String? {
        if (!url.startsWith("https://", ignoreCase = true)) return null
        val field = row.raw.fields["auth"] ?: return null
        val auth = field.v as? JsonObject ?: return null
        if (field.c == null || field.c != row.raw.fields["credentialOrigin"]?.c) return null
        val username = (auth["u"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        val password = (auth["p"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        // Credentials are sent only to the origin they were entered for, mirroring the client.
        val origin = row.text("credentialOrigin")?.let { UrlNormalizer.origin(it) }
        val feedOrigin = UrlNormalizer.origin(url)
        if (origin == null || origin != feedOrigin) return null
        val token = Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
        return "Basic $token"
    }

    private fun channelFeedUrls(row: StoredRecord): List<String> {
        val id = row.text("youtubeChannelId")?.takeIf(CHANNEL_ID::matches) ?: return emptyList()
        val variants = row.long("youtubeVariants") ?: 1L
        val base = "https://www.youtube.com/feeds/videos.xml"
        return buildList {
            for ((bit, prefix) in listOf(1L to "UULF", 2L to "UUSH", 4L to "UULV")) {
                if ((variants and bit) != 0L) add("$base?playlist_id=$prefix${id.drop(2)}")
            }
            add("$base?channel_id=$id")
        }
    }

    private fun boundedHeader(value: String?): String? = value?.takeIf { it.length <= ProtocolLimits.MAX_STRING }

    private fun sha256Hex(body: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(body).joinToString("") {
            it.toUByte().toString(16).padStart(2, '0')
        }

    private companion object {
        val CHANNEL_ID = Regex("UC[0-9A-Za-z_-]{21}[AQgw]")
        const val SOURCE_RSS = "RSS"
        const val SHA256_HEX_LENGTH = 64
    }
}
