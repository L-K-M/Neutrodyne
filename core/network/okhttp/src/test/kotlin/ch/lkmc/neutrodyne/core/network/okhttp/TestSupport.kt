// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network.okhttp

import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.LocalNetworkAccess
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.common.UserAgentProvider
import mockwebserver3.MockResponse
import okhttp3.Interceptor

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

internal fun testUserAgent(platform: PlatformInfo = fakePlatform()): UserAgentProvider =
    UserAgentProvider(platform, "0.1.0", "https://example.com/repo")

internal fun newCoreClients(
    access: LocalNetworkAccess = LocalNetworkAccess(),
    platform: PlatformInfo = fakePlatform(),
    hints: DnsFamilyHints = DnsFamilyHints(),
    debug: Set<Interceptor> = emptySet(),
): CoreClients =
    CoreClients(
        ua = UserAgentInterceptor(testUserAgent(platform)),
        lanGuard = LocalNetworkGuard(access, platform),
        hints = hints,
        debugInterceptors = debug,
    )

internal fun newNetworkClients(
    credentials: CredentialLookup = CredentialLookup.None,
    access: LocalNetworkAccess = LocalNetworkAccess(),
    platform: PlatformInfo = fakePlatform(),
    hints: DnsFamilyHints = DnsFamilyHints(),
): NetworkClients = NetworkClients(newCoreClients(access, platform, hints), AuthInterceptor(credentials))

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
