// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.core.testing.EngineUpdateFixtures
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Co-drift guard for the vendored upstream release vector. Every other OpenPGP case signs with
 * `EngineUpdateFixtures`' own writer, which mirrors the parser it feeds; these cases run the
 * production [EngineKeys], [OpenPgpDetachedVerifier] and [UpstreamReleaseVerifier] against the
 * real `youtube/ytdlp/engine/` + `youtube/ytdlp/keys/` bytes, so a parser drifted away from the
 * official packet shape fails here (04's canary verification relies on this test reading
 * whatever `bump-ytdlp.sh` vendored). The files are declared `test` inputs in
 * `youtube/engine/build.gradle.kts` and resolved through `neutrodyne.rootDir`.
 */
class UpstreamReleaseVerifierTest {
    private val vendored =
        File(
            requireNotNull(System.getProperty("neutrodyne.rootDir")) {
                "neutrodyne.rootDir is unset; run the tests through Gradle"
            },
            "youtube/ytdlp",
        )

    private fun engineBytes(name: String): ByteArray = File(vendored, "engine/$name").readBytes()

    private fun keyText(name: String): String = File(vendored, "keys/$name").readText()

    private fun vendoredKeys(): EngineKeys =
        EngineKeys.parse(keyText("engine-manifest-ed25519.pub"), keyText("yt-dlp-release-key.asc"))

    @Test
    fun vendoredUpstreamReleasePassesTheProductionVerifier() {
        val keys = vendoredKeys()
        assertEquals(EngineKeys.UPSTREAM_FINGERPRINT, keys.upstream.fingerprint.uppercase())
        assertEquals(32, keys.manifestEd25519.single().size)

        val sums = engineBytes("SHA2-256SUMS")
        val entries =
            assertNotNull(
                UpstreamReleaseVerifier.verify(sums, engineBytes("SHA2-256SUMS.sig"), keys.upstream),
                "the production verifier rejected upstream's real signature",
            )
        // Every sums line parsed strictly; none was dropped or hidden.
        assertEquals(sums.decodeToString().lines().count { it.isNotEmpty() }, entries.size)

        // The bundled engine's manifest-side pin: bundled.json's sha256 is the approved hash,
        // and the vendored zip must hash to the signed sums line, exactly as EngineUpdater's
        // three-way check demands (manifest sha == sums line == blob sha).
        val metadata = Json.decodeFromString<VendoredMetadata>(engineBytes("bundled.json").decodeToString())
        assertEquals(metadata.sha256, entries["yt-dlp"])
        assertEquals(entries["yt-dlp"], UpstreamReleaseVerifier.sha256(engineBytes("yt-dlp")))
        assertEquals(metadata.version, metadata.tag)
        assertTrue(Regex("[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}").matches(metadata.version))
    }

    @Test
    fun tamperedVendoredBytesFailTheProductionVerifier() {
        val keys = vendoredKeys()
        val sums = engineBytes("SHA2-256SUMS")
        val signature = engineBytes("SHA2-256SUMS.sig")
        val archive = engineBytes("yt-dlp")

        // A flipped hex char still parses as a sums line; only the RSA/SHA-512 check catches it.
        assertNull(UpstreamReleaseVerifier.verify(sums.flipped(1), signature, keys.upstream))
        // A flipped byte inside the RSA integer keeps the packet shape; verification must fail.
        assertNull(
            UpstreamReleaseVerifier.verify(sums, signature.flipped(signature.lastIndex), keys.upstream),
        )
        // The blob side of the pin: a flipped archive byte no longer hashes to the signed line.
        val entries =
            assertNotNull(UpstreamReleaseVerifier.verify(sums, signature, keys.upstream))
        assertNotEquals(
            entries["yt-dlp"],
            UpstreamReleaseVerifier.sha256(archive.flipped(archive.size / 2)),
        )
        // A different key's well-formed signature fails the issuer-fingerprint subpacket check.
        val fixtures = EngineUpdateFixtures()
        assertNull(
            UpstreamReleaseVerifier.verify(sums, fixtures.sumsSignature(sums), keys.upstream),
        )

        // A substituted or corrupted release key never reaches the signature check: the
        // fingerprint pin and the armor parse both reject before verification.
        assertFails {
            EngineKeys.parse(
                keyText("engine-manifest-ed25519.pub"),
                keyText("yt-dlp-release-key.asc"),
                "0".repeat(40),
            )
        }
        val armor = keyText("yt-dlp-release-key.asc").lines().toMutableList()
        armor[4] = (if (armor[4].startsWith("A")) "B" else "A") + armor[4].drop(1)
        assertFails {
            EngineKeys.parse(keyText("engine-manifest-ed25519.pub"), armor.joinToString("\n"))
        }
    }

    private fun ByteArray.flipped(at: Int): ByteArray = copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }

    /** Same wire shape as `BundledEngineStore`'s private BundleMetadata. */
    @Serializable
    private data class VendoredMetadata(
        val version: String,
        val tag: String,
        val sha256: String,
        val ejsVersion: String,
    )
}
