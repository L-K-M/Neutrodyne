// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

/**
 * Builds the app's User-Agent once at graph creation — `Neutrodyne/<versionName> (<platform>;
 * +<repoUrl>)`, e.g. `Neutrodyne/0.1.0 (Android 17; +https://github.com/L-K-M/Neutrodyne)`
 * (01 Networking baseline). Non-ASCII characters become `?` because OkHttp rejects them in
 * header values.
 */
class UserAgentProvider(
    info: PlatformInfo,
    versionName: String,
    repoUrl: String,
) {
    val value: String =
        "Neutrodyne/${versionName.asciiOnly()} (${info.userAgentPlatform.asciiOnly()}; +${repoUrl.asciiOnly()})"

    private fun String.asciiOnly(): String = map { if (it.code in 32..126) it else '?' }.joinToString("")
}
