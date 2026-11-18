// मुफ़्त वाला (बिना लाइसेंस) अपनी Gemini key से DK से बोलकर बात करता है - पर रोज़ सिर्फ़ 10 मिनट.
// key उसकी अपनी है, इसलिए यहाँ AI नहीं चलता; सर्वर सिर्फ़ समय गिनता है (फ़ोन पर गिनती = data मिटाओ और फिर से 10 मिनट).
// ऐप बात शुरू होने से पहले और फिर हर हिस्से (60 सेकंड) की मियाद से पहले यहाँ से इजाज़त लेता है.
// जवाब न मिले (नेट/सर्वर बंद) = ऐप आवाज़ से बात नहीं करता (मालिक: "बिना इंटरनेट मुफ़्त यूज़र की बात रुक जाएगी").
// /live/token वाला ही live_grants इस्तेमाल होता है - एक ही समय दो रास्तों से दो बार नहीं कटता.

import { LIVE_FREE_BLOCK_SECONDS, LIVE_PAID_MINUTES } from "./config.js";
import { Db } from "./db.js";
import { levelFor, quota, requireVoiceLeft, today } from "./license.js";
import { json } from "./util.js";

/** पिछला हिस्सा इतनी देर से कम बचा हो तो नया हिस्सा (वरना वही मियाद, दोबारा नहीं गिना) */
const REUSE_MIN_MS = 5_000;

/** POST /voice/grant */
export async function onVoiceGrant(env, ident, opts = {}) {
  const now = opts.now?.() ?? Date.now();
  const db = new Db(env.DB);
  const lv = ident.owner ? { level: "owner" } : await levelFor(db, ident, now);
  if (lv.level !== "free") return json({ ok: true, level: lv.level, now, until: now + LIVE_PAID_MINUTES * 60_000, metered: false });

  const day = today(now);
  const q = quota(await db.getUsage(ident.uid, day));
  requireVoiceLeft(q);
  const prev = await db.liveUntil(ident.uid);
  let until = prev;
  let charged = 0;
  if (prev <= now + REUSE_MIN_MS) {
    const block = Math.min(q.voice_left, LIVE_FREE_BLOCK_SECONDS);
    until = now + block * 1000;
    if (await db.claimLive(ident.uid, now + REUSE_MIN_MS, until)) {
      charged = block;
      await db.addUsage(ident.uid, day, block, 0);
    } else {
      until = await db.liveUntil(ident.uid);
    }
  }
  const voice_left = Math.max(0, q.voice_left - charged);
  return json({
    ok: true,
    level: "free",
    metered: true,
    now,   // फ़ोन की घड़ी गलत हो तो भी मियाद सही (ऐप until - now गिनता है)
    until,
    quota: { voice_left, chat_left: q.chat_left, warn: voice_left === 0 },
  });
}
