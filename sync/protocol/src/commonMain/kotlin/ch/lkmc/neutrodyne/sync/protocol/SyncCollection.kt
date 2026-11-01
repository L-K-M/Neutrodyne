// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.protocol

/** The complete v1 registry, independent of Room and client model classes. */
enum class FieldKind {
    LWW,
    LWW_POSITION,
    GROW_SET,
    MIN_FIELD,
    MAX_FIELD,
    TOMBSTONE,
    REDIRECT,
}

enum class ValueType {
    JSON,
    TEXT,
    URL,
    BOOLEAN,
    INTEGER,
    NUMBER,
    POSITION,
    AUTH,
    EPISODE_REF,
    CONTEXT,
    STRING_SET,
}

enum class Nullability {
    REQUIRED,
    NULLABLE,
}

data class FieldSpec(
    val name: String,
    val kind: FieldKind,
    val type: ValueType,
    val maxBytes: Int = ProtocolLimits.MAX_STRING,
    val nullability: Nullability = Nullability.REQUIRED,
    val min: Double? = null,
    val max: Double? = null,
    val choices: Set<String> = emptySet(),
)

enum class SyncCollection(
    val wire: String,
) {
    PODCAST("podcast"),
    GROUP("group"),
    MEMBER("member"),
    EPISODE("episode"),
    UPNEXT("upnext"),
    SESSION("session"),
    SETTING("setting"),
    ;

    val fields: Map<String, FieldSpec> get() = Registry.fields.getValue(this)

    companion object {
        fun fromWire(wire: String): SyncCollection =
            entries.firstOrNull { it.wire == wire }
                ?: throw ProtocolException(SyncErrorCode.UNKNOWN_COLLECTION, "unknown collection")
    }
}

private object Registry {
    private val orders = setOf("NEWEST_FIRST", "OLDEST_FIRST")
    private val media = setOf("ALL", "AUDIO", "VIDEO")
    private val overrides =
        listOf(
            FieldSpec(
                "s.playbackSpeed",
                FieldKind.LWW,
                ValueType.NUMBER,
                nullability = Nullability.NULLABLE,
                min = 0.5,
                max = 3.0,
            ),
            FieldSpec("s.skipSilence", FieldKind.LWW, ValueType.BOOLEAN, nullability = Nullability.NULLABLE),
            FieldSpec("s.boostDb", FieldKind.LWW, ValueType.NUMBER, nullability = Nullability.NULLABLE),
            FieldSpec("s.introSkipMs", FieldKind.LWW, ValueType.INTEGER, nullability = Nullability.NULLABLE, min = 0.0),
            FieldSpec("s.outroSkipMs", FieldKind.LWW, ValueType.INTEGER, nullability = Nullability.NULLABLE, min = 0.0),
        )

    private fun lww(
        name: String,
        type: ValueType,
        nullability: Nullability = Nullability.REQUIRED,
        cap: Int = ProtocolLimits.MAX_STRING,
        choices: Set<String> = emptySet(),
    ) = FieldSpec(name, FieldKind.LWW, type, cap, nullability, choices = choices)

    private fun tombstone(name: String) = FieldSpec(name, FieldKind.TOMBSTONE, ValueType.BOOLEAN)

    private fun minimum(name: String) = FieldSpec(name, FieldKind.MIN_FIELD, ValueType.INTEGER, min = 0.0)

    private fun maximum(
        name: String,
        nullability: Nullability = Nullability.REQUIRED,
    ) = FieldSpec(name, FieldKind.MAX_FIELD, ValueType.INTEGER, nullability = nullability, min = 0.0)

    private fun order() = lww("ok", ValueType.TEXT, cap = ProtocolLimits.MAX_ORDER_KEY)

    private fun specs(fields: List<FieldSpec>) = fields.associateBy { it.name }

    val fields: Map<SyncCollection, Map<String, FieldSpec>> =
        mapOf(
            SyncCollection.PODCAST to
                specs(
                    listOf(
                        lww("feedUrl", ValueType.URL),
                        FieldSpec("feedKeys", FieldKind.GROW_SET, ValueType.STRING_SET),
                        tombstone("subscribed"),
                        minimum("subscribedAt"),
                        lww(
                            "sourceType",
                            ValueType.TEXT,
                            choices = setOf("RSS", "YOUTUBE_CHANNEL", "YOUTUBE_PLAYLIST"),
                        ),
                        lww("youtubeChannelId", ValueType.TEXT, Nullability.NULLABLE),
                        lww("podcastGuid", ValueType.TEXT, Nullability.NULLABLE),
                        lww("title", ValueType.TEXT, cap = ProtocolLimits.MAX_TITLE),
                        lww("artworkUrl", ValueType.URL, Nullability.NULLABLE),
                        lww("link", ValueType.URL, Nullability.NULLABLE),
                        lww("customTitle", ValueType.TEXT, Nullability.NULLABLE, ProtocolLimits.MAX_TITLE),
                        lww("includeInAll", ValueType.BOOLEAN),
                        lww("episodeOrder", ValueType.TEXT, Nullability.NULLABLE, choices = orders),
                        FieldSpec("youtubeVariants", FieldKind.LWW, ValueType.INTEGER, min = 1.0, max = 7.0),
                        lww("needsCredentials", ValueType.BOOLEAN),
                        lww("credentialOrigin", ValueType.URL, Nullability.NULLABLE),
                        lww("auth", ValueType.AUTH),
                    ) + overrides,
                ),
            SyncCollection.GROUP to
                specs(
                    listOf(
                        lww("name", ValueType.TEXT, cap = ProtocolLimits.MAX_TITLE),
                        FieldSpec(
                            "colorArgb",
                            FieldKind.LWW,
                            ValueType.INTEGER,
                            nullability = Nullability.NULLABLE,
                            min = Int.MIN_VALUE.toDouble(),
                            max = Int.MAX_VALUE.toDouble(),
                        ),
                        lww("iconKey", ValueType.TEXT, Nullability.NULLABLE),
                        lww("feedOrder", ValueType.TEXT, choices = orders),
                        lww("playOrder", ValueType.TEXT, choices = orders),
                        FieldSpec("filterFlags", FieldKind.LWW, ValueType.INTEGER, min = 0.0, max = 7.0),
                        lww("mediaFilter", ValueType.TEXT, choices = media),
                        FieldSpec(
                            "hideOlderThanDays",
                            FieldKind.LWW,
                            ValueType.INTEGER,
                            nullability = Nullability.NULLABLE,
                            min = 1.0,
                        ),
                        lww("showAsTab", ValueType.BOOLEAN),
                        order(),
                        minimum("createdAt"),
                        tombstone("deleted"),
                    ) + overrides,
                ),
            SyncCollection.MEMBER to specs(listOf(tombstone("in"), order(), minimum("addedAt"))),
            SyncCollection.EPISODE to
                specs(
                    listOf(
                        FieldSpec("pos", FieldKind.LWW_POSITION, ValueType.POSITION),
                        lww("played", ValueType.BOOLEAN),
                        FieldSpec(
                            "playedAt",
                            FieldKind.LWW,
                            ValueType.INTEGER,
                            nullability = Nullability.NULLABLE,
                            min = 0.0,
                        ),
                        lww("fav", ValueType.BOOLEAN),
                        maximum("playCount"),
                        maximum("lastPlayedAt", Nullability.NULLABLE),
                        FieldSpec(
                            "measuredDurationMs",
                            FieldKind.LWW,
                            ValueType.INTEGER,
                            nullability = Nullability.NULLABLE,
                            min = 0.0,
                        ),
                    ),
                ),
            SyncCollection.UPNEXT to specs(listOf(tombstone("in"), order())),
            SyncCollection.SESSION to
                specs(
                    listOf(
                        lww("episode", ValueType.EPISODE_REF, Nullability.NULLABLE),
                        lww("context", ValueType.CONTEXT),
                    ),
                ),
            SyncCollection.SETTING to specs(listOf(lww("v", ValueType.JSON, Nullability.NULLABLE))),
        )
}
