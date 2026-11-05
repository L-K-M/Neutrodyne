// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Recorded-fixture builders for the update trust chain. Test keys are generated in memory —
 * nothing here may ever hold a production private key. The OpenPGP writer emits exactly the v4
 * RSA/SHA-512 packet shape `OpenPgpDetachedVerifier` accepts.
 */
class EngineUpdateFixtures {
    val ed25519: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val rsa: KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(4096) }.generateKeyPair()

    val upstreamKey: OpenPgpDetachedVerifier.PublicKey by lazy {
        OpenPgpDetachedVerifier.parsePublicKey(upstreamArmor())
    }

    val keys: EngineKeys by lazy {
        EngineKeys.parse(manifestKeysText(), upstreamArmor(), upstreamKey.fingerprint)
    }

    fun manifestKeysText(): String {
        val spki = ed25519.public.encoded
        return "# test manifest key, never a production key\n" +
            Base64.getEncoder().encodeToString(spki.copyOfRange(spki.size - 32, spki.size)) + "\n"
    }

    fun signManifest(document: ByteArray): String =
        Base64.getEncoder().encodeToString(
            Signature.getInstance("Ed25519").run {
                initSign(ed25519.private)
                update(document)
                sign()
            },
        )

    fun manifest(
        version: String = NEW_VERSION,
        sequence: Long = 7,
        sha256: String = "0".repeat(64),
        ejsVersion: String = "0.8.0",
        repo: String = "yt-dlp/yt-dlp",
        asset: String = "yt-dlp",
        shimMin: Int = 1,
        shimMax: Int = 1,
        revoked: List<String> = emptyList(),
    ): ByteArray =
        """
        {"schema":1,"sequence":$sequence,"issuedAt":"2026-11-01T00:00:00Z",
         "ytdlp":{"version":"$version","tag":"$version","repo":"$repo","asset":"$asset",
                  "sha256":"$sha256","ejsVersion":"$ejsVersion"},
         "shimApi":{"min":$shimMin,"max":$shimMax},
         "revoked":[${revoked.joinToString(",") { "\"$it\"" }}]}
        """.trimIndent().toByteArray()

    /** A minimal zipapp that satisfies `BundledZip`'s inventory and version pins. */
    fun archive(
        version: String = NEW_VERSION,
        ejsVersion: String = "0.8.0",
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(
                name: String,
                content: String,
            ) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            put("__main__.py", "import yt_dlp\nyt_dlp.main()\n")
            put("yt_dlp/__init__.py", "")
            put(
                "yt_dlp/version.py",
                "ORIGIN = 'yt-dlp/yt-dlp'\nCHANNEL = 'stable'\n__version__ = '$version'\n",
            )
            put("yt_dlp_ejs/__init__.py", "")
            put("yt_dlp_ejs/_version.py", "__version__ = version = '$ejsVersion'\n")
        }
        return out.toByteArray()
    }

    fun sha256(bytes: ByteArray): String = UpstreamReleaseVerifier.sha256(bytes)

    fun sums(
        archive: ByteArray,
        extra: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        val lines =
            (extra + ("yt-dlp" to archive)).entries.sortedBy { it.key }.joinToString("\n") {
                "${sha256(it.value)}  ${it.key}"
            } + "\n"
        return lines.toByteArray()
    }

    fun sumsSignature(sums: ByteArray): ByteArray = openPgpSignature(sums)

    // --- OpenPGP v4 writer (public key packet + detached binary signature) ---

    fun upstreamArmor(): String {
        val body = publicKeyPacket()
        val base64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(body)
        return "-----BEGIN PGP PUBLIC KEY BLOCK-----\n\n$base64\n=${armorCrc(body)}\n" +
            "-----END PGP PUBLIC KEY BLOCK-----\n"
    }

    private fun publicKeyPacket(): ByteArray {
        val public = rsa.public as RSAPublicKey
        val body =
            byteArrayOf(4, 0, 0, 0, 0, 1) + mpi(public.modulus) + mpi(public.publicExponent)
        return packet(6, body)
    }

    private fun openPgpSignature(content: ByteArray): ByteArray {
        val fingerprint = unhex(upstreamKey.fingerprint)
        val hashed =
            subpacket(2, byteArrayOf(0x68, 0x00, 0x00, 0x00)) +
                subpacket(33, byteArrayOf(4) + fingerprint)
        val header = byteArrayOf(4, 0, 1, 10) + u16(hashed.size) + hashed
        val trailer =
            byteArrayOf(
                4,
                0xff.toByte(),
                (header.size shr 24).toByte(),
                (header.size shr 16).toByte(),
                (header.size shr 8).toByte(),
                header.size.toByte(),
            )
        val digest =
            MessageDigest
                .getInstance("SHA-512")
                .apply {
                    update(content)
                    update(header)
                    update(trailer)
                }.digest()
        val rsa =
            Signature.getInstance("SHA512withRSA").run {
                initSign(rsa.private as RSAPrivateKey)
                update(content)
                update(header)
                update(trailer)
                sign()
            }
        val body = header + u16(0) + digest.copyOfRange(0, 2) + mpi(BigInteger(1, rsa))
        return packet(2, body)
    }

    private fun packet(
        tag: Int,
        body: ByteArray,
    ): ByteArray {
        require(body.size < 8384)
        val length =
            if (body.size < 192) {
                byteArrayOf(body.size.toByte())
            } else {
                byteArrayOf(
                    ((body.size - 192) / 256 + 192).toByte(),
                    ((body.size - 192) % 256).toByte(),
                )
            }
        return byteArrayOf((0xc0 or tag).toByte()) + length + body
    }

    private fun subpacket(
        kind: Int,
        value: ByteArray,
    ): ByteArray {
        val size = value.size + 1
        require(size < 192)
        return byteArrayOf(size.toByte(), kind.toByte()) + value
    }

    private fun mpi(value: BigInteger): ByteArray {
        val bits = value.bitLength()
        val bytes = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return u16(bits) + bytes
    }

    private fun u16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())

    private fun armorCrc(body: ByteArray): String {
        var crc = 0xb704ce
        for (b in body) {
            crc = crc xor ((b.toInt() and 255) shl 16)
            repeat(8) {
                crc = crc shl 1
                if (crc and 0x1000000 != 0) crc = crc xor 0x1864cfb
            }
        }
        return Base64.getEncoder().encodeToString(
            byteArrayOf((crc shr 16).toByte(), (crc shr 8).toByte(), crc.toByte()),
        )
    }

    private fun unhex(text: String): ByteArray =
        ByteArray(text.length / 2) { (text.substring(it * 2, it * 2 + 2).toInt(16) and 255).toByte() }

    companion object {
        const val BUNDLED_VERSION = "2026.08.19"
        const val NEW_VERSION = "2026.11.05"
        const val OTHER_VERSION = "2026.09.30"
    }
}
