// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Host regression of the transport expectation codec and the pre-`onCreate` file gate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BmgrTransportExpectationTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun expectationRoundTripsThroughBase64Url() {
        val decoded = BmgrTransportExpectation.decode(BmgrTransportExpectation.encode(expected()))
        assertThat(decoded).isEqualTo(expected())
        // base64url: no `+`, `/`, `=` or whitespace — the status printer must carry it verbatim.
        assertThat(BmgrTransportExpectation.encode(expected())).doesNotContainMatch("[+/=\\s]")
    }

    @Test
    fun verifyAcceptsExactlyTheTwoDeliveredPayloads() {
        val roots = rootsWithDelivered()
        BmgrTransportExpectation.verifyRestoredBeforeLaunch(roots, expected())
    }

    @Test
    fun verifyRejectsAThirdRestoredFile() {
        val roots = rootsWithDelivered()
        File(roots.dataDir, "files/first-launch-seed.json").writeText("{}")
        val failure =
            assertThrows(IllegalStateException::class.java) {
                BmgrTransportExpectation.verifyRestoredBeforeLaunch(roots, expected())
            }
        assertTrue(failure.message.orEmpty().contains("unexpected files"))
    }

    @Test
    fun verifyRejectsADeliveredDatabase() {
        val roots = rootsWithDelivered()
        File(roots.dataDir, "databases/neutrodyne.db").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(32))
        }
        assertThrows(IllegalStateException::class.java) {
            BmgrTransportExpectation.verifyRestoredBeforeLaunch(roots, expected())
        }
    }

    @Test
    fun verifyRejectsAMutatedPortablePayload() {
        val roots = rootsWithDelivered()
        File(roots.dataDir, "files/datastore/settings.preferences_pb").appendBytes(byteArrayOf(1))
        assertThrows(IllegalStateException::class.java) {
            BmgrTransportExpectation.verifyRestoredBeforeLaunch(roots, expected())
        }
    }

    @Test
    fun verifyRejectsARestoredCanary() {
        val roots = rootsWithDelivered()
        roots.resolve(expected().canaries.first()).apply {
            parentFile?.mkdirs()
            writeText("canary")
        }
        val failure =
            assertThrows(IllegalStateException::class.java) {
                BmgrTransportExpectation.verifyRestoredBeforeLaunch(roots, expected())
            }
        assertTrue(failure.message.orEmpty().contains("canary"))
    }

    @Test
    fun verifyRejectsADeliveredDeviceStore() {
        val roots = rootsWithDelivered()
        File(roots.dataDir, BmgrTransportExpectation.DEVICE_PB_REL).writeBytes(ByteArray(4))
        val failure =
            assertThrows(IllegalStateException::class.java) {
                BmgrTransportExpectation.verifyRestoredBeforeLaunch(roots, expected())
            }
        assertTrue(failure.message.orEmpty().contains("unexpected files"))
    }

    @Test
    fun verifyRejectsAMissingPayload() {
        val roots = rootsWithDelivered()
        File(roots.dataDir, BmgrTransportExpectation.SNAPSHOT_REL).delete()
        assertThrows(IllegalStateException::class.java) {
            BmgrTransportExpectation.verifyRestoredBeforeLaunch(roots, expected())
        }
    }

    @Test
    fun emptyLegAcceptsAPristineTree() {
        val roots = emptyRoots()
        BmgrTransportExpectation.verifyNothingRestored(roots)
    }

    @Test
    fun emptyLegRejectsAnyDeliveredFile() {
        val roots = emptyRoots()
        File(roots.dataDir, BmgrTransportExpectation.SNAPSHOT_REL).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(8))
        }
        assertThrows(IllegalStateException::class.java) {
            BmgrTransportExpectation.verifyNothingRestored(roots)
        }
    }

    private fun expected(): BmgrTransportExpectation.Expected {
        val snapshot = "snapshot".toByteArray()
        val portable = "portable".toByteArray()
        return BmgrTransportExpectation.Expected(
            nonce = "run-0001",
            packageName = "ch.lkmc.neutrodyne",
            api = 31,
            installationId = "install-1",
            snapshotSha256 = FirstLaunchRestoreChecks.sha256Hex(snapshot),
            snapshotBytes = snapshot.size.toLong(),
            portableSha256 = FirstLaunchRestoreChecks.sha256Hex(portable),
            portableBytes = portable.size.toLong(),
            portableKey = "backup.opml_layout",
            portableInSnapshot = "GROUPED",
            portableAfter = "FLAT",
            syncIds = mapOf("k1" to "s1"),
            groupUuids = mapOf("G" to "u1"),
            canaries = listOf(BmgrTransportExpectation.Canary("files", "downloads/bmgr-canary-run-0001", "ab", 3)),
            receiverLine = "ok at=1 bytes=2 level=3",
            preparedAt = 1L,
        )
    }

    private fun emptyRoots(): BmgrTransportExpectation.Roots {
        val dataDir = temporary.newFolder("data")
        return BmgrTransportExpectation.Roots(
            dataDir = dataDir,
            cacheDir = File(dataDir, "cache").apply { mkdirs() },
            noBackupDir = File(dataDir, "no_backup").apply { mkdirs() },
            externalPodcastsDir = null,
        )
    }

    private fun rootsWithDelivered(): BmgrTransportExpectation.Roots {
        val roots = emptyRoots()
        val e = expected()
        File(roots.dataDir, BmgrTransportExpectation.SNAPSHOT_REL).apply {
            parentFile?.mkdirs()
            writeBytes("snapshot".toByteArray())
        }
        File(roots.dataDir, BmgrTransportExpectation.PORTABLE_REL).apply {
            parentFile?.mkdirs()
            writeBytes("portable".toByteArray())
        }
        assertThat(e.snapshotBytes).isEqualTo(8L)
        return roots
    }
}
