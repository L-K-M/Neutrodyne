// SPDX-License-Identifier: Unlicense
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rule 2's text-scan half: JVM islands inside `commonMain` dependency blocks, over every
 * `commonMain` DSL form the scan accepts (the Gradle-side `verifyDependencyPolicy` check is
 * authoritative; these cover the forms the regex can isolate).
 */
class SourceScansTest {
    private fun island(name: String) = "implementation(project(\":$name\"))"

    @Test
    fun `islands in commonMain dependency blocks are found in every dsl form`() {
        for (island in JVM_ISLANDS) {
            val decl = island(island)
            val forms =
                listOf(
                    "commonMain.dependencies {\n    $decl\n}",
                    "commonMain {\n    dependencies {\n        $decl\n    }\n}",
                    "val commonMain by getting {\n    dependencies {\n        $decl\n    }\n}",
                    "getByName(\"commonMain\") {\n    dependencies {\n        $decl\n    }\n}",
                    "getByName(\"commonMain\").dependencies {\n    $decl\n}",
                    "getByName(\"commonMain\").apply {\n    dependencies {\n        $decl\n    }\n}",
                    "named(\"commonMain\") {\n    dependencies {\n        $decl\n    }\n}",
                    "named(\"commonMain\").also {\n    dependencies {\n        $decl\n    }\n}",
                )
            for (form in forms) {
                assertEquals(
                    listOf(island),
                    commonMainIslandEdges("sourceSets {\n    $form\n}"),
                    "island hidden in form: $form",
                )
            }
        }
    }

    @Test
    fun `islands outside commonMain do not fire`() {
        val decl = island("core:network:okhttp")
        val legal =
            listOf(
                "androidMain.dependencies {\n    $decl\n}",
                "desktopMain {\n    dependencies {\n        $decl\n    }\n}",
                "val androidMain by getting {\n    dependencies {\n        $decl\n    }\n}",
                "commonTest.dependencies {\n    $decl\n}",
                // a bare `by getting` must not swallow the NEXT source set's block —
                // the island here is androidMain's, which is legal
                "val commonMain by getting\n" +
                    "val androidMain by getting {\n    dependencies {\n        $decl\n    }\n}",
            )
        for (form in legal) {
            assertEquals(emptyList(), commonMainIslandEdges(form), "false positive on: $form")
        }
    }

    @Test
    fun `non-island edges in commonMain do not fire`() {
        assertEquals(
            emptyList(),
            commonMainIslandEdges(
                "commonMain.dependencies {\n    implementation(project(\":core:model\"))\n}",
            ),
        )
    }

    @Test
    fun `the island list itself is scanned`() {
        assertTrue(JVM_ISLANDS.containsAll(listOf("feeds:jvm", "core:network:okhttp", "youtube:engine")))
    }
}
