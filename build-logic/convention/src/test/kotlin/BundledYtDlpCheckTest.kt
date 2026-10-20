// SPDX-License-Identifier: Unlicense
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFails

class BundledYtDlpCheckTest {
    @get:Rule val temporary = TemporaryFolder()
    private val upstream = File("../../youtube/ytdlp").absoluteFile

    @Test fun genuinePinnedReleasePassesBothHostTasks() {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.create("verifyBundle", VerifyBundledYtDlpTask::class.java)
        task.engineDir.set(File(upstream, "engine"))
        task.releaseKey.set(File(upstream, "keys/yt-dlp-release-key.asc"))
        task.inventoryLock.set(File(upstream, "python-components.lock"))
        task.verify()
    }

    @Test fun tamperedSignatureSumsAssetAndMetadataFail() {
        for (name in listOf("SHA2-256SUMS.sig", "SHA2-256SUMS", "yt-dlp", "bundled.json")) {
            val copy = copy()
            val file = File(copy, name)
            val bytes = file.readBytes()
            val at = if (name == "bundled.json") file.readText().indexOf("2026.08.19") else bytes.lastIndex
            bytes[at] = (bytes[at].toInt() xor 1).toByte()
            file.writeBytes(bytes)
            assertFails(name) { BundledYtDlpCheck.verify(copy, key()) }
        }
    }

    @Test fun missingBundleOrAnyVerificationInputFails() {
        for (name in listOf("yt-dlp", "bundled.json", "SHA2-256SUMS", "SHA2-256SUMS.sig")) {
            val copy = copy()
            File(copy, name).delete()
            assertFails(name) { BundledYtDlpCheck.verify(copy, key()) }
        }
        assertFails { BundledYtDlpCheck.verify(temporary.newFolder(), key()) }
        assertFails { BundledYtDlpCheck.verify(copy(), File(temporary.root, "missing-key")) }
    }

    @Test fun unpinnedMalformedOrTruncatedKeyAndSignatureFailClosed() {
        val keyText = key().readText()
        val signature = File(upstream, "engine/SHA2-256SUMS.sig").readBytes()
        val sums = File(upstream, "engine/SHA2-256SUMS").readBytes()
        val encodedKey =
            keyText
                .lines()
                .filter { it.isNotBlank() && !it.startsWith("-----") && !it.startsWith("=") }
                .joinToString("")
        val rawKey =
            java.util.Base64
                .getDecoder()
                .decode(encodedKey)
        rawKey[30] = (rawKey[30].toInt() xor 1).toByte()
        val wrongKey =
            "-----BEGIN PGP PUBLIC KEY BLOCK-----\n\n" +
                java.util.Base64
                    .getEncoder()
                    .encodeToString(rawKey) +
                "\n-----END PGP PUBLIC KEY BLOCK-----"
        assertFails { OpenPgpSignatureCheck.verify(wrongKey, signature, sums) }

        assertFails { OpenPgpSignatureCheck.verify(keyText.replace("mQIN", "nQIN"), signature, sums) }
        assertFails { OpenPgpSignatureCheck.verify(keyText.take(100), signature, sums) }
        for (length in listOf(0, 1, 5, 50, signature.size - 1)) {
            assertFails { OpenPgpSignatureCheck.verify(keyText, signature.copyOf(length), sums) }
        }
        assertFails { OpenPgpSignatureCheck.verify(keyText, signature + signature, sums) }
        assertFails { OpenPgpSignatureCheck.verify(keyText, signature, sums + 0) }
    }

    private fun key() = File(upstream, "keys/yt-dlp-release-key.asc")

    private fun copy(): File =
        temporary.newFolder().also {
            File(upstream, "engine").copyRecursively(it, overwrite = true)
            val inventory = File(it.parentFile, "python-components.lock")
            File(upstream, "python-components.lock").copyTo(inventory, overwrite = true)
        }
}
