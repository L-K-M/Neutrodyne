// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.protocol

import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** JVM loading only; the pure runner is also usable by MS1's server conformance suite. */
@RunWith(Parameterized::class)
class ConformanceVectorTest(
    private val file: File,
) {
    @Test
    fun vector() {
        val result = ConformanceVectors.run(file.readText())
        assumeTrue(
            "Deferred until MS1 RecordWriter: ${result.name}",
            result.disposition != VectorDisposition.DEFERRED_MS1,
        )
        assertEquals(VectorDisposition.PASSED, result.disposition)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun vectors(): List<Array<File>> {
            val dir = File(System.getProperty("neutrodyne.moduleDir"), "vectors")
            val files = requireNotNull(dir.listFiles()).filter { it.extension == "json" }.sortedBy { it.name }
            check(files.isNotEmpty()) { "No conformance vectors found" }
            return files.map { arrayOf(it) }
        }
    }
}
