// 20 नवंबर 2026 (मालिक): इमेज बनाना - सिर्फ़ Cloudflare Workers AI का FLUX-1 schnell, सिर्फ़ मालिक का Google अकाउंट.
// कोई दूसरी कंपनी, कोई fallback, कोई key ऐप में नहीं. इमेज यहाँ रखी नहीं जाती - सीधे ऐप को (base64).

import { DAILY_NEURONS, IMAGE_MODEL, IMAGE_PROMPT_MAX, IMAGE_STEPS, NEURONS_PER_IMAGE } from "./config.js";
import { Db } from "./db.js";
import { isQuotaError } from "./stt.js";
import { CODES, HttpError, json, readJson } from "./util.js";

export const IMAGE_LIMIT_MSG = "आज की इमेज लिमिट पूरी हो गई, कल सुबह 5:30 बजे के बाद फिर बनेगी।";
export const IMAGE_OWNER_MSG = "इमेज बनाना सिर्फ़ मालिक के Google अकाउंट से चलता है।";

/** Cloudflare का दिन: 00:00 UTC पर नया (भारत में सुबह 5:30) */
export function utcDay(now) {
  return new Date(now).toISOString().slice(0, 10);
}

/** रोज़ कितनी इमेज (लगभग) */
export const IMAGES_PER_DAY = Math.floor(DAILY_NEURONS / NEURONS_PER_IMAGE);

/** आज की लगभग बची इमेज. Cloudflare ने सीमा पूरी बताई = 0 */
export function imagesLeft(u) {
  if (u?.limit_hit) return 0;
  return Math.max(0, IMAGES_PER_DAY - (u?.images ?? 0));
}

function requireOwner(ident) {
  if (!ident.owner) throw new HttpError(403, CODES.FORBIDDEN, IMAGE_OWNER_MSG);
}

async function usage(db, day) {
  if (!db) return null;
  try {
    return await db.getImageUsage(day);
  } catch {
    return null; // D1 की गलती - गिनती न दिखे, इमेज फिर भी बने
  }
}

function status(u) {
  return { left_approx: u ? imagesLeft(u) : null, per_day_approx: IMAGES_PER_DAY, reset: "00:00 UTC (भारत सुबह 5:30)" };
}

/** GET /image/status - आज की लगभग बची इमेज */
export async function onImageStatus(env, ident, opts) {
  requireOwner(ident);
  const now = opts.now?.() ?? Date.now();
  const db = env.DB ? new Db(env.DB) : null;
  return json({ ok: true, model: IMAGE_MODEL, ...status(await usage(db, utcDay(now))) });
}

/** POST /image {prompt} -> {image: base64, mime, left_approx} */
export async function onImage(req, env, ctx, ident, opts, later) {
  requireOwner(ident);
  if (!env.AI) throw new HttpError(503, CODES.NOT_CONFIGURED, "Workers AI जुड़ा नहीं");
  const b = await readJson(req, 16_000);
  const prompt = String(b?.prompt ?? "").trim();
  if (!prompt) throw new HttpError(400, CODES.BAD_REQUEST, "इमेज का वर्णन (prompt) खाली है");
  if (prompt.length > IMAGE_PROMPT_MAX) throw new HttpError(400, CODES.BAD_REQUEST, `वर्णन बहुत लंबा - ${IMAGE_PROMPT_MAX} अक्षर तक`);
  const now = opts.now?.() ?? Date.now();
  const day = utcDay(now);
  const db = env.DB ? new Db(env.DB) : null;
  let out;
  try {
    out = await env.AI.run(IMAGE_MODEL, { prompt, steps: IMAGE_STEPS });
  } catch (e) {
    if (isQuotaError(e)) {
      if (db) await later(ctx, () => db.addImage(day, true));
      throw new HttpError(429, CODES.IMAGE_LIMIT, IMAGE_LIMIT_MSG, { left_approx: 0 });
    }
    throw new HttpError(502, CODES.ALL_FAILED, "इमेज नहीं बन पाई - थोड़ी देर बाद फिर कोशिश करें");
  }
  const image = typeof out?.image === "string" ? out.image : "";
  if (!image) throw new HttpError(502, CODES.ALL_FAILED, "इमेज नहीं बन पाई - Cloudflare से खाली जवाब");
  const u = await usage(db, day);
  if (db) later(ctx, () => db.addImage(day, false));
  const after = u ? { images: u.images + 1, limit_hit: u.limit_hit } : null;
  return json({ ok: true, image, mime: image.startsWith("iVBOR") ? "image/png" : "image/jpeg", model: IMAGE_MODEL, ...status(after) });
}
