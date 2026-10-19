// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto
import ch.lkmc.neutrodyne.sync.protocol.dto.FieldValue
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncRequest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

internal class WriterFixture {
    val store = FakeSyncStore().apply { accounts[1] = FakeSyncTransaction() }
    var now = 1_791_072_000_000L
    val inputs = ServerInputs({ now }, { ByteArray(it) })
    val writer = StoredRecordWriter(store, inputs)
    val principal = AccountPrincipal(1, AccountRole.USER, TokenKind.DEVICE, "hash", DEVICE)

    fun clock(
        offset: Long = 0,
        counter: Long = 0,
    ): Hlc = Hlc.of(now + offset, counter, NodeId("1111111111111111"))

    suspend fun push(
        vararg changes: ChangeDto,
        since: String? = null,
    ): PushResult = writer.push(principal, SyncRequest(since, changes.toList()))

    suspend fun record(key: RecordKey): StoredRecord = store.read(1) { checkNotNull(record(key)) }

    suspend fun raw(key: RecordKey): RecordState = record(key).raw

    suspend fun cursor(): String = store.read(1) { account.cursor.wire() }

    suspend fun seed(
        key: RecordKey,
        raw: RecordState,
        seq: Long? = null,
        deadAt: Long? = null,
    ) {
        store.write(1) {
            val sequence = seq ?: nextSequence()
            put(StoredRecord(key, raw, sequence, DEVICE, now, deadAt))
            if (sequence > account.cursor.seq) setAccount(account.copy(cursor = account.cursor.copy(seq = sequence)))
            when (key.collection) {
                SyncCollection.PODCAST -> setPodcastKeys(key, RecordChanges.podcastKeys(raw))
                SyncCollection.GROUP -> setGroupName(key, RecordChanges.groupName(raw))
                else -> Unit
            }
        }
    }

    fun change(
        key: RecordKey,
        fields: Map<String, JsonElement>,
        at: Hlc = clock(),
    ): ChangeDto =
        ChangeDto(
            key.collection.wire,
            key.id.toWire(),
            fields =
                fields.mapValues { (_, v) ->
                    FieldValue(v, at.toString())
                },
        )

    fun write(
        key: RecordKey,
        name: String,
        value: JsonElement,
        at: Hlc = clock(),
    ): ChangeDto = change(key, mapOf(name to value), at)

    fun podcast(
        id: String = A,
        url: String = "https://example.com/feed",
        subscribedAt: Long = 1,
        at: Hlc = clock(),
    ): ChangeDto =
        change(
            key(SyncCollection.PODCAST, id),
            mapOf(
                "feedUrl" to JsonPrimitive(url),
                "subscribed" to JsonPrimitive(true),
            ),
            at,
        ).let { it.copy(fields = it.fields + ("subscribedAt" to FieldValue(JsonPrimitive(subscribedAt)))) }

    fun group(
        id: String = G,
        name: String = "News",
        createdAt: Long = 1,
        at: Hlc = clock(),
    ): ChangeDto =
        change(
            key(SyncCollection.GROUP, id),
            mapOf("name" to JsonPrimitive(name), "deleted" to JsonPrimitive(false)),
            at,
        ).let { it.copy(fields = it.fields + ("createdAt" to FieldValue(JsonPrimitive(createdAt)))) }

    fun state(
        key: RecordKey,
        name: String,
        value: JsonElement,
        at: Hlc = clock(),
    ): RecordState = RecordState(key.collection, mapOf(name to FieldState(value, at)))
}

internal const val A = "11111111-1111-4111-8111-111111111111"
internal const val B = "22222222-2222-4222-8222-222222222222"
internal const val C = "33333333-3333-4333-8333-333333333333"
internal const val G = "44444444-4444-4444-8444-444444444444"
internal const val H = "55555555-5555-4555-8555-555555555555"
internal const val DEVICE = "66666666-6666-4666-8666-666666666666"

internal fun key(
    coll: SyncCollection,
    rid: String,
): RecordKey = RecordKey(coll, RecordId.fromRid(coll, rid))

internal fun episode(
    podcast: String = A,
    episode: String = "g:ep",
    coll: SyncCollection = SyncCollection.EPISODE,
): RecordKey = key(coll, podcast + episode)

internal fun member(
    group: String = G,
    podcast: String = A,
): RecordKey = key(SyncCollection.MEMBER, group + podcast)

internal val podcastA = key(SyncCollection.PODCAST, A)
internal val groupG = key(SyncCollection.GROUP, G)
internal val session = key(SyncCollection.SESSION, "current")
internal val setting = key(SyncCollection.SETTING, "playback.speed")
