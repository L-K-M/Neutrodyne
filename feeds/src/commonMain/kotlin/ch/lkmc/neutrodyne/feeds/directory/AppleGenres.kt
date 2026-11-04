// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feeds.directory

import ch.lkmc.neutrodyne.feeds.text.nfc

/** Apple's stable navigation IDs, independent of client enums/localized display labels. */
object AppleGenres {
    fun idForGroupName(name: String): String? =
        names[
            name
                .trim()
                .nfc()
                .lowercase()
                .nfc(),
        ]

    private val names =
        """
        1318=tech|technology|technik
        1489=news|nachrichten|actualités|noticias
        1483=fiction|stories|audio drama|hörspiel
        1303=comedy|humour|humor
        1533=science|wissenschaft
        1487=history|geschichte
        1545=sport|sports
        1488=true crime|crime
        1321=business|wirtschaft
        1301=arts|kunst
        1304=education|bildung
        1512=health & fitness|health and fitness|gesundheit und fitness|gesundheit & fitness
        1305=kids & family|kids and family|kinder und familie|kinder & familie
        1310=music|musik
        1324=society & culture|society and culture|gesellschaft und kultur|gesellschaft & kultur
        1309=tv & film|tv and film|tv und film
        1314=religion|religion & spirituality|religion und spiritualität|religion & spiritualität
        1511=government|regierung
        1502=leisure|freizeit
        """.trimIndent()
            .lines()
            .flatMap { line ->
                val (id, synonyms) = line.split('=', limit = 2)
                synonyms.split('|').map { it to id }
            }.toMap()
}
