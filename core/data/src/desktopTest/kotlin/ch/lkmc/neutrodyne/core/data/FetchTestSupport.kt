// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.LocalNetworkAccess
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.common.UserAgentProvider
import ch.lkmc.neutrodyne.core.data.fetch.FeedFetcher
import ch.lkmc.neutrodyne.core.data.fetch.FeedTempFiles
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.network.NetErrorClassifier
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.network.okhttp.AuthInterceptor
import ch.lkmc.neutrodyne.core.network.okhttp.CoreClients
import ch.lkmc.neutrodyne.core.network.okhttp.DnsFamilyHints
import ch.lkmc.neutrodyne.core.network.okhttp.JvmNetErrors
import ch.lkmc.neutrodyne.core.network.okhttp.LocalNetworkGuard
import ch.lkmc.neutrodyne.core.network.okhttp.NetworkClients
import ch.lkmc.neutrodyne.core.network.okhttp.UserAgentInterceptor
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.collections.immutable.persistentListOf
import mockwebserver3.MockResponse
import okio.FileSystem
import java.io.File

internal fun fakePlatform(
    kind: PlatformKind = PlatformKind.DESKTOP,
    sdkInt: Int? = null,
): PlatformInfo =
    object : PlatformInfo {
        override val kind = kind
        override val userAgentPlatform = "Linux; x64"
        override val androidSdkInt = sdkInt
        override val regionCode = "DE"
    }

internal fun testBuildInfo(): BuildInfo =
    BuildInfo(
        versionName = "0.1.0",
        versionCode = 1,
        debug = false,
        platform = BuildInfo.Platform.DESKTOP,
        repoUrl = "https://example.com/repo",
        updateManifestUrl = "https://example.com/update.json",
        engineManifestUrl = "https://example.com/engine.json",
        youTubeEngineBundled = true,
        shippedLocales = persistentListOf("en"),
        podcastIndexKey = "",
        podcastIndexSecret = "",
    )

internal fun testUserAgent(platform: PlatformInfo = fakePlatform()): UserAgentProvider =
    UserAgentProvider(platform, "0.1.0", "https://example.com/repo")

/** The island client family exactly as `OkHttpNeutrodyneHttpClients` wires it (S12 shape). */
internal fun newNetworkClients(
    credentials: CredentialLookup = CredentialLookup.None,
    platform: PlatformInfo = fakePlatform(),
): NetworkClients =
    NetworkClients(
        CoreClients(
            ua = UserAgentInterceptor(testUserAgent(platform)),
            lanGuard = LocalNetworkGuard(LocalNetworkAccess(), platform),
            hints = DnsFamilyHints(),
            debugInterceptors = emptySet(),
        ),
        AuthInterceptor(credentials),
    )

/** The desktop classifier with a synthetic "connected" flag (Offline never wins in tests). */
internal val testClassifier =
    object : NetErrorClassifier {
        override fun classify(e: Throwable): ch.lkmc.neutrodyne.core.model.NetError =
            JvmNetErrors.classify(e, connected = true)
    }

/** Desktop `StoragePaths` over [root] (DataStore files land under `config/`). */
internal fun storagePathsFor(root: File): StoragePaths =
    StoragePaths(
        AppDirs(
            data = root.resolve("data").toPath(),
            config = root.resolve("config").toPath(),
            cache = root.resolve("cache").toPath(),
            state = root.resolve("state").toPath(),
            logs = root.resolve("logs").toPath(),
            downloadsDefault = root.resolve("downloads").toPath(),
        ),
    )

/** A `FeedFetcher` over the real client stack; temp bodies land under `root/cache/feeds`. */
internal fun newFetcher(
    root: File,
    credentials: CredentialLookup = CredentialLookup.None,
    clock: TestClock = TestClock(),
    networkClients: NetworkClients = newNetworkClients(credentials),
): FetcherBundle {
    val clients = OkHttpNeutrodyneHttpClients(networkClients, testUserAgent())
    val tempFiles = FeedTempFiles(storagePathsFor(root), FileSystem.SYSTEM)
    val fetcher =
        FeedFetcher(
            httpClients = clients,
            tempFiles = tempFiles,
            fileSystem = FileSystem.SYSTEM,
            classifier = testClassifier,
            credentialLookup = credentials,
            clock = clock,
        )
    return FetcherBundle(fetcher, tempFiles, clients)
}

internal class FetcherBundle(
    val fetcher: FeedFetcher,
    val tempFiles: FeedTempFiles,
    val clients: OkHttpNeutrodyneHttpClients,
) : AutoCloseable {
    override fun close() = clients.close()
}

internal fun mockResponse(
    code: Int = 200,
    body: String = "ok",
    vararg headers: Pair<String, String>,
): MockResponse =
    MockResponse
        .Builder()
        .code(code)
        .body(body)
        .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
        .build()

// --- Feed documents -------------------------------------------------------------------------------

internal fun rssItem(
    guid: String,
    title: String = "Episode $guid",
    enclosureUrl: String = "https://cdn.example.com/$guid.mp3",
    extra: String = "",
): String =
    """
    <item>
      <guid>$guid</guid>
      <title>$title</title>
      <enclosure url="$enclosureUrl" type="audio/mpeg" length="12345"/>
      $extra
    </item>
    """.trimIndent()

internal fun rssBody(
    title: String = "Test Show",
    link: String = "https://example.com",
    vararg items: String,
): String =
    """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0"><channel><title>$title</title><link>$link</link>
${items.joinToString("\n")}
</channel></rss>"""
