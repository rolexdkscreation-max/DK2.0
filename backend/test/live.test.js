// DK लाइव बिना अपनी key: /live/token (Google का छोटा टिकट), मुफ़्त समय की गिनती, असली key कभी बाहर नहीं.
import assert from "node:assert/strict";
import { afterEach, describe, it } from "node:test";
import { FREE_VOICE_SECONDS, LIVE_FREE_BLOCK_SECONDS, LIVE_PAID_MINUTES, LIVE_TOKEN_URL, LIVE_WS_URL } from "../src/config.js";
import { handle } from "../src/index.js";
import { LIVE_NEXT_WINDOW_MS, nextDue } from "../src/live.js";
import { MSG, clearLevelCache } from "../src/license.js";
import { errorResponse, istDay } from "../src/util.js";
import { KEYS, OWNER, ctx, env, mockFetch, req, token } from "./helpers.js";

const call = async (e, r, o = {}) => {
  try {
    return await handle(r, e, ctx(), { auth: { keys: KEYS }, ...o });
  } catch (err) {
    return errorResponse(err);
  }
};

let fx;
afterEach(() => {
  fx?.restore();
  fx = null;
  clearLevelCache();
});

function google(status = 200) {
  let n = 0;
  return mockFetch(async (url, init) => {
    assert.equal(url, LIVE_TOKEN_URL);
    n++;
    if (status !== 200) return new Response(JSON.stringify({ error: { message: "bad" } }), { status });
    return new Response(JSON.stringify({ name: `auth_tokens/t${n}`, body: JSON.parse(init.body) }));
  });
}

const used = async (e, uid) => {
  const r = await e.DB.prepare("SELECT voice_seconds FROM usage WHERE uid = ? AND day = ?").bind(uid, istDay(Date.now())).first();
  return r?.voice_seconds ?? 0;
};

describe("DK लाइव टिकट (/live/token)", () => {
  it("बिना लॉगिन = नहीं", async () => {
    fx = google();
    const r = await call(env(), req("/live/token", { method: "POST" }));
    assert.equal(r.status, 401);
    assert.equal(fx.calls.length, 0);
  });

  it("मुफ़्त: 60 सेकंड का टिकट, उतना समय गिना, असली key जवाब में नहीं", async () => {
    fx = google();
    const e = env();
    const t0 = Date.now();
    const r = await call(e, req("/live/token", { method: "POST", tok: await token() }));
    assert.equal(r.status, 200);
    const b = await r.json();
    assert.equal(b.token, "auth_tokens/t1");
    assert.equal(b.ws_url, LIVE_WS_URL);
    assert.equal(b.level, "free");
    assert.ok(b.expires_at >= t0 + LIVE_FREE_BLOCK_SECONDS * 1000 && b.expires_at <= Date.now() + LIVE_FREE_BLOCK_SECONDS * 1000);
    assert.equal(b.quota.voice_left, FREE_VOICE_SECONDS - LIVE_FREE_BLOCK_SECONDS);
    assert.ok(!JSON.stringify(b).includes("g-test"));
    assert.equal(await used(e, "uid-guest"), LIVE_FREE_BLOCK_SECONDS);
    // Google को: असली key header में, मियाद वही
    const sent = fx.calls[0].init;
    assert.equal(sent.headers["x-goog-api-key"], "g-test");
    const gb = JSON.parse(sent.body);
    assert.equal(gb.uses, 0);
    assert.equal(Date.parse(gb.expireTime), b.expires_at);
  });

  it("मियाद के अंदर दोबारा माँगा (दौड़ / दोबारा जुड़ना) = दोबारा नहीं गिना, वही मियाद", async () => {
    fx = google();
    const e = env();
    const tok = await token();
    const a = await (await call(e, req("/live/token", { method: "POST", tok }))).json();
    const b = await (await call(e, req("/live/token", { method: "POST", tok }))).json();
    assert.equal(b.expires_at, a.expires_at);
    assert.equal(await used(e, "uid-guest"), LIVE_FREE_BLOCK_SECONDS);
    assert.equal(b.quota.voice_left, FREE_VOICE_SECONDS - LIVE_FREE_BLOCK_SECONDS);
  });

  it("मियाद खत्म = अगला हिस्सा गिना", async () => {
    fx = google();
    const e = env();
    const tok = await token();
    let now = Date.now();
    await call(e, req("/live/token", { method: "POST", tok }), { now: () => now });
    now += LIVE_FREE_BLOCK_SECONDS * 1000 + 1;
    const b = await (await call(e, req("/live/token", { method: "POST", tok }), { now: () => now })).json();
    assert.equal(b.quota.voice_left, FREE_VOICE_SECONDS - 2 * LIVE_FREE_BLOCK_SECONDS);
  });

  it("मियाद से पहले next = अगला हिस्सा चालू के ठीक बाद से (बीच में कटना नहीं), एक ही बार गिना", async () => {
    fx = google();
    const e = env();
    const tok = await token();
    let now = Date.now();
    const a = await (await call(e, req("/live/token", { method: "POST", tok }), { now: () => now })).json();
    // बहुत पहले next = अभी वही हिस्सा (समय नहीं कटता)
    now = a.expires_at - LIVE_NEXT_WINDOW_MS - 1_000;
    const early = await (await call(e, req("/live/token", { method: "POST", tok, body: { next: true } }), { now: () => now })).json();
    assert.equal(early.expires_at, a.expires_at);
    assert.equal(await used(e, "uid-guest"), LIVE_FREE_BLOCK_SECONDS);
    // आख़िरी 20s में next = नया टिकट, मियाद = पुरानी + एक हिस्सा
    now = a.expires_at - 12_000;
    const b = await (await call(e, req("/live/token", { method: "POST", tok, body: { next: true } }), { now: () => now })).json();
    assert.equal(b.expires_at, a.expires_at + LIVE_FREE_BLOCK_SECONDS * 1000);
    assert.notEqual(b.token, a.token);
    assert.equal(await used(e, "uid-guest"), 2 * LIVE_FREE_BLOCK_SECONDS);
    assert.equal(Date.parse(JSON.parse(fx.calls.at(-1).init.body).expireTime), b.expires_at);
    // दोबारा next (दौड़) = वही नई मियाद, दोबारा नहीं गिना
    const c = await (await call(e, req("/live/token", { method: "POST", tok, body: { next: true } }), { now: () => now + 500 })).json();
    assert.equal(c.expires_at, b.expires_at);
    assert.equal(await used(e, "uid-guest"), 2 * LIVE_FREE_BLOCK_SECONDS);
  });

  it("बिना next = आख़िरी 20s में भी वही हिस्सा (पुराना तरीका वैसा ही)", async () => {
    fx = google();
    const e = env();
    const tok = await token();
    let now = Date.now();
    const a = await (await call(e, req("/live/token", { method: "POST", tok }), { now: () => now })).json();
    now = a.expires_at - 12_000;
    const b = await (await call(e, req("/live/token", { method: "POST", tok }), { now: () => now })).json();
    assert.equal(b.expires_at, a.expires_at);
    assert.equal(await used(e, "uid-guest"), LIVE_FREE_BLOCK_SECONDS);
  });

  it("nextDue: सिर्फ़ माँगने पर और आख़िरी हिस्से में", () => {
    const now = 1_000_000;
    assert.equal(nextDue(true, now + 15_000, now), true);
    assert.equal(nextDue(false, now + 15_000, now), false);
    assert.equal(nextDue(true, now + LIVE_NEXT_WINDOW_MS + 1, now), false);
    assert.equal(nextDue(true, now + 5_000, now), false);   // 5s से कम = पुराना रास्ता वैसे भी नया हिस्सा देता है
    assert.equal(nextDue(true, 0, now), false);
  });

  it("आख़िरी बचे सेकंड = उतना ही छोटा टिकट; पूरा = LICENSE_REQUIRED (तय शब्द), Google को बुलाया ही नहीं", async () => {
    fx = google();
    const e = env();
    const day = istDay(Date.now());
    await e.DB.prepare("INSERT INTO usage (uid, day, voice_seconds, chat_count) VALUES (?, ?, ?, 0)").bind("uid-guest", day, FREE_VOICE_SECONDS - 20).run();
    const tok = await token();
    const now = Date.now();
    const a = await (await call(e, req("/live/token", { method: "POST", tok }), { now: () => now })).json();
    assert.ok(a.expires_at - now === 20_000);
    assert.equal(a.quota.voice_left, 0);
    const later = now + 21_000;
    const n = fx.calls.length;
    const r = await call(e, req("/live/token", { method: "POST", tok }), { now: () => later });
    assert.equal(r.status, 402);
    const b = await r.json();
    assert.equal(b.code, "LICENSE_REQUIRED");
    assert.equal(b.say, MSG.VOICE_DONE);
    assert.equal(fx.calls.length, n);
  });

  it("Google ने टिकट नहीं दिया = गलती, और समय नहीं कटा", async () => {
    fx = google(500);
    const e = env();
    const r = await call(e, req("/live/token", { method: "POST", tok: await token() }));
    assert.equal(r.status, 502);
    assert.equal(await used(e, "uid-guest"), 0);
  });

  it("सर्वर पर Gemini key नहीं = NOT_CONFIGURED", async () => {
    fx = google();
    const r = await call(env({ GEMINI_API_KEY: undefined }), req("/live/token", { method: "POST", tok: await token() }));
    assert.equal(r.status, 503);
    assert.equal((await r.json()).code, "NOT_CONFIGURED");
  });

  it("मालिक: 30 मिनट का टिकट, कोई गिनती नहीं", async () => {
    fx = google();
    const e = env();
    const now = Date.now();
    const b = await (await call(e, req("/live/token", { method: "POST", tok: await token(OWNER) }), { now: () => now })).json();
    assert.equal(b.level, "owner");
    assert.equal(b.expires_at, now + LIVE_PAID_MINUTES * 60_000);
    assert.equal(b.quota, undefined);
    assert.equal(await used(e, "uid-owner"), 0);
  });
});
