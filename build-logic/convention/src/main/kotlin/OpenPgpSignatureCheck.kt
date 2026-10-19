// SPDX-License-Identifier: Unlicense
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** Deliberately accepts only upstream's v4 RSA/SHA-512 binary detached signature, offline. */
internal object OpenPgpSignatureCheck {
    private const val FINGERPRINT = "AC0CBBE6848D6A873464AF4E57CF65933B5A7581"
    private const val MAX_PACKET_BYTES = 64 * 1024

    fun verify(
        keyArmor: String,
        signature: ByteArray,
        content: ByteArray,
    ) {
        val primary = packets(unarmor(keyArmor)).filter { it.first == 6 }.map { it.second }.single()
        require(primary.size <= 65535 && primary[0] == 4.toByte() && primary[5] == 1.toByte()) { "Expected v4 RSA key" }
        val fingerprint =
            MessageDigest
                .getInstance(
                    "SHA-1",
                ).digest(byteArrayOf(0x99.toByte(), (primary.size shr 8).toByte(), primary.size.toByte()) + primary)
        require(hex(fingerprint) == FINGERPRINT.lowercase()) { "Unpinned yt-dlp release key" }
        val key = Cursor(primary, 6)
        val modulus = key.mpi()
        val exponent = key.mpi()
        require(key.remaining == 0 && modulus.bitLength() == 4096 && exponent == BigInteger.valueOf(65537))

        val body = packets(signature).single().also { require(it.first == 2) }.second
        val packet = Cursor(body)
        require(packet.byte() == 4 && packet.byte() == 0 && packet.byte() == 1 && packet.byte() == 10) {
            "Expected v4 binary RSA/SHA-512 signature"
        }
        val hashedSize = packet.u16()
        val hashed = packet.take(hashedSize)
        subpackets(hashed, fingerprint)
        val signedHeader = body.copyOfRange(0, packet.position)
        subpackets(packet.take(packet.u16()), fingerprint)
        val hashPrefix = packet.take(2)
        val rsa =
            packet.mpi().toByteArray().let {
                if (it.size == 513 &&
                    it[0] == 0.toByte()
                ) {
                    it.copyOfRange(1, it.size)
                } else {
                    it
                }
            }
        require(packet.remaining == 0 && rsa.size <= 512)
        val trailer =
            byteArrayOf(4, 0xff.toByte(), 0, 0, (signedHeader.size shr 8).toByte(), signedHeader.size.toByte())
        val digest =
            MessageDigest
                .getInstance("SHA-512")
                .apply {
                    update(content)
                    update(signedHeader)
                    update(trailer)
                }.digest()
        require(hashPrefix.contentEquals(digest.copyOfRange(0, 2))) { "Signature digest mismatch" }
        val verifier = Signature.getInstance("SHA512withRSA")
        verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent)))
        verifier.update(content)
        verifier.update(signedHeader)
        verifier.update(trailer)
        require(verifier.verify(ByteArray(512 - rsa.size) + rsa)) { "Invalid upstream signature" }
    }

    private fun subpackets(
        bytes: ByteArray,
        fingerprint: ByteArray,
    ) {
        val cursor = Cursor(bytes)
        while (cursor.remaining > 0) {
            val size = cursor.newLength()
            require(size > 0)
            val type = cursor.byte()
            val value = cursor.take(size - 1)
            val kind = type and 0x7f
            require(type and 0x80 == 0 || kind in setOf(2, 16, 33)) { "Unknown critical signature subpacket" }
            if (kind == 33) require(value.contentEquals(byteArrayOf(4) + fingerprint)) { "Wrong signature issuer" }
            if (kind == 16) require(value.contentEquals(fingerprint.copyOfRange(12, 20))) { "Wrong signature issuer" }
        }
    }

    private fun packets(bytes: ByteArray): List<Pair<Int, ByteArray>> {
        require(bytes.size <= MAX_PACKET_BYTES)
        val cursor = Cursor(bytes)
        val result = mutableListOf<Pair<Int, ByteArray>>()
        while (cursor.remaining > 0) {
            val header = cursor.byte()
            require(header and 0x80 != 0)
            val modern = header and 0x40 != 0
            val tag = if (modern) header and 0x3f else (header shr 2) and 0x0f
            val size =
                if (modern) {
                    cursor.newLength()
                } else {
                    val kind = header and 3
                    require(kind != 3) { "Indeterminate packet" }
                    cursor.length(1 shl kind)
                }
            result += tag to cursor.take(size)
        }
        return result
    }

    private fun unarmor(text: String): ByteArray {
        val lines = text.trim().lines()
        require(
            lines.first() == "-----BEGIN PGP PUBLIC KEY BLOCK-----" &&
                lines.last() == "-----END PGP PUBLIC KEY BLOCK-----",
        )
        val body =
            lines
                .drop(1)
                .dropLast(1)
                .dropWhile { it.isNotEmpty() }
                .filter { it.isNotEmpty() }
        require(body.isNotEmpty())
        val payload = Base64.getDecoder().decode(body.filterNot { it.startsWith("=") }.joinToString(""))
        val checksums = body.filter { it.startsWith("=") }
        require(checksums.size <= 1)
        if (checksums.isNotEmpty()) {
            var crc = 0xb704ce
            for (b in payload) {
                crc = crc xor ((b.toInt() and 255) shl 16)
                repeat(8) {
                    crc = crc shl 1
                    if (crc and 0x1000000 != 0) crc = crc xor 0x1864cfb
                }
            }
            require(
                Base64.getDecoder().decode(checksums.single().drop(1)).contentEquals(
                    byteArrayOf(
                        (crc shr 16).toByte(),
                        (
                            crc shr
                                8
                        ).toByte(),
                        crc.toByte(),
                    ),
                ),
            ) { "Key armor checksum mismatch" }
        }
        return payload
    }

    private class Cursor(
        private val bytes: ByteArray,
        var position: Int = 0,
    ) {
        val remaining get() = bytes.size - position

        fun byte(): Int = take(1)[0].toInt() and 255

        fun u16(): Int = length(2)

        fun length(count: Int): Int {
            var size = 0L
            for (b in take(count)) size = (size shl 8) or (b.toLong() and 255)
            require(size <= MAX_PACKET_BYTES)
            return size.toInt()
        }

        fun newLength(): Int {
            val first = byte()
            return when {
                first < 192 -> first
                first < 224 -> (first - 192) * 256 + byte() + 192
                first == 255 -> length(4)
                else -> error("Partial packet length")
            }
        }

        fun take(size: Int): ByteArray {
            require(size >= 0 && size <= remaining) { "Truncated OpenPGP packet" }
            return bytes.copyOfRange(position, position + size).also { position += size }
        }

        fun mpi(): BigInteger {
            val bits = u16()
            val value = BigInteger(1, take((bits + 7) / 8))
            require(value.bitLength() == bits && bits > 0)
            return value
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
