// मुफ़्त वाला अपनी Gemini key से: सर्वर की AI keys नहीं (FREE_SERVER_AI बंद), आवाज़ का रोज़ का समय /voice/grant से.
import assert from "node:assert/strict";
import { afterEach, describe, it } from "node:test";
import { FREE_SERVER_AI, FREE_VOICE_SECONDS, LIVE_FREE_BLOCK_SECONDS } from "../src/config.js";
import { handle } from "../src/index.js";
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
/** असली default: मुफ़्त वाले को सर्वर की AI नहीं */
const own = (extra = {}) => env({ FREE_SERVER_AI: undefined, ...extra });
const used = async (e, uid) => {
  const r = await e.DB.prepare("SELECT voice_seconds FROM usage WHERE uid = ? AND day = ?").bind(uid, istDay(Date.now())).first();
  return r?.voice_seconds ?? 0;
};
const grant = (e, tok, o) => call(e, req("/voice/grant", { method: "POST", tok }), o);

let fx;
afterEach(() => {
  fx?.restore();
  fx = null;
  clearLevelCache();
});

describe("मुफ़्त = अपनी key (सर्वर की AI नहीं)", () => {
  it("default बंद है (मालिक की शर्त)", () => {
    assert.equal(FREE_SERVER_AI, false);
  });

  it("/ai/chat, /stt, /live/token: मुफ़्त वाले को 402 NEED_OWN_KEY, किसी AI कंपनी को बुलावा नहीं", async () => {
    fx = mockFetch(async () => new Response("{}"));
    const e = own();
    const tok = await token();
    for (const [p, body] of [["/ai/chat", { messages: [{ role: "user", content: "नमस्ते" }] }], ["/stt", new Uint8Array(10)], ["/live/token", undefined]]) {
      const r = await call(e, req(p, { method: "POST", tok, body }));
      assert.equal(r.status, 402, p);
      const b = await r.json();
      assert.equal(b.code, "NEED_OWN_KEY", p);
      assert.equal(b.say, MSG.NEED_OWN_KEY);
    }
    assert.equal(fx.calls.length, 0);
    assert.equal(await used(e, "uid-guest"), 0);
  });

  it("मालिक पर कोई रोक नहीं (सर्वर की keys से)", async () => {
    fx = mockFetch(async () => new Response(JSON.stringify({ name: "auth_tokens/x" })));
    const r = await call(own(), req("/live/token", { method: "POST", tok: await token(OWNER) }));
    assert.equal(r.status, 200);
  });
});

describe("आवाज़ का समय (/voice/grant)", () => {
  it("बिना लॉगिन = नहीं", async () => {
    assert.equal((await call(own(), req("/voice/grant", { method: "POST" }))).status, 401);
  });

  it("मुफ़्त: 60 सेकंड का हिस्सा, उतना गिना; मियाद में दोबारा = दोबारा नहीं गिना", async () => {
    const e = own();
    const tok = await token();
    const t0 = Date.now();
    const a = await (await grant(e, tok)).json();
    assert.equal(a.level, "free");
    assert.equal(a.metered, true);
    assert.ok(a.until >= t0 + LIVE_FREE_BLOCK_SECONDS * 1000);
    assert.equal(a.quota.voice_left, FREE_VOICE_SECONDS - LIVE_FREE_BLOCK_SECONDS);
    assert.equal(a.quota.warn, false);
    const b = await (await grant(e, tok)).json();
    assert.equal(b.until, a.until);
    assert.equal(await used(e, "uid-guest"), LIVE_FREE_BLOCK_SECONDS);
  });

  it("10 मिनट: आख़िरी हिस्से पर चेतावनी, फिर 402 LICENSE_REQUIRED + तय शब्द", async () => {
    const e = own();
    const tok = await token();
    let now = Date.now();
    const o = { now: () => now };
    const blocks = FREE_VOICE_SECONDS / LIVE_FREE_BLOCK_SECONDS;
    let last;
    for (let i = 0; i < blocks; i++) {
      const r = await grant(e, tok, o);
      assert.equal(r.status, 200, "हिस्सा " + i);
      last = await r.json();
      assert.equal(last.quota.warn, i === blocks - 1, "चेतावनी " + i);
      now = last.until;   // मियाद खत्म = अगला हिस्सा
    }
    const done = await grant(e, tok, o);
    assert.equal(done.status, 402);
    const b = await done.json();
    assert.equal(b.code, "LICENSE_REQUIRED");
    assert.equal(b.say, MSG.VOICE_DONE);
    assert.equal(await used(e, "uid-guest"), FREE_VOICE_SECONDS);
  });

  it("/live/token और /voice/grant एक ही समय दो बार नहीं काटते", async () => {
    fx = mockFetch(async () => new Response(JSON.stringify({ name: "auth_tokens/t" })));
    const e = env();   // सर्वर वाला रास्ता चालू (FREE_SERVER_AI = "1")
    const tok = await token();
    await call(e, req("/live/token", { method: "POST", tok }));
    await grant(e, tok);
    assert.equal(await used(e, "uid-guest"), LIVE_FREE_BLOCK_SECONDS);
  });

  it("लाइसेंस वाला / मालिक: कोई गिनती नहीं", async () => {
    const e = own();
    const lic = await (await call(e, req("/admin/licenses", { method: "POST", tok: await token(OWNER), body: { level: "basic", days: 10, max_devices: 1 } }))).json();
    const a = await token({ sub: "uid-a" });
    assert.equal((await call(e, req("/license/activate", { method: "POST", tok: a, body: { key: lic.license.key } }))).status, 200);
    const g = await (await grant(e, a)).json();
    assert.equal(g.level, "basic");
    assert.equal(g.metered, false);
    assert.equal(await used(e, "uid-a"), 0);
    const ow = await (await grant(e, await token(OWNER))).json();
    assert.equal(ow.level, "owner");
    assert.equal(ow.metered, false);
  });
});
