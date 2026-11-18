-- DK backend (D1: dk-db). हर deploy पर चलता है - इसलिए सब IF NOT EXISTS, पुराना डेटा नहीं मिटता.

CREATE TABLE IF NOT EXISTS tasks (
  id TEXT PRIMARY KEY,
  owner TEXT NOT NULL,
  title TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'running',
  step TEXT NOT NULL DEFAULT '',
  progress INTEGER NOT NULL DEFAULT 0,
  branch TEXT,
  pr_url TEXT,
  error TEXT,
  meta TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS tasks_owner_updated ON tasks(owner, updated_at DESC);
CREATE INDEX IF NOT EXISTS tasks_branch ON tasks(branch);

CREATE TABLE IF NOT EXISTS model_health (
  model TEXT PRIMARY KEY,
  ok_count INTEGER NOT NULL DEFAULT 0,
  fail_count INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  last_ok INTEGER,
  last_fail INTEGER
);

CREATE TABLE IF NOT EXISTS licenses (
  id TEXT PRIMARY KEY,
  key_hash TEXT NOT NULL UNIQUE,
  level TEXT NOT NULL,
  active INTEGER NOT NULL DEFAULT 1,
  expires_at INTEGER NOT NULL,
  max_devices INTEGER NOT NULL DEFAULT 1,
  note TEXT,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS license_uses (
  key_id TEXT NOT NULL,
  uid TEXT NOT NULL,
  activated_at INTEGER NOT NULL,
  PRIMARY KEY (key_id, uid)
);
CREATE INDEX IF NOT EXISTS license_uses_uid ON license_uses(uid);

CREATE TABLE IF NOT EXISTS usage (
  uid TEXT NOT NULL,
  day TEXT NOT NULL,
  voice_seconds INTEGER NOT NULL DEFAULT 0,
  chat_count INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (uid, day)
);
