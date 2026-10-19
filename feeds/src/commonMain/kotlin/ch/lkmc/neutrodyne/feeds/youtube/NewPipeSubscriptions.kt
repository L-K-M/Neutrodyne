// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.youtube

import ch.lkmc.neutrodyne.feeds.importing.ImportDocument
import ch.lkmc.neutrodyne.feeds.importing.ImportEntry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.BufferedSink
import okio.Source

/** Format interoperability only; no NewPipe implementation code or dependencies. */
public object NewPipeSubscriptions {
    public fun parse(source: Source): ImportDocument {
        val raw = boundedJson(source)
        val doc =
            try {
                json.decodeFromString<Document>(raw)
            } catch (e: ImportParseException) {
                throw e
            } catch (_: Exception) {
                throw ImportParseException(ImportParseException.Reason.MALFORMED)
            }
        checkItemCount(doc.subscriptions.size)
        return ImportDocument(
            "NEWPIPE_JSON",
            doc.subscriptions.mapIndexed { index, row ->
                ImportEntry(
                    index,
                    row.url,
                    row.name,
                    errorDetail =
                        if (row.serviceId ==
                            0
                        ) {
                            null
                        } else {
                            "newpipe_service:${row.serviceId}"
                        },
                )
            },
            emptyList(),
        )
    }

    public fun write(
        sink: BufferedSink,
        versionName: String,
        versionCode: Int,
        channels: List<Channel>,
    ) {
        val doc =
            Document(
                versionName,
                versionCode,
                channels.map {
                    require(CHANNEL_ID.matches(it.id))
                    Subscription(YOUTUBE_SERVICE, "https://www.youtube.com/channel/${it.id}", it.name)
                },
            )
        sink.writeUtf8(json.encodeToString(doc))
    }

    public data class Channel(
        val id: String,
        val name: String,
    )

    @Serializable
    private data class Document(
        @SerialName("app_version") val version: String = "",
        @SerialName("app_version_int") val versionInt: Int = 0,
        @Serializable(with = SubscriptionsSerializer::class) val subscriptions: List<Subscription>,
    )

    @Serializable
    private data class Subscription(
        @SerialName("service_id") val serviceId: Int,
        val url: String = "",
        val name: String? = null,
    )

    private object SubscriptionsSerializer : CappedListSerializer<Subscription>(Subscription.serializer())

    private const val YOUTUBE_SERVICE = 0
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
}
