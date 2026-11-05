// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.LocalNetworkAccess
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.common.UserAgentProvider
import ch.lkmc.neutrodyne.core.network.okhttp.AuthInterceptor
import ch.lkmc.neutrodyne.core.network.okhttp.CoreClients
import ch.lkmc.neutrodyne.core.network.okhttp.DnsFamilyHints
import ch.lkmc.neutrodyne.core.network.okhttp.LocalNetworkGuard
import ch.lkmc.neutrodyne.core.network.okhttp.NetworkClients
import ch.lkmc.neutrodyne.core.network.okhttp.UserAgentInterceptor

internal fun testMediaClient() =
    NetworkClients(
        CoreClients(
            UserAgentInterceptor(UserAgentProvider(TestDesktopPlatform, "0.1.0", "https://example.com/repo")),
            LocalNetworkGuard(LocalNetworkAccess(), TestDesktopPlatform),
            DnsFamilyHints(),
            emptySet(),
        ),
        AuthInterceptor { CredentialLookup.None },
    ).media

private object TestDesktopPlatform : PlatformInfo {
    override val kind = PlatformKind.DESKTOP
    override val userAgentPlatform = "Linux; x64"
    override val androidSdkInt: Int? = null
    override val regionCode = "DE"
}
