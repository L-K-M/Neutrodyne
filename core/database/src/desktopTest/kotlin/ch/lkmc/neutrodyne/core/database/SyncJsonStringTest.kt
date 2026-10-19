// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncJsonStringTest {
    @Test
    fun sqlEscapingEqualsJsonForEveryIdentityKindAndXmlWhitespace() =
        runTest {
            BundledSQLiteDriver().open(":memory:").use { c ->
                for (key in listOf(
                    "g:abc",
                    "u:https://host/path?a=b&c=d",
                    "t:abc123",
                    "l:ab123",
                    "h:abcdef",
                    "2g:future",
                    "g:quote\"slash\\\ttab\nline\rreturn",
                    "g:日本語😀",
                )) {
                    c.prepare("SELECT " + SyncTriggers.jsonString("?")).use { s ->
                        s.bindText(1, key)
                        check(s.step())
                        assertEquals(JsonPrimitive(key).toString(), s.getText(0))
                    }
                }
            }
        }
}
