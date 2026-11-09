// SPDX-License-Identifier: Unlicense
import org.gradle.api.GradleException
import org.gradle.api.tasks.CacheableTask
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ProfileCapturesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val committed = File("../../app/profiles").absoluteFile

    private val baselineText = "Lcom/a/A;->m()V\nLcom/b/B;->n()V\n" // trailing newline is optional upstream
    private val startupText = "Lcom/c/C;->s()V"

    @Test fun genuineCommittedCapturesInflateToReceiptBytes() {
        val receipt = File(committed, "capture-receipt.properties")
        assertTrue("committed receipt must exist for this test", receipt.isFile)
        val specs = ProfileCaptureReceipt.read(receipt)
        assertEquals(ProfileCaptureReceipt.EXPECTED_NAMES, specs.map { it.name })
        for (capture in specs) {
            val raw = ProfileCaptureReceipt.inflate(File(committed, "${capture.name}.gz"), capture)
            assertEquals(capture.bytes, raw.size.toLong())
        }
    }

    @Test fun taskInflatesFixtureCapturesExactly() {
        val (task, out) = fixtureTask()
        task.inflate()
        assertEquals(listOf("baseline-prof.txt", "startup-prof.txt"), out.list().sorted())
        assertEquals(baselineText, out.resolve("baseline-prof.txt").readText())
        assertEquals(startupText, out.resolve("startup-prof.txt").readText())
    }

    @Test fun changedProfileTextFailsAtRawSha256() {
        val (task, out) =
            fixtureTask(
                diskBlob = { spec ->
                    val raw = spec.text.toByteArray()
                    if (spec.name == "baseline-prof.txt") raw[0] = (raw[0].toInt() xor 1).toByte()
                    gzip(raw)
                },
            )
        assertFailsWith<GradleException> { task.inflate() }
        assertTrue("no outputs may be written when a capture fails", out.list().isNullOrEmpty())
    }

    @Test fun corruptGzipFailsClosedWithoutOutputs() {
        val (task, out) =
            fixtureTask(
                diskBlob = { spec ->
                    if (spec.name == "startup-prof.txt") {
                        ByteArray(97) { 0x7f }
                    } else {
                        gzip(spec.text.toByteArray())
                    }
                },
            )
        assertFailsWith<GradleException> { task.inflate() }
        assertTrue("no outputs may be written when a capture fails", out.list().isNullOrEmpty())
    }

    @Test fun receiptHashMismatchFailsClosedWithoutOutputs() {
        val (task, out) =
            fixtureTask(
                receiptTamper = { text ->
                    text.replace(
                        Regex("baseline-prof\\.txt\\.gzip_sha256=[0-9a-f]{64}"),
                        "baseline-prof.txt.gzip_sha256=${"a".repeat(64)}",
                    )
                },
            )
        assertFailsWith<GradleException> { task.inflate() }
        assertTrue("no outputs may be written when a capture fails", out.list().isNullOrEmpty())
    }

    @Test fun missingOrUnexpectedCaptureFileFails() {
        val (task, _) = fixtureTask()
        task.captures.setFrom()
        assertFailsWith<GradleException> { task.inflate() }

        val (task2, _) = fixtureTask()
        val stray = temporary.newFile("rogue.txt.gz")
        stray.writeBytes(gzip("Lx;".toByteArray()))
        task2.captures.from(stray)
        assertFailsWith<GradleException> { task2.inflate() }
    }

    @Test fun malformedReceiptFails() {
        val valid = receiptText()
        val cases =
            mapOf(
                "unknown key" to valid + "bogus=1\n",
                "missing field" to valid.lineSequence().drop(1).joinToString("\n"),
                "non-hex hash" to valid.replace(Regex("[0-9a-f]{64}"), "x".repeat(64)),
                "non-numeric bytes" to
                    valid.replace(Regex("baseline-prof\\.txt\\.bytes=\\d+"), "baseline-prof.txt.bytes=many"),
                "blank run" to valid.replace(Regex("generation_run=\\d+"), "generation_run="),
            )
        for ((label, text) in cases) {
            val receipt = temporary.newFolder().resolve("capture-receipt.properties").apply { writeText(text) }
            assertFailsWith<GradleException>(label) { ProfileCaptureReceipt.read(receipt) }
        }
    }

    @Test fun taskIsCacheable() {
        assertNotNull(InflateBaselineProfileCapturesTask::class.java.getAnnotation(CacheableTask::class.java))
    }

    private data class FixtureSpec(
        val name: String,
        val text: String,
    )

    /**
     * The receipt's raw fields always describe `FixtureSpec.text`; its gzip fields describe the
     * blob actually written to disk (so corrupt-disk and tampered-receipt cases are distinct).
     */
    private fun fixtureTask(
        diskBlob: (FixtureSpec) -> ByteArray = { gzip(it.text.toByteArray()) },
        receiptTamper: (String) -> String = { it },
    ): Pair<InflateBaselineProfileCapturesTask, File> {
        val specs = listOf(FixtureSpec("baseline-prof.txt", baselineText), FixtureSpec("startup-prof.txt", startupText))
        val dir = temporary.newFolder()
        for (spec in specs) File(dir, "${spec.name}.gz").writeBytes(diskBlob(spec))
        File(dir, "capture-receipt.properties").writeText(receiptTamper(receiptText(dir, specs)))

        val project = ProjectBuilder.builder().build()
        val task =
            project.tasks.create(
                "inflateBaselineProfileCaptures",
                InflateBaselineProfileCapturesTask::class.java,
            )
        task.receipt.set(File(dir, "capture-receipt.properties"))
        task.captures.from(project.fileTree(dir) { include("*.txt.gz") })
        val out = temporary.newFolder()
        task.outputDir.set(out)
        return task to out
    }

    private fun receiptText(
        dir: File? = null,
        specs: List<FixtureSpec> =
            listOf(FixtureSpec("baseline-prof.txt", baselineText), FixtureSpec("startup-prof.txt", startupText)),
    ): String {
        val lines = StringBuilder("source_commit=test\ngeneration_run=1\n")
        for (spec in specs) {
            val raw = spec.text.toByteArray()
            val blob = dir?.resolve("${spec.name}.gz")?.readBytes() ?: gzip(raw)
            lines.append(
                "${spec.name}.bytes=${raw.size}\n" +
                    "${spec.name}.lines=${ProfileCaptureReceipt.lineCount(raw)}\n" +
                    "${spec.name}.sha256=${sha256(raw)}\n" +
                    "${spec.name}.gzip_bytes=${blob.size}\n" +
                    "${spec.name}.gzip_sha256=${sha256(blob)}\n",
            )
        }
        return lines.toString()
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().use { bos ->
            GZIPOutputStream(bos).use { it.write(bytes) }
            bos.toByteArray()
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
