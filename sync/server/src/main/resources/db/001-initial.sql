-- SPDX-License-Identifier: Unlicense
-- Initial server schema; independent of the client Room schema.
CREATE TABLE schema_version (version INTEGER NOT NULL);
CREATE TABLE account (
  id INTEGER PRIMARY KEY,
  username TEXT NOT NULL UNIQUE,
  display_name TEXT,
  role TEXT NOT NULL CHECK (role IN ('admin','user')),
  password_set_at INTEGER,
  clock_packed INTEGER NOT NULL DEFAULT 0,
  password_hash TEXT,
  seq INTEGER NOT NULL DEFAULT 0,
  epoch TEXT NOT NULL,
  min_cursor INTEGER NOT NULL DEFAULT 0,
  quota_records INTEGER NOT NULL DEFAULT 500000,
  created_at INTEGER NOT NULL, disabled_at INTEGER
);
CREATE TABLE device (
  id TEXT PRIMARY KEY,
  account_id INTEGER NOT NULL REFERENCES account(id) ON DELETE CASCADE,
  name TEXT NOT NULL, platform TEXT NOT NULL,
  app_version TEXT, node_id TEXT NOT NULL,
  created_at INTEGER NOT NULL, last_seen_at INTEGER, last_cursor INTEGER, revoked_at INTEGER,
  UNIQUE (account_id, id)
);
CREATE INDEX device_account ON device (account_id);
CREATE TABLE token (
  hash TEXT PRIMARY KEY,
  account_id INTEGER REFERENCES account(id) ON DELETE CASCADE,
  device_id TEXT,
  kind TEXT NOT NULL CHECK (kind IN ('device','web_session','invite','setup','app_password')),
  label TEXT, created_at INTEGER NOT NULL, last_used_at INTEGER, expires_at INTEGER, revoked_at INTEGER,
  consumed_at INTEGER,
  UNIQUE (account_id, hash),
  FOREIGN KEY (account_id, device_id) REFERENCES device(account_id, id) ON DELETE CASCADE,
  CHECK (account_id IS NOT NULL OR kind = 'setup')
);
CREATE INDEX token_account ON token (account_id, kind);
CREATE TABLE link_request (
  device_code_hash TEXT PRIMARY KEY,
  user_code TEXT NOT NULL UNIQUE,
  device_json TEXT NOT NULL,
  account_id INTEGER REFERENCES account(id) ON DELETE CASCADE,
  purpose TEXT NOT NULL CHECK (purpose IN ('device','web')),
  start_ip TEXT NOT NULL,
  consumed_at INTEGER, approver_token_hash TEXT,
  approved_at INTEGER, denied_at INTEGER, lookups INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, last_poll_at INTEGER, interval_s INTEGER NOT NULL DEFAULT 5,
  FOREIGN KEY (account_id, approver_token_hash) REFERENCES token(account_id, hash)
);
CREATE TABLE record (
  account_id INTEGER NOT NULL REFERENCES account(id) ON DELETE CASCADE,
  coll TEXT NOT NULL, rid TEXT NOT NULL,
  seq INTEGER NOT NULL,
  data TEXT NOT NULL,
  max_hlc TEXT,
  dead INTEGER NOT NULL DEFAULT 0 CHECK (dead IN (0,1)),
  dead_at INTEGER,
  merged_into TEXT,
  by_device TEXT,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (account_id, coll, rid)
);
CREATE UNIQUE INDEX record_seq ON record (account_id, seq);
CREATE INDEX record_gc ON record (account_id, dead, dead_at);
CREATE TABLE podcast_key (
  account_id INTEGER NOT NULL, feed_key TEXT NOT NULL, podcast_rid TEXT NOT NULL,
  live INTEGER NOT NULL, dead_at INTEGER,
  coll TEXT NOT NULL DEFAULT 'podcast' CHECK (coll = 'podcast'),
  PRIMARY KEY (account_id, feed_key, podcast_rid),
  FOREIGN KEY (account_id, coll, podcast_rid) REFERENCES record(account_id, coll, rid) ON DELETE CASCADE
);
CREATE INDEX podcast_key_lookup ON podcast_key (account_id, feed_key, live);
CREATE TABLE group_name (
  account_id INTEGER NOT NULL, name_key TEXT NOT NULL, group_rid TEXT NOT NULL,
  coll TEXT NOT NULL DEFAULT 'group' CHECK (coll = 'group'),
  PRIMARY KEY (account_id, name_key),
  FOREIGN KEY (account_id, coll, group_rid) REFERENCES record(account_id, coll, rid) ON DELETE CASCADE
);
CREATE TABLE audit (
  id INTEGER PRIMARY KEY, account_id INTEGER, at INTEGER NOT NULL, event TEXT NOT NULL,
  ip TEXT, device_id TEXT, detail TEXT
);
CREATE INDEX audit_at ON audit (at);
CREATE TABLE server_metadata (installation_id TEXT NOT NULL);
CREATE TABLE identity_redirect (
  account_id INTEGER NOT NULL REFERENCES account(id) ON DELETE CASCADE,
  coll TEXT NOT NULL, from_rid TEXT NOT NULL, to_rid TEXT NOT NULL,
  PRIMARY KEY (account_id, coll, from_rid),
  CHECK (from_rid <> to_rid)
);
