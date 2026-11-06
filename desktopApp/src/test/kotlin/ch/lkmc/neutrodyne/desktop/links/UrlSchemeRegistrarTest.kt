// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.links

import ch.lkmc.neutrodyne.desktop.system.WindowsRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 11's Windows URL-scheme rows against a map-backed registry (the logic is the same off
 * Windows; the nightly windows-2025 job exercises the real `HKCU`):
 * `neutrodyne` is written at every start; a foreign `feed` handler is never overwritten;
 * one of ours is updated to the current launcher; the explicit takeover overwrites all
 * four podcast schemes.
 */
class UrlSchemeRegistrarTest {
    private val launcher = """C:\Users\alex\AppData\Local\Programs\Neutrodyne\Neutrodyne.exe"""
    private val registry = MapWindowsRegistry()

    private val registrar = UrlSchemeRegistrar(registry, launcher)

    @Test
    fun `registerAtStart writes the neutrodyne scheme`() {
        registrar.registerAtStart()

        assertThat(registry.strings["Software\\Classes\\neutrodyne" to "URL Protocol"]).isEqualTo("")
        assertThat(
            registry.strings["Software\\Classes\\neutrodyne\\shell\\open\\command" to ""],
        ).isEqualTo("\"$launcher\" \"%1\"")
        assertThat(registry.strings["Software\\Classes\\neutrodyne\\DefaultIcon" to ""]).isNotEmpty()
    }

    @Test
    fun `a foreign feed handler is not overwritten`() {
        registry.writeString(
            "Software\\Classes\\feed\\shell\\open\\command",
            "",
            "\"C:\\Program Files\\OtherPodcast\\app.exe\" \"%1\"",
        )

        registrar.registerAtStart()

        assertThat(registry.strings["Software\\Classes\\feed\\shell\\open\\command" to ""])
            .isEqualTo("\"C:\\Program Files\\OtherPodcast\\app.exe\" \"%1\"")
        assertThat(registrar.ownsPodcastSchemes()).isFalse()
    }

    @Test
    fun `one of ours is updated to the current launcher`() {
        registry.writeString(
            "Software\\Classes\\podcast\\shell\\open\\command",
            "",
            "\"C:\\Old\\Neutrodyne.exe\" \"%1\"",
        )

        registrar.registerAtStart()

        assertThat(registry.strings["Software\\Classes\\podcast\\shell\\open\\command" to ""])
            .isEqualTo("\"$launcher\" \"%1\"")
    }

    @Test
    fun `an unclaimed podcast scheme is claimed at start`() {
        registrar.registerAtStart()

        for (scheme in listOf("feed", "podcast", "pcast", "itpc")) {
            assertThat(registry.strings["Software\\Classes\\$scheme\\shell\\open\\command" to ""])
                .isEqualTo("\"$launcher\" \"%1\"")
            assertThat(registry.strings["Software\\Classes\\$scheme" to "URL Protocol"]).isEqualTo("")
        }
        assertThat(registrar.ownsPodcastSchemes()).isTrue()
    }

    @Test
    fun `take over writes all four podcast schemes`() {
        registry.writeString(
            "Software\\Classes\\itpc\\shell\\open\\command",
            "",
            "\"C:\\Program Files\\OtherPodcast\\app.exe\" \"%1\"",
        )

        registrar.takeOverPodcastSchemes()

        assertThat(registry.strings["Software\\Classes\\itpc\\shell\\open\\command" to ""])
            .isEqualTo("\"$launcher\" \"%1\"")
        assertThat(registrar.ownsPodcastSchemes()).isTrue()
    }

    @Test
    fun `a dev run without a launcher writes nothing`() {
        val dev = UrlSchemeRegistrar(registry, launcher = null)

        dev.registerAtStart()
        dev.takeOverPodcastSchemes()

        assertThat(registry.strings).isEmpty()
        assertThat(dev.ownsPodcastSchemes()).isFalse()
    }

    private class MapWindowsRegistry : WindowsRegistry {
        val strings = mutableMapOf<Pair<String, String>, String>()
        val binary = mutableMapOf<Pair<String, String>, ByteArray>()

        override fun readString(
            keyPath: String,
            valueName: String,
        ): String? = strings[keyPath to valueName]

        override fun readBinary(
            keyPath: String,
            valueName: String,
        ): ByteArray? = binary[keyPath to valueName]

        override fun writeString(
            keyPath: String,
            valueName: String,
            value: String,
        ) {
            strings[keyPath to valueName] = value
        }

        override fun deleteValue(
            keyPath: String,
            valueName: String,
        ): Boolean = strings.remove(keyPath to valueName) != null
    }
}
