// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.youtube

import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YouTubeImportParsersTest {
    @Test
    fun newPipeReportsEveryUnsupportedService() {
        val entries =
            (0..4).joinToString(
                ",",
            ) { """{"service_id":$it,"url":"https://www.youtube.com/user/old","name":"A"}""" }
        val result = NewPipeSubscriptions.parse(Buffer().writeUtf8("""{"subscriptions":[$entries],"ignored":true}"""))
        assertEquals(5, result.entries.size)
        assertEquals(
            listOf(
                null,
                "newpipe_service:1",
                "newpipe_service:2",
                "newpipe_service:3",
                "newpipe_service:4",
            ),
            result.entries.map {
                it.errorDetail
            },
        )
    }

    @Test
    fun newPipeExportRoundTripsUnicodeQuotesAndVersion() {
        val buffer = Buffer()
        NewPipeSubscriptions.write(buffer, "1.2", 12, listOf(NewPipeSubscriptions.Channel(ID, "A, \"B\" 日本語")))
        val text = buffer.clone().readUtf8()
        assertTrue("\"app_version_int\":12" in text)
        val result = NewPipeSubscriptions.parse(buffer)
        assertEquals("A, \"B\" 日本語", result.entries.single().title)
        assertEquals("https://www.youtube.com/channel/$ID", result.entries.single().url)
    }

    @Test
    fun libreTubeAliasesPreserveGroupOrderAndGroupOnlyChannels() {
        val result =
            LibreTubeBackupParser.parse(
                Buffer().writeUtf8(
                    """{"subscriptions":[{"url":"https://www.youtube.com/channel/$ID","name":"Original","avatar":"https://proxy.invalid/ignored"}],"channelGroups":[{"name":" TECH ","index":2,"channels":["$ID"]},{"groupName":"Science","index":0,"channels":["$OTHER"]},{"name":"tech","index":3,"channels":["$OTHER"]}],"history":[]}""",
                ),
            )
        assertEquals(listOf("Science", " TECH ", "tech"), result.groups.map { it.name })
        assertEquals(listOf(ID, OTHER), result.entries.map { it.url })
        assertEquals(listOf("Science", "tech"), result.entries[1].groupNames)
        assertEquals(listOf(0, 1), result.entries.map { it.ordinal })
        assertFalse(result.toString().contains("proxy.invalid"))
        assertEquals(
            result,
            LibreTubeBackupParser.parse(
                Buffer().writeUtf8(
                    """{"localSubscriptions":[{"channelId":"$ID","name":"Original"}],"groups":[{"groupName":" TECH ","index":2,"channels":["$ID"]},{"groupName":"Science","index":0,"channels":["$OTHER"]},{"groupName":"tech","index":3,"channels":["$OTHER"]}]}""",
                ),
            ),
        )
    }

    @Test
    fun takeoutSupportsBomLocalizedHeadersCrLfQuotesAndEmbeddedNewlines() {
        val result =
            TakeoutSubscriptionsParser.parse(
                Buffer().writeUtf8(
                    "\uFEFFKennung,URL,Titel\r\n$ID,ignored,\"A, \"\"quoted\"\"\r\n日本語\"\r\ninvalid,https://www.youtube.com/@other,Fallback\ninvalid,bad,Invalid\n",
                ),
            )
        assertEquals("A, \"quoted\"\r\n日本語", result.entries[0].title)
        assertEquals(ID, result.entries[0].url)
        assertEquals("https://www.youtube.com/@other", result.entries[1].url)
        assertEquals("bad", result.entries[2].url)
    }

    @Test
    fun takeoutRejectsUnclosedQuotesAndTextAfterQuote() {
        for (row in listOf("$ID,url,\"open", "$ID,url,\"closed\"oops")) {
            assertEquals(
                ImportParseException.Reason.MALFORMED,
                assertFailsWith<ImportParseException> {
                    TakeoutSubscriptionsParser.parse(Buffer().writeUtf8("Id,Url,Title\n$row"))
                }.reason,
            )
        }
    }

    @Test
    fun urlListsRetainMixedRssInvalidRowsAndTrimComments() {
        val result =
            UrlListParser.parse(
                Buffer().writeUtf8("\uFEFF # comment\n $ID \n @handle\nhttps://rss.example/feed\nbad\n"),
            )
        assertEquals(listOf(ID, "@handle", "https://rss.example/feed", "bad"), result.entries.map { it.url })
    }

    @Test
    fun exactJsonCountCapIsAcceptedAndOneExtraRejected() {
        fun json(count: Int) =
            Buffer().writeUtf8(
                "{\"subscriptions\":[" +
                    List(count) {
                        "{\"service_id\":0,\"url\":\"$ID\"}"
                    }.joinToString(",") + "]}",
            )
        assertEquals(10_000, NewPipeSubscriptions.parse(json(10_000)).entries.size)
        assertEquals(
            ImportParseException.Reason.TOO_MANY_ITEMS,
            assertFailsWith<ImportParseException> {
                NewPipeSubscriptions.parse(json(10_001))
            }.reason,
        )
    }

    @Test
    fun urlListsEnforceByteAndPhysicalLineCaps() {
        assertEquals(5_000, UrlListParser.parse(Buffer().writeUtf8("@a\n".repeat(5_000))).entries.size)
        assertEquals(
            ImportParseException.Reason.TOO_MANY_ITEMS,
            assertFailsWith<ImportParseException> {
                UrlListParser.parse(Buffer().writeUtf8("# ignored\n".repeat(5_001)))
            }.reason,
        )
        assertEquals(
            ImportParseException.Reason.TOO_LARGE,
            assertFailsWith<ImportParseException> {
                UrlListParser.parse(Buffer().write(ByteArray(1_000_001) { 32 }))
            }.reason,
        )
    }

    @Test
    fun jsonRejectsDeepIgnoredStructuresAndMalformedUtf8() {
        assertEquals(
            ImportParseException.Reason.TOO_DEEP,
            assertFailsWith<ImportParseException> {
                NewPipeSubscriptions.parse(
                    Buffer().writeUtf8("{\"ignored\":" + "[".repeat(65) + "0" + "]".repeat(65) + "}"),
                )
            }.reason,
        )
        assertFailsWith<ImportParseException> { UrlListParser.parse(Buffer().write(byteArrayOf(0xff.toByte()))) }
    }

    @Test
    fun jsonByteLimitIsDecimalAndAppliesBeforeDecoding() {
        val base = "{\"subscriptions\":[]}"
        val exact = base + " ".repeat(10_000_000 - base.length)
        assertEquals(0, NewPipeSubscriptions.parse(Buffer().writeUtf8(exact)).entries.size)
        assertEquals(
            ImportParseException.Reason.TOO_LARGE,
            assertFailsWith<ImportParseException> {
                NewPipeSubscriptions.parse(Buffer().writeUtf8(exact + " "))
            }.reason,
        )
        val crowded = "{\"localSubscriptions\":[" + List(10_001) { "{}" }.joinToString(",") + "]}"
        assertEquals(
            ImportParseException.Reason.TOO_MANY_ITEMS,
            assertFailsWith<ImportParseException> {
                LibreTubeBackupParser.parse(Buffer().writeUtf8(crowded))
            }.reason,
        )
    }

    @Test
    fun takeoutInvalidPaddingBitsUseTheUrlColumn() {
        val invalidId = "UC" + "0".repeat(22)
        val url = "https://www.youtube.com/channel/$ID"
        val doc =
            TakeoutSubscriptionsParser.parse(
                Buffer().writeUtf8("Id,Url,Title\n$invalidId,$url,Name\n"),
            )
        assertEquals(url, doc.entries.single().url)
    }

    private companion object {
        const val ID = "UCBJycsmduvYEL83R_U4JriQ"
        const val OTHER = "UCBR8-60-B28hp2BmDPdntcQ"
    }
}
