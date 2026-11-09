// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import java.util.Base64

/**
 * The pinned trust anchors of 04's engine-update chain: the Neutrodyne manifest Ed25519 public
 * keys (slot order = line order; slot 2 is the rotation slot) and the yt-dlp release RSA key.
 * Parsing pins the upstream fingerprint so a swapped key file cannot pass unnoticed.
 */
class EngineKeys private constructor(
    val manifestEd25519: List<ByteArray>,
    val upstream: OpenPgpDetachedVerifier.PublicKey,
) {
    companion object {
        const val UPSTREAM_FINGERPRINT = "AC0CBBE6848D6A873464AF4E57CF65933B5A7581"
        const val MAX_KEY_SLOTS = 2

        fun parse(
            manifestKeysText: String,
            upstreamArmor: String,
            upstreamFingerprint: String = UPSTREAM_FINGERPRINT,
        ): EngineKeys {
            val manifest =
                manifestKeysText
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .map { Base64.getDecoder().decode(it) }
                    .onEach { require(it.size == 32) { "Ed25519 public key must be 32 bytes" } }
                    .toList()
            require(manifest.size in 1..MAX_KEY_SLOTS) { "Expected one or two manifest keys" }
            val upstream = OpenPgpDetachedVerifier.parsePublicKey(upstreamArmor)
            require(upstream.fingerprint.equals(upstreamFingerprint, ignoreCase = true)) {
                "Unpinned yt-dlp release key"
            }
            return EngineKeys(manifest, upstream)
        }
    }
}
