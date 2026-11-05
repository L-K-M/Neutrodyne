// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.Base64

/**
 * Check 1 of the trust chain: the detached Ed25519 signature covers the exact manifest bytes and
 * the JSON itself must be a well-formed approval document. The same verifier authenticates the
 * canary heartbeat (`kind == "heartbeat"`, so it can never be replayed as a manifest).
 */
object EngineManifestVerifier {
    const val SCHEMA = 1

    /** The shim API this build speaks; `shimApi.min ≤ SHIM_API_VERSION ≤ shimApi.max` must hold. */
    const val SHIM_API_VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }
    private val versionPattern = Regex("[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}")
    private val ejsPattern = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")

    /** The parsed manifest when signature and schema check out, null on any rejection. */
    fun verify(
        manifest: ByteArray,
        signatureBase64: String,
        keys: EngineKeys,
        verifier: Ed25519Verifier,
    ): EngineManifest? {
        val signature =
            try {
                Base64.getDecoder().decode(signatureBase64.trim())
            } catch (invalid: IllegalArgumentException) {
                return null
            }
        if (keys.manifestEd25519.none { verifier.verify(it, manifest, signature) }) return null
        val parsed =
            try {
                json.decodeFromString<EngineManifest>(manifest.decodeToString())
            } catch (invalid: Exception) {
                return null
            }
        return parsed.takeIf { it.schema == SCHEMA && it.isWellFormed() }
    }

    /** The heartbeat's own timestamp when valid; any failure is ignored, never blocking. */
    fun verifyHeartbeat(
        document: ByteArray,
        signatureBase64: String,
        keys: EngineKeys,
        verifier: Ed25519Verifier,
    ): Long? {
        val signature =
            try {
                Base64.getDecoder().decode(signatureBase64.trim())
            } catch (invalid: IllegalArgumentException) {
                return null
            }
        if (keys.manifestEd25519.none { verifier.verify(it, document, signature) }) return null
        return try {
            val heartbeat = json.decodeFromString<EngineHeartbeat>(document.decodeToString())
            if (heartbeat.kind != "heartbeat") return null
            Instant.parse(heartbeat.lastRunAt).epochSecond * 1000
        } catch (invalid: Exception) {
            null
        }
    }

    private fun EngineManifest.isWellFormed(): Boolean =
        sequence > 0 &&
            versionPattern.matches(ytdlp.version) &&
            ytdlp.tag == ytdlp.version &&
            ejsPattern.matches(ytdlp.ejsVersion) &&
            Regex("[0-9a-f]{64}").matches(ytdlp.sha256) &&
            ytdlp.repo.isNotBlank() &&
            ytdlp.asset.isNotBlank() &&
            shimApi.min in 1..SHIM_API_VERSION + 1 &&
            shimApi.min <= shimApi.max &&
            revoked.all { versionPattern.matches(it) } &&
            try {
                Instant.parse(issuedAt)
                true
            } catch (invalid: Exception) {
                false
            }
}

@Serializable
data class EngineManifest(
    val schema: Int,
    val sequence: Long,
    val issuedAt: String,
    val ytdlp: EngineManifestRelease,
    val shimApi: EngineManifestShimApi,
    val revoked: List<String> = emptyList(),
) {
    val version: String get() = ytdlp.version
}

@Serializable
data class EngineManifestRelease(
    val version: String,
    val tag: String,
    val repo: String,
    val asset: String,
    val sha256: String,
    val ejsVersion: String,
)

@Serializable
data class EngineManifestShimApi(
    val min: Int,
    val max: Int,
)

@Serializable
private data class EngineHeartbeat(
    val kind: String,
    val lastRunAt: String,
    val sequence: Long = 0,
)
