// DK लाइव (Gemini Live) उनके लिए जिनके पास अपनी Gemini key नहीं.
// ऐप पहले की तरह सीधे Google के Live WebSocket से जुड़ता है - बस x-goog-api-key की जगह यहाँ से मिला छोटा टिकट
// (Google का ephemeral token). असली GEMINI_API_KEY कभी फ़ोन पर नहीं जाती, और आवाज़ Worker से होकर नहीं जाती
// (मुफ़्त plan की 10 ms CPU सीमा में WebSocket proxy नहीं चल सकता).
// मुफ़्त वाले: टिकट की मियाद = जितना समय गिना गया. मियाद पर Google खुद बातचीत रोकता है, ऐप नया टिकट माँगता है;
// रोज़ का समय पूरा = LICENSE_REQUIRED (वही तय शब्द).

import { LIVE_FREE_BLOCK_SECONDS, LIVE_PAID_MINUTES, LIVE_TOKEN_URL, LIVE_WS_URL } from "./config.js";
import { Db } from "./db.js";
import { levelFor, quota, requireLevel, requireServerAi, requireVoiceLeft, taskLevel, today } from "./license.js";
import { CODES, HttpError, json } from "./util.js";

/** पिछला टिकट इतनी देर से कम बचा हो तो उसे दोबारा न दो (नया लो) */
const REUSE_MIN_MS = 5_000;

/**
 * 22 नवंबर 2026 (मालिक: "DK लाइव का connection बार-बार टूट रहा"): मियाद पर Google बातचीत काटता था (मुफ़्त = हर 60s).
 * अब ऐप मियाद से पहले {next: true} माँगता है - चालू हिस्से में इतना या कम बचा हो तो अगला हिस्सा ठीक चालू के खत्म होने
 * से जुड़ा (from = until, समय दोबारा नहीं कटता), ताकि ऐप पीछे से नए टिकट वाला connection बनाकर बिना कटे बदल सके.
 */
export const LIVE_NEXT_WINDOW_MS = 20_000;

/** Google से टिकट. uses 0 = इस मियाद में कितने भी connection (दौड़/दोबारा जुड़ना) */
export async function createLiveToken(env, expiresAt, now) {
  if (!env.GEMINI_API_KEY) throw new HttpError(503, CODES.NOT_CONFIGURED, "सर्वर पर Gemini key (GEMINI_API_KEY) नहीं डली");
  const iso = (ms) => new Date(ms).toISOString();
  const r = await fetch(LIVE_TOKEN_URL, {
    method: "POST",
    headers: { "content-type": "application/json", "x-goog-api-key": env.GEMINI_API_KEY },
    body: JSON.stringify({ uses: 0, expireTime: iso(expiresAt), newSessionExpireTime: iso(Math.max(now + 1_000, expiresAt)) }),
  });
  let b = null;
  try {
    b = await r.json();
  } catch {
    b = null;
  }
  if (!r.ok || typeof b?.name !== "string" || !b.name) {
    throw new HttpError(502, CODES.ALL_FAILED, `Gemini Live का टिकट नहीं बना (${r.status})`);
  }
  return b.name;
}

/** ऐप ने अगला हिस्सा माँगा और चालू हिस्सा ख़त्म होने वाला है (पर अभी चल रहा) */
export function nextDue(want, until, now) {
  return want && until > now + REUSE_MIN_MS && until - now <= LIVE_NEXT_WINDOW_MS;
}

/** POST /live/token */
export async function onLiveToken(env, ident, opts = {}) {
  const now = opts.now?.() ?? Date.now();
  const db = env.DB ? new Db(env.DB) : null;
  const lv = ident.owner ? { level: "owner" } : await levelFor(db ?? new Db(env.DB), ident, now);
  requireLevel(lv.level, taskLevel("live"));
  requireServerAi(env, lv.level);

  if (lv.level !== "free") {
    const expires_at = now + LIVE_PAID_MINUTES * 60_000;
    const token = await createLiveToken(env, expires_at, now);
    return json({ ok: true, token, ws_url: LIVE_WS_URL, expires_at, level: lv.level });
  }

  const day = today(now);
  const q = quota(await db.getUsage(ident.uid, day));
  requireVoiceLeft(q);
  const until = await db.liveUntil(ident.uid);
  let expires_at = until;
  let charged = 0;
  const next = nextDue(opts.next === true, until, now);
  if (until <= now + REUSE_MIN_MS || next) {
    // नया हिस्सा: पहले Google से टिकट, फिर गिनती (टिकट न बने तो समय नहीं कटता)
    const block = Math.min(q.voice_left, LIVE_FREE_BLOCK_SECONDS);
    expires_at = (next ? until : now) + block * 1000;
    const token = await createLiveToken(env, expires_at, now);
    if (await db.claimLive(ident.uid, next ? until : now + REUSE_MIN_MS, expires_at)) {
      charged = block;
      await db.addUsage(ident.uid, day, block, 0);
    }
    return json(freeOut(token, expires_at, q, charged));
  }
  // पिछला हिस्सा अभी चल रहा (दौड़ / दोबारा जुड़ना) = उसी मियाद का टिकट, दोबारा नहीं गिना
  const token = await createLiveToken(env, expires_at, now);
  return json(freeOut(token, expires_at, q, charged));
}

function freeOut(token, expires_at, q, charged) {
  const voice_left = Math.max(0, q.voice_left - charged);
  return {
    ok: true,
    token,
    ws_url: LIVE_WS_URL,
    expires_at,
    level: "free",
    quota: { voice_left, chat_left: q.chat_left, warn: voice_left > 0 && voice_left <= 60 },
  };
}
