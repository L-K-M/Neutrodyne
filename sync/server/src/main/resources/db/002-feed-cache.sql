-- SPDX-License-Identifier: Unlicense
-- Server-side feed cache (one account's subscriptions). The scheduler keeps the latest publisher
-- document and its validators for batched refresh.
CREATE TABLE feed_cache (
  account_id INTEGER NOT NULL REFERENCES account(id) ON DELETE CASCADE,
  coll TEXT NOT NULL DEFAULT 'podcast' CHECK (coll = 'podcast'),
  podcast_rid TEXT NOT NULL,
  url TEXT NOT NULL,
  final_url TEXT NOT NULL,
  permanent_url TEXT,
  http_charset TEXT,
  sha256 TEXT NOT NULL,
  etag TEXT,
  last_modified TEXT,
  body BLOB NOT NULL,
  body_bytes INTEGER NOT NULL,
  checked_at INTEGER NOT NULL,
  PRIMARY KEY (account_id, url),
  FOREIGN KEY (account_id, coll, podcast_rid) REFERENCES record(account_id, coll, rid) ON DELETE CASCADE
);
CREATE INDEX feed_cache_account_checked ON feed_cache (account_id, checked_at);
