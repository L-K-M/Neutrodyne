// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.update

/** D63's normal-release scheme, shared with release.sh and verify-tag.sh. */
internal object VersionScheme {
    private val version = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]?)\\.(0|[1-9][0-9]?)")
    private const val MAJOR_WEIGHT = 1_000_000L
    private const val MINOR_WEIGHT = 10_000L
    private const val PATCH_WEIGHT = 100L
    private const val RELEASE_SUFFIX = 95L

    fun versionCode(name: String): Long? {
        val parts = version.matchEntire(name)?.groupValues ?: return null
        val major = parts[1].toLongOrNull() ?: return null
        val tail = parts[2].toLong() * MINOR_WEIGHT + parts[3].toLong() * PATCH_WEIGHT + RELEASE_SUFFIX
        if (major > (Long.MAX_VALUE - tail) / MAJOR_WEIGHT) return null

        return major * MAJOR_WEIGHT + tail
    }
}
