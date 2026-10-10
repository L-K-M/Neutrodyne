// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.model

import kotlinx.serialization.Serializable

/**
 * An RSS `enclosure`, an Atom `link[rel=enclosure]` or an audio/video `media:content`.
 * `effectiveType` is [EnclosureTypes.effective] of `type` and `url`; `length` is null when missing,
 * unparsable or ≤ 0 (03 Enclosure types and media acceptance).
 */
@Serializable
public data class Enclosure(
    val url: String,
    val type: String? = null,
    val length: Long? = null,
    val effectiveType: String? = null,
)

/** A `podcast:alternateEnclosure` `source`: one transport for the same media (03 Field mapping). */
@Serializable
public data class AlternateEnclosureSource(
    val uri: String,
    val contentType: String? = null,
)

/** SRI integrity metadata of a `podcast:alternateEnclosure`. */
@Serializable
public data class AlternateEnclosureIntegrity(
    val type: String,
    val value: String,
)

/**
 * `podcast:alternateEnclosure`: an alternative transport for the item's media. It changes transport only,
 * never episode identity (03 Enclosure types and media acceptance).
 */
@Serializable
public data class AlternateEnclosure(
    val type: String? = null,
    val length: Long? = null,
    val bitrate: Long? = null,
    val height: Long? = null,
    val lang: String? = null,
    val title: String? = null,
    val rel: String? = null,
    val codecs: String? = null,
    val isDefault: Boolean = false,
    val sources: List<AlternateEnclosureSource> = emptyList(),
    val integrity: List<AlternateEnclosureIntegrity> = emptyList(),
)
