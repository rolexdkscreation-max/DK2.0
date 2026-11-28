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

-- DK लाइव (बिना अपनी key): मुफ़्त वाले का आख़िरी टिकट कब तक चलेगा. उस समय तक नया टिकट = फिर से नहीं गिना जाता.
CREATE TABLE IF NOT EXISTS live_grants (
  uid TEXT PRIMARY KEY,
  until_ms INTEGER NOT NULL
);

-- इमेज (FLUX, सिर्फ़ मालिक): Cloudflare के रोज़ के 10,000 Neurons का अंदाज़ा. दिन UTC से (00:00 UTC = भारत सुबह 5:30).
-- limit_hit = 1: Cloudflare ने आज सीमा पूरी बताई (तब बची गिनती 0).
CREATE TABLE IF NOT EXISTS image_usage (
  day TEXT PRIMARY KEY,
  images INTEGER NOT NULL DEFAULT 0,
  limit_hit INTEGER NOT NULL DEFAULT 0
);

-- 21 नवंबर 2026 (मालिक): "सबसे तेज़ पहले". हर AI कंपनी की एक ही row (बढ़ती नहीं, बस बदलती है).
-- ewma_ms = पहला शब्द आने का औसत समय (पुराना x 0.7 + नया x 0.3). cool_until = 429/5xx/timeout पर 60 सेकंड आराम.
-- off_until = रोज़ की सीमा पूरी - अगली 00:00 UTC (भारत सुबह 5:30) तक बंद.
CREATE TABLE IF NOT EXISTS provider_stats (
  provider TEXT PRIMARY KEY,
  ewma_ms REAL,
  samples INTEGER NOT NULL DEFAULT 0,
  cool_until INTEGER NOT NULL DEFAULT 0,
  off_until INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  last_error_at INTEGER,
  updated_at INTEGER NOT NULL DEFAULT 0
);

-- हर कंपनी की आज की गिनती (दिन UTC से, जैसे कंपनियाँ गिनती हैं). 7 दिन से पुरानी rows रोज़ अपने-आप मिटती हैं (cron).
CREATE TABLE IF NOT EXISTS provider_daily (
  day TEXT NOT NULL,
  provider TEXT NOT NULL,
  requests INTEGER NOT NULL DEFAULT 0,
  tokens INTEGER NOT NULL DEFAULT 0,
  neurons REAL NOT NULL DEFAULT 0,
  PRIMARY KEY (day, provider)
);

-- 28 नवंबर 2026 (मालिक): One AI के मॉडलों की हर काम (chat, coding, deep_research, phone) की प्राथमिकता.
-- pos छोटा = पहले. ttft_ms = पहला शब्द आने का औसत. fail_streak = लगातार चूक (3+ = सबसे नीचे). रोज़ cron + /oneai/bench से बदलती है.
CREATE TABLE IF NOT EXISTS oneai_rank (
  task TEXT NOT NULL,
  model TEXT NOT NULL,
  pos INTEGER NOT NULL DEFAULT 0,
  ttft_ms REAL,
  ok_count INTEGER NOT NULL DEFAULT 0,
  fail_count INTEGER NOT NULL DEFAULT 0,
  fail_streak INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  updated_at INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (task, model)
);
