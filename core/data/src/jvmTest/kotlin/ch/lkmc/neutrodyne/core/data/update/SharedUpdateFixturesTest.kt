// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.update

import ch.lkmc.neutrodyne.core.common.Outcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** W7 can read these same files directly from its shell/Python script tests. */
class SharedUpdateFixturesTest {
    private val parser = UpdateManifestParser(updateBuild())

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/updates/$name.json")).bufferedReader().use { it.readText() }

    @Test fun allReleaseStagesParseIncludingMacZipToDmg() {
        listOf("android-only", "desktop-tester", "full-release").forEach { name ->
            assertIs<Outcome.Success<*>>(parser.parse(fixture(name).encodeToByteArray()))
        }
    }

    @Test fun sharedRawReleaseUrlCasesMatchTheAppPolicy() {
        Json.parseToJsonElement(fixture("release-url-cases")).jsonArray.forEach { row ->
            val entry = row.jsonObject
            val input = changedJson(fixture("full-release"), "releaseUrl", entry.getValue("url"))
            val accepted = parser.parse(input.encodeToByteArray()) is Outcome.Success
            assertEquals(entry.getValue("accepted").jsonPrimitive.boolean, accepted, entry.toString())
        }
    }
}
