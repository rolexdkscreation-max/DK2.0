// छोटे साझा काम: जवाब, गलती के कोड, समय, hash, तुलना.

const enc = new TextEncoder();

/** मशीन के पढ़ने लायक गलती कोड - ऐप इन्हीं पर हिंदी संदेश दिखाता है */
export const CODES = {
  AUTH_REQUIRED: "AUTH_REQUIRED",
  AUTH_INVALID: "AUTH_INVALID",
  NEED_PREMIUM: "NEED_PREMIUM",
  LICENSE_REQUIRED: "LICENSE_REQUIRED",
  CHAT_LIMIT: "CHAT_LIMIT",
  STT_LIMIT: "STT_LIMIT",
  DB_LIMIT: "DB_LIMIT",
  BAD_REQUEST: "BAD_REQUEST",
  NOT_FOUND: "NOT_FOUND",
  FORBIDDEN: "FORBIDDEN",
  ALL_FAILED: "ALL_FAILED",
  NOT_CONFIGURED: "NOT_CONFIGURED",
  LICENSE_INVALID: "LICENSE_INVALID",
  LICENSE_FULL: "LICENSE_FULL",
};

export class HttpError extends Error {
  constructor(status, code, message, extra = {}) {
    super(message);
    this.status = status;
    this.code = code;
    this.extra = extra;
  }
}

export function json(data, status = 200, headers = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...headers },
  });
}

export function errorResponse(e) {
  if (e instanceof HttpError) return json({ ok: false, code: e.code, error: e.message, ...e.extra }, e.status);
  // अनजान गलती: अंदर का संदेश (जिसमें कोई key हो सकती है) बाहर नहीं
  return json({ ok: false, code: "SERVER_ERROR", error: "सर्वर में गलती" }, 500);
}

/** भारतीय समय (UTC+5:30) का दिन, जैसे 2026-11-17 */
export function istDay(nowMs = Date.now()) {
  return new Date(nowMs + 330 * 60_000).toISOString().slice(0, 10);
}

export async function sha256Hex(s) {
  const d = await crypto.subtle.digest("SHA-256", enc.encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** समय से न भटकने वाली तुलना (कोड लंबाई या अक्षर-दर-अक्षर का अंदाज़ा न लगे) */
export function safeEqual(a, b) {
  const x = enc.encode(String(a ?? ""));
  const y = enc.encode(String(b ?? ""));
  let diff = x.length ^ y.length;
  const n = Math.max(x.length, y.length);
  for (let i = 0; i < n; i++) diff |= (x[i] ?? 0) ^ (y[i] ?? 0);
  return diff === 0 && x.length > 0;
}

export function b64urlEncode(bytes) {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function b64urlDecode(s) {
  const t = s.replace(/-/g, "+").replace(/_/g, "/");
  const bin = atob(t + "===".slice((t.length + 3) % 4));
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

export async function hmac(secret, data) {
  const k = await crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return b64urlEncode(new Uint8Array(await crypto.subtle.sign("HMAC", k, enc.encode(data))));
}

/** लाइसेंस की: DK-XXXX-XXXX-XXXX (उलझन वाले अक्षर 0/O/1/I नहीं), 60 bit बेतरतीब */
const KEY_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
export function newLicenseKey() {
  const groups = [];
  const r = crypto.getRandomValues(new Uint8Array(12));
  for (let g = 0; g < 3; g++) {
    let s = "";
    for (let i = 0; i < 4; i++) s += KEY_CHARS[r[g * 4 + i] % 32];
    groups.push(s);
  }
  return "DK-" + groups.join("-");
}

/** डाली गई की को एक रूप में: बड़े अक्षर, बीच की खाली जगह हटे */
export function normalizeKey(k) {
  return String(k ?? "").toUpperCase().replace(/\s+/g, "").trim();
}

export function isLicenseKeyShape(k) {
  return /^DK-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}$/.test(normalizeKey(k));
}

export async function readJson(req, max = 1_000_000) {
  const len = Number(req.headers.get("content-length") || 0);
  if (len > max) throw new HttpError(413, CODES.BAD_REQUEST, "बहुत बड़ा अनुरोध");
  try {
    return await req.json();
  } catch {
    throw new HttpError(400, CODES.BAD_REQUEST, "JSON सही नहीं");
  }
}

/** D1 की रोज़ की फ्री सीमा पार? (Cloudflare के संदेश से पहचान) */
export function isDbLimit(e) {
  const m = String(e?.message ?? e ?? "").toLowerCase();
  return m.includes("limit") && (m.includes("d1") || m.includes("exceeded") || m.includes("quota") || m.includes("rows"));
}
