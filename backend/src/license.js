// लाइसेंस, स्तर और रोज़ की मुफ़्त सीमा - सब यहीं तय होता है (ऐप पर भरोसा नहीं).

import {
  FREE_CHAT_MESSAGES, FREE_SERVER_AI, FREE_VOICE_SECONDS, LEVEL_CACHE_MS, LEVELS, TASK_LEVEL, VOICE_TICKET_MS, WARN_SECONDS_LEFT,
} from "./config.js";
import { CODES, HttpError, hmac, istDay, isLicenseKeyShape, newLicenseKey, normalizeKey, safeEqual, sha256Hex } from "./util.js";

export const MSG = {
  VOICE_DONE: "आज की मुफ़्त बातचीत पूरी हो गई। इससे ज़्यादा बात करने के लिए पहले लाइसेंस की लें।",
  CHAT_DONE: "आज के मुफ़्त संदेश पूरे हो गए। इससे ज़्यादा बात करने के लिए पहले लाइसेंस की लें।",
  WARN: "आज की मुफ़्त बातचीत का बस एक मिनट बचा है।",
  NEED_PREMIUM: "यह सुविधा प्रीमियम लाइसेंस के साथ मिलती है।",
  NEED_BASIC: "यह सुविधा लाइसेंस के साथ मिलती है।",
  NEED_OWN_KEY: "मुफ़्त में DK आपकी अपनी Gemini key से चलता है। Settings → AI दिमाग में अपनी Gemini key डालें, या लाइसेंस की लें।",
  INVALID: "यह लाइसेंस की सही नहीं है, बंद है या इसकी मियाद खत्म हो गई।",
  FULL: "इस लाइसेंस की पर तय गिनती के फ़ोन पहले से चालू हैं।",
};

// uid -> { at, level, expires_at }
const levelCache = new Map();

export function forgetLevel(uid) {
  levelCache.delete(uid);
}

export function clearLevelCache() {
  levelCache.clear();
}

/** @returns {{level: string, expires_at: number|null}} */
export async function levelFor(db, ident, now = Date.now()) {
  if (ident.owner) return { level: "owner", expires_at: null };
  const c = levelCache.get(ident.uid);
  if (c && now - c.at < LEVEL_CACHE_MS && (c.expires_at == null || c.expires_at > now)) return c;
  const rows = await db.activeLicensesFor(ident.uid, now);
  let best = { level: "free", expires_at: null };
  for (const r of rows) {
    const lv = LEVELS[r.level] ?? 0;
    if (lv > (LEVELS[best.level] ?? 0) || (lv === LEVELS[best.level] && r.expires_at > (best.expires_at ?? 0))) {
      best = { level: r.level, expires_at: r.expires_at };
    }
  }
  const v = { at: now, ...best };
  if (levelCache.size > 5000) levelCache.clear();
  levelCache.set(ident.uid, v);
  return v;
}

export function taskLevel(task) {
  return TASK_LEVEL[task] ?? TASK_LEVEL.chat;
}

/** कम स्तर पर NEED_PREMIUM (ऐप हिंदी में बताता है कि सुविधा लाइसेंस के साथ है) */
export function requireLevel(have, need) {
  if ((LEVELS[have] ?? 0) >= (LEVELS[need] ?? 0)) return;
  throw new HttpError(403, CODES.NEED_PREMIUM, need === "basic" ? MSG.NEED_BASIC : MSG.NEED_PREMIUM, { need, level: have });
}

/** मुफ़्त वाला + सर्वर की AI = मना (config FREE_SERVER_AI). env.FREE_SERVER_AI = "1" से चालू (test / मालिक) */
export function requireServerAi(env, level) {
  if (level !== "free" || FREE_SERVER_AI || env?.FREE_SERVER_AI === "1") return;
  throw new HttpError(402, CODES.NEED_OWN_KEY, MSG.NEED_OWN_KEY, { kind: "own_key", say: MSG.NEED_OWN_KEY });
}

export function quota(usage) {
  const voice_left = Math.max(0, FREE_VOICE_SECONDS - (usage.voice_seconds || 0));
  const chat_left = Math.max(0, FREE_CHAT_MESSAGES - (usage.chat_count || 0));
  return {
    voice_left,
    chat_left,
    voice_limit: FREE_VOICE_SECONDS,
    chat_limit: FREE_CHAT_MESSAGES,
    warn: voice_left > 0 && voice_left <= WARN_SECONDS_LEFT,
  };
}

/** मुफ़्त वाले की आवाज़ का समय बचा है? नहीं तो LICENSE_REQUIRED */
export function requireVoiceLeft(q) {
  if (q.voice_left <= 0) throw new HttpError(402, CODES.LICENSE_REQUIRED, MSG.VOICE_DONE, { kind: "voice", say: MSG.VOICE_DONE, ...q });
}

export function requireChatLeft(q) {
  if (q.chat_left <= 0) throw new HttpError(402, CODES.LICENSE_REQUIRED, MSG.CHAT_DONE, { kind: "chat", say: MSG.CHAT_DONE, ...q });
}

export function today(now = Date.now()) {
  return istDay(now);
}

// ---------- लाइसेंस चालू करना ----------
export async function activate(db, uid, rawKey, now = Date.now()) {
  const key = normalizeKey(rawKey);
  if (!isLicenseKeyShape(key)) throw new HttpError(400, CODES.LICENSE_INVALID, MSG.INVALID);
  const lic = await db.licenseByHash(await sha256Hex(key));
  if (!lic || !lic.active || lic.expires_at <= now) throw new HttpError(400, CODES.LICENSE_INVALID, MSG.INVALID);
  if (!(await db.hasUse(lic.id, uid))) {
    if ((await db.countUses(lic.id)) >= lic.max_devices) throw new HttpError(409, CODES.LICENSE_FULL, MSG.FULL);
    await db.addUse(lic.id, uid, now);
  }
  forgetLevel(uid);
  return { level: lic.level, expires_at: lic.expires_at };
}

// ---------- मालिक: नई की ----------
export function checkNewLicense(body) {
  const level = String(body?.level || "");
  const days = Number(body?.days);
  const max = Number(body?.max_devices ?? 1);
  const note = body?.note == null ? null : String(body.note).slice(0, 200);
  if (!["basic", "premium"].includes(level)) throw new HttpError(400, CODES.BAD_REQUEST, "स्तर basic या premium होना चाहिए");
  if (!Number.isInteger(days) || days < 1 || days > 3650) throw new HttpError(400, CODES.BAD_REQUEST, "दिन 1 से 3650 के बीच");
  if (!Number.isInteger(max) || max < 1 || max > 100) throw new HttpError(400, CODES.BAD_REQUEST, "फ़ोन 1 से 100 के बीच");
  return { level, days, max_devices: max, note };
}

/** असली की सिर्फ़ इसी जवाब में एक बार लौटती है - D1 में सिर्फ़ उसका SHA-256 */
export async function createLicense(db, body, now = Date.now()) {
  const v = checkNewLicense(body);
  const key = newLicenseKey();
  const row = {
    id: crypto.randomUUID(),
    key_hash: await sha256Hex(key),
    level: v.level,
    expires_at: now + v.days * 86_400_000,
    max_devices: v.max_devices,
    note: v.note,
    created_at: now,
  };
  await db.insertLicense(row);
  const { key_hash: _h, ...pub } = row;
  return { key, ...pub, active: 1 };
}

// ---------- आवाज़ का टिकट ----------
// /stt के बाद मिलता है: उसी बात का /ai/chat फिर से चैट-गिनती में नहीं जुड़ता (समय /stt में जुड़ चुका).
// Cloudflare: global scope में random नहीं बन सकता (deploy पर error 10021) - पहली request पर बनता है
let isolateSecret = "";
const usedTickets = new Map();

function ticketSecret(env) {
  if (!env.WEBHOOK_SECRET && !isolateSecret) isolateSecret = crypto.randomUUID();
  return (env.WEBHOOK_SECRET || isolateSecret) + ":voice";
}

export async function voiceTicket(env, uid, now = Date.now()) {
  const exp = now + VOICE_TICKET_MS;
  const nonce = crypto.randomUUID().slice(0, 8);
  const body = `${exp}.${nonce}`;
  return `${body}.${await hmac(ticketSecret(env), uid + "." + body)}`;
}

/** टिकट एक बार ही चलता है (इसी isolate में) */
export async function useVoiceTicket(env, uid, ticket, now = Date.now()) {
  const p = String(ticket || "").split(".");
  if (p.length !== 3) return false;
  const exp = Number(p[0]);
  if (!Number.isFinite(exp) || exp < now) return false;
  if (!safeEqual(p[2], await hmac(ticketSecret(env), `${uid}.${p[0]}.${p[1]}`))) return false;
  if (usedTickets.has(ticket)) return false;
  for (const [k, e] of usedTickets) if (e < now) usedTickets.delete(k);
  usedTickets.set(ticket, exp);
  return true;
}
