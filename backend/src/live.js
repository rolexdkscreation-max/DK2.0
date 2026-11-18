// DK लाइव (Gemini Live) उनके लिए जिनके पास अपनी Gemini key नहीं.
// ऐप पहले की तरह सीधे Google के Live WebSocket से जुड़ता है - बस x-goog-api-key की जगह यहाँ से मिला छोटा टिकट
// (Google का ephemeral token). असली GEMINI_API_KEY कभी फ़ोन पर नहीं जाती, और आवाज़ Worker से होकर नहीं जाती
// (मुफ़्त plan की 10 ms CPU सीमा में WebSocket proxy नहीं चल सकता).
// मुफ़्त वाले: टिकट की मियाद = जितना समय गिना गया. मियाद पर Google खुद बातचीत रोकता है, ऐप नया टिकट माँगता है;
// रोज़ का समय पूरा = LICENSE_REQUIRED (वही तय शब्द).

import { LIVE_FREE_BLOCK_SECONDS, LIVE_PAID_MINUTES, LIVE_TOKEN_URL, LIVE_WS_URL } from "./config.js";
import { Db } from "./db.js";
import { levelFor, quota, requireLevel, requireVoiceLeft, taskLevel, today } from "./license.js";
import { CODES, HttpError, json } from "./util.js";

/** पिछला टिकट इतनी देर से कम बचा हो तो उसे दोबारा न दो (नया लो) */
const REUSE_MIN_MS = 5_000;

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

/** POST /live/token */
export async function onLiveToken(env, ident, opts = {}) {
  const now = opts.now?.() ?? Date.now();
  const db = env.DB ? new Db(env.DB) : null;
  const lv = ident.owner ? { level: "owner" } : await levelFor(db ?? new Db(env.DB), ident, now);
  requireLevel(lv.level, taskLevel("live"));

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
  if (until <= now + REUSE_MIN_MS) {
    // नया हिस्सा: पहले Google से टिकट, फिर गिनती (टिकट न बने तो समय नहीं कटता)
    const block = Math.min(q.voice_left, LIVE_FREE_BLOCK_SECONDS);
    expires_at = now + block * 1000;
    const token = await createLiveToken(env, expires_at, now);
    if (await db.claimLive(ident.uid, now + REUSE_MIN_MS, expires_at)) {
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
