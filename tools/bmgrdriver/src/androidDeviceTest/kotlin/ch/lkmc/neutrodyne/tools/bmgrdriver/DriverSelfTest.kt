// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.tools.bmgrdriver

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.neutrodyne.feeds.backup.BackupCodec
import okio.buffer
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * The always-selected device-test lane (unarmed): exercises the driver's own fixture pipeline
 * — real `:feeds` codec encodes, the bounded ZIP writer, manifest decode and the FileProvider —
 * in the driver's own process, never touching the target package. Keeps `ciGroupAndroidDeviceTest`
 * meaningful on this module while the armed phases stay excluded before discovery.
 */
@RunWith(AndroidJUnit4::class)
class DriverSelfTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun fixturePipelineRoundTrips() {
        val nonce = "selftest"
        val seed = java.io.File(SeedVault.dir(context), "selftest-seed.zip")
        val (content, _) = SeedFixture.seedZip(nonce, seed)

        ZipFile(seed).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertEquals(
                listOf("manifest.json", "library.json", "episodes.jsonl", "queue.json", "settings.json"),
                names,
            )
        }
        val manifest =
            ZipFile(seed).use { zip ->
                zip.getInputStream(zip.getEntry(BackupCodec.ENTRY_MANIFEST)).use { input ->
                    BackupCodec().decodeManifest(input.source().buffer())
                }
            }
        assertEquals(BackupCodec.FORMAT, manifest.format)
        assertEquals("install-seed-$nonce", manifest.installationId)
        assertEquals(content.podcasts.size, manifest.counts.podcasts)

        val uri = FileProvider.getUriForFile(context, SeedVault.AUTHORITY, seed)
        assertTrue(uri.toString().startsWith("content://${SeedVault.AUTHORITY}/"))

        val blob = SeedFixture.encryptedBlob(java.io.File(SeedVault.dir(context), "selftest-encrypted.zip"))
        try {
            ZipFile(blob).use { zip ->
                val entry = zip.getEntry(BackupCodec.ENTRY_MANIFEST)
                zip.getInputStream(entry).use { it.readBytes() }
            }
            throw AssertionError("encrypted ZIP read without error")
        } catch (expected: java.util.zip.ZipException) {
            // A platform reader may refuse encryption while opening the directory or entry.
        }
        assertReadableControl(blob)
    }

    /** A flag-only negative must become a valid backup when the encryption bits are cleared. */
    private fun assertReadableControl(blob: File) {
        val bytes = blob.readBytes()
        val headers = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val end = bytes.size - 22
        var central = headers.getInt(end + 16)
        assertEquals(end - central, headers.getInt(end + 12))
        repeat(headers.getShort(end + 10).toInt() and 0xFFFF) {
            assertEquals(0x02014B50, headers.getInt(central))
            val local = headers.getInt(central + 42)
            assertEquals(0x04034B50, headers.getInt(local))
            for (offset in listOf(central + 8, local + 6)) {
                val flags = headers.getShort(offset).toInt()
                assertEquals(1, flags and 1)
                headers.putShort(offset, (flags and 1.inv()).toShort())
            }
            central += 46 + (headers.getShort(central + 28).toInt() and 0xFFFF) +
                (headers.getShort(central + 30).toInt() and 0xFFFF) +
                (headers.getShort(central + 32).toInt() and 0xFFFF)
        }
        val control = File(blob.parentFile, "selftest-plain-control.zip").apply { writeBytes(bytes) }
        ZipFile(control).use { zip ->
            assertEquals(5, zip.size())
            zip.getInputStream(zip.getEntry(BackupCodec.ENTRY_MANIFEST)).use { input ->
                assertEquals(BackupCodec.FORMAT, BackupCodec().decodeManifest(input.source().buffer()).format)
            }
        }
        ZipInputStream(control.inputStream()).use { input ->
            var entries = 0
            while (input.nextEntry != null) {
                input.readBytes()
                entries++
            }
            assertEquals(5, entries)
        }
    }

    /** The Bundle key supplies the prefix; its value contains only phase, nonce and result. */
    @Test
    fun statusTokenIsValueOnly() {
        assertEquals("seed:n-1:ok", phaseStatusToken("seed", "n-1", "ok"))
        assertEquals("flip:n-1:error:x", phaseStatusToken("flip", "n-1", "error:x"))
    }

    /**
     * The bgstart leg's loopback rewrite only touches the session.current episode and
     * leaves the unarmed fixture byte-identical to the bmgr lane's expectations.
     */
    @Test
    fun fixtureEnclosureRewriteIsScopedToSessionCurrent() {
        val base = "http://127.0.0.1:47123"
        val armed = SeedFixture.content("selftest-enc", base)
        val plain = SeedFixture.content("selftest-enc")
        val current = armed.episodes[1]
        assertEquals("$base/ep2.mp3", current.u)
        assertEquals("audio/wav", current.ty)
        assertEquals(armed.session!!.current!!.k, current.k)
        val other = armed.episodes.filterNot { it == current }
        assertEquals(plain.episodes.filterNot { it == plain.episodes[1] }, other)
        assertEquals("https://alpha.example.test/selftest-enc/ep2.mp3", plain.episodes[1].u)
        assertEquals("audio/mpeg", plain.episodes[1].ty)
    }

    /** The loopback body is a well-formed RIFF/WAVE long enough to cover the seeded position. */
    @Test
    fun fixtureAudioIsWellFormedWav() {
        val wav = FixtureAudio.pcm(FixtureAudio.STREAM_SECONDS)
        assertEquals(44 + FixtureAudio.STREAM_SECONDS * FixtureAudio.BYTES_PER_SECOND, wav.size)
        assertEquals("RIFF", wav.decodeToString(0, 4))
        assertEquals("WAVE", wav.decodeToString(8, 12))
        val dataSize = ByteBuffer.wrap(wav, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(FixtureAudio.STREAM_SECONDS * FixtureAudio.BYTES_PER_SECOND, dataSize)
        assertTrue(FixtureAudio.STREAM_SECONDS * 1_000L > 750_000L)
    }

    /** Validate serialized fixture identities before they can strand the published restore UI. */
    @Test
    fun fixturePassesStagerIdentityContract() {
        val file = java.io.File(SeedVault.dir(context), "selftest-contract.zip")
        val (_, seed) = SeedFixture.seedZip("selftest-contract", file)
        val library =
            ZipFile(seed).use { zip ->
                zip.getInputStream(zip.getEntry(BackupCodec.ENTRY_LIBRARY)).use { input ->
                    BackupCodec().decodeLibrary(input.source().buffer())
                }
            }
        for (p in library.podcasts) {
            assertEquals(
                p.key,
                ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
                    .forIdentity("https://${p.key}"),
            )
            assertEquals(
                p.syncId,
                java.util.UUID
                    .fromString(requireNotNull(p.syncId))
                    .toString(),
            )
            assertEquals(
                p.feedUrl,
                ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
                    .splitUserInfo(p.feedUrl)
                    .first,
            )
            assertTrue(
                ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
                    .forIdentity(p.feedUrl) != null,
            )
        }
        for (g in library.groups) {
            assertEquals(
                g.uuid,
                java.util.UUID
                    .fromString(g.uuid)
                    .toString(),
            )
        }
    }
}
