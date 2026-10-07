// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.add

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.artwork.ArtworkKeys
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.data.ingest.FeedIngestor
import ch.lkmc.neutrodyne.core.data.ingest.IngestContext
import ch.lkmc.neutrodyne.core.data.ingest.IngestMode
import ch.lkmc.neutrodyne.core.data.ingest.IngestResult
import ch.lkmc.neutrodyne.core.data.ingest.IngestionEventBus
import ch.lkmc.neutrodyne.core.data.refresh.RefreshOrigin
import ch.lkmc.neutrodyne.core.data.refresh.RefreshScheduler
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupMemberEntity
import ch.lkmc.neutrodyne.core.database.PodcastUrlAliasEntity
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.AddPodcastResolver
import ch.lkmc.neutrodyne.core.domain.OrderKeys
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.SubscribeError
import ch.lkmc.neutrodyne.core.domain.SubscribeUseCase
import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.MemberSource
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.feeds.parse.FeedParser
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.uuid.Uuid

/**
 * The RSS subscribe transaction of 03 (one `withWriteTransaction`): dedupe inside it, insert the
 * podcast with a fresh `syncId`, the input/redirect aliases, the `INITIAL` ingest and the
 * requested memberships; post-commit it emits `NewEpisodes(initialFetch = true)`, schedules the
 * pending paging run and rebases the periodic tick.
 *
 * M1a deviations (2026-10-07): `SecretStore.put`/`CredentialCommitCoordinator` are M1b — pending
 * preview credentials are dropped and `credentialId` stays null until M1b lands; the artwork pin
 * request is M4.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class SubscribeUseCaseImpl(
    private val db: NeutrodyneDatabase,
    private val cache: PreviewCache,
    // Interface-typed: Metro's contribution providers can't expose the impl binding (Metro 1.4.5,
    // `@ExposeImplBinding` on an internal contributed class drops the interface provider). The
    // `resolveEntry` bridge downcasts below — `@SingleIn` guarantees the single binding is the impl.
    private val resolver: AddPodcastResolver,
    private val ingestor: FeedIngestor,
    private val eventBus: IngestionEventBus,
    private val scheduler: RefreshScheduler,
    private val settings: SettingsRepository,
    private val orderKeys: OrderKeys,
    private val clock: Clock,
) : SubscribeUseCase {
    override suspend fun invoke(
        previewId: String,
        groupIds: Set<Long>,
    ): Outcome<Long, SubscribeError> {
        // Step 1: the cached entry, or a re-fetch of the preview URL when it expired.
        val entry =
            cache.get(previewId)
                ?: when (val r = (resolver as AddPodcastResolverImpl).resolveEntry(previewId)) {
                    is Outcome.Success -> r.value
                    is Outcome.Failure -> return Outcome.Failure(SubscribeError.Fetch(r.error))
                }

        val feed = entry.feed
        if (feed.items.isNotEmpty() &&
            feed.items.none { it.primaryEnclosure != null || it.externalMediaId != null }
        ) {
            return Outcome.Failure(SubscribeError.NoMedia)
        }
        // Bug guard (03 Host recognition): a preview must never resolve to a YouTube feed.
        if (HostChecks.isYouTube(entry.meta.finalUrl)) {
            return Outcome.Failure(SubscribeError.Fetch(AddPodcastError.InvalidUrl))
        }

        val now = clock.now()
        val feedUrl = entry.meta.permanentUrl ?: entry.meta.finalUrl
        val feedKey = UrlNormalizer.forIdentity(feedUrl) ?: return Outcome.Failure(SubscribeError.Storage)
        val inputKey = UrlNormalizer.forIdentity(entry.inputUrl)
        val hopKeys = entry.hops.mapNotNull { UrlNormalizer.forIdentity(it.url) }
        val backfill = settings.get(FeedsSettingKeys.BACKFILL_PAGED_FEEDS)
        val link = feed.paging.next ?: feed.paging.prevArchive
        val artworkUrl = feed.artwork.firstOrNull()?.url

        var pendingPaging = false
        val result =
            suspendRunCatching {
                db.withWriteTransaction<TxOutcome> {
                    val dao = db.podcastDao()

                    // Step 3.1: dedupe again inside the transaction (03 Subscribe).
                    var existing = dao.byFeedKey(feedKey)?.id ?: inputKey?.let { dao.aliasOwner(it) }
                    var hopIndex = 0
                    while (existing == null && hopIndex < hopKeys.size) {
                        existing = dao.aliasOwner(hopKeys[hopIndex++])
                    }
                    if (existing != null) return@withWriteTransaction TxOutcome.Duplicate(existing)

                    // Step 3.2: the podcast row; paging columns per `feeds.backfill_paged_feeds`.
                    pendingPaging = backfill && link != null
                    val id =
                        dao.insertPodcast(
                            PodcastEntity(
                                syncId = Uuid.random().toString(),
                                sourceType = SourceType.RSS,
                                feedUrl = feedUrl,
                                feedKey = feedKey,
                                title = feed.title?.takeUnless { it.isBlank() } ?: feedUrl,
                                artworkUrl = artworkUrl,
                                artworkKey =
                                    artworkUrl?.let(ArtworkKeys::forUrl) ?: ArtworkKeys.monogram(feedKey),
                                status = PodcastStatus.ACTIVE,
                                initialFetch = false,
                                subscribedAt = now,
                                lastAttemptAt = now,
                                lastSuccessAt = now,
                                // "pending" / "not wanted" / "nothing older" (03 RFC 5005 paging).
                                pagingNextUrl = link,
                                pagingComplete = link == null || !backfill,
                            ),
                        )

                    // Step 3.3: the normalised input plus every redirect hop that differs from feedKey.
                    val aliases =
                        buildList {
                            if (inputKey != null && inputKey != feedKey) {
                                add(PodcastUrlAliasEntity(inputKey, id, AliasReason.SUBSCRIBE_INPUT, now))
                            }
                            for (key in hopKeys) {
                                if (key != feedKey && key != inputKey) {
                                    add(PodcastUrlAliasEntity(key, id, AliasReason.REDIRECT, now))
                                }
                            }
                        }
                    if (aliases.isNotEmpty()) dao.insertAliases(aliases)

                    // Step 3.4: the initial ingest writes feed metadata, validators and scheduling.
                    val dueFeed = dao.dueFeedById(id) ?: return@withWriteTransaction TxOutcome.Missing
                    val ingest =
                        ingestor.ingestInTransaction(
                            podcast = dueFeed,
                            parsed = feed,
                            ctx =
                                IngestContext(
                                    mode = IngestMode.INITIAL,
                                    partial = false,
                                    fetch = entry.meta,
                                ),
                        )

                    // Step 3.5: requested group memberships (`OrderKey.after` per group).
                    for (groupId in groupIds) {
                        db
                            .groupDao()
                            .insertMember(
                                PodcastGroupMemberEntity(
                                    groupId = groupId,
                                    podcastId = id,
                                    orderKey =
                                        orderKeys.after(db.groupDao().lastMemberOrderKey(groupId)),
                                    addedAt = now,
                                    source = MemberSource.MANUAL,
                                ),
                            )
                    }
                    TxOutcome.Inserted(id, ingest)
                }
            }.getOrElse { e ->
                // A `feedKey`/alias unique violation races step 3.1's dedupe (03 Subscribe); any
                // other database failure maps to `Storage`.
                Log.w(TAG, e) { "Subscribe transaction failed" }
                val owner =
                    db.podcastDao().byFeedKey(feedKey)?.id
                        ?: inputKey?.let { db.podcastDao().aliasOwner(it) }
                if (owner != null) return Outcome.Failure(SubscribeError.AlreadySubscribed(owner))
                return Outcome.Failure(SubscribeError.Storage)
            }

        val (podcastId, ingest) =
            when (result) {
                is TxOutcome.Duplicate -> {
                    return Outcome.Failure(SubscribeError.AlreadySubscribed(result.podcastId))
                }

                is TxOutcome.Inserted -> {
                    result.podcastId to result.ingest
                }

                TxOutcome.Missing -> {
                    return Outcome.Failure(SubscribeError.Storage)
                }
            }

        // Step 4: the initial-fetch event, the pending paging run, the rebase, the cache drop.
        eventBus.emit(podcastId, ingest.inserted, initialFetch = true)
        if (pendingPaging) {
            scheduler.enqueueNow(
                scope = RefreshScope.Podcasts(listOf(podcastId)),
                force = false,
                pagesOnly = true,
                origin = RefreshOrigin.SUBSCRIBE,
            )
        }
        scheduler.reschedulePeriodic()
        cache.remove(previewId)
        return Outcome.Success(podcastId)
    }

    private sealed interface TxOutcome {
        /** Step 3.1's in-transaction dedupe hit (a feedKey or alias race → `AlreadySubscribed`). */
        data class Duplicate(
            val podcastId: Long,
        ) : TxOutcome

        data class Inserted(
            val podcastId: Long,
            val ingest: IngestResult,
        ) : TxOutcome

        /** The just-inserted row did not read back — treated as `Storage`. */
        data object Missing : TxOutcome
    }

    private companion object {
        const val TAG = "SubscribeUseCase"
    }
}
