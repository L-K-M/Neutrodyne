// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlpdesktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * scripts/engine/trim-python.sh over crafted archives (11 Trim list and checks). POSIX-only:
 * the script and the fixture tars are POSIX tools, like the Gradle task that invokes them.
 */
class TrimPythonScriptTest {
    @Test fun aMemberPathOutsideTheExtractionRootIsRejected() {
        val fixture = fixture()
        val marker = fixture.root.resolve("marker.txt").apply { writeText("outside\n") }
        fixture.tar(
            fixture.root.resolve("evil-traversal.tar.gz"),
            fixture.root.resolve("pack"),
            "../marker.txt",
        )
        val out = fixture.root.resolve("out-traversal")
        val result = fixture.trim("evil-traversal.tar.gz", out)
        assertNotEquals(0, result.exitCode, "trim must refuse the escaping member: ${result.stderr}")
        assertFalse(out.resolve("python").exists(), "nothing is extracted into the owned output")
        assertEquals("outside\n", marker.readText(), "the member never reached the file outside")
    }

    @Test fun aLinkTargetOutsideTheExtractionRootIsRejected() {
        val fixture = fixture()
        val outside = fixture.root.resolve("escape-target").apply { mkdirs() }
        val pack = fixture.root.resolve("pack")
        val link = pack.resolve("python/evillink").apply { parentFile.mkdirs() }
        Files.createSymbolicLink(link.toPath(), File("../../escape-target").toPath())
        assertTrue(Files.isSymbolicLink(link.toPath()))
        // A member recorded below the symlink would, extracted naively, land outside the tree.
        outside.resolve("carried.txt").writeText("escape payload\n")
        fixture.tar(
            fixture.root.resolve("evil-link.tar.gz"),
            pack,
            "python/evillink/carried.txt",
            "python/evillink",
        )
        val out = fixture.root.resolve("out-link")
        val result = fixture.trim("evil-link.tar.gz", out)
        assertNotEquals(0, result.exitCode, "trim must refuse the escaping link: ${result.stderr}")
        assertFalse(out.resolve("python").exists(), "nothing is extracted into the owned output")
        assertEquals("escape payload\n", outside.resolve("carried.txt").readText())
    }

    @Test fun aMinimalPinnedTreeTrimsIntoPythonAndCacert() {
        val fixture = fixture()
        val pack = fixture.root.resolve("pack")
        val stdlib = pack.resolve("python/lib/python3.14")
        stdlib.resolve("site-packages/pip/_vendor/certifi").mkdirs()
        stdlib.resolve("site-packages/pip/_vendor/certifi/cacert.pem").writeText("CA DATA\n")
        stdlib.resolve("LICENSE.txt").writeText("upstream licence\n")
        pack.resolve("python/bin").mkdirs()
        pack.resolve("python/bin/python3.14").apply {
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }
        fixture.tar(fixture.root.resolve("good.tar.gz"), pack, "python")
        val out = fixture.root.resolve("out-good")
        val result = fixture.trim("good.tar.gz", out)
        assertEquals(0, result.exitCode, "trim must accept the pinned-shaped tree: ${result.stderr}")
        assertTrue(out.resolve("python/bin/python3.14").isFile)
        assertTrue(out.resolve("python/lib/python3.14/LICENSE.txt").isFile)
        assertEquals("CA DATA\n", out.resolve("cacert.pem").readText())
        assertFalse(out.resolve("python/lib/python3.14/site-packages/pip").exists())
    }

    private fun fixture(): Fixture {
        org.junit.Assume.assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val root = tempRoot("trim")
        val script = File(rootDir(), "scripts/engine/trim-python.sh")
        assertTrue(script.isFile, "scripts/engine/trim-python.sh is missing")
        return Fixture(root, script)
    }

    private class Fixture(
        val root: File,
        val script: File,
    ) {
        data class Result(
            val exitCode: Int,
            val stderr: String,
        )

        /** `tar -czf` over [members] relative to [base] (a member name may itself escape). */
        fun tar(
            archive: File,
            base: File,
            vararg members: String,
        ) {
            base.mkdirs()
            val process =
                ProcessBuilder(listOf("tar", "-czf", archive.absolutePath, "-C", base.absolutePath) + members)
                    .redirectErrorStream(true)
                    .start()
            val text = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor() == 0, "fixture tar failed: $text")
        }

        fun trim(
            archiveName: String,
            out: File,
        ): Result {
            val process =
                ProcessBuilder(
                    "bash",
                    script.absolutePath,
                    root.resolve(archiveName).absolutePath,
                    out.absolutePath,
                ).start()
            val stderr = process.errorStream.bufferedReader().readText()
            process.inputStream.bufferedReader().readText()
            return Result(process.waitFor(), stderr)
        }
    }
}
