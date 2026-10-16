// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feeds.groups

import ch.lkmc.neutrodyne.feeds.text.nfc

/** The shared client/server group-name rules (05 Names). */
public object GroupNames {
    public const val MAX_CODE_POINTS: Int = 40

    public sealed interface Validation {
        public data class Valid(
            val name: String,
            val nameKey: String,
        ) : Validation

        public data object Empty : Validation

        public data class TooLong(
            val max: Int,
        ) : Validation
    }

    public fun validate(input: String): Validation {
        val name = normalize(input)
        if (name.isEmpty()) return Validation.Empty

        var count = 0
        var index = 0
        while (index < name.length) {
            val char = name[index++]
            if (char.isHighSurrogate() && index < name.length && name[index].isLowSurrogate()) index++
            if (++count > MAX_CODE_POINTS) return Validation.TooLong(MAX_CODE_POINTS)
        }
        return Validation.Valid(name, name.nfc().lowercase().nfc())
    }

    public fun suggestsOldestFirst(name: String): Boolean {
        val key = normalize(name).lowercase().nfc()
        return oldestFirstWords.any { it in key }
    }

    private fun normalize(input: String): String =
        buildString {
            var spacePending = false
            for (char in input.nfc()) {
                if (char in '\u202A'..'\u202E' || char in '\u2066'..'\u2069') continue
                if (char.category == CharCategory.CONTROL || char.isWhitespace() ||
                    char == '\u00A0' || char == '\u2007' || char == '\u202F'
                ) {
                    spacePending = isNotEmpty()
                    continue
                }
                if (spacePending) append(' ')
                append(char)
                spacePending = false
            }
        }

    private val oldestFirstWords =
        listOf(
            "fiction",
            "audiobook",
            "audio book",
            "drama",
            "serial",
            "story",
            "stories",
            "novel",
            "hörbuch",
            "hörspiel",
        )
}
