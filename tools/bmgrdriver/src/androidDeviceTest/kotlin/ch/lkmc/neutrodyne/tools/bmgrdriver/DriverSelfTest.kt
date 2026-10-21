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
import java.util.zip.ZipFile

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
    }
}
