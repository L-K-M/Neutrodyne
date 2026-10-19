CREATE TRIGGER sync_cap_podcast_ins
AFTER INSERT ON podcast
WHEN (SELECT enabled AND NOT applying FROM sync_state WHERE id = 0) AND (1) AND ((1))
BEGIN
  UPDATE sync_state SET hlc = max(hlc + 1, CAST(((julianday('now') - 2440587.5) * 86400000 + clockOffsetMs) AS INTEGER) << 16) WHERE id = 0;
  DELETE FROM sync_outbox WHERE coll = 'podcast' AND rid = NEW.syncId AND field IN (SELECT '*' AS field WHERE (1));
  INSERT INTO sync_outbox(coll, rid, field, hlc, nodeId, value)
    SELECT 'podcast', NEW.syncId, f.field, s.hlc, s.nodeId, f.value FROM sync_state s, (SELECT '*' AS field, NULL AS value WHERE (1)) f WHERE s.id = 0;
END;

CREATE TRIGGER sync_cap_podcast_upd
AFTER UPDATE OF feedUrl, feedKey, podcastGuid, podcastGuidDerived, title, artworkUrl, link, customTitle, includeInAll, episodeOrder, youtubeVariants, credentialId, needsCredentials ON podcast
WHEN (SELECT enabled AND NOT applying FROM sync_state WHERE id = 0) AND (1) AND ((NEW.feedUrl IS NOT OLD.feedUrl) OR (NEW.feedKey IS NOT OLD.feedKey) OR (NEW.podcastGuidDerived = 0 AND (NEW.podcastGuid IS NOT OLD.podcastGuid OR NEW.podcastGuidDerived IS NOT OLD.podcastGuidDerived)) OR (NEW.title IS NOT OLD.title) OR (NEW.artworkUrl IS NOT OLD.artworkUrl) OR (NEW.link IS NOT OLD.link) OR (NEW.customTitle IS NOT OLD.customTitle) OR (NEW.includeInAll IS NOT OLD.includeInAll) OR (NEW.episodeOrder IS NOT OLD.episodeOrder) OR (NEW.youtubeVariants IS NOT OLD.youtubeVariants) OR (NEW.credentialId IS NOT OLD.credentialId) OR (NEW.credentialId IS NOT OLD.credentialId) OR (NEW.needsCredentials IS NOT OLD.needsCredentials OR NEW.credentialId IS NOT OLD.credentialId))
BEGIN
  UPDATE sync_state SET hlc = max(hlc + 1, CAST(((julianday('now') - 2440587.5) * 86400000 + clockOffsetMs) AS INTEGER) << 16) WHERE id = 0;
  DELETE FROM sync_outbox WHERE coll = 'podcast' AND rid = NEW.syncId AND field IN (SELECT 'feedUrl' AS field WHERE (NEW.feedUrl IS NOT OLD.feedUrl) UNION ALL SELECT 'feedKeys' AS field WHERE (NEW.feedKey IS NOT OLD.feedKey) UNION ALL SELECT 'podcastGuid' AS field WHERE (NEW.podcastGuidDerived = 0 AND (NEW.podcastGuid IS NOT OLD.podcastGuid OR NEW.podcastGuidDerived IS NOT OLD.podcastGuidDerived)) UNION ALL SELECT 'title' AS field WHERE (NEW.title IS NOT OLD.title) UNION ALL SELECT 'artworkUrl' AS field WHERE (NEW.artworkUrl IS NOT OLD.artworkUrl) UNION ALL SELECT 'link' AS field WHERE (NEW.link IS NOT OLD.link) UNION ALL SELECT 'customTitle' AS field WHERE (NEW.customTitle IS NOT OLD.customTitle) UNION ALL SELECT 'includeInAll' AS field WHERE (NEW.includeInAll IS NOT OLD.includeInAll) UNION ALL SELECT 'episodeOrder' AS field WHERE (NEW.episodeOrder IS NOT OLD.episodeOrder) UNION ALL SELECT 'youtubeVariants' AS field WHERE (NEW.youtubeVariants IS NOT OLD.youtubeVariants) UNION ALL SELECT 'credentialOrigin' AS field WHERE (NEW.credentialId IS NOT OLD.credentialId) UNION ALL SELECT 'auth' AS field WHERE (NEW.credentialId IS NOT OLD.credentialId) UNION ALL SELECT 'needsCredentials' AS field WHERE (NEW.needsCredentials IS NOT OLD.needsCredentials OR NEW.credentialId IS NOT OLD.credentialId));
  INSERT INTO sync_outbox(coll, rid, field, hlc, nodeId, value)
    SELECT 'podcast', NEW.syncId, f.field, s.hlc, s.nodeId, f.value FROM sync_state s, (SELECT 'feedUrl' AS field, NULL AS value WHERE (NEW.feedUrl IS NOT OLD.feedUrl) UNION ALL SELECT 'feedKeys' AS field, NULL AS value WHERE (NEW.feedKey IS NOT OLD.feedKey) UNION ALL SELECT 'podcastGuid' AS field, NULL AS value WHERE (NEW.podcastGuidDerived = 0 AND (NEW.podcastGuid IS NOT OLD.podcastGuid OR NEW.podcastGuidDerived IS NOT OLD.podcastGuidDerived)) UNION ALL SELECT 'title' AS field, NULL AS value WHERE (NEW.title IS NOT OLD.title) UNION ALL SELECT 'artworkUrl' AS field, NULL AS value WHERE (NEW.artworkUrl IS NOT OLD.artworkUrl) UNION ALL SELECT 'link' AS field, NULL AS value WHERE (NEW.link IS NOT OLD.link) UNION ALL SELECT 'customTitle' AS field, NULL AS value WHERE (NEW.customTitle IS NOT OLD.customTitle) UNION ALL SELECT 'includeInAll' AS field, NULL AS value WHERE (NEW.includeInAll IS NOT OLD.includeInAll) UNION ALL SELECT 'episodeOrder' AS field, NULL AS value WHERE (NEW.episodeOrder IS NOT OLD.episodeOrder) UNION ALL SELECT 'youtubeVariants' AS field, NULL AS value WHERE (NEW.youtubeVariants IS NOT OLD.youtubeVariants) UNION ALL SELECT 'credentialOrigin' AS field, NULL AS value WHERE (NEW.credentialId IS NOT OLD.credentialId) UNION ALL SELECT 'auth' AS field, NULL AS value WHERE (NEW.credentialId IS NOT OLD.credentialId) UNION ALL SELECT 'needsCredentials' AS field, NULL AS value WHERE (NEW.needsCredentials IS NOT OLD.needsCredentials OR NEW.credentialId IS NOT OLD.credentialId)) f WHERE s.id = 0;
END;

CREATE TRIGGER sync_cap_podcast_del
AFTER DELETE ON podcast
WHEN (SELECT enabled AND NOT applying FROM sync_state WHERE id = 0) AND (1) AND ((1))
BEGIN
  UPDATE sync_state SET hlc = max(hlc + 1, CAST(((julianday('now') - 2440587.5) * 86400000 + clockOffsetMs) AS INTEGER) << 16) WHERE id = 0;
  DELETE FROM sync_outbox WHERE coll = 'podcast' AND rid = OLD.syncId AND field IN (SELECT 'subscribed' AS field WHERE (1));
  INSERT INTO sync_outbox(coll, rid, field, hlc, nodeId, value)
    SELECT 'podcast', OLD.syncId, f.field, s.hlc, s.nodeId, f.value FROM sync_state s, (SELECT 'subscribed' AS field, 'false' AS value WHERE (1)) f WHERE s.id = 0;
END;

CREATE TRIGGER sync_cap_podcast_settings_ins
AFTER INSERT ON podcast_settings
WHEN (SELECT enabled AND NOT applying FROM sync_state WHERE id = 0) AND ((SELECT syncId FROM podcast WHERE id = NEW.podcastId) IS NOT NULL) AND ((NEW.playbackSpeed IS NOT NULL) OR (NEW.skipSilence IS NOT NULL) OR (NEW.boostDb IS NOT NULL) OR (NEW.introSkipMs IS NOT NULL) OR (NEW.outroSkipMs IS NOT NULL))
BEGIN
  UPDATE sync_state SET hlc = max(hlc + 1, CAST(((julianday('now') - 2440587.5) * 86400000 + clockOffsetMs) AS INTEGER) << 16) WHERE id = 0;
  DELETE FROM sync_outbox WHERE coll = 'podcast' AND rid = (SELECT syncId FROM podcast WHERE id = NEW.podcastId) AND field IN (SELECT 's.playbackSpeed' AS field WHERE (NEW.playbackSpeed IS NOT NULL) UNION ALL SELECT 's.skipSilence' AS field WHERE (NEW.skipSilence IS NOT NULL) UNION ALL SELECT 's.boostDb' AS field WHERE (NEW.boostDb IS NOT NULL) UNION ALL SELECT 's.introSkipMs' AS field WHERE (NEW.introSkipMs IS NOT NULL) UNION ALL SELECT 's.outroSkipMs' AS field WHERE (NEW.outroSkipMs IS NOT NULL));
  INSERT INTO sync_outbox(coll, rid, field, hlc, nodeId, value)
    SELECT 'podcast', (SELECT syncId FROM podcast WHERE id = NEW.podcastId), f.field, s.hlc, s.nodeId, f.value FROM sync_state s, (SELECT 's.playbackSpeed' AS field, NULL AS value WHERE (NEW.playbackSpeed IS NOT NULL) UNION ALL SELECT 's.skipSilence' AS field, NULL AS value WHERE (NEW.skipSilence IS NOT NULL) UNION ALL SELECT 's.boostDb' AS field, NULL AS value WHERE (NEW.boostDb IS NOT NULL) UNION ALL SELECT 's.introSkipMs' AS field, NULL AS value WHERE (NEW.introSkipMs IS NOT NULL) UNION ALL SELECT 's.outroSkipMs' AS field, NULL AS value WHERE (NEW.outroSkipMs IS NOT NULL)) f WHERE s.id = 0;
END;

CREATE TRIGGER sync_cap_podcast_settings_upd
AFTER UPDATE OF playbackSpeed, skipSilence, boostDb, introSkipMs, outroSkipMs ON podcast_settings
WHEN (SELECT enabled AND NOT applying FROM sync_state WHERE id = 0) AND ((SELECT syncId FROM podcast WHERE id = NEW.podcastId) IS NOT NULL) AND ((NEW.playbackSpeed IS NOT OLD.playbackSpeed) OR (NEW.skipSilence IS NOT OLD.skipSilence) OR (NEW.boostDb IS NOT OLD.boostDb) OR (NEW.introSkipMs IS NOT OLD.introSkipMs) OR (NEW.outroSkipMs IS NOT OLD.outroSkipMs))
BEGIN
  UPDATE sync_state SET hlc = max(hlc + 1, CAST(((julianday('now') - 2440587.5) * 86400000 + clockOffsetMs) AS INTEGER) << 16) WHERE id = 0;
  DELETE FROM sync_outbox WHERE coll = 'podcast' AND rid = (SELECT syncId FROM podcast WHERE id = NEW.podcastId) AND field IN (SELECT 's.playbackSpeed' AS field WHERE (NEW.playbackSpeed IS NOT OLD.playbackSpeed) UNION ALL SELECT 's.skipSilence' AS field WHERE (NEW.skipSilence IS NOT OLD.skipSilence) UNION ALL SELECT 's.boostDb' AS field WHERE (NEW.boostDb IS NOT OLD.boostDb) UNION ALL SELECT 's.introSkipMs' AS field WHERE (NEW.introSkipMs IS NOT OLD.introSkipMs) UNION ALL SELECT 's.outroSkipMs' AS field WHERE (NEW.outroSkipMs IS NOT OLD.outroSkipMs));
  INSERT INTO sync_outbox(coll, rid, field, hlc, nodeId, value)
    SELECT 'podcast', (SELECT syncId FROM podcast WHERE id = NEW.podcastId), f.field, s.hlc, s.nodeId, f.value FROM sync_state s, (SELECT 's.playbackSpeed' AS field, NULL AS value WHERE (NEW.playbackSpeed IS NOT OLD.playbackSpeed) UNION ALL SELECT 's.skipSilence' AS field, NULL AS value WHERE (NEW.skipSilence IS NOT OLD.skipSilence) UNION ALL SELECT 's.boostDb' AS field, NULL AS value WHERE (NEW.boostDb IS NOT OLD.boostDb) UNION ALL SELECT 's.introSkipMs' AS field, NULL AS value WHERE (NEW.introSkipMs IS NOT OLD.introSkipMs) UNION ALL SELECT 's.outroSkipMs' AS field, NULL AS value WHERE (NEW.outroSkipMs IS NOT OLD.outroSkipMs)) f WHERE s.id = 0;
END;

CREATE TRIGGER sync_cap_podcast_settings_del
AFTER DELETE ON podcast_settings
WHEN (SELECT enabled AND NOT applying FROM sync_state WHERE id = 0) AND ((SELECT syncId FROM podcast WHERE id = OLD.podcastId) IS NOT NULL) AND ((OLD.playbackSpeed IS NOT NULL) OR (OLD.skipSilence IS NOT NULL) OR (OLD.boostDb IS NOT NULL) OR (OLD.introSkipMs IS NOT NULL) OR (OLD.outroSkipMs IS NOT NULL))
BEGIN
  UPDATE sync_state SET hlc = max(hlc + 1, CAST(((julianday('now') - 2440587.5) * 86400000 + clockOffsetMs) AS INTEGER) << 16) WHERE id = 0;
  DELETE FROM sync_outbox WHERE coll = 'podcast' AND rid = (SELECT syncId FROM podcast WHERE id = OLD.podcastId) AND field IN (SELECT 's.playbackSpeed' AS field WHERE (OLD.playbackSpeed IS NOT NULL) UNION ALL SELECT 's.skipSilence' AS field WHERE (OLD.skipSilence IS NOT NULL) UNION ALL SELECT 's.boostDb' AS field WHERE (OLD.boostDb IS NOT NULL) UNION ALL SELECT 's.introSkipMs' AS field WHERE (OLD.introSkipMs IS NOT NULL) UNION ALL SELECT 's.outroSkipMs' AS field WHERE (OLD.outroSkipMs IS NOT NULL));
  INSERT INTO sync_outbox(coll, rid, field, hlc, nodeId, value)
    SELECT 'podcast', (SELECT syncId FROM podcast WHERE id = OLD.podcastId), f.field, s.hlc, s.nodeId, f.value FROM sync_state s, (SELECT 's.playbackSpeed' AS field, 'null' AS value WHERE (OLD.playbackSpeed IS NOT NULL) UNION ALL SELECT 's.skipSilence' AS field, 'null' AS value WHERE (OLD.skipSilence IS NOT NULL) UNION ALL SELECT 's.boostDb' AS field, 'null' AS value WHERE (OLD.boostDb IS NOT NULL) UNION ALL SELECT 's.introSkipMs' AS field, 'null' AS value WHERE (OLD.introSkipMs IS NOT NULL) UNION ALL SELECT 's.outroSkipMs' AS field, 'null' AS value WHERE (OLD.outroSkipMs IS NOT NULL)) f WHERE s.id = 0;
END;

CREATE TRIGGER sync_cap_podcast_group_settings_ins
AFTER INSERT ON podcast_group_settings
WHEN (SELECT enabled AND NOT applying FROM sync_state WHERE id = 0) AND ((SELECT uuid FROM podcast_group WHERE id = NEW.groupId) IS NOT NULL) AND ((NEW.playbackSpeed IS NOT NULL) OR (NEW.skipSilence IS NOT NULL) OR (NEW.boostDb IS NOT NULL) OR (NEW.introSkipMs IS NOT NULL) OR (NEW.outroSkipMs IS NOT NULL))
BEGIN
  UPDATE sync_state SET hlc = max(hlc + 1, CAST(((julianday('now') - 2440587.5) * 86400000 + clockOffsetMs) AS INTEGER) << 16) WHERE id = 0;
  DELETE FROM sync_outbox WHERE coll = 'group' AND rid = (SELECT uuid FROM podcast_group WHERE id = NEW.groupId) AND field IN (SELECT 's.playbackSpeed' AS field WHERE (NEW.playbackSpeed IS NOT NULL) UNION ALL SELECT 's.skipSilence' AS field WHERE (NEW.skipSilence IS NOT NULL) UNION ALL SELECT 's.boostDb' AS field WHERE (NEW.boostDb IS NOT NULL) UNION ALL SELECT 's.introSkipMs' AS field WHERE (NEW.introSkipMs IS NOT NULL) UNION ALL SELECT 's.outroSkipMs' AS field WHERE (NEW.outroSkipMs IS NOT NULL));
  INSERT INTO sync_outbox(coll, rid, field, hlc, nodeId, value)
    SELECT 'group', (SELECT uuid FROM podcast_group WHERE id = NEW.groupId), f.field, s.hlc, s.nodeId, f.value FROM sync_state s, (SELECT 's.playbackSpeed' AS field, NULL AS value WHERE (NEW.playbackSpeed IS NOT NULL) UNION ALL SELECT 's.skipSilence' AS field, NULL AS value WHERE (NEW.skipSilence IS NOT NULL) UNION ALL SELECT 's.boostDb' AS field, NULL AS value WHERE (NEW.boostDb IS NOT NULL) UNION ALL SELECT 's.introSkipMs' AS field, NULL AS value WHERE (NEW.introSkipMs IS NOT NULL) UNION ALL SELECT 's.outroSkipMs' AS field, NULL AS value WHERE (NEW.outroSkipMs IS NOT NULL)) f WHERE s.id = 0;
END;

CREATE TRIGGER sync_cap_podcast_group_settings_upd
AFTER UPDATE OF playbackSpeed, skipSilence, boostDb, introSkipMs, outroSkipMs ON podcast_group_settings
WHEN (SELECT enabled AND NOT applying FROM sync_state WHERE id = 0) AND ((SELECT uuid FROM podcast_group WHERE id = NEW.groupId) IS NOT NULL) AND ((NEW.playbackSpeed IS NOT OLD.playbackSpeed) OR (NEW.skipSilence IS NOT OLD.skipSilence) OR (NEW.boostDb IS NOT OLD.boostDb) OR (NEW.introSkipMs IS NOT OLD.introSkipMs) OR (NEW.outroSkipMs IS NOT OLD.outroSkipMs))
BEGIN
  UPDATE sync_state SET hlc = max(hlc + 1, CAST(((julianday('now') - 2440587.5) * 86400000 + clockOffsetMs) AS INTEGER) << 16) WHERE id = 0;
  DELETE FROM sync_outbox WHERE coll = 'group' AND rid = (SELECT uuid FROM podcast_group WHERE id = NEW.groupId) AND field IN (SELECT 's.playbackSpeed' AS field WHERE (NEW.playbackSpeed IS NOT OLD.playbackSpeed) UNION ALL SELECT 's.skipSilence' AS field WHERE (NEW.skipSilence IS NOT OLD.skipSilence) UNION ALL SELECT 's.boostDb' AS field WHERE (NEW.boostDb IS NOT OLD.boostDb) UNION ALL SELECT 's.introSkipMs' AS field WHERE (NEW.introSkipMs IS NOT OLD.introSkipMs) UNION ALL SELECT 's.outroSkipMs' AS field WHERE (NEW.outroSkipMs IS NOT OLD.outroSkipMs));
  INSERT INTO sync_outbox(coll, rid, field, hlc, nodeId, value)
    SELECT 'group', (SELECT uuid FROM podcast_group WHERE id = NEW.groupId), f.field, s.hlc, s.nodeId, f.value FROM sync_state s, (SELECT 's.playbackSpeed' AS field, NULL AS value WHERE (NEW.playbackSpeed IS NOT OLD.playbackSpeed) UNION ALL SELECT 's.skipSilence' AS field, NULL AS value WHERE (NEW.skipSilence IS NOT OLD.skipSilence) UNION ALL SELECT 's.boostDb' AS field, NULL AS value WHERE (NEW.boostDb IS NOT OLD.boostDb) UNION ALL SELECT 's.introSkipMs' AS field, NULL AS value WHERE (NEW.introSkipMs IS NOT OLD.introSkipMs) UNION ALL SELECT 's.outroSkipMs' AS field, NULL AS value WHERE (NEW.outroSkipMs IS NOT OLD.outroSkipMs)) f WHERE s.id = 0;
END;

CREATE TRIGGER sync_cap_podcast_group_settings_del
AFTER DELETE ON podcast_group_settings
WHEN (SELECT enabled AND NOT applying FROM sync_state WHERE id = 0) AND ((SELECT uuid FROM podcast_group WHERE id = OLD.groupId) IS NOT NULL) AND ((OLD.playbackSpeed IS NOT NULL) OR (OLD.skipSilence IS NOT NULL) OR (OLD.boostDb IS NOT NULL) OR (OLD.introSkipMs IS NOT NULL) OR (OLD.outroSkipMs IS NOT NULL))
BEGIN
  UPDATE sync_state SET hlc = max(hlc + 1, CAST(((julianday('now') - 2440587.5) * 86400000 + clockOffsetMs) AS INTEGER) << 16) WHERE id = 0;
  DELETE FROM sync_outbox WHERE coll = 'group' AND rid = (SELECT uuid FROM podcast_group WHERE id = OLD.groupId) AND field IN (SELECT 's.playbackSpeed' AS field WHERE (OLD.playbackSpeed IS NOT NULL) UNION ALL SELECT 's.skipSilence' AS field WHERE (OLD.skipSilence IS NOT NULL) UNION ALL SELECT 's.boostDb' AS field WHERE (OLD.boostDb IS NOT NULL) UNION ALL SELECT 's.introSkipMs' AS field WHERE (OLD.introSkipMs IS NOT NULL) UNION ALL SELECT 's.outroSkipMs' AS field WHERE (OLD.outroSkipMs IS NOT NULL));
  INSERT INTO sync_outbox(coll, rid, field, hlc, nodeId, value)
    SELECT 'group', (SELECT uuid FROM podcast_group WHERE id = OLD.groupId), f.field, s.hlc, s.nodeId, f.value FROM sync_state s, (SELECT 's.playbackSpeed' AS field, 'null' AS value WHERE (OLD.playbackSpeed IS NOT NULL) UNION ALL SELECT 's.skipSilence' AS field, 'null' AS value WHERE (OLD.skipSilence IS NOT NULL) UNION ALL SELECT 's.boostDb' AS field, 'null' AS value WHERE (OLD.boostDb IS NOT NULL) UNION ALL SELECT 's.introSkipMs' AS field, 'null' AS value WHERE (OLD.introSkipMs IS NOT NULL) UNION ALL SELECT 's.outroSkipMs' AS field, 'null' AS value WHERE (OLD.outroSkipMs IS NOT NULL)) f WHERE s.id = 0;
END;

CREATE TRIGGER sync_cap_podcast_group_ins
AFTER INSERT ON podcast_group
WHEN (SELECT enabled AND NOT applying FROM sync_state WHERE id = 0) AND (1) AND ((1))
BEGIN
  UPDATE sync_state SET hlc = max(hlc + 1, CAST(((julianday('now') - 2440587.5) * 86400000 + clockOffsetMs) AS INTEGER) << 16) WHERE id = 0;
  DELETE FROM sync_outbox WHERE coll = 'group' AND rid = NEW.uuid AND field IN (SELECT '*' AS field WHERE (1));
  INSERT INTO sync_outbox(coll, rid, field, hlc, nodeId, value)
    SELECT 'group', NEW.uuid, f.field, s.hlc, s.nodeId, f.value FROM sync_state s, (SELECT '*' AS field, NULL AS value WHERE (1)) f WHERE s.id = 0;
END;

CREATE TRIGGER sync_cap_podcast_group_upd
AFTER UPDATE OF name, orderKey, colorArgb, iconKey, feedOrder, playOrder, filterFlags, mediaFilter, hideOlderThanDays, showAsTab ON podcast_group
WHEN (SELECT enabled AND NOT applying FROM sync_state WHERE id = 0) AND (1) AND ((NEW.name IS NOT OLD.name) OR (NEW.orderKey IS NOT OLD.orderKey) OR (NEW.colorArgb IS NOT OLD.colorArgb) OR (NEW.iconKey IS NOT OLD.iconKey) OR (NEW.feedOrder IS NOT OLD.feedOrder) OR (NEW.playOrder IS NOT OLD.playOrder) OR (NEW.filterFlags IS NOT OLD.filterFlags) OR (NEW.mediaFilter IS NOT OLD.mediaFilter) OR (NEW.hideOlderThanDays IS NOT OLD.hideOlderThanDays) OR (NEW.showAsTab IS NOT OLD.showAsTab))
BEGIN
  UPDATE sync_state SET hlc = max(hlc + 1, CAST(((julianday('now') - 2440587.5) * 86400000 + clockOffsetMs) AS INTEGER) << 16) WHERE id = 0;
  DELETE FROM sync_outbox WHERE coll = 'group' AND rid = NEW.uuid AND field IN (SELECT 'name' AS field WHERE (NEW.name IS NOT OLD.name) UNION ALL SELECT 'ok' AS field WHERE (NEW.orderKey IS NOT OLD.orderKey) UNION ALL SELECT 'colorArgb' AS field WHERE (NEW.colorArgb IS NOT OLD.colorArgb) UNION ALL SELECT 'iconKey' AS field WHERE (NEW.iconKey IS NOT OLD.iconKey) UNION ALL SELECT 'feedOrder' AS field WHERE (NEW.feedOrder IS NOT OLD.feedOrder) UNION ALL SELECT 'playOrder' AS field WHERE (NEW.playOrder IS NOT OLD.playOrder) UNION ALL SELECT 'filterFlags' AS field WHERE (NEW.filterFlags IS NOT OLD.filterFlags) UNION ALL SELECT 'mediaFilter' AS field WHERE (NEW.mediaFilter IS NOT OLD.mediaFilter) UNION ALL SELECT 'hideOlderThanDays' AS field WHERE (NEW.hideOlderThanDays IS NOT OLD.hideOlderThanDays) UNION ALL SELECT 'showAsTab' AS field WHERE (NEW.showAsTab IS NOT OLD.showAsTab));
  INSERT INTO sync_outbox(coll, rid, field, hlc, nodeId, value)
    SELECT 'group', NEW.uuid, f.field, s.hlc, s.nodeId, f.value FROM sync_state s, (SELECT 'name' AS field, NULL AS value WHERE (NEW.name IS NOT OLD.name) UNION ALL SELECT 'ok' AS field, NULL AS value WHERE (NEW.orderKey IS NOT OLD.orderKey) UNION ALL SELECT 'colorArgb' AS field, NULL AS value WHERE (NEW.colorArgb IS NOT OLD.colorArgb) UNION ALL SELECT 'iconKey' AS field, NULL AS value WHERE (NEW.iconKey IS NOT OLD.iconKey) UNION ALL SELECT 'feedOrder' AS field, NULL AS value WHERE (NEW.feedOrder IS NOT OLD.feedOrder) UNION ALL SELECT 'playOrder' AS field, NULL AS value WHERE (NEW.playOrder IS NOT OLD.playOrder) UNION ALL SELECT 'filterFlags' AS field, NULL AS value WHERE (NEW.filterFlags IS NOT OLD.filterFlags) UNION ALL SELECT 'mediaFilter' AS field, NULL AS value WHERE (NEW.mediaFilter IS NOT OLD.mediaFilter) UNION ALL SELECT 'hideOlderThanDays' AS field, NULL AS value WHERE (NEW.hideOlderThanDays IS NOT OLD.hideOlderThanDays) UNION ALL SELECT 'showAsTab' AS field, NULL AS value WHERE (NEW.showAsTab IS NOT OLD.showAsTab)) f WHERE s.id = 0;
END;

CREATE TRIGGER sync_cap_podcast_group_del
AFTER DELETE ON podcast_group
WHEN (SELECT enabled AND NOT applying FROM sync_state WHERE id = 0) AND (1) AND ((1))
BEGIN
  UPDATE sync_state SET hlc = max(hlc + 1, CAST(((julianday('now') - 2440587.5) * 86400000 + clockOffsetMs) AS INTEGER) << 16) WHERE id = 0;
  DELETE FROM sync_outbox WHERE coll = 'group' AND rid = OLD.uuid AND field IN (SELECT 'deleted' AS field WHERE (1));
  INSERT INTO sync_outbox(coll, rid, field, hlc, nodeId, value)
    SELECT 'group', OLD.uuid, f.field, s.hlc, s.nodeId, f.value FROM sync_state s, (SELECT 'deleted' AS field, 'true' AS value WHERE (1)) f WHERE s.id = 0;
END;

CREATE TRIGGER sync_cap_podcast_group_member_ins
AFTER INSERT ON podcast_group_member
WHEN (SELECT enabled AND NOT applying FROM sync_state WHERE id = 0) AND ((SELECT g.uuid || p.syncId FROM podcast_group g, podcast p WHERE g.id = NEW.groupId AND p.id = NEW.podcastId) IS NOT NULL) AND ((1) OR (1) OR (1))
BEGIN
  UPDATE sync_state SET hlc = max(hlc + 1, CAST(((julianday('now') - 2440587.5) * 86400000 + clockOffsetMs) AS INTEGER) << 16) WHERE id = 0;
  DELETE FROM sync_outbox WHERE coll = 'member' AND rid = (SELECT g.uuid || p.syncId FROM podcast_group g, podcast p WHERE g.id = NEW.groupId AND p.id = NEW.podcastId) AND field IN (SELECT 'in' AS field WHERE (1) UNION ALL SELECT 'ok' AS field WHERE (1) UNION ALL SELECT 'addedAt' AS field WHERE (1));
  INSERT INTO sync_outbox(coll, rid, field, hlc, nodeId, value)
    SELECT 'member', (SELECT g.uuid || p.syncId FROM podcast_group g, podcast p WHERE g.id = NEW.groupId AND p.id = NEW.podcastId), f.field, s.hlc, s.nodeId, f.value FROM sync_state s, (SELECT 'in' AS field, NULL AS value WHERE (1) UNION ALL SELECT 'ok' AS field, NULL AS value WHERE (1) UNION ALL SELECT 'addedAt' AS field, NULL AS value WHERE (1)) f WHERE s.id = 0;
END;

CREATE TRIGGER sync_cap_podcast_group_member_upd
AFTER UPDATE OF orderKey ON podcast_group_member
WHEN (SELECT enabled AND NOT applying FROM sync_state WHERE id = 0) AND ((SELECT g.uuid || p.syncId FROM podcast_group g, podcast p WHERE g.id = NEW.groupId AND p.id = NEW.podcastId) IS NOT NULL) AND ((NEW.orderKey IS NOT OLD.orderKey))
BEGIN
  UPDATE sync_state SET hlc = max(hlc + 1, CAST(((julianday('now') - 2440587.5) * 86400000 + clockOffsetMs) AS INTEGER) << 16) WHERE id = 0;
  DELETE FROM sync_outbox WHERE coll = 'member' AND rid = (SELECT g.uuid || p.syncId FROM podcast_group g, podcast p WHERE g.id = NEW.groupId AND p.id = NEW.podcastId) AND field IN (SELECT 'ok' AS field WHERE (NEW.orderKey IS NOT OLD.orderKey));
  INSERT INTO sync_outbox(coll, rid, field, hlc, nodeId, value)
    SELECT 'member', (SELECT g.uuid || p.syncId FROM podcast_group g, podcast p WHERE g.id = NEW.groupId AND p.id = NEW.podcastId), f.field, s.hlc, s.nodeId, f.value FROM sync_state s, (SELECT 'ok' AS field, NULL AS value WHERE (NEW.orderKey IS NOT OLD.orderKey)) f WHERE s.id = 0;
END;

CREATE TRIGGER sync_cap_podcast_group_member_del
AFTER DELETE ON podcast_group_member
WHEN (SELECT enabled AND NOT applying FROM sync_state WHERE id = 0) AND ((SELECT g.uuid || p.syncId FROM podcast_group g, podcast p WHERE g.id = OLD.groupId AND p.id = OLD.podcastId) IS NOT NULL) AND ((1))
BEGIN
  UPDATE sync_state SET hlc = max(hlc + 1, CAST(((julianday('now') - 2440587.5) * 86400000 + clockOffsetMs) AS INTEGER) << 16) WHERE id = 0;
  DELETE FROM sync_outbox WHERE coll = 'member' AND rid = (SELECT g.uuid || p.syncId FROM podcast_group g, podcast p WHERE g.id = OLD.groupId AND p.id = OLD.podcastId) AND field IN (SELECT 'in' AS field WHERE (1));
  INSERT INTO sync_outbox(coll, rid, field, hlc, nodeId, value)
    SELECT 'member', (SELECT g.uuid || p.syncId FROM podcast_group g, podcast p WHERE g.id = OLD.groupId AND p.id = OLD.podcastId), f.field, s.hlc, s.nodeId, f.value FROM sync_state s, (SELECT 'in' AS field, 'false' AS value WHERE (1)) f WHERE s.id = 0;
END;

CREATE TRIGGER sync_cap_queue_entry_ins
AFTER INSERT ON queue_entry
WHEN (SELECT enabled AND NOT applying FROM sync_state WHERE id = 0) AND ((SELECT p.syncId || e.identityKey FROM episode e JOIN podcast p ON p.id = e.podcastId WHERE e.id = NEW.episodeId) IS NOT NULL) AND ((1) OR (1))
BEGIN
  UPDATE sync_state SET hlc = max(hlc + 1, CAST(((julianday('now') - 2440587.5) * 86400000 + clockOffsetMs) AS INTEGER) << 16) WHERE id = 0;
  DELETE FROM sync_outbox WHERE coll = 'upnext' AND rid = (SELECT p.syncId || e.identityKey FROM episode e JOIN podcast p ON p.id = e.podcastId WHERE e.id = NEW.episodeId) AND field IN (SELECT 'in' AS field WHERE (1) UNION ALL SELECT 'ok' AS field WHERE (1));
  INSERT INTO sync_outbox(coll, rid, field, hlc, nodeId, value)
    SELECT 'upnext', (SELECT p.syncId || e.identityKey FROM episode e JOIN podcast p ON p.id = e.podcastId WHERE e.id = NEW.episodeId), f.field, s.hlc, s.nodeId, f.value FROM sync_state s, (SELECT 'in' AS field, NULL AS value WHERE (1) UNION ALL SELECT 'ok' AS field, NULL AS value WHERE (1)) f WHERE s.id = 0;
END;

CREATE TRIGGER sync_cap_queue_entry_upd
AFTER UPDATE OF orderKey ON queue_entry
WHEN (SELECT enabled AND NOT applying FROM sync_state WHERE id = 0) AND ((SELECT p.syncId || e.identityKey FROM episode e JOIN podcast p ON p.id = e.podcastId WHERE e.id = NEW.episodeId) IS NOT NULL) AND ((NEW.orderKey IS NOT OLD.orderKey))
BEGIN
  UPDATE sync_state SET hlc = max(hlc + 1, CAST(((julianday('now') - 2440587.5) * 86400000 + clockOffsetMs) AS INTEGER) << 16) WHERE id = 0;
  DELETE FROM sync_outbox WHERE coll = 'upnext' AND rid = (SELECT p.syncId || e.identityKey FROM episode e JOIN podcast p ON p.id = e.podcastId WHERE e.id = NEW.episodeId) AND field IN (SELECT 'ok' AS field WHERE (NEW.orderKey IS NOT OLD.orderKey));
  INSERT INTO sync_outbox(coll, rid, field, hlc, nodeId, value)
    SELECT 'upnext', (SELECT p.syncId || e.identityKey FROM episode e JOIN podcast p ON p.id = e.podcastId WHERE e.id = NEW.episodeId), f.field, s.hlc, s.nodeId, f.value FROM sync_state s, (SELECT 'ok' AS field, NULL AS value WHERE (NEW.orderKey IS NOT OLD.orderKey)) f WHERE s.id = 0;
END;

CREATE TRIGGER sync_cap_queue_entry_del
AFTER DELETE ON queue_entry
WHEN (SELECT enabled AND NOT applying FROM sync_state WHERE id = 0) AND ((SELECT p.syncId || e.identityKey FROM episode e JOIN podcast p ON p.id = e.podcastId WHERE e.id = OLD.episodeId) IS NOT NULL) AND ((1))
BEGIN
  UPDATE sync_state SET hlc = max(hlc + 1, CAST(((julianday('now') - 2440587.5) * 86400000 + clockOffsetMs) AS INTEGER) << 16) WHERE id = 0;
  DELETE FROM sync_outbox WHERE coll = 'upnext' AND rid = (SELECT p.syncId || e.identityKey FROM episode e JOIN podcast p ON p.id = e.podcastId WHERE e.id = OLD.episodeId) AND field IN (SELECT 'in' AS field WHERE (1));
  INSERT INTO sync_outbox(coll, rid, field, hlc, nodeId, value)
    SELECT 'upnext', (SELECT p.syncId || e.identityKey FROM episode e JOIN podcast p ON p.id = e.podcastId WHERE e.id = OLD.episodeId), f.field, s.hlc, s.nodeId, f.value FROM sync_state s, (SELECT 'in' AS field, 'false' AS value WHERE (1)) f WHERE s.id = 0;
END;

CREATE TRIGGER sync_cap_episode_state_ins
AFTER INSERT ON episode_state
WHEN (SELECT enabled AND NOT applying FROM sync_state WHERE id = 0) AND (1) AND ((NEW.playedAt IS NOT NULL) OR (NEW.isFavorite <> 0) OR (NEW.playCount <> 0) OR (NEW.lastPlayedAt IS NOT NULL) OR (NEW.measuredDurationMs IS NOT NULL))
BEGIN
  UPDATE sync_state SET hlc = max(hlc + 1, CAST(((julianday('now') - 2440587.5) * 86400000 + clockOffsetMs) AS INTEGER) << 16) WHERE id = 0;
  DELETE FROM sync_outbox WHERE coll = 'episode' AND rid = (SELECT p.syncId || e.identityKey FROM episode e JOIN podcast p ON p.id = e.podcastId WHERE e.id = NEW.episodeId) AND field IN (SELECT 'played' AS field WHERE (NEW.playedAt IS NOT NULL) UNION ALL SELECT 'fav' AS field WHERE (NEW.isFavorite <> 0) UNION ALL SELECT 'playCount' AS field WHERE (NEW.playCount <> 0) UNION ALL SELECT 'lastPlayedAt' AS field WHERE (NEW.lastPlayedAt IS NOT NULL) UNION ALL SELECT 'measuredDurationMs' AS field WHERE (NEW.measuredDurationMs IS NOT NULL));
  INSERT INTO sync_outbox(coll, rid, field, hlc, nodeId, value)
    SELECT 'episode', (SELECT p.syncId || e.identityKey FROM episode e JOIN podcast p ON p.id = e.podcastId WHERE e.id = NEW.episodeId), f.field, s.hlc, s.nodeId, f.value FROM sync_state s, (SELECT 'played' AS field, '{"played":' || CASE WHEN NEW.playedAt IS NULL THEN 'false' ELSE 'true' END || ',"playedAt":' || COALESCE(NEW.playedAt, 'null') || '}' AS value WHERE (NEW.playedAt IS NOT NULL) UNION ALL SELECT 'fav' AS field, NULL AS value WHERE (NEW.isFavorite <> 0) UNION ALL SELECT 'playCount' AS field, NULL AS value WHERE (NEW.playCount <> 0) UNION ALL SELECT 'lastPlayedAt' AS field, NULL AS value WHERE (NEW.lastPlayedAt IS NOT NULL) UNION ALL SELECT 'measuredDurationMs' AS field, NULL AS value WHERE (NEW.measuredDurationMs IS NOT NULL)) f WHERE s.id = 0;
END;

CREATE TRIGGER sync_cap_episode_state_upd
AFTER UPDATE OF playedAt, isFavorite, playCount, lastPlayedAt, measuredDurationMs ON episode_state
WHEN (SELECT enabled AND NOT applying FROM sync_state WHERE id = 0) AND (1) AND ((NEW.playedAt IS NOT OLD.playedAt) OR (NEW.isFavorite IS NOT OLD.isFavorite) OR (NEW.playCount IS NOT OLD.playCount) OR (NEW.lastPlayedAt IS NOT OLD.lastPlayedAt) OR (NEW.measuredDurationMs IS NOT OLD.measuredDurationMs))
BEGIN
  UPDATE sync_state SET hlc = max(hlc + 1, CAST(((julianday('now') - 2440587.5) * 86400000 + clockOffsetMs) AS INTEGER) << 16) WHERE id = 0;
  DELETE FROM sync_outbox WHERE coll = 'episode' AND rid = (SELECT p.syncId || e.identityKey FROM episode e JOIN podcast p ON p.id = e.podcastId WHERE e.id = NEW.episodeId) AND field IN (SELECT 'played' AS field WHERE (NEW.playedAt IS NOT OLD.playedAt) UNION ALL SELECT 'fav' AS field WHERE (NEW.isFavorite IS NOT OLD.isFavorite) UNION ALL SELECT 'playCount' AS field WHERE (NEW.playCount IS NOT OLD.playCount) UNION ALL SELECT 'lastPlayedAt' AS field WHERE (NEW.lastPlayedAt IS NOT OLD.lastPlayedAt) UNION ALL SELECT 'measuredDurationMs' AS field WHERE (NEW.measuredDurationMs IS NOT OLD.measuredDurationMs));
  INSERT INTO sync_outbox(coll, rid, field, hlc, nodeId, value)
    SELECT 'episode', (SELECT p.syncId || e.identityKey FROM episode e JOIN podcast p ON p.id = e.podcastId WHERE e.id = NEW.episodeId), f.field, s.hlc, s.nodeId, f.value FROM sync_state s, (SELECT 'played' AS field, '{"played":' || CASE WHEN NEW.playedAt IS NULL THEN 'false' ELSE 'true' END || ',"playedAt":' || COALESCE(NEW.playedAt, 'null') || '}' AS value WHERE (NEW.playedAt IS NOT OLD.playedAt) UNION ALL SELECT 'fav' AS field, NULL AS value WHERE (NEW.isFavorite IS NOT OLD.isFavorite) UNION ALL SELECT 'playCount' AS field, NULL AS value WHERE (NEW.playCount IS NOT OLD.playCount) UNION ALL SELECT 'lastPlayedAt' AS field, NULL AS value WHERE (NEW.lastPlayedAt IS NOT OLD.lastPlayedAt) UNION ALL SELECT 'measuredDurationMs' AS field, NULL AS value WHERE (NEW.measuredDurationMs IS NOT OLD.measuredDurationMs)) f WHERE s.id = 0;
END;

CREATE TRIGGER sync_cap_episode_position_ins
AFTER INSERT ON episode_position
WHEN (SELECT enabled AND NOT applying FROM sync_state WHERE id = 0) AND (1) AND ((NEW.positionMs > 0))
BEGIN
  UPDATE sync_state SET hlc = max(hlc + 1, CAST(((julianday('now') - 2440587.5) * 86400000 + clockOffsetMs) AS INTEGER) << 16) WHERE id = 0;
  DELETE FROM sync_outbox WHERE coll = 'episode' AND rid = (SELECT p.syncId || e.identityKey FROM episode e JOIN podcast p ON p.id = e.podcastId WHERE e.id = NEW.episodeId) AND field IN (SELECT 'pos' AS field WHERE (NEW.positionMs > 0));
  INSERT INTO sync_outbox(coll, rid, field, hlc, nodeId, value)
    SELECT 'episode', (SELECT p.syncId || e.identityKey FROM episode e JOIN podcast p ON p.id = e.podcastId WHERE e.id = NEW.episodeId), f.field, s.hlc, s.nodeId, f.value FROM sync_state s, (SELECT 'pos' AS field, CASE WHEN NEW.positionMs = 0 THEN '{"ms":0,"reset":true}' ELSE '{"ms":' || NEW.positionMs || ',"dur":' || COALESCE(NEW.durationMs, 'null') || ',"src":' || '"' || replace(replace(replace(replace(replace(NEW.positionSource, '\', '\\'), '"', '\"'), char(10), '\n'), char(13), '\r'), char(9), '\t') || '"' || '}' END AS value WHERE (NEW.positionMs > 0)) f WHERE s.id = 0;
END;

CREATE TRIGGER sync_cap_episode_position_upd
AFTER UPDATE OF positionMs ON episode_position
WHEN (SELECT enabled AND NOT applying FROM sync_state WHERE id = 0) AND (1) AND ((NEW.positionMs IS NOT OLD.positionMs))
BEGIN
  UPDATE sync_state SET hlc = max(hlc + 1, CAST(((julianday('now') - 2440587.5) * 86400000 + clockOffsetMs) AS INTEGER) << 16) WHERE id = 0;
  DELETE FROM sync_outbox WHERE coll = 'episode' AND rid = (SELECT p.syncId || e.identityKey FROM episode e JOIN podcast p ON p.id = e.podcastId WHERE e.id = NEW.episodeId) AND field IN (SELECT 'pos' AS field WHERE (NEW.positionMs IS NOT OLD.positionMs));
  INSERT INTO sync_outbox(coll, rid, field, hlc, nodeId, value)
    SELECT 'episode', (SELECT p.syncId || e.identityKey FROM episode e JOIN podcast p ON p.id = e.podcastId WHERE e.id = NEW.episodeId), f.field, s.hlc, s.nodeId, f.value FROM sync_state s, (SELECT 'pos' AS field, CASE WHEN NEW.positionMs = 0 THEN '{"ms":0,"reset":true}' ELSE '{"ms":' || NEW.positionMs || ',"dur":' || COALESCE(NEW.durationMs, 'null') || ',"src":' || '"' || replace(replace(replace(replace(replace(NEW.positionSource, '\', '\\'), '"', '\"'), char(10), '\n'), char(13), '\r'), char(9), '\t') || '"' || '}' END AS value WHERE (NEW.positionMs IS NOT OLD.positionMs)) f WHERE s.id = 0;
END;

CREATE TRIGGER sync_cap_play_session_upd
AFTER UPDATE OF currentEpisodeId, contextType, contextId, contextOrder, contextFilterFlags, contextMediaFilter, contextMinSortDate, contextAnchorEpisodeId, contextAnchorSortDate ON play_session
WHEN (SELECT enabled AND NOT applying FROM sync_state WHERE id = 0) AND (1) AND ((NEW.currentEpisodeId IS NOT OLD.currentEpisodeId) OR (NEW.contextType IS NOT OLD.contextType OR NEW.contextId IS NOT OLD.contextId OR NEW.contextOrder IS NOT OLD.contextOrder OR NEW.contextFilterFlags IS NOT OLD.contextFilterFlags OR NEW.contextMediaFilter IS NOT OLD.contextMediaFilter OR NEW.contextMinSortDate IS NOT OLD.contextMinSortDate OR NEW.contextAnchorEpisodeId IS NOT OLD.contextAnchorEpisodeId OR NEW.contextAnchorSortDate IS NOT OLD.contextAnchorSortDate))
BEGIN
  UPDATE sync_state SET hlc = max(hlc + 1, CAST(((julianday('now') - 2440587.5) * 86400000 + clockOffsetMs) AS INTEGER) << 16) WHERE id = 0;
  DELETE FROM sync_outbox WHERE coll = 'session' AND rid = 'current' AND field IN (SELECT 'episode' AS field WHERE (NEW.currentEpisodeId IS NOT OLD.currentEpisodeId) UNION ALL SELECT 'context' AS field WHERE (NEW.contextType IS NOT OLD.contextType OR NEW.contextId IS NOT OLD.contextId OR NEW.contextOrder IS NOT OLD.contextOrder OR NEW.contextFilterFlags IS NOT OLD.contextFilterFlags OR NEW.contextMediaFilter IS NOT OLD.contextMediaFilter OR NEW.contextMinSortDate IS NOT OLD.contextMinSortDate OR NEW.contextAnchorEpisodeId IS NOT OLD.contextAnchorEpisodeId OR NEW.contextAnchorSortDate IS NOT OLD.contextAnchorSortDate));
  INSERT INTO sync_outbox(coll, rid, field, hlc, nodeId, value)
    SELECT 'session', 'current', f.field, s.hlc, s.nodeId, f.value FROM sync_state s, (SELECT 'episode' AS field, NULL AS value WHERE (NEW.currentEpisodeId IS NOT OLD.currentEpisodeId) UNION ALL SELECT 'context' AS field, NULL AS value WHERE (NEW.contextType IS NOT OLD.contextType OR NEW.contextId IS NOT OLD.contextId OR NEW.contextOrder IS NOT OLD.contextOrder OR NEW.contextFilterFlags IS NOT OLD.contextFilterFlags OR NEW.contextMediaFilter IS NOT OLD.contextMediaFilter OR NEW.contextMinSortDate IS NOT OLD.contextMinSortDate OR NEW.contextAnchorEpisodeId IS NOT OLD.contextAnchorEpisodeId OR NEW.contextAnchorSortDate IS NOT OLD.contextAnchorSortDate)) f WHERE s.id = 0;
END;

CREATE TRIGGER sync_cap_episode_rekey
AFTER UPDATE OF identityKey ON episode
WHEN (SELECT enabled AND NOT applying FROM sync_state WHERE id = 0) AND (NEW.identityKey IS NOT OLD.identityKey AND NEW.podcastId = OLD.podcastId AND (EXISTS (SELECT 1 FROM sync_clock WHERE coll IN ('episode', 'upnext') AND rid = (SELECT p.syncId || OLD.identityKey FROM podcast p WHERE p.id = OLD.podcastId)) OR EXISTS (SELECT 1 FROM sync_outbox WHERE coll IN ('episode', 'upnext') AND rid = (SELECT p.syncId || OLD.identityKey FROM podcast p WHERE p.id = OLD.podcastId)))) AND (1)
BEGIN
  UPDATE sync_state SET hlc = max(hlc + 1, CAST(((julianday('now') - 2440587.5) * 86400000 + clockOffsetMs) AS INTEGER) << 16) WHERE id = 0;
  SELECT RAISE(ABORT, 'Conflicting rekey payloads at the same complete clock') WHERE EXISTS (
    SELECT 1 FROM sync_outbox a JOIN sync_outbox b ON a.coll = b.coll AND a.field = b.field
    WHERE a.coll IN ('episode', 'upnext') AND a.rid = (SELECT p.syncId || OLD.identityKey FROM podcast p WHERE p.id = OLD.podcastId) AND b.rid = (SELECT p.syncId || NEW.identityKey FROM podcast p WHERE p.id = NEW.podcastId)
      AND a.hlc = b.hlc AND a.nodeId = b.nodeId AND a.value IS NOT b.value);
  DELETE FROM sync_outbox WHERE coll IN ('episode', 'upnext') AND rid = (SELECT p.syncId || NEW.identityKey FROM podcast p WHERE p.id = NEW.podcastId) AND EXISTS (
    SELECT 1 FROM sync_outbox o WHERE o.coll = sync_outbox.coll AND o.rid = (SELECT p.syncId || OLD.identityKey FROM podcast p WHERE p.id = OLD.podcastId) AND o.field = sync_outbox.field
      AND (o.hlc > sync_outbox.hlc OR (o.hlc = sync_outbox.hlc AND o.nodeId >= sync_outbox.nodeId)));
  DELETE FROM sync_outbox WHERE coll IN ('episode', 'upnext') AND rid = (SELECT p.syncId || OLD.identityKey FROM podcast p WHERE p.id = OLD.podcastId) AND EXISTS (
    SELECT 1 FROM sync_outbox o WHERE o.coll = sync_outbox.coll AND o.rid = (SELECT p.syncId || NEW.identityKey FROM podcast p WHERE p.id = NEW.podcastId) AND o.field = sync_outbox.field);
  UPDATE sync_outbox SET rid = (SELECT p.syncId || NEW.identityKey FROM podcast p WHERE p.id = NEW.podcastId) WHERE coll IN ('episode', 'upnext') AND rid = (SELECT p.syncId || OLD.identityKey FROM podcast p WHERE p.id = OLD.podcastId);
  UPDATE sync_clock SET rid = (SELECT p.syncId || NEW.identityKey FROM podcast p WHERE p.id = NEW.podcastId) WHERE coll IN ('episode', 'upnext') AND rid = (SELECT p.syncId || OLD.identityKey FROM podcast p WHERE p.id = OLD.podcastId)
    AND NOT EXISTS (SELECT 1 FROM sync_clock c WHERE c.coll = sync_clock.coll AND c.rid = (SELECT p.syncId || NEW.identityKey FROM podcast p WHERE p.id = NEW.podcastId));
  INSERT INTO sync_outbox(coll, rid, field, hlc, nodeId, value)
    SELECT 'episode', (SELECT p.syncId || OLD.identityKey FROM podcast p WHERE p.id = OLD.podcastId), '~rekey', hlc, nodeId, '{"to":' || '"' || replace(replace(replace(replace(replace((SELECT p.syncId || NEW.identityKey FROM podcast p WHERE p.id = NEW.podcastId), '\', '\\'), '"', '\"'), char(10), '\n'), char(13), '\r'), char(9), '\t') || '"' || '}' FROM sync_state WHERE id = 0;
END;
