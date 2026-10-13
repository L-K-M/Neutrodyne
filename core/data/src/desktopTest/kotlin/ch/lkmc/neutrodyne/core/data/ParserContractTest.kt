// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.refresh.AdapterResult
import ch.lkmc.neutrodyne.core.data.refresh.FetchMode
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.AddResolution
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.feeds.parse.FeedParser
import ch.lkmc.neutrodyne.feeds.parse.ParseFailure
import ch.lkmc.neutrodyne.feeds.parse.ParseLimits
import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import kotlinx.coroutines.test.runTest
import mockwebserver3.junit4.MockWebServerRule
import okio.IOException
import okio.Source
import org.junit.Rule
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 03 Parser → the callers' side of the final `FeedParser` contract: a stub parser exercises the
 * `ParseFailure` mapping and the propagated source-I/O cases a real document cannot reach (the
 * wire cap always fires before a >32 MB document would reach the parse cap).
 */
class ParserContractTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val server get() = serverRule.server

    private val clock = TestClock()
    private val db = newDb(clock)

    private lateinit var root: File

    private fun setUpRoot() {
        if (!::root.isInitialized) {
            root =
                kotlin.io.path
                    .createTempDirectory("nd-parser-contract-test")
                    .toFile()
        }
    }

    @AfterTest
    fun tearDown() {
        if (::root.isInitialized) root.deleteRecursively()
    }

    private fun feedUrl(path: String = "/feed.xml") = server.url(path).toString()

    private fun stubParser(result: ParseResult) =
        object : FeedParser {
            override fun parse(
                open: () -> Source,
                httpCharset: String?,
                baseUrl: String,
                limits: ParseLimits,
            ): ParseResult = result
        }

    private val ioParser =
        object : FeedParser {
            override fun parse(
                open: () -> Source,
                httpCharset: String?,
                baseUrl: String,
                limits: ParseLimits,
            ): ParseResult = throw IOException("temp file gone")
        }

    // --- `RssSourceAdapter` (03 Source adapters) ----------------------------------------------------

    @Test
    fun oversizedDocumentMapsToTooLargeKind() =
        runTest {
            setUpRoot()
            val feedUrl = feedUrl()
            val id = seedPodcast(db, feedUrl = feedUrl, feedKey = feedUrl)
            server.enqueue(mockResponse(body = rssBody()))
            val bundle = newRssAdapter(root, clock, parser = stubParser(tooLarge))
            try {
                val result = bundle.adapter.fetchAndParse(dueFeedOf(db, id), FetchMode.REFRESH)

                // The document cap is a size failure with failure backoff (03 Limits), not a
                // malformed document: `TOO_LARGE`, still transient, never `gone`.
                val failed = assertIs<AdapterResult.Failed>(result)
                assertEquals(FeedErrorKind.TOO_LARGE, failed.kind)
                assertTrue(failed.transient)
            } finally {
                bundle.close()
            }
        }

    @Test
    fun malformedDocumentStillMapsToParseError() =
        runTest {
            setUpRoot()
            val feedUrl = feedUrl()
            val id = seedPodcast(db, feedUrl = feedUrl, feedKey = feedUrl)
            server.enqueue(mockResponse(body = rssBody()))
            val bundle = newRssAdapter(root, clock, parser = stubParser(malformed))
            try {
                val result = bundle.adapter.fetchAndParse(dueFeedOf(db, id), FetchMode.REFRESH)

                val failed = assertIs<AdapterResult.Failed>(result)
                assertEquals(FeedErrorKind.PARSE_ERROR, failed.kind)
                assertTrue(failed.transient)
            } finally {
                bundle.close()
            }
        }

    @Test
    fun propagatedSourceIoMapsToStorageNotParseError() =
        runTest {
            setUpRoot()
            val feedUrl = feedUrl()
            val id = seedPodcast(db, feedUrl = feedUrl, feedKey = feedUrl)
            server.enqueue(mockResponse(body = rssBody()))
            val bundle = newRssAdapter(root, clock, parser = ioParser)
            try {
                val result = bundle.adapter.fetchAndParse(dueFeedOf(db, id), FetchMode.REFRESH)

                // A throw from `open`/the read propagates out of `parse` (03 Parser): the
                // `FetchOutcome.Storage` class — a retryable source fault, not a broken feed.
                val failed = assertIs<AdapterResult.Failed>(result)
                assertEquals(FeedErrorKind.STORAGE, failed.kind)
                assertTrue(failed.transient)
            } finally {
                bundle.close()
            }
        }

    // --- `AddPodcastResolverImpl` (03 Add podcast flow) ---------------------------------------------

    @Test
    fun oversizedDocumentMapsToTooLarge() =
        runTest {
            setUpRoot()
            server.enqueue(mockResponse(body = rssBody()))
            val bundle = newResolver(root, db, clock, parser = stubParser(tooLarge))
            try {
                val failure = assertIs<AddResolution.Failure>(bundle.resolver.resolve(feedUrl()))
                assertEquals(AddPodcastError.TooLarge, failure.error)
            } finally {
                bundle.close()
            }
        }

    @Test
    fun malformedDocumentStillMapsToMalformed() =
        runTest {
            setUpRoot()
            server.enqueue(mockResponse(body = rssBody()))
            val bundle = newResolver(root, db, clock, parser = stubParser(malformed))
            try {
                val failure = assertIs<AddResolution.Failure>(bundle.resolver.resolve(feedUrl()))
                assertEquals(AddPodcastError.Malformed, failure.error)
            } finally {
                bundle.close()
            }
        }

    @Test
    fun propagatedSourceIoMapsToNetworkNotMalformed() =
        runTest {
            setUpRoot()
            server.enqueue(mockResponse(body = rssBody()))
            val bundle = newResolver(root, db, clock, parser = ioParser)
            try {
                // Source I/O is a fetch-class fault (03 Parser), the same bucket the fetcher's
                // `FetchOutcome.Storage` uses — never `Malformed`.
                val failure = assertIs<AddResolution.Failure>(bundle.resolver.resolve(feedUrl()))
                assertEquals(AddPodcastError.Network(NetError.Other("storage")), failure.error)
            } finally {
                bundle.close()
            }
        }

    private companion object {
        val tooLarge = ParseResult.Failed(ParseFailure.TOO_LARGE, "document over 32 MiB")
        val malformed = ParseResult.Failed(ParseFailure.MALFORMED, "unclosed tag")
    }
}
