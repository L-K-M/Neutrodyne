// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.feeds.groups.GroupNames
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.sync.protocol.ChangeStatus
import ch.lkmc.neutrodyne.sync.protocol.ProtocolException
import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits
import ch.lkmc.neutrodyne.sync.protocol.ProtocolValidation
import ch.lkmc.neutrodyne.sync.protocol.RecordMerger
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncErrorCode
import ch.lkmc.neutrodyne.sync.protocol.ValidatedChange
import ch.lkmc.neutrodyne.sync.protocol.dto.AccountResetRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeResultDto
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncRequest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

internal class StoredRecordWriter(
    private val store: SyncStore,
    private val inputs: ServerInputs,
    private val revivalDays: Int = ServerDefaults.RETENTION_UNSUBSCRIBED_DAYS,
) : RecordWriter {
    init {
        require(revivalDays > 0)
    }

    override suspend fun push(
        principal: AccountPrincipal,
        request: SyncRequest,
    ): PushResult {
        if (request.changes.size > ProtocolLimits.MAX_CHANGES) {
            throw ProtocolException(SyncErrorCode.PAYLOAD_TOO_LARGE, "too many changes")
        }
        val now = inputs.wallMs()
        // Shared field validation/admission and domain normalization happen before the writer lock.
        val admitted =
            request.changes.map { dto ->
                try {
                    Result.success(ProtocolValidation.change(dto, now).also(::validateIdentity))
                } catch (failure: ProtocolException) {
                    Result.failure(failure)
                }
            }
        val (results, pending) =
            store.write(principal.accountId) {
                val after = account.cursor.validate(request.since)
                val firstSequence = account.cursor.seq
                val seed = maximumClock()
                var liveCount = records().count { !RecordMerger.isDead(it.raw) }.toLong()
                val extraKeys = linkedSetOf<RecordKey>()
                val results =
                    admitted.mapIndexed { index, admission ->
                        try {
                            val change = admission.getOrThrow()
                            val mutation = RecordChanges(this, inputs.clock(account, seed), now, principal.deviceId)
                            val changeExtras = linkedSetOf<RecordKey>()
                            val result = applyChange(mutation, change, index, now, changeExtras)
                            liveCount = mutation.commit(liveCount)
                            extraKeys += changeExtras
                            result
                        } catch (failure: ProtocolException) {
                            ChangeResultDto(index, ChangeStatus.REJECTED.wire, code = failure.code.wire)
                        }
                    }
                val finalView = RecordChanges(this, inputs.clock(account, seed), now, principal.deviceId)
                val extras = extraKeys.flatMap { key -> listOfNotNull(record(key), record(finalView.resolve(key))) }
                principal.deviceId?.let { acknowledge(it, request.since, now) }
                if (account.cursor.seq != firstSequence) {
                    emit(ServerEvent.Changed(principal.accountId, account.cursor.wire(), principal.deviceId))
                }
                results to candidates(after, extras)
            }
        return PushResult(results, pending.page(), now)
    }

    private fun applyChange(
        mutation: RecordChanges,
        change: ValidatedChange,
        index: Int,
        now: Long,
        changeExtras: MutableSet<RecordKey>,
    ): ChangeResultDto {
        val deduper = PodcastDeduper(mutation, now, revivalDays.toLong() * MILLIS_PER_DAY)
        return when (change) {
            is ValidatedChange.Rekey -> {
                val target = deduper.rekey(change.from, change.to)
                val source = RecordKey(SyncCollection.EPISODE, change.from)
                changeExtras += source
                changeExtras += target
                changeExtras += RecordKey(SyncCollection.UPNEXT, change.from)
                changeExtras += RecordKey(SyncCollection.UPNEXT, target.id)
                ChangeResultDto(index, ChangeStatus.MERGED.wire, target.id.toWire())
            }

            is ValidatedChange.Write -> {
                change.state.fields.values
                    .mapNotNull { it.c }
                    .plus(change.state.matchClocks.values)
                    .forEach(mutation.clock::receive)
                val original = RecordKey(change.state.coll, change.id)
                val target = mutation.resolve(original)
                val before = mutation.record(target)
                val merged = RecordMerger.merge(before?.raw, change.state)
                mutation.save(target, merged.record)
                val destination =
                    when (target.collection) {
                        SyncCollection.PODCAST -> {
                            deduper.lifecycle(target, before)
                            deduper.dedupe(target)
                        }

                        SyncCollection.GROUP -> {
                            val groups = GroupNameMerger(mutation)
                            groups.lifecycle(target, before)
                            groups.dedupe(target)
                        }

                        else -> {
                            target
                        }
                    }
                val survivor = mutation.resolve(destination)
                val status = if (survivor != original) ChangeStatus.MERGED else merged.status
                if (target != original) mutation.routeUnseen(original, target)
                if (status == ChangeStatus.STALE || status == ChangeStatus.MERGED) {
                    changeExtras += original
                    changeExtras += survivor
                }
                val redirect = survivor.id.toWire().takeIf { status == ChangeStatus.MERGED }
                ChangeResultDto(index, status.wire, redirect)
            }
        }
    }

    /** Pull acknowledgements use the applied request cursor, including when the returned page is empty. */
    suspend fun changes(
        principal: AccountPrincipal,
        since: String?,
    ): RecordPage =
        store
            .write(principal.accountId) {
                val after = account.cursor.validate(since)
                principal.deviceId?.let { acknowledge(it, since, inputs.wallMs()) }
                candidates(after)
            }.page()

    /** Purge/auth/backup deletion is owned by W6/W7; this service only prunes the staged library. */
    suspend fun resetLibrary(
        principal: AccountPrincipal,
        request: AccountResetRequest,
    ): String {
        if (request.confirm != "DELETE" || request.mode != "library") invalidChange("invalid library reset")
        val now = inputs.wallMs()
        return store.write(principal.accountId) {
            val cutoff = account.cursor.validate(request.beforeCursor, CursorPurpose.RESET)
            val firstSequence = account.cursor.seq
            val mutation = RecordChanges(this, inputs.clock(account, maximumClock()), now, principal.deviceId)
            // Snapshot eligibility before any write. Reset does not run unsubscribe cascades: a
            // newer queue item must survive even when its older parent falls inside the cutoff.
            records().filter { it.seq <= cutoff && !RecordMerger.isDead(it.raw) }.toList().forEach { record ->
                val removal =
                    when (record.key.collection) {
                        SyncCollection.PODCAST -> "subscribed" to JsonPrimitive(false)
                        SyncCollection.GROUP -> "deleted" to JsonPrimitive(true)
                        SyncCollection.MEMBER, SyncCollection.UPNEXT -> "in" to JsonPrimitive(false)
                        SyncCollection.SESSION -> "episode" to JsonNull
                        else -> null
                    }
                removal?.let { (name, value) -> mutation.stamp(record.key, name, value) }
            }
            mutation.commit(records().count { !RecordMerger.isDead(it.raw) }.toLong())
            if (account.cursor.seq != firstSequence) {
                emit(ServerEvent.Changed(principal.accountId, account.cursor.wire(), principal.deviceId))
            }
            account.cursor.wire()
        }
    }

    private fun validateIdentity(change: ValidatedChange) {
        if (change !is ValidatedChange.Write) return
        if (change.state.coll == SyncCollection.PODCAST) {
            change.state.fields["feedUrl"]?.v?.let {
                if (UrlNormalizer.forIdentity((it as JsonPrimitive).content) == null) invalidChange("invalid feed URL")
            }
        }
        if (change.state.coll == SyncCollection.GROUP) {
            change.state.fields["name"]?.v?.let {
                if (GroupNames.validate((it as JsonPrimitive).content) !is GroupNames.Validation.Valid) {
                    invalidChange("invalid group name")
                }
            }
        }
    }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
