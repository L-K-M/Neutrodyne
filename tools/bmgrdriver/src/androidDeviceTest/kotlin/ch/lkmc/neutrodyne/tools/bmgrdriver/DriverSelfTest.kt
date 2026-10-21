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

    /**
     * The armed-phase stream marker carries only `phase:nonce:result` — `sendStatus`'s
     * `bmgrDriver` Bundle key supplies the prefix, so a value-side `bmgrDriver=` would
     * double-prefix every status and the host matcher could never see `ok`.
     */
    @Test
    fun statusTokenIsValueOnly() {
        assertEquals("seed:n-1:ok", phaseStatusToken("seed", "n-1", "ok"))
        assertEquals("flip:n-1:error:x", phaseStatusToken("flip", "n-1", "error:x"))
    }

    /**
     * The seed library must satisfy the production staging contract the restore surface
     * enforces (BackupStager.validate): every feed key already in `forIdentity` form and
     * every non-null syncId/group uuid a canonical UUID — otherwise the archive is refused
     * before the Merge row ever appears.
     */
    @Test
    fun fixturePassesStagerIdentityContract() {
        val content = SeedFixture.content("selftest-contract")
        for (p in content.podcasts) {
            assertEquals(
                p.key,
                ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
                    .forIdentity("https://${p.key}"),
            )
            assertEquals(
                p.syncId,
                java.util.UUID
                    .fromString(p.syncId)
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
        for (g in content.groups) {
            assertEquals(
                g.uuid,
                java.util.UUID
                    .fromString(g.uuid)
                    .toString(),
            )
        }
    }
}
