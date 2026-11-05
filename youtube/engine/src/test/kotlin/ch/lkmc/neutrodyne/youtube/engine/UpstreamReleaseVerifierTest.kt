// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The vendored-release case of 04's canary verify step: whatever `bump-ytdlp.sh` left in
 * `youtube/ytdlp/engine/` must pass the same `OpenPgpDetachedVerifier`, SHA-256 and zip-content
 * rules the updater applies on devices. In a normal checkout that is the pinned bundle; in the
 * `engine-canary.yml` gate it is the candidate under test.
 */
class UpstreamReleaseVerifierTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun vendoredReleasePassesTheDeviceChain() {
        val engine = File("../ytdlp/engine")
        val key =
            OpenPgpDetachedVerifier.parsePublicKey(
                File("../ytdlp/keys/yt-dlp-release-key.asc").readText(),
            )
        val entries =
            UpstreamReleaseVerifier.verify(
                engine.resolve("SHA2-256SUMS").readBytes(),
                engine.resolve("SHA2-256SUMS.sig").readBytes(),
                key,
            )
        assertNotNull(entries, "vendored SHA2-256SUMS must verify against the pinned yt-dlp key")
        val bundled = Json.parseToJsonElement(engine.resolve("bundled.json").readText()).jsonObject
        val archive = engine.resolve("yt-dlp")
        val hash = UpstreamReleaseVerifier.sha256(archive.readBytes())
        assertEquals(entries["yt-dlp"], hash)
        assertEquals(hash, bundled.getValue("sha256").jsonPrimitive.content)

        // BundledZip applies the zip-content, ORIGIN, CHANNEL, __version__ and ejs pins (check 10).
        val destination = File(temporary.newFolder(), "lib")
        BundledZip.extract(
            archive,
            destination,
            bundled.getValue("version").jsonPrimitive.content,
            bundled.getValue("ejsVersion").jsonPrimitive.content,
        )
        assertTrue(destination.resolve("yt_dlp/version.py").isFile)
    }

    @Test fun forgedSignatureIsRejected() {
        val fixtures = EngineUpdateFixtures()
        val archive = fixtures.archive()
        val sums = fixtures.sums(archive)
        // A signature produced by a different RSA key must not verify.
        val forged = EngineUpdateFixtures()
        assertNull(
            UpstreamReleaseVerifier.verify(sums, forged.sumsSignature(sums), fixtures.upstreamKey),
        )
    }

    @Test fun signedSumsDoNotCoverATamperedArchive() {
        val fixtures = EngineUpdateFixtures()
        val archive = fixtures.archive()
        val sums = fixtures.sums(archive)
        assertNotNull(UpstreamReleaseVerifier.verify(sums, fixtures.sumsSignature(sums), fixtures.upstreamKey))
        val tampered = archive.copyOf().apply { this[40] = (this[40].toInt() + 1).toByte() }
        assertTrue(
            UpstreamReleaseVerifier.sha256(tampered) !=
                UpstreamReleaseVerifier.verify(sums, fixtures.sumsSignature(sums), fixtures.upstreamKey)!!["yt-dlp"],
        )
    }

    @Test fun mangledSumsAreRejected() {
        val fixtures = EngineUpdateFixtures()
        val archive = fixtures.archive()
        val sums = fixtures.sums(archive)
        val verified =
            UpstreamReleaseVerifier.verify(sums, fixtures.sumsSignature(sums), fixtures.upstreamKey)
        assertNotNull(verified)
        assertEquals(UpstreamReleaseVerifier.sha256(archive), verified["yt-dlp"])
        // A sums file whose signature is genuine but whose lines do not parse must not pass.
        val mangled = "not a sums line\n".toByteArray() + sums
        val resigned = fixtures.sumsSignature(mangled)
        assertNull(UpstreamReleaseVerifier.verify(mangled, resigned, fixtures.upstreamKey))
    }
}
