// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

/** Which product is running — `PlatformInfo.kind` is how features ask "am I on desktop" (11). */
enum class PlatformKind { ANDROID, DESKTOP }

/**
 * Read-only facts about the host platform, bound `@SingleIn(AppScope::class)` by the shells
 * (01 Networking baseline). Implemented per-platform from `Build.*` on Android and
 * `System.getProperty` on desktop.
 */
interface PlatformInfo {
    val kind: PlatformKind

    /**
     * The `<platform>` segment of the app's User-Agent, e.g. `Android 17`,
     * `Windows 11; x64`, `macOS 15.1; arm64`, `Linux; x64`.
     */
    val userAgentPlatform: String

    /**
     * `Build.VERSION.SDK_INT` on Android, `null` on the desktop. `:core:network:okhttp`'s
     * `LocalNetworkGuard` reads it: the LAN guard engages only on Android API 37+ (01 Interceptors).
     */
    val androidSdkInt: Int?

    /**
     * ISO 3166-1 alpha-2 uppercase from the system locale (`DE`) — the `discover.country`
     * default (03) and Podcast Index `cc` hint. Derived, never a stored setting.
     */
    val regionCode: String
}
