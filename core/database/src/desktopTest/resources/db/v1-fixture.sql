-- SPDX-License-Identifier: Unlicense
-- Version-1 fixture rows for MigrateAllTest and RebuildProcedureTest (02 Testing): every table
-- gets a row, including the edge values the spec asks for — null optionals, a 1 ms position,
-- emoji and a linked sync_state with outbox, clock, parked and held rows. Inserted into the
-- schema that MigrationTestHelper.createDatabase(1) builds from the frozen 1.json, so this file
-- must never create or alter tables.

INSERT INTO credential(id, origin, username, secretCipher, iv, createdAt)
VALUES (1, 'feeds.example.com', 'alice', X'01020304', X'05060708', 1700000000000);

INSERT INTO podcast(
    id, syncId, sourceType, feedUrl, feedKey, youtubeChannelId, youtubeVariants,
    channelMetadataAt, podcastGuid, podcastGuidDerived, title, author, link, language,
    explicit, showType, medium, locked, complete, artworkUrl, artworkKey, bannerUrl,
    customTitle, includeInAll, episodeOrder, autoDownloadEligibleAfter, status,
    initialFetch, subscribedAt, latestEpisodeAt, etag, lastModified, contentSha256,
    parserVersion, lastParseOk, lastAttemptAt, lastSuccessAt, lastFullFetchAt,
    nextRefreshAt, failureCount, lastErrorKind, lastErrorDetail, gone, needsCredentials,
    ttlMinutes, updateFrequencyRrule, pendingNewFeedUrl, pagingNextUrl, pagingComplete,
    hubUrl, usesPodping, credentialId, descriptionHtml, categoriesJson
) VALUES (
    1, 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'RSS',
    'https://feeds.example.com/show.xml', 'https://feeds.example.com/show.xml',
    NULL, 1, NULL, 'podcast-guid-1', 0,
    'Fixture Show 🎙️', 'Fixture Author', 'https://example.com', 'en',
    0, 'EPISODIC', 'podcast', 0, 0, 'https://example.com/cover.jpg', 'u-cover-1', NULL,
    'My Show', 1, 'OLDEST_FIRST', NULL,
    'ACTIVE', 0, 1699999999999, 1700000000000, 'etag-1', 'Mon, 01 Jan 2026', 'sha-1',
    3, 1, 1700000000001, 1700000000002, 1700000000003,
    1700001000000, 0, NULL, NULL, 0, 0,
    60, NULL, NULL, 'https://feeds.example.com/show.xml?page=2', 0,
    'https://hub.example.com', 1, 1,
    '<p>Shownotes with emoji 🎧</p>', '[["Technology"],["Society & Culture","Documentary"]]'
);

INSERT INTO podcast(id, syncId, sourceType, feedUrl, feedKey, title, artworkKey, status,
                    initialFetch, subscribedAt)
VALUES (2, 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'YOUTUBE_CHANNEL',
        'https://www.youtube.com/feeds/videos.xml?channel_id=UCfixture',
        'yt:channel:UCfixture', 'Fixture Channel', 'u-cover-2', 'PENDING_FIRST_FETCH', 1,
        1700000000004);

INSERT INTO podcast_url_alias(url, podcastId, reason, addedAt)
VALUES ('https://feeds.example.com/old.xml', 1, 'REDIRECT', 1700000000005);

INSERT INTO podcast_settings(podcastId, playbackSpeed, skipSilence, deleteAfterPlayed)
VALUES (1, 1.5, 1, 'AFTER_24H');

INSERT INTO podcast_group(
    id, uuid, name, nameKey, orderKey, colorArgb, iconKey, kind, feedOrder, playOrder,
    filterFlags, mediaFilter, hideOlderThanDays, showAsTab, lastViewedAt, createdAt,
    updatedAt, ruleJson
) VALUES (
    1, 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', 'Tech', 'tech', 'a0', -16776961, 'icon-tech',
    'MANUAL', 'NEWEST_FIRST', 'NEWEST_FIRST', 0, 'ALL', NULL, 1, NULL, 1700000000006,
    1700000000006, NULL
);

INSERT INTO podcast_group_member(groupId, podcastId, orderKey, addedAt, source)
VALUES (1, 1, 'a0', 1700000000007, 'MANUAL');

INSERT INTO podcast_group_settings(groupId, playbackSpeed)
VALUES (1, 2.0);

-- Two episodes: the second exercises null optionals and the odd enum storage values.
INSERT INTO episode(
    id, podcastId, identityKey, guid, title, pubDate, rawPubDate, sortDate, feedOrder,
    firstSeenAt, lastSeenAt, inFeed, isNew, enclosureUrl, enclosureType, enclosureLength,
    externalMediaId, isVideo, durationMs, season, seasonName, episodeNumber,
    episodeDisplay, episodeType, explicit, imageUrl, artworkKey, link, chaptersUrl,
    chaptersType, contentHash, availability, isShort, snippet
) VALUES (
    1, 1, 'identity-1', 'guid-1', 'Episode One 😀', 1700000001000, 'Wed, 01 Jan 2026',
    1700000001000, 0, 1700000001001, 1700000001002, 1, 1,
    'https://cdn.example.com/ep1.mp3', 'audio/mpeg', 48000000,
    NULL, 0, 1800000, 2, 'Season 2', '12',
    '12', 'FULL', 0, 'https://example.com/ep1.jpg', 'u-ep-1', 'https://example.com/ep1',
    'https://example.com/ep1-chapters.json', 'application/json+chapters',
    1234567890, 'AVAILABLE', 0, 'The first episode.'
), (
    2, 1, 'identity-2', NULL, 'Episode Two', NULL, NULL,
    1700000002000, 1, 1700000002001, 1700000002002, 1, 0,
    NULL, NULL, NULL,
    'yt-video-id-2', 1, NULL, NULL, NULL, NULL,
    NULL, 'TRAILER', NULL, NULL, NULL, NULL,
    NULL, NULL,
    -99, 'MEMBERS_ONLY', 1, NULL
);

INSERT INTO episode_description(episodeId, html)
VALUES (1, X'01789C');

INSERT INTO episode_transcript(episodeId, url, type, language, rel)
VALUES (1, 'https://example.com/ep1.vtt', 'text/vtt', 'en', 'captions');

INSERT INTO episode_alt_enclosure(
    episodeId, ordinal, type, length, bitrate, height, lang, title, rel, codecs,
    isDefault, integrityType, integrityValue, sourcesJson
) VALUES (
    1, 0, 'audio/mpeg', 48000000, 128, NULL, 'en', 'Low bitrate', 'alternate',
    'mp3', 1, 'sri', 'sha256-abc', '[{"uri":"https://cdn2.example.com/ep1.mp3"}]'
);

INSERT INTO person(id, ownerType, ownerId, name, role, grp, imageUrl, href)
VALUES (1, 'PODCAST', 1, 'Fixture Host', 'host', 'cast', NULL, 'https://example.com/host'),
       (2, 'EPISODE', 1, 'Fixture Guest', 'guest', 'cast', NULL, NULL);

INSERT INTO funding(id, ownerType, ownerId, url, label)
VALUES (1, 'PODCAST', 1, 'https://example.com/support', 'Support us');

INSERT INTO chapter(episodeId, source, ordinal, startMs, endMs, title, imageUrl, linkUrl, hidden)
VALUES (1, 'PSC', 0, 0, 60000, 'Intro', NULL, NULL, 0),
       (1, 'PSC', 1, 60000, NULL, 'Main segment 🎯', 'https://example.com/ch.jpg',
        'https://example.com/seg', 0);

INSERT INTO episode_state(
    episodeId, startedAt, playedAt, playCount, lastPlayedAt, isFavorite,
    downloadDismissedAt, measuredDurationMs, updatedAt
) VALUES (1, 1700000003000, NULL, 0, NULL, 1, NULL, 1799500, 1700000003001);

-- The 1 ms position of the fixture spec.
INSERT INTO episode_position(episodeId, positionMs, durationMs, positionSource, updatedAt)
VALUES (1, 1, 1800000, 'STREAM', 1700000003002);

INSERT INTO queue_entry(id, episodeId, orderKey, addedAt)
VALUES (1, 1, 'a0', 1700000003003);

INSERT INTO play_session(
    id, currentEpisodeId, contextType, contextId, contextOrder, contextFilterFlags,
    contextMediaFilter, contextMinSortDate, contextAnchorEpisodeId, contextAnchorSortDate,
    generation, updatedAt
) VALUES (0, 1, 'PODCAST', 1, 'NEWEST_FIRST', 0, 'ALL', NULL, 1, 1700000001000, 0, 1700000003004);

INSERT INTO download(
    episodeId, lane, state, waitReason, priority, requestedAt, sourceKind, sourceRef,
    formatPref, resolvedItag, rootId, tempPath, relativePath, finalUri, totalBytes,
    downloadedBytes, estimatedBytes, etag, lastModified, mimeType, allowMetered,
    requireCharging, attempt, integrityFailures, nextAttemptAt, lastError, lastHttpStatus,
    lastStopReason, completedAt, runnerToken
) VALUES (
    1, 'MANUAL', 'COMPLETED', 'NONE', 100, 1700000004000, 'RSS_ENCLOSURE',
    'https://cdn.example.com/ep1.mp3',
    NULL, NULL, 'int', NULL, 'Show/ep1.mp3', 'file:///downloads/Show/ep1.mp3', 48000000,
    48000000, NULL, 'etag-dl-1', NULL, 'audio/mpeg', 0,
    0, 0, 0, NULL, NULL, NULL,
    NULL, 1700000004001, 'token-1'
);

INSERT INTO artwork(
    key, url, localPath, width, height, seedArgb, avgArgb, version, fetchedAt, pinCount,
    lastError
) VALUES ('u-cover-1', 'https://example.com/cover.jpg', 'artwork/u-cover-1', 300, 300,
          -16776961, -8355712, 4, 1700000005000, 1, NULL);

INSERT INTO import_session(
    id, createdAt, finishedAt, sourceName, sourceFormat, state, recoveredBySalvage,
    payloadPath, optionsJson, warningsJson
) VALUES (1, 1700000006000, 1700000006001, 'subs.opml', 'OPML', 'DONE', 0,
          'import/1.bin', '{"merge":true}', NULL);

INSERT INTO import_item(
    sessionId, ordinal, title, originalUrl, normalizedUrl, kind, groupNamesJson, selected,
    status, podcastId, errorDetail
) VALUES (1, 0, 'Fixture Show', 'https://feeds.example.com/show.xml',
          'https://feeds.example.com/show.xml', 'RSS', '["Tech"]', 1,
          'SUBSCRIBED', 1, NULL);

-- A linked state: enabled, with node id and outbox/clock/parked/held rows (02).
INSERT INTO sync_state(
    id, enabled, applying, serverUrl, accountId, deviceId, cursor, hlc, nodeId,
    clockOffsetMs, protocol, linkedAt, lastSyncAt, lastError
) VALUES (0, 1, 0, 'https://sync.example.com', 'acct-1', 'dev-1', 'cursor-1',
          111411200458752000, '0123456789abcdef', 0, 1, 1700000006002, 1700000007001, NULL);

INSERT INTO sync_outbox(coll, rid, field, hlc, nodeId, captureKind, value)
VALUES ('podcast', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'customTitle', 111411200458883072,
        '0123456789abcdef', 'LOCAL', '"Renamed Show"');

INSERT INTO sync_clock(coll, rid, clocks)
VALUES ('podcast', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', '{"customTitle":1234}');

INSERT INTO sync_parked(id, podcastSyncId, identityKey, guid, enclosureKey, record, receivedAt)
VALUES (1, 'dddddddd-dddd-4ddd-8ddd-dddddddddddd', 'identity-x', 'guid-x', 'enc-x',
        '{"kind":"episode"}', 1700000007003);

INSERT INTO sync_held(id, batch, summary, heldAt)
VALUES (1, '{"removals":[]}', 'Device X removes 2 podcasts', 1700000007004);
