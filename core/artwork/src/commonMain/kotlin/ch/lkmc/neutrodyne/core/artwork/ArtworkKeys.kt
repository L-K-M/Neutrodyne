// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.artwork

import okio.ByteString.Companion.encodeUtf8

/**
 * The `artwork` key formats 02 stores and 03/04/05 compute (08 Keys and versions). Deterministic:
 * the same URL or feed key always maps to the same artwork row key, on every platform and the sync
 * server.
 */
object ArtworkKeys {
    /** `u-…` for fetched images: SHA-1 over the [normalize]d URL. */
    fun forUrl(url: String): String = "u-" + normalize(url).encodeUtf8().sha1().hex()

    /** `m-…` for a podcast without artwork: the monogram row of its feed key. */
    fun monogram(feedKey: String): String = "m-" + feedKey.encodeUtf8().sha1().hex()

    /** `g-…` group mosaics (M10). */
    fun mosaic(groupUuid: String): String = "g-$groupUuid"

    /** Provider and mapper input check (08 Keys and versions). */
    val VALID = Regex("^(u-[0-9a-f]{40}|m-[0-9a-f]{40}|g-[0-9a-f-]{36})$")

    /**
     * The URL form a key hashes (08): trim; drop userinfo and fragment; lowercase scheme and host;
     * drop `:80` for http and `:443` for https; keep path and query verbatim. Deliberately not
     * `UrlNormalizer.forIdentity` — an artwork key distinguishes bytes a feed key does not (the
     * scheme, `www.` hosts, query).
     */
    internal fun normalize(url: String): String {
        var s = url.trim()
        val frag = s.indexOf('#')
        if (frag >= 0) s = s.substring(0, frag)

        val schemeEnd = s.indexOf("://")
        val scheme = if (schemeEnd >= 0) s.substring(0, schemeEnd).lowercase() else ""
        var rest = if (schemeEnd >= 0) s.substring(schemeEnd + 3) else s

        // Authority ends at the first '/', '?' is part of the path+query tail here.
        val authorityEnd = rest.indexOf('/').let { if (it < 0) rest.length else it }
        var authority = rest.substring(0, authorityEnd)
        val tail = rest.substring(authorityEnd)

        val at = authority.lastIndexOf('@')
        if (at >= 0) authority = authority.substring(at + 1)

        val host = authority.substringBefore(':').lowercase()
        val port = authority.substringAfter(':', "")
        val keepPort =
            port.isNotEmpty() && !((scheme == "http" && port == "80") || (scheme == "https" && port == "443"))

        return buildString {
            if (scheme.isNotEmpty()) append(scheme).append("://")
            append(host)
            if (keepPort) append(':').append(port)
            append(tail)
        }
    }
}
