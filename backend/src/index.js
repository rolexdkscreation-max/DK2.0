// DK backend - Cloudflare Worker. सारे रास्ते यहीं; असली काम अलग-अलग फ़ाइलों में.

import { available, cleanMessages, collect, dkReady, openChain, pickLane, planChain, streamResponse } from "./ai.js";
import { authenticate } from "./auth.js";
import { CHAT_CHAIN, DK_LLM, KEEP_DAYS, SEARCH_CHAIN } from "./config.js";
import { countUse, health as speedHealth, persist, stat as speedState, utcDay } from "./speed.js";
import { Db } from "./db.js";
import {
  activate, createLicense, levelFor, quota, requireChatLeft, requireLevel, requireServerAi, requireVoiceLeft, serverAiOn, taskLevel, today, useVoiceTicket, voiceTicket,
} from "./license.js";
import { onImage, onImageStatus } from "./image.js";
import { onLiveToken } from "./live.js";
import { onVoiceGrant } from "./voice.js";
import { PROVIDERS as SEARCH_PROVIDERS, read, search } from "./search.js";
import { readAudio, transcribe } from "./stt.js";
import { CODES, HttpError, errorResponse, istDay, json, readJson, safeEqual } from "./util.js";

/** पीछे का काम (usage, model_health): गलती हो तो भी असली जवाब न रुके */
function later(ctx, fn) {
  const p = (async () => {
    try {
      await fn();
    } catch {
      /* D1 की सीमा/गलती - मुख्य काम पर असर नहीं */
    }
  })();
  if (ctx?.waitUntil) ctx.waitUntil(p);
  return p;
}

const TASK_STATUS = new Set(["queued", "running", "waiting", "done", "failed", "stopped"]);
const ID_RE = /^[A-Za-z0-9_.:-]{1,80}$/;

/** tasks के खेतों की जाँच. full = नया काम (title ज़रूरी) */
export function cleanTask(b, full) {
  const out = {};
  const str = (k, n) => {
    if (!(k in b)) return;
    if (b[k] != null && typeof b[k] !== "string") throw new HttpError(400, CODES.BAD_REQUEST, `${k} text होना चाहिए`);
    out[k] = b[k] == null ? null : b[k].slice(0, n);
  };
  str("title", 300);
  str("step", 500);
  str("branch", 200);
  str("pr_url", 500);
  str("error", 1000);
  if ("status" in b) {
    if (!TASK_STATUS.has(b.status)) throw new HttpError(400, CODES.BAD_REQUEST, "status गलत");
    out.status = b.status;
  }
  if ("progress" in b) {
    const p = Number(b.progress);
    if (!Number.isFinite(p)) throw new HttpError(400, CODES.BAD_REQUEST, "progress अंक होना चाहिए");
    out.progress = Math.min(100, Math.max(0, Math.round(p)));
  }
  if ("meta" in b) {
    if (b.meta != null && typeof b.meta !== "object") throw new HttpError(400, CODES.BAD_REQUEST, "meta object होना चाहिए");
    if (b.meta != null && JSON.stringify(b.meta).length > 16_000) throw new HttpError(413, CODES.BAD_REQUEST, "meta बहुत बड़ा");
    out.meta = b.meta ?? null;
  }
  if (out.pr_url && !/^https:\/\//.test(out.pr_url)) throw new HttpError(400, CODES.BAD_REQUEST, "pr_url गलत");
  if (full && !out.title) throw new HttpError(400, CODES.BAD_REQUEST, "title ज़रूरी");
  return out;
}

function isUniqueError(e) {
  return /unique|constraint/i.test(String(e?.message ?? ""));
}

async function onChat(req, env, ctx, ident, opts) {
  const body = await readJson(req);
  const { system, messages } = cleanMessages(body.messages, body.system);
  const task = typeof body.task === "string" ? body.task : "chat";
  const voice = body.mode === "voice" || body.mode === "live";
  const now = opts.now?.() ?? Date.now();
  const db = env.DB ? new Db(env.DB) : null;
  const lv = ident.owner ? { level: "owner", expires_at: null } : await levelFor(db ?? new Db(env.DB), ident, now);
  requireLevel(lv.level, taskLevel(task));
  requireServerAi(env, lv.level);

  let add = { voice: 0, chat: 0 };
  let q = null;
  const day = today(now);
  if (lv.level === "free") {
    q = quota(await db.getUsage(ident.uid, day));
    if (voice) {
      requireVoiceLeft(q);
      // /stt से आई बात का समय वहीं जुड़ चुका. फ़ोन की अपनी पहचान वाली बात: ऐप का बताया समय (कम से कम 3 सेकंड)
      if (!(await useVoiceTicket(env, ident.uid, body.voice_ticket, now))) {
        add.voice = Math.min(60, Math.max(3, Number(body.voice_seconds) || 0));
      }
    } else {
      requireChatLeft(q);
      add.chat = 1;
    }
  }

  const lane = pickLane({ task, messages, chain: body.chain, dk: body.dk });
  let open;
  try {
    open = await openChain(env, { system, messages, chain: body.chain, lane }, opts);
  } catch (e) {
    // सब फेल: रफ़्तार/आराम का हिसाब फिर भी D1 में (ऐप को सिर्फ़ साफ़ हिंदी संदेश)
    for (const m of e?.marks ?? []) later(ctx, () => saveMark(env, m, now));
    throw e;
  }
  const inChars = (system?.length ?? 0) + messages.reduce((n, m) => n + m.content.length, 0);
  const left = q ? { voice_left: Math.max(0, q.voice_left - add.voice), chat_left: Math.max(0, q.chat_left - add.chat) } : null;
  const extra = { level: lv.level, lane, ...(left ? { quota: { ...left, warn: left.voice_left > 0 && left.voice_left <= 60 } } : {}) };
  const after = (text = "") => {
    if (db && (add.voice || add.chat)) later(ctx, () => db.addUsage(ident.uid, day, add.voice, add.chat));
    if (db) for (const t of open.tried) later(ctx, () => db.recordModel(`${t.provider}/${t.model}`, t.ok, t.error, now));
    for (const m of open.marks ?? []) later(ctx, () => saveMark(env, m, now));
    later(ctx, () => countUse(env, open.provider, inChars, String(text ?? "").length, now));
  };
  if (body.stream === false) {
    const out = await collect(open);
    after(out.text);
    return json({ ok: true, ...out, ...extra });
  }
  const headers = { "x-dk-level": lv.level, "x-dk-provider": open.provider, "x-dk-model": open.model, "x-dk-lane": lane };
  if (left) {
    headers["x-dk-voice-left"] = String(left.voice_left);
    headers["x-dk-chat-left"] = String(left.chat_left);
  }
  return streamResponse(open, headers, (r) => after(r?.text), extra);
}

/** एक कोशिश का नतीजा (याद में पहले ही) D1 में */
function saveMark(env, m, now) {
  return persist(env, m, speedState(env, m.provider, now), now);
}

async function onStt(req, env, ctx, ident, opts) {
  const now = opts.now?.() ?? Date.now();
  const db = new Db(env.DB);
  const lv = ident.owner ? { level: "owner" } : await levelFor(db, ident, now);
  requireServerAi(env, lv.level);
  const day = today(now);
  let q = null;
  if (lv.level === "free") {
    q = quota(await db.getUsage(ident.uid, day));
    requireVoiceLeft(q);
  }
  const audio = await readAudio(req);
  const r = await transcribe(env, audio);
  const out = { ok: true, text: r.text, language: r.language, seconds: r.seconds, level: lv.level };
  if (q) {
    later(ctx, () => db.addUsage(ident.uid, day, r.seconds, 0));
    const voice_left = Math.max(0, q.voice_left - r.seconds);
    out.quota = { voice_left, chat_left: q.chat_left, warn: voice_left > 0 && voice_left <= 60 };
    out.voice_ticket = await voiceTicket(env, ident.uid, now);
  }
  return json(out);
}

async function onTasks(req, env, ident, path, opts) {
  const now = opts.now?.() ?? Date.now();
  const db = new Db(env.DB);
  const lv = ident.owner ? { level: "owner" } : await levelFor(db, ident, now);
  requireLevel(lv.level, "premium");
  const m = /^\/tasks\/([^/]+)$/.exec(path);
  if (path === "/tasks" && req.method === "GET") {
    const lim = Math.min(200, Math.max(1, Number(new URL(req.url).searchParams.get("limit")) || 50));
    return json({ ok: true, tasks: await db.listTasks(ident.uid, lim) });
  }
  if (path === "/tasks" && req.method === "POST") {
    const b = await readJson(req, 64_000);
    const t = cleanTask(b, true);
    const id = b.id == null ? crypto.randomUUID() : String(b.id);
    if (!ID_RE.test(id)) throw new HttpError(400, CODES.BAD_REQUEST, "id गलत");
    const row = { id, owner: ident.uid, status: "running", step: "", progress: 0, ...t, created_at: now, updated_at: now };
    try {
      await db.insertTask(row);
    } catch (e) {
      if (!isUniqueError(e)) throw e;
      // वही काम दोबारा भेजा (ऐप फिर खुला) = अपडेट; किसी और का id = मना
      if (!(await db.updateTask(ident.uid, id, t, now))) throw new HttpError(409, CODES.FORBIDDEN, "यह id पहले से किसी और काम का है");
    }
    return json({ ok: true, task: await db.getTask(ident.uid, id) }, 201);
  }
  if (m && ID_RE.test(decodeURIComponent(m[1]))) {
    const id = decodeURIComponent(m[1]);
    if (req.method === "GET") {
      const t = await db.getTask(ident.uid, id);
      if (!t) throw new HttpError(404, CODES.NOT_FOUND, "काम नहीं मिला");
      return json({ ok: true, task: t });
    }
    if (req.method === "PATCH") {
      const t = cleanTask(await readJson(req, 64_000), false);
      if (!(await db.updateTask(ident.uid, id, t, now))) throw new HttpError(404, CODES.NOT_FOUND, "काम नहीं मिला");
      return json({ ok: true, task: await db.getTask(ident.uid, id) });
    }
  }
  throw new HttpError(404, CODES.NOT_FOUND, "रास्ता नहीं मिला");
}

async function onWebhook(req, env, opts) {
  if (!env.WEBHOOK_SECRET) throw new HttpError(503, CODES.NOT_CONFIGURED, "WEBHOOK_SECRET सेट नहीं");
  const got = req.headers.get("x-dk-webhook-secret") || (req.headers.get("authorization") || "").replace(/^Bearer\s+/i, "");
  if (!safeEqual(got, env.WEBHOOK_SECRET)) throw new HttpError(401, CODES.AUTH_INVALID, "गलत webhook");
  const b = await readJson(req, 16_000);
  const branch = String(b.branch || "").slice(0, 200);
  const status = String(b.status || "").slice(0, 20);
  const runUrl = String(b.run_url || "").slice(0, 500);
  if (!branch || !status) throw new HttpError(400, CODES.BAD_REQUEST, "branch और status ज़रूरी");
  if (runUrl && !/^https:\/\/github\.com\//.test(runUrl)) throw new HttpError(400, CODES.BAD_REQUEST, "run_url गलत");
  const n = await new Db(env.DB).updateTasksByBranch(branch, status, runUrl || null, opts.now?.() ?? Date.now());
  return json({ ok: true, updated: n });
}

async function onAdmin(req, env, ident, path, opts) {
  if (!ident.owner) throw new HttpError(403, CODES.FORBIDDEN, "यह सिर्फ़ मालिक के लिए है");
  const db = new Db(env.DB);
  if (path === "/admin/licenses" && req.method === "GET") return json({ ok: true, licenses: await db.listLicenses() });
  if (path === "/admin/licenses" && req.method === "POST") {
    return json({ ok: true, license: await createLicense(db, await readJson(req, 4_000), opts.now?.() ?? Date.now()) }, 201);
  }
  const m = /^\/admin\/licenses\/([0-9a-f-]{36})(\/revoke)?$/.exec(path);
  if (m && ((req.method === "POST" && m[2]) || (req.method === "DELETE" && !m[2]))) {
    if (!(await db.revokeLicense(m[1]))) throw new HttpError(404, CODES.NOT_FOUND, "की नहीं मिली");
    return json({ ok: true, revoked: m[1] });
  }
  throw new HttpError(404, CODES.NOT_FOUND, "रास्ता नहीं मिला");
}

async function onLicenseStatus(env, ident, opts) {
  const now = opts.now?.() ?? Date.now();
  const db = new Db(env.DB);
  const lv = ident.owner ? { level: "owner", expires_at: null } : await levelFor(db, ident, now);
  const out = { ok: true, level: lv.level, expires_at: lv.expires_at ?? null, owner: ident.owner, day: today(now), server_ai: serverAiOn(env, lv.level) };
  if (lv.level === "free") out.quota = quota(await db.getUsage(ident.uid, today(now)));
  return json(out);
}

export async function handle(req, env, ctx, opts = {}) {
  const url = new URL(req.url);
  const path = url.pathname.replace(/\/+$/, "") || "/";
  const M = req.method;

  if (path === "/health" && M === "GET") return onHealth(req, env, opts);
  if (path === "/webhook/build" && M === "POST") return onWebhook(req, env, opts);

  const ident = await authenticate(req, env, opts.auth);

  if (path === "/ai/chat" && M === "POST") return onChat(req, env, ctx, ident, opts);
  if (path === "/stt" && M === "POST") return onStt(req, env, ctx, ident, opts);
  if (path === "/live/token" && M === "POST") {
    // 22 नवंबर 2026: {next: true} = मियाद से पहले अगला टिकट (बिना कटे बदलना) - खाली / गलत body = पहले जैसा
    const b = await req.json().catch(() => ({}));
    return onLiveToken(env, ident, { ...opts, next: b?.next === true });
  }
  if (path === "/voice/grant" && M === "POST") return onVoiceGrant(env, ident, opts);
  // 20 नवंबर 2026 (मालिक): इमेज - सिर्फ़ Cloudflare FLUX, सिर्फ़ मालिक ([onImage])
  if (path === "/image" && M === "POST") return onImage(req, env, ctx, ident, opts, later);
  if (path === "/image/status" && M === "GET") return onImageStatus(env, ident, opts);
  if (path === "/search" && M === "POST") {
    const now = opts.now?.() ?? Date.now();
    const lv = ident.owner ? { level: "owner" } : await levelFor(new Db(env.DB), ident, now);
    const b = await readJson(req, 8_000);
    // 18 नवंबर 2026: deep = डीप रिसर्च (उसका अपना स्तर, Google वाली खोज नहीं); only = सिर्फ़ एक कंपनी
    requireLevel(lv.level, taskLevel(b.deep === true ? "research" : "search"));
    return json({ ok: true, ...(await search(env, b.query, b.n, b.deep === true, typeof b.only === "string" ? b.only : "")) });
  }
  if (path === "/search/read" && M === "POST") {
    const now = opts.now?.() ?? Date.now();
    const lv = ident.owner ? { level: "owner" } : await levelFor(new Db(env.DB), ident, now);
    requireLevel(lv.level, taskLevel("research"));
    const b = await readJson(req, 8_000);
    return json({ ok: true, ...(await read(env, b.urls)) });
  }
  if (path === "/models/health" && M === "GET") {
    let health = [];
    try {
      health = await new Db(env.DB).modelHealth();
    } catch {
      health = [];
    }
    return json({
      ok: true,
      chain: CHAT_CHAIN.map((c) => ({ provider: c.id, model: c.model, configured: available(env, c) })),
      active: planChain(env).map((c) => c.id),
      dk_llm: { on: dkReady(env), model: DK_LLM.model, lora: !!(env.DK_LLM_LORA || DK_LLM.lora) },
      search: SEARCH_CHAIN.map((id) => ({ provider: id, configured: !!env[SEARCH_PROVIDERS[id].secret] })),
      stt: !!env.AI,
      image: !!env.AI,
      live: !!env.GEMINI_API_KEY,
      health,
    });
  }
  if (path === "/license/status" && M === "GET") return onLicenseStatus(env, ident, opts);
  if (path === "/license/activate" && M === "POST") {
    const b = await readJson(req, 2_000);
    if (ident.owner) return json({ ok: true, level: "owner", expires_at: null });
    const r = await activate(new Db(env.DB), ident.uid, b.key, opts.now?.() ?? Date.now());
    return json({ ok: true, ...r });
  }
  if (path === "/tasks" || path.startsWith("/tasks/")) return onTasks(req, env, ident, path, opts);
  if (path.startsWith("/admin/licenses")) return onAdmin(req, env, ident, path, opts);
  throw new HttpError(404, CODES.NOT_FOUND, "रास्ता नहीं मिला");
}

/**
 * /health: बिना लॉगिन = बस "चल रहा है". मालिक का token = हर AI कंपनी की हालत, औसत रफ़्तार, आज की गिनती, आख़िरी गलती.
 * (keys कभी नहीं - सिर्फ़ हाँ/ना)
 */
async function onHealth(req, env, opts) {
  const basic = { ok: true, service: "dk-backend" };
  if (!req.headers.get("authorization")) return json(basic);
  let ident;
  try {
    ident = await authenticate(req, env, opts.auth);
  } catch {
    return json(basic);
  }
  if (!ident.owner) return json(basic);
  const now = opts.now?.() ?? Date.now();
  return json({ ...basic, ai: await speedHealth(env, CHAT_CHAIN, available, now) });
}

/** रोज़ की सफ़ाई (cron, wrangler.toml [triggers]): 7 दिन से पुराना हिसाब मिटे. लाइसेंस / tasks नहीं छूते */
export async function cleanup(env, now = Date.now()) {
  const cut = now - KEEP_DAYS * 86_400_000;
  return new Db(env.DB).cleanup(istDay(cut), utcDay(cut), cut);
}

export default {
  async fetch(req, env, ctx) {
    try {
      return await handle(req, env, ctx);
    } catch (e) {
      return errorResponse(e);
    }
  },
  async scheduled(event, env, ctx) {
    ctx.waitUntil(cleanup(env, event?.scheduledTime ?? Date.now()));
  },
};
