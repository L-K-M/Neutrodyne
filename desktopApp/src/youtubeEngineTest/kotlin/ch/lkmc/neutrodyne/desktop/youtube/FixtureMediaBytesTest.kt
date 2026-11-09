// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.youtube

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.security.MessageDigest
import java.util.HexFormat

/**
 * [FixtureMediaBytes]' composition contract (MD3 AC2): the caller's head preserved byte for
 * byte, exactly one spec-valid `free` box padding to the declared size, and the expected
 * whole-file digest the proof compares against — without ever materialising the body.
 */
class FixtureMediaBytesTest {
    private fun stubHead(): ByteArray =
        // The recorded synthetic m4a's first box: a complete 28-byte ftyp box — the small
        // head stub unit contracts use while the proof supplies the real m4a_lc_2s.m4a.
        HexFormat.of().parseHex("0000001c667479704d344120000002004d34412069736f6d69736f32")

    private fun bytes(
        size: Long = TOTAL_BYTES,
        head: ByteArray = stubHead(),
    ) = FixtureMediaBytes(head, size)

    @Test
    fun `preserves the head byte for byte`() {
        val head = stubHead()
        assertThat(bytes().readRange(0, head.size)).isEqualTo(head)
    }

    @Test
    fun `appends exactly one big-endian free box padding to the size`() {
        val media = bytes()
        val head = stubHead()
        val padding = TOTAL_BYTES - head.size
        val header = media.readRange(head.size.toLong(), BOX_HEADER_BYTES)
        // Big-endian 32-bit length followed by the literal type "free".
        assertThat(HexFormat.of().formatHex(header))
            .isEqualTo("%08x66726565".format(padding))
        // The box payload is zeros through the end of the stream.
        assertThat(media.readRange(TOTAL_BYTES - 4, 4)).isEqualTo(byteArrayOf(0, 0, 0, 0))
        assertThat(media.readRange(head.size + BOX_HEADER_BYTES.toLong(), 4))
            .isEqualTo(byteArrayOf(0, 0, 0, 0))
    }

    @Test
    fun `uses the extended free-box form beyond 32-bit lengths`() {
        val head = stubHead()
        val total = head.size + 0x1_0000_0010L // padding > UINT32_MAX
        val media = FixtureMediaBytes(head, total)
        // size==1, type "free", then the 64-bit big-endian real box length.
        val header = media.readRange(head.size.toLong(), EXTENDED_BOX_HEADER_BYTES)
        assertThat(HexFormat.of().formatHex(header))
            .isEqualTo("0000000166726565%016x".format(total - head.size))
    }

    @Test
    fun `composes head header and padding seamlessly across boundaries`() {
        val media = bytes(size = 512)
        val head = stubHead()
        // A read straddling head -> free header -> zeros returns the concatenation.
        val straddle = media.readRange(head.size - 4L, 16)
        assertThat(straddle.copyOfRange(0, 4)).isEqualTo(head.copyOfRange(head.size - 4, head.size))
        assertThat(straddle.copyOfRange(4, 12)).isEqualTo(media.readRange(head.size.toLong(), 8))
        assertThat(straddle.copyOfRange(12, 16)).isEqualTo(byteArrayOf(0, 0, 0, 0))
        assertThat(media.byteAt(0)).isEqualTo(head[0])
        assertThat(media.byteAt(head.size - 1L)).isEqualTo(head.last())
        assertThat(media.byteAt(head.size.toLong()))
            .isEqualTo(((512L - head.size) ushr 24).toByte()) // the free box's big-endian length
    }

    @Test
    fun `the expected sha256 is deterministic and matches a streamed pass`() {
        val media = bytes()
        assertThat(media.sha256Hex).isEqualTo(bytes().sha256Hex)
        val digest = MessageDigest.getInstance("SHA-256")
        media.openStream().use { stream ->
            val buffer = ByteArray(65_536)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        assertThat(media.sha256Hex).isEqualTo(digest.digest().joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `openStream skips into the padding and stops at the size`() {
        val media = bytes(size = 1_000)
        media.openStream().use { stream ->
            assertThat(stream.skip(995)).isEqualTo(995)
            val tail = stream.readBytes()
            assertThat(tail).hasLength(5)
            assertThat(media.readRange(995, 5)).isEqualTo(tail)
            assertThat(stream.read()).isEqualTo(-1)
        }
    }

    @Test
    fun `readRange refuses offsets outside the composition`() {
        val media = bytes(size = 512)
        for (
        call in
        listOf(
            { media.readRange(-1, 4) },
            { media.readRange(512, 1) },
            { media.readRange(510, 3) },
            { media.readRange(0, -1) },
        )
        ) {
            assertThat(runCatching { call() }.exceptionOrNull())
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `rejects a head without the ftyp contract or room for the free box`() {
        val junk = ByteArray(64) { 0x55 }
        assertThat(runCatching { bytes(head = junk) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        // A head whose declared first box overflows the head is junk, not an m4a.
        val lying = stubHead().also { it[3] = 0x7f }
        assertThat(runCatching { bytes(head = lying) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        // The free box always exists: no room, undersized head or negative pad all refuse.
        val head = stubHead()
        for (size in listOf(head.size.toLong(), head.size + 7L, head.size - 1L, 0L)) {
            assertThat(runCatching { FixtureMediaBytes(head, size) }.exceptionOrNull())
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    private companion object {
        private const val BOX_HEADER_BYTES = 8
        private const val EXTENDED_BOX_HEADER_BYTES = 16

        /** 21 MiB + 123 — two full 10 MiB ranges plus a tail, the proof's media length. */
        private const val TOTAL_BYTES = 21L * 1024 * 1024 + 123
    }
}
