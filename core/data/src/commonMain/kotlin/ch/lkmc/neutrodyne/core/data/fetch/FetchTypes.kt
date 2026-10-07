// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.fetch

import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.NetError
import okio.Path

/**
 * The 03 fetch-pipeline request/response types (`:core:data` commonMain, internal). `FeedFetcher`
 * runs the manual redirect chain, conditional GETs, temp-file storage, SHA-256, the 32 MB body cap
 * and the byte-level sniff; adapters turn outcomes into refresh policy.
 */

/** One feed fetch: [url] plus the stored validators ([conditional] gates them). */
internal data class FeedRequest(
    val url: String,
    val etag: String?,
    val lastModified: String?,
    val conditional: Boolean,
    val maxBytes: Long = MAX_FEED_BYTES,
    /** Probes (autodiscovery) stop the body read after this many bytes. */
    val sniffOnlyBytes: Int? = null,
    /** Not-yet-stored credentials (add flow, `setCredentials` probe). */
    val credentials: BasicCredentials? = null,
)

internal sealed interface FetchOutcome {
    /** 304 to a conditional request. */
    data class NotModified(
        val maxAgeSec: Long?,
        val serverDateMs: Long?,
    ) : FetchOutcome

    /** A 200 body in a temp file; the caller owns and deletes [file]. */
    data class Body(
        val file: Path,
        val sha256Hex: String,
        val requestedUrl: String,
        val finalUrl: String,
        /** URL reached by the leading run of 301/308 hops, else null (03 Request rules). */
        val permanentUrl: String?,
        /** Every followed redirect, in order (aliases at subscribe). */
        val hops: List<RedirectHop>,
        val etag: String?,
        val lastModified: String?,
        val charset: String?,
        val maxAgeSec: Long?,
        val serverDateMs: Long?,
        val sniff: Sniff,
    ) : FetchOutcome

    /** A response the fetcher does not stream: errors and unusable 3xx. */
    data class Http(
        val code: Int,
        val retryAfterMs: Long?,
        val basicChallenge: Boolean,
        val realm: String?,
    ) : FetchOutcome

    /** A transport failure classified by `NetErrorClassifier`. */
    data class Network(
        val error: NetError,
    ) : FetchOutcome

    /** The body exceeded `maxBytes`; the temp file was deleted. */
    data object TooLarge : FetchOutcome

    /** More than [MAX_REDIRECT_FOLLOWUPS] follow-ups, or a URL repeated in the chain. */
    data object RedirectLoop : FetchOutcome
}

/** The request URL of one 3xx hop and its status. */
internal data class RedirectHop(
    val url: String,
    val status: Int,
)

/** `FeedSniffer`'s first-element classification (03 Body, hashing and sniffing). */
internal enum class Sniff { RSS, ATOM, RDF, OPML, HTML, JSON, OTHER }

/** 32 MiB: the fetch-side body cap (03 Body, hashing and sniffing). */
internal const val MAX_FEED_BYTES: Long = 32L * 1024 * 1024

/** OkHttp's former follow-up limit, kept by the manual chain (03 Request rules). */
internal const val MAX_REDIRECT_FOLLOWUPS = 20

/** The Accept header of every feed request (03 Request rules). */
internal const val FEED_ACCEPT =
    "application/rss+xml, application/atom+xml;q=0.9, application/xml;q=0.8, text/xml;q=0.8, */*;q=0.5"
