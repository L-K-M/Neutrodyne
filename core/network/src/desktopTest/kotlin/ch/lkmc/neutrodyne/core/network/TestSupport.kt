// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network

import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.common.LocalNetworkAccess
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.common.PowerEvent
import ch.lkmc.neutrodyne.core.common.PowerMonitor
import ch.lkmc.neutrodyne.core.common.UserAgentProvider
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.network.okhttp.AuthInterceptor
import ch.lkmc.neutrodyne.core.network.okhttp.CoreClients
import ch.lkmc.neutrodyne.core.network.okhttp.DnsFamilyHints
import ch.lkmc.neutrodyne.core.network.okhttp.LocalNetworkGuard
import ch.lkmc.neutrodyne.core.network.okhttp.NetworkClients
import ch.lkmc.neutrodyne.core.network.okhttp.UserAgentInterceptor
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import mockwebserver3.MockResponse
import java.net.InetAddress

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

internal fun newNetworkClients(
    credentials: CredentialLookup = CredentialLookup.None,
    access: LocalNetworkAccess = LocalNetworkAccess(),
    platform: PlatformInfo = fakePlatform(),
    hints: DnsFamilyHints = DnsFamilyHints(),
): NetworkClients =
    NetworkClients(
        CoreClients(
            ua = UserAgentInterceptor(testUserAgent(platform)),
            lanGuard = LocalNetworkGuard(access, platform),
            hints = hints,
            debugInterceptors = emptySet(),
        ),
        AuthInterceptor(credentials),
    )

internal fun newHttpClients(
    networkClients: NetworkClients = newNetworkClients(),
    userAgent: UserAgentProvider = testUserAgent(),
): NeutrodyneHttpClients = OkHttpNeutrodyneHttpClients(networkClients, userAgent)

internal fun NeutrodyneHttpClients.closeAll() {
    HttpClientKind.entries.forEach { client(it).close() }
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

/** Scriptable `PowerMonitor` — emit [PowerEvent]s into [send]. */
internal class FakePowerMonitor : PowerMonitor {
    private val _events = MutableSharedFlow<PowerEvent>(extraBufferCapacity = 8)
    override val events: Flow<PowerEvent> = _events

    suspend fun send(event: PowerEvent) = _events.emit(event)
}

/** Mutable `NetworkInterfaceSource` — swap [current] and call `monitor.recheck()`. */
internal class FakeInterfaceSource(
    var current: List<NdInterface> = emptyList(),
) : NetworkInterfaceSource {
    var queries = 0
        private set

    override fun interfaces(): List<NdInterface> {
        queries++
        return current
    }
}

internal fun ndInterface(
    name: String,
    up: Boolean = true,
    loopback: Boolean = false,
    pointToPoint: Boolean = false,
    vararg addresses: String,
): NdInterface =
    NdInterface(
        name = name,
        up = up,
        loopback = loopback,
        pointToPoint = pointToPoint,
        addresses = addresses.map { InetAddress.getByName(it) },
    )
