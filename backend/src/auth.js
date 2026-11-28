// Firebase ID token की असली जाँच: Google की public keys से दस्तख़त (RS256), exp, iat, aud, iss.
// कोई Firebase SDK नहीं - Workers के अपने WebCrypto से.

import { JWKS_CACHE_MS } from "./config.js";
import { CODES, HttpError, b64urlDecode } from "./util.js";

export const JWKS_URL = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";
const SKEW_S = 300;

// Worker का isolate कुछ देर ज़िंदा रहता है - इतने में keys हर बार नहीं मँगाते
let cache = { at: 0, ttl: 0, keys: null };

export function resetKeyCache() {
  cache = { at: 0, ttl: 0, keys: null };
}

function maxAge(h) {
  const m = /max-age=(\d+)/i.exec(h || "");
  return m ? Number(m[1]) * 1000 : JWKS_CACHE_MS;
}

/** kid -> CryptoKey. fetcher बदलकर test में अपनी keys दी जा सकती हैं */
export async function googleKeys(fetcher = fetch, now = Date.now()) {
  if (cache.keys && now - cache.at < cache.ttl) return cache.keys;
  const r = await fetcher(JWKS_URL);
  if (!r.ok) {
    if (cache.keys) return cache.keys; // पुरानी keys से काम चलाओ
    throw new HttpError(503, CODES.AUTH_INVALID, "Google की keys नहीं मिलीं");
  }
  const body = await r.json();
  const keys = {};
  for (const jwk of body.keys || []) {
    if (!jwk.kid || jwk.kty !== "RSA") continue;
    keys[jwk.kid] = await crypto.subtle.importKey(
      "jwk",
      { kty: "RSA", n: jwk.n, e: jwk.e, alg: "RS256", ext: true },
      { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
      false,
      ["verify"],
    );
  }
  cache = { at: now, ttl: Math.min(maxAge(r.headers.get("cache-control")), 24 * 3_600_000), keys };
  return keys;
}

function bad(msg) {
  return new HttpError(401, CODES.AUTH_INVALID, msg);
}

/**
 * @returns token के claims (sub = uid)
 * @param opts.keys kid -> CryptoKey (न दें तो Google से)
 */
export async function verifyIdToken(token, projectId, opts = {}) {
  if (!projectId) throw new HttpError(503, CODES.NOT_CONFIGURED, "FIREBASE_PROJECT_ID सेट नहीं");
  const parts = String(token || "").split(".");
  if (parts.length !== 3) throw bad("token का रूप गलत");
  let header, claims;
  try {
    header = JSON.parse(new TextDecoder().decode(b64urlDecode(parts[0])));
    claims = JSON.parse(new TextDecoder().decode(b64urlDecode(parts[1])));
  } catch {
    throw bad("token पढ़ा नहीं गया");
  }
  if (header.alg !== "RS256" || !header.kid) throw bad("token का दस्तख़त-तरीका गलत");
  const nowS = Math.floor((opts.now ?? Date.now()) / 1000);
  const keys = opts.keys ?? (await googleKeys(opts.fetcher, opts.now));
  let key = keys[header.kid];
  if (!key && !opts.keys) {
    // Google ने keys बदलीं - एक बार नई मँगाओ
    resetKeyCache();
    key = (await googleKeys(opts.fetcher, opts.now))[header.kid];
  }
  if (!key) throw bad("token की key पहचानी नहीं");
  const ok = await crypto.subtle.verify(
    "RSASSA-PKCS1-v1_5",
    key,
    b64urlDecode(parts[2]),
    new TextEncoder().encode(parts[0] + "." + parts[1]),
  );
  if (!ok) throw bad("token का दस्तख़त गलत");
  if (typeof claims.exp !== "number" || claims.exp <= nowS) throw bad("token की मियाद खत्म");
  if (typeof claims.iat !== "number" || claims.iat > nowS + SKEW_S) throw bad("token का समय गलत");
  if (claims.auth_time != null && claims.auth_time > nowS + SKEW_S) throw bad("token का समय गलत");
  if (claims.aud !== projectId) throw bad("token किसी और project का");
  if (claims.iss !== "https://securetoken.google.com/" + projectId) throw bad("token किसी और ने बनाया");
  if (typeof claims.sub !== "string" || !claims.sub || claims.sub.length > 128) throw bad("token में पहचान नहीं");
  return claims;
}

export function allowedEmails(env) {
  return String(env.ALLOWED_EMAILS || "")
    .split(",")
    .map((s) => s.trim().toLowerCase())
    .filter(Boolean);
}

/**
 * मालिक = Google साइन-इन + ईमेल सूची में + email_verified. बाकी सब (गुमनाम या कोई और Google खाता) = मेहमान.
 */
export function identity(claims, env) {
  const provider = claims?.firebase?.sign_in_provider || "";
  const email = String(claims?.email || "").toLowerCase();
  const owner = provider === "google.com" && claims.email_verified === true && !!email && allowedEmails(env).includes(email);
  return { uid: claims.sub, provider, email: owner ? email : "", owner };
}

/**
 * 28 नवंबर 2026 (मालिक): DK चैट में लॉगिन की जाँच का नतीजा कुछ मिनट याद (हर संदेश पर दस्तख़त दोबारा नहीं).
 * चाबी = token का sha256 (token खुद याद में नहीं), token की मियाद (exp) के बाद कभी नहीं, सूची सीमित.
 */
export const LOGIN_CACHE_MS = 5 * 60_000;
const LOGIN_CACHE_MAX = 500;
let loginCache = new Map();

export function resetLoginCache() {
  loginCache = new Map();
}

async function tokenKey(token, projectId) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(projectId + "|" + token));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function cachedClaims(token, projectId, opts) {
  const now = opts.now ?? Date.now();
  const key = await tokenKey(token, projectId);
  const hit = loginCache.get(key);
  if (hit && hit.until > now && hit.claims.exp > Math.floor(now / 1000)) return hit.claims;
  if (hit) loginCache.delete(key);
  const claims = await verifyIdToken(token, projectId, opts);
  if (loginCache.size >= LOGIN_CACHE_MAX) loginCache.delete(loginCache.keys().next().value);
  loginCache.set(key, { claims, until: Math.min(now + LOGIN_CACHE_MS, claims.exp * 1000) });
  return claims;
}

/** Authorization: Bearer <Firebase ID token>. opts.cache = जाँच का नतीजा कुछ मिनट याद (सिर्फ़ /ai/chat) */
export async function authenticate(req, env, opts = {}) {
  const h = req.headers.get("authorization") || "";
  const m = /^Bearer\s+(.+)$/i.exec(h);
  if (!m) throw new HttpError(401, CODES.AUTH_REQUIRED, "लॉगिन ज़रूरी");
  const token = m[1].trim();
  const claims = opts.cache && env.FIREBASE_PROJECT_ID ? await cachedClaims(token, env.FIREBASE_PROJECT_ID, opts) : await verifyIdToken(token, env.FIREBASE_PROJECT_ID, opts);
  return identity(claims, env);
}
