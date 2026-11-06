// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.links

import ch.lkmc.neutrodyne.desktop.system.WindowsRegistry

/**
 * 11's Windows URL-scheme registration at run time: jpackage has no URL-scheme option
 * and the MSI's `main.wxs` may not be copied into this repository (D3), so every start of
 * a packaged build writes the per-user keys under `HKCU\Software\Classes\<scheme>`
 * (`URL Protocol` = "", `DefaultIcon`, `shell\open\command` = `"<launcher>" "%1"`).
 *
 * `neutrodyne` is always (re)written. The four podcast schemes are written only when the
 * key is absent or its command already names a `Neutrodyne.exe`, so another podcast
 * app's registration is never taken over silently; [takeOverPodcastSchemes] is the
 * Settings row's explicit "Open podcast links with Neutrodyne" that takes them over on
 * request. The keys survive an uninstall by design (11 Uninstall and data retention).
 * The registry itself stays behind the [WindowsRegistry] seam, so the logic is
 * unit-testable off Windows.
 */
class UrlSchemeRegistrar(
    private val registry: WindowsRegistry,
    private val launcher: String?,
) {
    /** Startup: `neutrodyne` always, the podcast schemes only when unclaimed or already ours. */
    fun registerAtStart() {
        writeScheme(NEUTRODYNE_SCHEME)
        for (scheme in PODCAST_SCHEMES) {
            if (unclaimedOrOurs(scheme)) writeScheme(scheme)
        }
    }

    /** The Settings row's explicit takeover of the four podcast schemes. */
    fun takeOverPodcastSchemes() {
        for (scheme in PODCAST_SCHEMES) writeScheme(scheme)
    }

    /** Whether every podcast scheme's command names this app's launcher. */
    fun ownsPodcastSchemes(): Boolean = PODCAST_SCHEMES.all(::ours)

    private fun unclaimedOrOurs(scheme: String): Boolean =
        registry.readString(commandKey(scheme), DEFAULT_VALUE) == null || ours(scheme)

    private fun ours(scheme: String): Boolean =
        registry
            .readString(commandKey(scheme), DEFAULT_VALUE)
            ?.contains(EXE_NAME, ignoreCase = true) == true

    private fun writeScheme(scheme: String) {
        val path = launcher ?: return // a dev run without jpackage.app-path writes nothing
        registry.writeString(classesKey(scheme), URL_PROTOCOL_VALUE, "")
        registry.writeString(defaultIconKey(scheme), DEFAULT_VALUE, "\"$path\",$ICON_INDEX")
        registry.writeString(commandKey(scheme), DEFAULT_VALUE, "\"$path\" \"%$ARG_PLACEHOLDER\"")
    }

    private fun classesKey(scheme: String) = "$CLASSES_ROOT\\$scheme"

    private fun defaultIconKey(scheme: String) = "${classesKey(scheme)}\\$DEFAULT_ICON_KEY"

    private fun commandKey(scheme: String) = "${classesKey(scheme)}\\shell\\open\\command"

    companion object {
        const val NEUTRODYNE_SCHEME = "neutrodyne"
        const val PODCAST_SCHEME_FEED = "feed"
        const val PODCAST_SCHEME_PODCAST = "podcast"
        const val PODCAST_SCHEME_PCAST = "pcast"
        const val PODCAST_SCHEME_ITPC = "itpc"
        val PODCAST_SCHEMES =
            listOf(PODCAST_SCHEME_FEED, PODCAST_SCHEME_PODCAST, PODCAST_SCHEME_PCAST, PODCAST_SCHEME_ITPC)

        private const val CLASSES_ROOT = "Software\\Classes"
        private const val URL_PROTOCOL_VALUE = "URL Protocol"
        private const val DEFAULT_ICON_KEY = "DefaultIcon"
        private const val DEFAULT_VALUE = ""
        private const val EXE_NAME = "neutrodyne.exe"
        private const val ARG_PLACEHOLDER = "1"
        private const val ICON_INDEX = 0
    }
}
