import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { afterEach, beforeEach, describe, it } from "node:test";
import { googleKeys, resetKeyCache, verifyIdToken } from "../src/auth.js";
import { AI_CHAIN, FREE_CHAT_MESSAGES, FREE_VOICE_SECONDS, SEARCH_CHAIN, SECRET_NAMES } from "../src/config.js";
import { handle } from "../src/index.js";
import { MSG, clearLevelCache } from "../src/license.js";
import { istDay, safeEqual, sha256Hex } from "../src/util.js";
import { KEYS, OWNER, PID, PUBLIC_JWK, ctx, env, gem, mockFetch, oai, readSse, req, sse, token } from "./helpers.js";

const opts = (extra = {}) => ({ auth: { keys: KEYS }, ...extra });
const call = async (e, r, o = {}) => {
  try {
    return await handle(r, e, o.ctx ?? ctx(), opts(o));
  } catch (err) {
    const { errorResponse } = await import("../src/util.js");
    return errorResponse(err);
  }
};
const body = async (res) => res.json();

let fx;
afterEach(() => {
  fx?.restore();
  fx = null;
  clearLevelCache();
});

describe("पहचान (Firebase token)", () => {
  it("/health बिना लॉगिन", async () => {
    const r = await call(env(), req("/health"));
    assert.equal(r.status, 200);
    assert.deepEqual(await body(r), { ok: true, service: "dk-backend" });
  });

  it("बिना token = AUTH_REQUIRED", async () => {
    const r = await call(env(), req("/license/status"));
    assert.equal(r.status, 401);
    assert.equal((await body(r)).code, "AUTH_REQUIRED");
  });

  for (const [name, claims, o] of [
    ["गलत दस्तख़त", {}, { wrongKey: true }],
    ["मियाद खत्म", { exp: Math.floor(Date.now() / 1000) - 5 }, {}],
    ["दूसरा project (aud)", { aud: "other" }, {}],
    ["दूसरा बनाने वाला (iss)", { iss: "https://securetoken.google.com/other" }, {}],
    ["भविष्य का iat", { iat: Math.floor(Date.now() / 1000) + 3600 }, {}],
    ["अनजान kid", {}, { kid: "nope" }],
    ["HS256", {}, { header: { alg: "HS256" } }],
    ["खाली sub", { sub: "" }, {}],
  ]) {
    it(`नामंज़ूर: ${name}`, async () => {
      const r = await call(env(), req("/license/status", { tok: await token(claims, o) }));
      assert.equal(r.status, 401);
      assert.equal((await body(r)).code, "AUTH_INVALID");
    });
  }

  it("मालिक: Google + सूची का ईमेल + verified", async () => {
    const r = await body(await call(env(), req("/license/status", { tok: await token(OWNER) })));
    assert.equal(r.level, "owner");
    assert.equal(r.owner, true);
  });

  it("Google पर ईमेल verified नहीं या सूची में नहीं = मेहमान", async () => {
    for (const c of [{ ...OWNER, email_verified: false }, { ...OWNER, email: "x@example.com" }, { ...OWNER, firebase: { sign_in_provider: "anonymous" } }]) {
      const r = await body(await call(env(), req("/license/status", { tok: await token(c) })));
      assert.equal(r.level, "free");
      assert.equal(r.owner, false);
    }
  });

  it("Google keys की cache: दूसरी बार fetch नहीं", async () => {
    resetKeyCache();
    let n = 0;
    const f = async () => {
      n++;
      return new Response(JSON.stringify({ keys: [{ ...PUBLIC_JWK, kid: "k1", kty: "RSA" }] }), { headers: { "cache-control": "public, max-age=20000" } });
    };
    const t = await token();
    await verifyIdToken(t, PID, { fetcher: f });
    await verifyIdToken(t, PID, { fetcher: f });
    assert.equal(n, 1);
    resetKeyCache();
    await googleKeys(f);
    assert.equal(n, 2);
    resetKeyCache();
  });
});

describe("लाइसेंस", () => {
  it("सिर्फ़ मालिक की बना सकता है; DK-XXXX-XXXX-XXXX; D1 में सिर्फ़ hash", async () => {
    const e = env();
    const guest = await call(e, req("/admin/licenses", { method: "POST", tok: await token(), body: { level: "premium", days: 30 } }));
    assert.equal(guest.status, 403);
    const r = await body(await call(e, req("/admin/licenses", { method: "POST", tok: await token(OWNER), body: { level: "premium", days: 30, max_devices: 2, note: "राम" } })));
    assert.match(r.license.key, /^DK-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}$/);
    const rows = e.DB.db.exec("SELECT key_hash FROM licenses")[0].values;
    assert.equal(rows[0][0], await sha256Hex(r.license.key));
    assert.ok(!JSON.stringify(e.DB.db.exec("SELECT * FROM licenses")).includes(r.license.key));
    const list = await body(await call(e, req("/admin/licenses", { tok: await token(OWNER) })));
    assert.equal(list.licenses.length, 1);
    assert.ok(!("key" in list.licenses[0]) && !("key_hash" in list.licenses[0]));
    assert.equal(list.licenses[0].note, "राम");
  });

  it("गलत स्तर/दिन मना", async () => {
    const e = env();
    for (const b of [{ level: "owner", days: 3 }, { level: "basic", days: 0 }, { level: "basic", days: 5, max_devices: 0 }]) {
      const r = await call(e, req("/admin/licenses", { method: "POST", tok: await token(OWNER), body: b }));
      assert.equal(r.status, 400);
    }
  });

  it("चालू करना, फ़ोन की गिनती, बंद करना", async () => {
    const e = env();
    const { license } = await body(await call(e, req("/admin/licenses", { method: "POST", tok: await token(OWNER), body: { level: "basic", days: 10, max_devices: 1 } })));
    const a = await token({ sub: "uid-a" });
    const b = await token({ sub: "uid-b" });
    const ok = await body(await call(e, req("/license/activate", { method: "POST", tok: a, body: { key: license.key.toLowerCase().replace(/-/g, "- ") } })));
    assert.equal(ok.level, "basic");
    // वही फ़ोन दोबारा = ठीक
    assert.equal((await call(e, req("/license/activate", { method: "POST", tok: a, body: { key: license.key } }))).status, 200);
    const full = await call(e, req("/license/activate", { method: "POST", tok: b, body: { key: license.key } }));
    assert.equal(full.status, 409);
    assert.equal((await body(full)).code, "LICENSE_FULL");
    assert.equal((await body(await call(e, req("/license/status", { tok: a })))).level, "basic");
    assert.equal((await call(e, req(`/admin/licenses/${license.id}/revoke`, { method: "POST", tok: await token(OWNER) }))).status, 200);
    clearLevelCache();
    assert.equal((await body(await call(e, req("/license/status", { tok: a })))).level, "free");
    const again = await call(e, req("/license/activate", { method: "POST", tok: b, body: { key: license.key } }));
    assert.equal((await body(again)).code, "LICENSE_INVALID");
  });

  it("मियाद खत्म = वापस free", async () => {
    const e = env();
    const { license } = await body(await call(e, req("/admin/licenses", { method: "POST", tok: await token(OWNER), body: { level: "premium", days: 1 } })));
    const a = await token({ sub: "uid-a" });
    await call(e, req("/license/activate", { method: "POST", tok: a, body: { key: license.key } }));
    const later = { now: () => Date.now() + 2 * 86_400_000 };
    assert.equal((await body(await call(e, req("/license/status", { tok: a }), later))).level, "free");
  });

  it("गलत की", async () => {
    for (const key of ["DK-AAAA-AAAA-AAAA", "hello", ""]) {
      const r = await call(env(), req("/license/activate", { method: "POST", tok: await token(), body: { key } }));
      assert.equal((await body(r)).code, "LICENSE_INVALID");
    }
  });
});

function aiOk(text = "नमस्ते दोस्त") {
  return mockFetch((url) => {
    if (url.includes("generativelanguage")) return new Response(sse([gem(text.slice(0, 3)), gem(text.slice(3))]), { status: 200 });
    return new Response("{}", { status: 500 });
  });
}

describe("/ai/chat", () => {
  it("मालिक: stream, provider/model/tried आख़िर में", async () => {
    fx = aiOk();
    const r = await call(env(), req("/ai/chat", { method: "POST", tok: await token(OWNER), body: { messages: [{ role: "user", content: "hi" }], system: "DK" } }));
    assert.equal(r.headers.get("content-type").split(";")[0], "text/event-stream");
    const s = await readSse(r);
    assert.equal(s.text, "नमस्ते दोस्त");
    assert.equal(s.done.provider, "gemini");
    assert.equal(s.done.model, "gemini-3.8-flash");
    assert.deepEqual(s.done.tried.map((t) => t.provider), ["gemini"]);
    const sent = JSON.parse(fx.calls[0].init.body);
    assert.equal(sent.systemInstruction.parts[0].text, "DK");
    assert.equal(fx.calls[0].init.headers["x-goog-api-key"], "g-test");
  });

  it("कड़ी: Gemini 429 → हल्का Gemini खाली → Mistral", async () => {
    fx = mockFetch((url) => {
      if (url.includes("gemini-3.8-flash")) return new Response("{}", { status: 429 });
      if (url.includes("flash-lite")) return new Response(sse([gem("")]), { status: 200 });
      if (url.includes("mistral")) return new Response(sse([oai("ठीक"), oai(" है"), "[DONE]"]), { status: 200 });
      throw new Error("x");
    });
    const s = await readSse(await call(env(), req("/ai/chat", { method: "POST", tok: await token(OWNER), body: { messages: [{ role: "user", content: "hi" }] } })));
    assert.equal(s.text, "ठीक है");
    assert.deepEqual(s.done.tried, [
      { provider: "gemini", model: "gemini-3.8-flash", ok: false, error: "limit" },
      { provider: "gemini-lite", model: "gemini-3.5-flash-lite", ok: false, error: "empty" },
      { provider: "mistral", model: "mistral-small-latest", ok: true },
    ]);
    assert.equal(fx.calls[2].init.headers.authorization, "Bearer m-test");
  });

  it("पहला शब्द समय पर नहीं = अगला मॉडल", async () => {
    fx = mockFetch((url) => {
      if (url.includes("gemini-3.8-flash")) return new Response(sse([gem("देर")], { firstDelayMs: 400 }), { status: 200 });
      return new Response(sse([gem("जल्दी")]), { status: 200 });
    });
    const s = await readSse(await call(env(), req("/ai/chat", { method: "POST", tok: await token(OWNER), body: { messages: [{ role: "user", content: "hi" }] } }), { timeoutMs: 100 }));
    assert.equal(s.text, "जल्दी");
    assert.equal(s.done.tried[0].error, "timeout");
  });

  it("सब बंद = ALL_FAILED; कोई key नहीं = NOT_CONFIGURED", async () => {
    fx = mockFetch(() => new Response("{}", { status: 503 }));
    const r = await call(env(), req("/ai/chat", { method: "POST", tok: await token(OWNER), body: { messages: [{ role: "user", content: "hi" }] } }));
    const b = await body(r);
    assert.equal(b.code, "ALL_FAILED");
    assert.equal(b.tried.length, 3);
    const r2 = await call(env({ GEMINI_API_KEY: "", MISTRAL_API_KEY: "" }), req("/ai/chat", { method: "POST", tok: await token(OWNER), body: { messages: [{ role: "user", content: "hi" }] } }));
    assert.equal((await body(r2)).code, "NOT_CONFIGURED");
  });

  it("बाकी कंपनियाँ भी (Groq, OpenRouter, Z.ai, NVIDIA) - key हो तो", async () => {
    fx = mockFetch((url) => (url.includes("nvidia") ? new Response(sse([oai("nv")]), { status: 200 }) : new Response("{}", { status: 500 })));
    const e = env({ GEMINI_API_KEY: "", MISTRAL_API_KEY: "", GROQ_API_KEY: "a", OPENROUTER_API_KEY: "b", ZAI_API_KEY: "c", NVIDIA_API_KEY: "d" });
    const s = await readSse(await call(e, req("/ai/chat", { method: "POST", tok: await token(OWNER), body: { messages: [{ role: "user", content: "hi" }] } })));
    assert.deepEqual(s.done.tried.map((t) => t.provider), ["groq", "openrouter", "zai", "nvidia"]);
    // ऐप की भेजी कड़ी (सिर्फ़ नाम) से क्रम
    fx.restore();
    fx = mockFetch(() => new Response(sse([oai("z")]), { status: 200 }));
    const s2 = await readSse(await call(e, req("/ai/chat", { method: "POST", tok: await token(OWNER), body: { chain: ["zai"], messages: [{ role: "user", content: "hi" }] } })));
    assert.equal(s2.done.provider, "zai");
  });

  it("ऐप से भेजी key कभी नहीं ली जाती", async () => {
    fx = aiOk();
    await readSse(await call(env(), req("/ai/chat", { method: "POST", tok: await token(OWNER), body: { api_key: "USER-KEY", key: "USER-KEY", messages: [{ role: "user", content: "hi" }] } })));
    assert.ok(!JSON.stringify(fx.calls).includes("USER-KEY"));
  });

  it(`मुफ़्त: ${FREE_CHAT_MESSAGES} संदेश, फिर LICENSE_REQUIRED; अगले भारतीय दिन फिर`, async () => {
    fx = aiOk();
    const e = env();
    const t = await token({ sub: "uid-free" });
    const day = istDay();
    e.DB.db.run("INSERT INTO usage VALUES (?, ?, 0, ?)", ["uid-free", day, FREE_CHAT_MESSAGES - 1]);
    const c = ctx();
    const s = await readSse(await call(e, req("/ai/chat", { method: "POST", tok: t, body: { messages: [{ role: "user", content: "hi" }] } }), { ctx: c }));
    assert.equal(s.done.quota.chat_left, 0);
    await c.flush();
    const r = await call(e, req("/ai/chat", { method: "POST", tok: t, body: { messages: [{ role: "user", content: "hi" }] } }));
    assert.equal(r.status, 402);
    assert.equal((await body(r)).code, "LICENSE_REQUIRED");
    const tomorrow = { now: () => Date.now() + 86_400_000 };
    assert.equal((await call(e, req("/ai/chat", { method: "POST", tok: t, body: { messages: [{ role: "user", content: "hi" }] } }), tomorrow)).status, 200);
  });

  it("मुफ़्त: कोडिंग/DK Computer = NEED_PREMIUM; basic: खोज हाँ, कोडिंग नहीं", async () => {
    fx = aiOk();
    const e = env();
    const t = await token({ sub: "uid-free" });
    for (const task of ["coder", "computer"]) {
      const r = await call(e, req("/ai/chat", { method: "POST", tok: t, body: { task, messages: [{ role: "user", content: "hi" }] } }));
      assert.equal(r.status, 403);
      const b = await body(r);
      assert.equal(b.code, "NEED_PREMIUM");
      assert.equal(b.error, MSG.NEED_PREMIUM);
    }
    assert.equal((await body(await call(e, req("/search", { method: "POST", tok: t, body: { query: "x" } })))).code, "NEED_PREMIUM");
    const { license } = await body(await call(e, req("/admin/licenses", { method: "POST", tok: await token(OWNER), body: { level: "basic", days: 5 } })));
    await call(e, req("/license/activate", { method: "POST", tok: t, body: { key: license.key } }));
    assert.equal((await call(e, req("/ai/chat", { method: "POST", tok: t, body: { task: "research", messages: [{ role: "user", content: "hi" }] } }))).status, 200);
    assert.equal((await call(e, req("/ai/chat", { method: "POST", tok: t, body: { task: "coder", messages: [{ role: "user", content: "hi" }] } }))).status, 403);
    // basic = रोज़ की कोई सीमा नहीं
    e.DB.db.run("INSERT OR REPLACE INTO usage VALUES (?, ?, 9999, 9999)", ["uid-free", istDay()]);
    assert.equal((await call(e, req("/ai/chat", { method: "POST", tok: t, body: { messages: [{ role: "user", content: "hi" }] } }))).status, 200);
  });

  it("D1 की सीमा: मुफ़्त वाले को साफ़ हिंदी संदेश; मालिक की बात चलती रहे", async () => {
    fx = aiOk();
    const e = env();
    e.DB.failWith = "D1_ERROR: Exceeded maximum daily rows limit";
    const r = await call(e, req("/ai/chat", { method: "POST", tok: await token({ sub: "u" }), body: { messages: [{ role: "user", content: "hi" }] } }));
    assert.equal(r.status, 503);
    const b = await body(r);
    assert.equal(b.code, "DB_LIMIT");
    assert.match(b.error, /डेटाबेस/);
    const c = ctx();
    const ok = await readSse(await call(e, req("/ai/chat", { method: "POST", tok: await token(OWNER), body: { messages: [{ role: "user", content: "hi" }] } }), { ctx: c }));
    assert.equal(ok.text, "नमस्ते दोस्त");
    await c.flush(); // model_health लिखना फेल - फिर भी कोई गलती ऊपर नहीं
  });
});

describe("/stt और आवाज़ की सीमा", () => {
  const wav = (seconds) => {
    const n = 44 + seconds * 32000;
    const b = new Uint8Array(n);
    b.set([82, 73, 70, 70]);
    new DataView(b.buffer).setUint32(28, 32000, true);
    return b;
  };
  const ai = (impl) => ({ run: impl });

  it("Whisper turbo: base64 आवाज़, समय जुड़ता है, टिकट से वही बात चैट में नहीं गिनती", async () => {
    let got;
    const e = env({ AI: ai(async (model, input) => ((got = { model, input }), { text: " नमस्ते ", transcription_info: { duration: 5, language: "hi" } })) });
    const t = await token({ sub: "uid-v" });
    const c = ctx();
    const r = await body(await call(e, req("/stt?lang=hi", { method: "POST", tok: t, body: wav(5), headers: { "content-type": "audio/wav" } }), { ctx: c }));
    await c.flush();
    assert.equal(got.model, "@cf/openai/whisper-large-v3-turbo");
    assert.equal(typeof got.input.audio, "string");
    assert.equal(got.input.language, "hi");
    assert.equal(r.text, "नमस्ते");
    assert.equal(r.seconds, 5);
    assert.equal(r.quota.voice_left, FREE_VOICE_SECONDS - 5);
    assert.ok(r.voice_ticket);
    fx = aiOk();
    const c2 = ctx();
    await readSse(await call(e, req("/ai/chat", { method: "POST", tok: t, body: { mode: "voice", voice_ticket: r.voice_ticket, messages: [{ role: "user", content: "hi" }] } }), { ctx: c2 }));
    await c2.flush();
    const u = e.DB.db.exec("SELECT voice_seconds, chat_count FROM usage")[0].values[0];
    assert.deepEqual(u, [5, 0]);
    // वही टिकट दोबारा = फ़ोन वाली आवाज़ जैसा (कम से कम 3 सेकंड)
    const c3 = ctx();
    await readSse(await call(e, req("/ai/chat", { method: "POST", tok: t, body: { mode: "voice", voice_ticket: r.voice_ticket, messages: [{ role: "user", content: "hi" }] } }), { ctx: c3 }));
    await c3.flush();
    assert.deepEqual(e.DB.db.exec("SELECT voice_seconds, chat_count FROM usage")[0].values[0], [8, 0]);
  });

  it("दूसरे uid का टिकट नहीं चलता", async () => {
    const e = env({ AI: ai(async () => ({ text: "x" })) });
    const r = await body(await call(e, req("/stt", { method: "POST", tok: await token({ sub: "a" }), body: wav(2) })));
    fx = aiOk();
    const c = ctx();
    await readSse(await call(e, req("/ai/chat", { method: "POST", tok: await token({ sub: "b" }), body: { mode: "voice", voice_ticket: r.voice_ticket, voice_seconds: 10, messages: [{ role: "user", content: "hi" }] } }), { ctx: c }));
    await c.flush();
    assert.equal(e.DB.db.exec("SELECT voice_seconds FROM usage WHERE uid='b'")[0].values[0][0], 10);
  });

  it("10 मिनट पूरे = LICENSE_REQUIRED और ठीक वही हिंदी वाक्य; 1 मिनट बचे = चेतावनी", async () => {
    const e = env({ AI: ai(async () => ({ text: "x", transcription_info: { duration: 2 } })) });
    const t = await token({ sub: "uid-v" });
    e.DB.db.run("INSERT INTO usage VALUES ('uid-v', ?, ?, 0)", [istDay(), FREE_VOICE_SECONDS - 50]);
    const w = await body(await call(e, req("/stt", { method: "POST", tok: t, body: wav(2) })));
    assert.equal(w.quota.warn, true);
    e.DB.db.run("UPDATE usage SET voice_seconds = ?", [FREE_VOICE_SECONDS]);
    for (const r of [
      await call(e, req("/stt", { method: "POST", tok: t, body: wav(2) })),
      await call(e, req("/ai/chat", { method: "POST", tok: t, body: { mode: "live", messages: [{ role: "user", content: "hi" }] } })),
    ]) {
      assert.equal(r.status, 402);
      const b = await body(r);
      assert.equal(b.code, "LICENSE_REQUIRED");
      assert.equal(b.say, "आज की मुफ़्त बातचीत पूरी हो गई। इससे ज़्यादा बात करने के लिए पहले लाइसेंस की लें।");
    }
    const st = await body(await call(e, req("/license/status", { tok: t })));
    assert.equal(st.quota.voice_left, 0);
  });

  it("Whisper की मुफ़्त सीमा = STT_LIMIT (ऐप फ़ोन की पहचान पर)", async () => {
    const e = env({ AI: ai(async () => { throw new Error("AiError: 4006: you have used up your daily free allocation of 10,000 neurons"); }) });
    const r = await call(e, req("/stt", { method: "POST", tok: await token(OWNER), body: wav(1) }));
    assert.equal(r.status, 429);
    assert.equal((await body(r)).code, "STT_LIMIT");
  });

  it("आवाज़ कहीं नहीं रखी जाती", async () => {
    const e = env({ AI: ai(async () => ({ text: "x" })) });
    await call(e, req("/stt", { method: "POST", tok: await token(OWNER), body: wav(1) }));
    const all = JSON.stringify(["tasks", "usage", "licenses", "license_uses", "model_health"].map((tb) => e.DB.db.exec("SELECT * FROM " + tb)));
    assert.ok(all.length < 200);
  });
});

describe("ऐप की सारी AI कंपनियाँ backend में भी", () => {
  it("Gemini, Mistral, Groq, OpenRouter, Z.ai, NVIDIA, DeepSeek, OpenAI, Claude - हर एक का अपना secret", () => {
    const ids = AI_CHAIN.map((c) => c.id);
    for (const id of ["gemini", "mistral", "groq", "openrouter", "zai", "nvidia", "deepseek", "openai", "anthropic"]) assert.ok(ids.includes(id), id);
    for (const c of AI_CHAIN) assert.ok(SECRET_NAMES.includes(c.secret), c.secret);
    for (const n of ["TAVILY_API_KEY", "EXA_API_KEY", "LINKUP_API_KEY", "FIRECRAWL_API_KEY"]) assert.ok(SECRET_NAMES.includes(n), n);
    // paid वाली सबसे आख़िर में (मुफ़्त पहले)
    assert.ok(ids.indexOf("nvidia") < ids.indexOf("deepseek"));
  });

  it("deploy workflow हर secret भेजता है", async () => {
    const wf = readFileSync(new URL("../../.github/workflows/deploy-backend.yml", import.meta.url), "utf8");
    for (const n of SECRET_NAMES) {
      assert.ok(wf.includes(`${n}: \${{ secrets.${n} }}`), n);
      assert.ok(new RegExp(`for n in [^;]*\\b${n}\\b`).test(wf), n + " loop");
    }
  });
});

describe("/search", () => {
  it("Tavily फेल → Exa; नतीजे title/url/snippet", async () => {
    fx = mockFetch((url) => {
      if (url.includes("tavily")) return new Response("{}", { status: 432 });
      if (url.includes("exa.ai")) return Response.json({ results: [{ title: "DK", url: "https://dk.example", text: "पाठ" }, { title: "bad", url: "javascript:1" }] });
      throw new Error("x");
    });
    const r = await body(await call(env({ TAVILY_API_KEY: "t", EXA_API_KEY: "x" }), req("/search", { method: "POST", tok: await token(OWNER), body: { query: "dk" } })));
    assert.equal(r.provider, "exa");
    assert.deepEqual(r.results, [{ title: "DK", url: "https://dk.example", snippet: "पाठ" }]);
  });

  it("ऐप की सारी खोज कंपनियाँ: Tavily → Exa → Linkup → Firecrawl → Google", async () => {
    assert.deepEqual(SEARCH_CHAIN, ["tavily", "exa", "linkup", "firecrawl", "google"]);
    fx = mockFetch((url, init) => {
      if (url.includes("linkup.so")) {
        assert.equal(init.headers.authorization, "Bearer l");
        return new Response("{}", { status: 500 });
      }
      if (url.includes("firecrawl.dev")) return Response.json({ data: { web: [{ title: "F", url: "https://f.example", description: "फ़ायर" }] } });
      throw new Error("x");
    });
    const r = await body(await call(env({ GEMINI_API_KEY: "", LINKUP_API_KEY: "l", FIRECRAWL_API_KEY: "f" }), req("/search", { method: "POST", tok: await token(OWNER), body: { query: "dk" } })));
    assert.equal(r.provider, "firecrawl");
    assert.deepEqual(r.results, [{ title: "F", url: "https://f.example", snippet: "फ़ायर" }]);
    assert.deepEqual(r.tried.map((t) => t.provider), ["linkup", "firecrawl"]);
  });

  it("Linkup के नतीजे (name/url/content)", async () => {
    fx = mockFetch(() => Response.json({ results: [{ name: "L", url: "https://l.example", content: "लिंक" }] }));
    const r = await body(await call(env({ GEMINI_API_KEY: "", LINKUP_API_KEY: "l" }), req("/search", { method: "POST", tok: await token(OWNER), body: { query: "dk" } })));
    assert.equal(r.provider, "linkup");
    assert.equal(r.results[0].title, "L");
  });

  it("आख़िर में Google (Gemini google_search)", async () => {
    fx = mockFetch((url, init) => {
      assert.ok(url.includes("generateContent"));
      assert.deepEqual(JSON.parse(init.body).tools, [{ google_search: {} }]);
      return Response.json({ candidates: [{ content: { parts: [{ text: "जवाब" }] }, groundingMetadata: { groundingChunks: [{ web: { uri: "https://g.example", title: "G" } }] } }] });
    });
    const r = await body(await call(env(), req("/search", { method: "POST", tok: await token(OWNER), body: { query: "dk" } })));
    assert.equal(r.provider, "google");
    assert.equal(r.results[0].url, "https://g.example");
  });
});

describe("/tasks", () => {
  it("सिर्फ़ premium/मालिक; हर काम सिर्फ़ उसके uid को", async () => {
    const e = env();
    const free = await call(e, req("/tasks", { tok: await token({ sub: "f" }) }));
    assert.equal((await body(free)).code, "NEED_PREMIUM");
    const { license } = await body(await call(e, req("/admin/licenses", { method: "POST", tok: await token(OWNER), body: { level: "premium", days: 5, max_devices: 2 } })));
    const a = await token({ sub: "pa" });
    const b = await token({ sub: "pb" });
    await call(e, req("/license/activate", { method: "POST", tok: a, body: { key: license.key } }));
    await call(e, req("/license/activate", { method: "POST", tok: b, body: { key: license.key } }));
    const made = await call(e, req("/tasks", { method: "POST", tok: a, body: { id: "job-1", title: "ऐप बनाओ", branch: "feature/dk-x", meta: { who: "coder" } } }));
    assert.equal(made.status, 201);
    const p = await body(await call(e, req("/tasks/job-1", { method: "PATCH", tok: a, body: { step: "फ़ाइल लिखी", progress: 140, status: "running" } })));
    assert.equal(p.task.progress, 100);
    assert.equal(p.task.step, "फ़ाइल लिखी");
    assert.deepEqual(p.task.meta, { who: "coder" });
    assert.equal((await call(e, req("/tasks/job-1", { tok: b }))).status, 404);
    assert.equal((await call(e, req("/tasks/job-1", { method: "PATCH", tok: b, body: { status: "done" } }))).status, 404);
    assert.equal((await call(e, req("/tasks", { method: "POST", tok: b, body: { id: "job-1", title: "चोरी" } }))).status, 409);
    assert.equal((await body(await call(e, req("/tasks", { tok: b })))).tasks.length, 0);
    assert.equal((await body(await call(e, req("/tasks", { tok: a })))).tasks[0].title, "ऐप बनाओ");
    // वही काम फिर से POST (ऐप दोबारा खुला) = अपडेट
    await call(e, req("/tasks", { method: "POST", tok: a, body: { id: "job-1", title: "ऐप बनाओ", status: "waiting" } }));
    assert.equal((await body(await call(e, req("/tasks/job-1", { tok: a })))).task.status, "waiting");
    for (const bad of [{ status: "hacked" }, { pr_url: "javascript:x" }, { meta: "x" }]) {
      assert.equal((await call(e, req("/tasks/job-1", { method: "PATCH", tok: a, body: bad }))).status, 400);
    }
  });

  it("webhook: secret बराबर हो तभी; उस branch के काम में build की हालत", async () => {
    const e = env();
    await call(e, req("/tasks", { method: "POST", tok: await token(OWNER), body: { id: "j", title: "t", branch: "feature/dk-x" } }));
    const bad = await call(e, req("/webhook/build", { method: "POST", headers: { "x-dk-webhook-secret": "wrong" }, body: { branch: "feature/dk-x", status: "success" } }));
    assert.equal(bad.status, 401);
    const none = await call(env({ WEBHOOK_SECRET: "" }), req("/webhook/build", { method: "POST", body: { branch: "x", status: "y" } }));
    assert.equal(none.status, 503);
    const ok = await body(await call(e, req("/webhook/build", { method: "POST", headers: { "x-dk-webhook-secret": "test-webhook-secret-123" }, body: { branch: "feature/dk-x", status: "success", run_url: "https://github.com/o/r/actions/runs/1" } })));
    assert.equal(ok.updated, 1);
    const t = await body(await call(e, req("/tasks/j", { tok: await token(OWNER) })));
    assert.equal(t.task.meta.build_status, "success");
    assert.equal(t.task.meta.run_url, "https://github.com/o/r/actions/runs/1");
  });
});

describe("बाकी", () => {
  it("safeEqual", () => {
    assert.ok(safeEqual("abc", "abc"));
    assert.ok(!safeEqual("abc", "abd"));
    assert.ok(!safeEqual("abc", "abcd"));
    assert.ok(!safeEqual("", ""));
  });

  it("भारतीय दिन: रात 12 बजे IST पर बदलता है", () => {
    assert.equal(istDay(Date.parse("2026-11-16T18:29:00Z")), "2026-11-16");
    assert.equal(istDay(Date.parse("2026-11-16T18:31:00Z")), "2026-11-17");
  });

  it("schema दो बार चले तो भी ठीक (IF NOT EXISTS)", async () => {
    const e = env();
    const { SCHEMA } = await import("./helpers.js");
    e.DB.db.exec(SCHEMA);
    assert.ok(/CREATE TABLE IF NOT EXISTS usage/.test(SCHEMA));
    for (const t of ["tasks", "model_health", "licenses", "license_uses", "usage"]) assert.ok(SCHEMA.includes("CREATE TABLE IF NOT EXISTS " + t + " ("));
  });

  it("wrangler.toml में कोई असली ID नहीं - सिर्फ़ placeholder", () => {
    const w = readFileSync(new URL("../wrangler.toml", import.meta.url), "utf8");
    assert.match(w, /database_id = "__D1_DATABASE_ID__"/);
    assert.match(w, /FIREBASE_PROJECT_ID = "__FIREBASE_PROJECT_ID__"/);
    assert.match(w, /binding = "AI"/);
    assert.ok(!/account_id/.test(w));
  });

  it("सारा SQL सिर्फ़ db.js में", () => {
    for (const f of ["index.js", "license.js", "ai.js", "search.js", "stt.js", "auth.js"]) {
      const s = readFileSync(new URL("../src/" + f, import.meta.url), "utf8");
      assert.ok(!/\b(SELECT|INSERT|UPDATE|DELETE)\s+(\*|INTO|FROM|[a-z_]+\s+SET)/.test(s), f);
    }
  });

  it("models/health: कौन-सी key है बस हाँ/ना - key खुद नहीं", async () => {
    const r = await body(await call(env(), req("/models/health", { tok: await token(OWNER) })));
    assert.ok(!JSON.stringify(r).includes("g-test"));
    assert.equal(r.chain.find((c) => c.provider === "mistral").configured, true);
    assert.equal(r.chain.find((c) => c.provider === "groq").configured, false);
  });

  it("अनजानी गलती में अंदर का संदेश बाहर नहीं", async () => {
    const e = env();
    e.DB.failWith = "secret-ish internal g-test";
    const r = await call(e, req("/license/status", { tok: await token() }));
    assert.equal(r.status, 500);
    assert.ok(!(await r.text()).includes("g-test"));
  });
});
