// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import java.util.Properties

/**
 * The server version of the discovery document's `serverVersion` (10 Discovery): from the
 * `neutrodyne-server.properties` resource generated at build time from
 * `gradle.properties`' `neutrodyne.versionName` — no timestamps — then the fat JAR manifest,
 * then `dev` for plain `run` and tests.
 */
internal object ServerVersion {
    fun current(): String = fromResource() ?: fromManifest() ?: DEV_VERSION

    private fun fromResource(): String? =
        ServerVersion::class.java.getResourceAsStream(RESOURCE)?.use { stream ->
            Properties().apply { load(stream) }.getProperty(VERSION_KEY)?.let(::usableVersion)
        }

    private fun fromManifest(): String? =
        ServerVersion::class.java.`package`
            ?.implementationVersion
            ?.let(::usableVersion)

    /** Blank values and Gradle's `unspecified` (written when the version is unset) count as absent. */
    internal fun usableVersion(version: String): String? =
        version.takeIf { it.isNotBlank() && it != GRADLE_UNSPECIFIED }

    private const val RESOURCE = "/neutrodyne-server.properties"
    private const val VERSION_KEY = "serverVersion"
    private const val DEV_VERSION = "dev"
    private const val GRADLE_UNSPECIFIED = "unspecified"
}
