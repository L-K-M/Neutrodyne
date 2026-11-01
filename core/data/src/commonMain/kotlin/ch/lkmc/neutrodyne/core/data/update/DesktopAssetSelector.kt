// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.update

internal fun numericOsVersion(value: String): List<Long>? {
    if (!Regex("[0-9]+(?:\\.[0-9]+)*").matches(value)) return null
    return value.split('.').map { it.toLongOrNull() ?: return null }
}

private fun compareOsVersions(
    left: List<Long>,
    right: List<Long>,
): Int {
    for (index in 0 until maxOf(left.size, right.size)) {
        val compared = (left.getOrNull(index) ?: 0).compareTo(right.getOrNull(index) ?: 0)
        if (compared != 0) return compared
    }
    return 0
}
