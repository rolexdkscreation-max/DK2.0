// सारा SQL सिर्फ़ इसी फ़ाइल में. बाद में D1 की जगह कोई और डेटाबेस/सर्वर आए तो सिर्फ़ यह परत बदलेगी.

import { CODES, HttpError, isDbLimit } from "./util.js";

export const DB_LIMIT_MSG = "आज डेटाबेस की मुफ़्त सीमा पूरी हो गई। थोड़ी देर बाद या कल फिर कोशिश करें।";

async function run(fn) {
  try {
    return await fn();
  } catch (e) {
    if (e instanceof HttpError) throw e;
    if (isDbLimit(e)) throw new HttpError(503, CODES.DB_LIMIT, DB_LIMIT_MSG);
    throw e;
  }
}

const TASK_FIELDS = ["title", "status", "step", "progress", "branch", "pr_url", "error", "meta"];

function taskRow(r) {
  if (!r) return null;
  let meta = null;
  try {
    meta = r.meta ? JSON.parse(r.meta) : null;
  } catch {
    meta = null;
  }
  return { ...r, meta };
}

export class Db {
  constructor(d1) {
    if (!d1) throw new HttpError(503, CODES.NOT_CONFIGURED, "D1 डेटाबेस जुड़ा नहीं");
    this.d1 = d1;
  }

  // ---------- usage (रोज़ की गिनती) ----------
  getUsage(uid, day) {
    return run(async () => {
      const r = await this.d1.prepare("SELECT voice_seconds, chat_count FROM usage WHERE uid = ? AND day = ?").bind(uid, day).first();
      return { voice_seconds: r?.voice_seconds ?? 0, chat_count: r?.chat_count ?? 0 };
    });
  }

  addUsage(uid, day, voiceSeconds, chats) {
    return run(() =>
      this.d1
        .prepare(
          "INSERT INTO usage (uid, day, voice_seconds, chat_count) VALUES (?, ?, ?, ?) " +
            "ON CONFLICT(uid, day) DO UPDATE SET voice_seconds = voice_seconds + excluded.voice_seconds, chat_count = chat_count + excluded.chat_count",
        )
        .bind(uid, day, Math.max(0, Math.round(voiceSeconds)), Math.max(0, chats))
        .run(),
    );
  }

  // ---------- licenses ----------
  insertLicense(l) {
    return run(() =>
      this.d1
        .prepare("INSERT INTO licenses (id, key_hash, level, active, expires_at, max_devices, note, created_at) VALUES (?, ?, ?, 1, ?, ?, ?, ?)")
        .bind(l.id, l.key_hash, l.level, l.expires_at, l.max_devices, l.note ?? null, l.created_at)
        .run(),
    );
  }

  licenseByHash(hash) {
    return run(() => this.d1.prepare("SELECT * FROM licenses WHERE key_hash = ?").bind(hash).first());
  }

  listLicenses() {
    return run(async () => {
      const r = await this.d1
        .prepare(
          "SELECT l.id, l.level, l.active, l.expires_at, l.max_devices, l.note, l.created_at, " +
            "(SELECT COUNT(*) FROM license_uses u WHERE u.key_id = l.id) AS devices FROM licenses l ORDER BY l.created_at DESC LIMIT 500",
        )
        .all();
      return r.results ?? [];
    });
  }

  revokeLicense(id) {
    return run(async () => {
      const r = await this.d1.prepare("UPDATE licenses SET active = 0 WHERE id = ?").bind(id).run();
      return (r.meta?.changes ?? 0) > 0;
    });
  }

  countUses(keyId) {
    return run(async () => {
      const r = await this.d1.prepare("SELECT COUNT(*) AS n FROM license_uses WHERE key_id = ?").bind(keyId).first();
      return r?.n ?? 0;
    });
  }

  hasUse(keyId, uid) {
    return run(async () => !!(await this.d1.prepare("SELECT 1 AS x FROM license_uses WHERE key_id = ? AND uid = ?").bind(keyId, uid).first()));
  }

  addUse(keyId, uid, now) {
    return run(() =>
      this.d1.prepare("INSERT OR IGNORE INTO license_uses (key_id, uid, activated_at) VALUES (?, ?, ?)").bind(keyId, uid, now).run(),
    );
  }

  /** इस uid के सारे चालू, मियाद-वाले लाइसेंस (सबसे ऊँचा स्तर ऊपर चुना जाता है license.js में) */
  activeLicensesFor(uid, now) {
    return run(async () => {
      const r = await this.d1
        .prepare(
          "SELECT l.id, l.level, l.expires_at FROM license_uses u JOIN licenses l ON l.id = u.key_id " +
            "WHERE u.uid = ? AND l.active = 1 AND l.expires_at > ?",
        )
        .bind(uid, now)
        .all();
      return r.results ?? [];
    });
  }

  // ---------- tasks ----------
  insertTask(t) {
    return run(() =>
      this.d1
        .prepare(
          "INSERT INTO tasks (id, owner, title, status, step, progress, branch, pr_url, error, meta, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        )
        .bind(t.id, t.owner, t.title, t.status, t.step, t.progress, t.branch ?? null, t.pr_url ?? null, t.error ?? null,
          t.meta == null ? null : JSON.stringify(t.meta), t.created_at, t.updated_at)
        .run(),
    );
  }

  listTasks(owner, limit = 50) {
    return run(async () => {
      const r = await this.d1.prepare("SELECT * FROM tasks WHERE owner = ? ORDER BY updated_at DESC LIMIT ?").bind(owner, limit).all();
      return (r.results ?? []).map(taskRow);
    });
  }

  /** सिर्फ़ मालिक uid का काम लौटता है - दूसरे का id डालने पर null */
  getTask(owner, id) {
    return run(async () => taskRow(await this.d1.prepare("SELECT * FROM tasks WHERE id = ? AND owner = ?").bind(id, owner).first()));
  }

  updateTask(owner, id, patch, now) {
    return run(async () => {
      const sets = [];
      const vals = [];
      for (const f of TASK_FIELDS) {
        if (!(f in patch)) continue;
        sets.push(f + " = ?");
        vals.push(f === "meta" ? (patch.meta == null ? null : JSON.stringify(patch.meta)) : patch[f]);
      }
      sets.push("updated_at = ?");
      vals.push(now);
      const r = await this.d1.prepare("UPDATE tasks SET " + sets.join(", ") + " WHERE id = ? AND owner = ?").bind(...vals, id, owner).run();
      return (r.meta?.changes ?? 0) > 0;
    });
  }

  /** GitHub build खत्म: उस branch वाले सारे काम में build की हालत */
  updateTasksByBranch(branch, status, runUrl, now) {
    return run(async () => {
      const r = await this.d1
        .prepare(
          "UPDATE tasks SET meta = json_set(COALESCE(meta, '{}'), '$.build_status', ?, '$.run_url', ?, '$.build_at', ?), updated_at = ? WHERE branch = ?",
        )
        .bind(status, runUrl, now, now, branch)
        .run();
      return r.meta?.changes ?? 0;
    });
  }

  // ---------- model_health ----------
  recordModel(model, ok, error, now) {
    return run(() =>
      ok
        ? this.d1
            .prepare(
              "INSERT INTO model_health (model, ok_count, fail_count, last_ok) VALUES (?, 1, 0, ?) " +
                "ON CONFLICT(model) DO UPDATE SET ok_count = ok_count + 1, last_ok = excluded.last_ok",
            )
            .bind(model, now)
            .run()
        : this.d1
            .prepare(
              "INSERT INTO model_health (model, ok_count, fail_count, last_error, last_fail) VALUES (?, 0, 1, ?, ?) " +
                "ON CONFLICT(model) DO UPDATE SET fail_count = fail_count + 1, last_error = excluded.last_error, last_fail = excluded.last_fail",
            )
            .bind(model, String(error ?? "").slice(0, 300), now)
            .run(),
    );
  }

  modelHealth() {
    return run(async () => (await this.d1.prepare("SELECT * FROM model_health ORDER BY model").all()).results ?? []);
  }
}
