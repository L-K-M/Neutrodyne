// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * German resources must cover every translatable string in a `values/` file that has a `values-de/`
 * counterpart; otherwise the German UI silently falls back to English. Files without a counterpart (for
 * example `update_notifications.xml`) are out of scope until they are translated.
 */
class AppStringResourceParityTest {
    @Test
    fun everyTranslatableValueStringExistsInGerman() {
        val valuesDir = File("src/main/res/values")
        val germanDir = File("src/main/res/values-de")
        // A wrong working directory would otherwise compare nothing and pass.
        assertTrue(
            valuesDir.isDirectory && germanDir.isDirectory,
            "resource directories not found from ${File(".").absolutePath}",
        )
        val pairs =
            valuesDir
                .listFiles()
                .orEmpty()
                .filter { it.isFile && it.extension == "xml" }
                .filter { File(germanDir, it.name).isFile }
        assertTrue(pairs.isNotEmpty(), "no values/ file has a values-de/ counterpart")
        val missing =
            pairs
                .flatMap { valuesFile ->
                    val german = stringNames(File(germanDir, valuesFile.name))
                    stringNames(valuesFile)
                        .filterNot { it in german }
                        .map { "${valuesFile.name}: $it" }
                }
        assertEquals(emptyList<String>(), missing)
    }

    private fun stringNames(file: File): Set<String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .filter { it.getAttribute("translatable") != "false" }
            .map { it.getAttribute("name") }
            .toSet()
    }
}
