// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.identity

import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8

/**
 * `podcast:guid` handling (03 podcast:guid). Derived values are a local key only (`podcastGuidDerived`),
 * never exported as real, never synced, never used for dedupe.
 */
public object PodcastGuid {
    /** 8-4-4-4-12 hex, the form the Podcast Index spec requires. */
    private val guidPattern = Regex("""^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""")

    /** The nil UUID is junk data, not an identity: two broken feeds emitting it must not dedupe. */
    private const val NIL_UUID = "00000000-0000-0000-0000-000000000000"

    /** The podcast:guid namespace for UUIDv5 derivation (podcastindex.org podcast namespace). */
    private val namespaceBytes = "ead4c236-bf58-58c6-a2c6-a6b28d128cb6".replace("-", "").decodeHex()

    private const val GUID_GROUP_SPLIT_1 = 4
    private const val GUID_GROUP_SPLIT_2 = 6
    private const val GUID_GROUP_SPLIT_3 = 8
    private const val GUID_GROUP_SPLIT_4 = 10
    private const val UUID_BYTES = 16
    private const val UUID_VERSION_5 = 0x50
    private const val UUID_VARIANT_RFC4122 = 0x80

    private val hexDigits = "0123456789abcdef"

    /**
     * Validates a feed-supplied GUID and lowercases it; null when it is not a UUID-shaped value —
     * or is the nil UUID, which is junk rather than an identity.
     */
    public fun parse(raw: String): String? =
        raw
            .trim()
            .takeIf { guidPattern.matches(it) }
            ?.lowercase()
            ?.takeIf { it != NIL_UUID }

    /**
     * UUIDv5 over the URL with the scheme and trailing slashes removed, namespace
     * `ead4c236-bf58-58c6-a2c6-a6b28d128cb6` (03 podcast:guid; reproduces both spec examples:
     * `mp3s.nashownotes.com/pc20rss.xml` → `917393e3-…`, `podnews.net/rss` → `9b024349-…`).
     */
    public fun derive(feedUrl: String): String {
        val name =
            feedUrl
                .replaceFirst(Regex("""^[A-Za-z][A-Za-z0-9+.\-]*://"""), "")
                .trimEnd('/')
        val digestInput =
            okio
                .Buffer()
                .write(namespaceBytes)
                .write(name.encodeUtf8())
                .readByteString()
        val bytes = digestInput.sha1().toByteArray().copyOf(UUID_BYTES)

        // Set the v5 version and RFC 4122 variant bits (UUIDv5 algorithm).
        bytes[GUID_GROUP_SPLIT_2] = ((bytes[GUID_GROUP_SPLIT_2].toInt() and 0x0F) or UUID_VERSION_5).toByte()
        bytes[GUID_GROUP_SPLIT_3] =
            ((bytes[GUID_GROUP_SPLIT_3].toInt() and 0x3F) or UUID_VARIANT_RFC4122).toByte()

        val hex = StringBuilder(UUID_BYTES * 2 + 4)
        for (i in bytes.indices) {
            if (i == GUID_GROUP_SPLIT_1 || i == GUID_GROUP_SPLIT_2 ||
                i == GUID_GROUP_SPLIT_3 || i == GUID_GROUP_SPLIT_4
            ) {
                hex.append('-')
            }
            val value = bytes[i].toInt() and 0xFF
            hex.append(hexDigits[value ushr 4]).append(hexDigits[value and 0x0F])
        }
        return hex.toString()
    }
}
