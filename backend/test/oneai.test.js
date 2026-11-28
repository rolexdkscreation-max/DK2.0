// 28 नवंबर 2026 (मालिक): One AI API Key - /oneai/models (10 मिनट याद), /oneai/chat (stream), कड़ी में नई कड़ी,
// 429/5xx/timeout/खाली = चुपचाप अगली, 4 सेकंड, कतार (एक कुंजी = एक अनुरोध), D1 रैंकिंग, रोज़ परखना, पैसे वाला fallback नहीं.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { afterEach, describe, it } from "node:test";
import { AI_CHAIN, CHAT_CHAIN, ONEAI, PROVIDER_LIMITS, SECRET_NAMES } from "../src/config.js";
import { handle } from "../src/index.js";
import { clearLevelCache } from "../src/license.js";
import { acquire, autoOk, isFree, oneaiTask, orderRows, resetOneai } from "../src/oneai.js";
import { errorResponse } from "../src/util.js";
import { KEYS, OWNER, ctx, env, mockFetch, oai, readSse, req, sse, token } from "./helpers.js";

const call = async (e, r, o = {}) => {
  try {
    return await handle(r, e, o.ctx ?? ctx(), { auth: { keys: KEYS }, ...o });
  } catch (err) {
    return errorResponse(err);
  }
};
const rows = (e, sql) => {
  const r = e.DB.db.exec(sql);
  if (!r.length) return [];
  return r[0].values.map((v) => Object.fromEntries(r[0].columns.map((c, i) => [c, v[i]])));
};
const MODELS = [
  "deepseek-deepseek-v4.1-flash:free", "z-ai-glm-5.3-flash:free", "minimaxai-minimax-m3-1-flash:free", "grok-4.7-fast:free",
  "moonshotai-kimi-k3.1-pro:free", "z-ai-glm-5.3:free", "nvidia-nemotron-3-ultra-550b-a55b:free",
  "gpt-6-luna:free", "gpt-6-luna", "roleplay-chat:free", "qwen3.8-27b-uncensored:free", "claude-opus-5-5",
];
/** सिर्फ़ One AI (बाकी कंपनियाँ नहीं) */
const oneEnv = (extra = {}) => env({ GEMINI_API_KEY: "", MISTRAL_API_KEY: "", ONEAI_API_KEY: "oa-secret-1", ...extra });
const models = () => Response.json({ object: "list", data: MODELS.map((id) => ({ id, object: "model" })) });
const chatBody = (calls) => calls.filter((c) => c.url.endsWith("/chat/completions")).map((c) => JSON.parse(c.init.body));
const oneChat = async (b = {}, path = "/oneai/chat") =>
  req(path, { method: "POST", tok: await token(OWNER), body: { messages: [{ role: "user", content: "hi" }], ...b } });

let fx;
afterEach(() => {
  fx?.restore();
  fx = null;
  clearLevelCache();
});

describe("One AI: config और secret", () => {
  it("नई कड़ी मुफ़्त वालों के बाद, paid से पहले; बाकी क्रम वही", () => {
    const ids = AI_CHAIN.map((c) => c.id);
    assert.deepEqual(ids.filter((x) => x !== "oneai"), ["gemini", "gemini-lite", "mistral", "groq", "cohere", "openrouter", "zai", "nvidia", "deepseek", "openai", "anthropic"]);
    assert.equal(ids.indexOf("oneai"), ids.indexOf("nvidia") + 1);
    assert.ok(CHAT_CHAIN.some((c) => c.id === "oneai"));
    const c = AI_CHAIN.find((x) => x.id === "oneai");
    assert.equal(c.base, "https://api.oneaiapikey.com/v1");
    assert.equal(c.firstTokenMs, 4_000);
    assert.ok(SECRET_NAMES.includes("ONEAI_API_KEY") && SECRET_NAMES.includes("ONEAI_API_KEY_2"));
    assert.equal(PROVIDER_LIMITS.oneai.requests, 100);
  });

  it("deploy workflow दोनों secret भेजता है; कोड में असली कुंजी नहीं", () => {
    const wf = readFileSync(new URL("../../.github/workflows/deploy-backend.yml", import.meta.url), "utf8");
    for (const n of ["ONEAI_API_KEY", "ONEAI_API_KEY_2"]) assert.ok(wf.includes(`${n}: \${{ secrets.${n} }}`), n);
    const src = readFileSync(new URL("../src/oneai.js", import.meta.url), "utf8");
    assert.ok(!/sk-[A-Za-z0-9]{8,}/.test(src));
  });

  it("मुफ़्त = ID के अंत में :free; मना वाले और पैसे वाले जोड़ीदार अपने-आप नहीं", () => {
    const ids = new Set(MODELS);
    assert.ok(isFree("z-ai-glm-5.3:free") && !isFree("claude-opus-5-5") && !isFree("free-model"));
    assert.ok(autoOk("z-ai-glm-5.3:free", ids));
    assert.ok(!autoOk("roleplay-chat:free", ids));
    assert.ok(!autoOk("qwen3.8-27b-uncensored:free", ids));
    assert.ok(!autoOk("stepaudio-3-chat-preview:free", null));
    assert.ok(!autoOk("gpt-6-luna:free", ids)); // "gpt-6-luna" (पैसे वाला) भी सूची में = क्रेडिट कटेगा
    assert.ok(!autoOk("grok-4.7-fast:free", new Set(["z-ai-glm-5.3:free"]))); // असली सूची में नहीं
    assert.equal(oneaiTask("coder"), "coding");
    assert.equal(oneaiTask("research"), "deep_research");
    assert.equal(oneaiTask("phone"), "phone");
    assert.equal(oneaiTask("voice"), "chat");
  });
});

describe("/oneai/models", () => {
  it("लॉगिन ज़रूरी; सूची + मुफ़्त बैज + क्रम; 10 मिनट याद (दूसरी बार API नहीं)", async () => {
    const e = oneEnv();
    fx = mockFetch((url) => (url.endsWith("/v1/models") ? models() : new Response("x", { status: 500 })));
    assert.equal((await call(e, req("/oneai/models"))).status, 401);
    let t = 1_000_000;
    const now = () => t;
    const r1 = await (await call(e, req("/oneai/models", { tok: await token(OWNER) }), { now })).json();
    assert.equal(r1.configured, true);
    assert.ok(r1.models.find((m) => m.id === "z-ai-glm-5.3:free").free);
    assert.equal(r1.models.find((m) => m.id === "claude-opus-5-5").free, false);
    assert.equal(r1.models.find((m) => m.id === "gpt-6-luna:free").auto, false);
    assert.equal(r1.rank.chat[0].model, "deepseek-deepseek-v4.1-flash:free");
    assert.equal(r1.rank.coding[0].model, "moonshotai-kimi-k3.1-pro:free");
    assert.equal(fx.calls[0].init.headers.authorization, "Bearer oa-secret-1");
    assert.ok(!JSON.stringify(r1).includes("oa-secret-1"));
    t += 5 * 60_000;
    await call(e, req("/oneai/models", { tok: await token(OWNER) }), { now });
    assert.equal(fx.calls.filter((c) => c.url.endsWith("/v1/models")).length, 1);
    t += 6 * 60_000;
    await call(e, req("/oneai/models", { tok: await token(OWNER) }), { now });
    assert.equal(fx.calls.filter((c) => c.url.endsWith("/v1/models")).length, 2);
  });

  it("कुंजी नहीं = configured false (गलती नहीं)", async () => {
    fx = mockFetch(() => new Response("x", { status: 500 }));
    const r = await (await call(env(), req("/oneai/models", { tok: await token(OWNER) }))).json();
    assert.equal(r.ok, true);
    assert.equal(r.configured, false);
    assert.equal(fx.calls.length, 0);
  });
});

describe("/oneai/chat और कड़ी", () => {
  it("stream: One AI पहले, काम के हिसाब से मॉडल (coding = Kimi), Bearer कुंजी", async () => {
    const e = oneEnv({ GEMINI_API_KEY: "g-test" });
    fx = mockFetch((url) => (url.endsWith("/v1/models") ? models() : new Response(sse([oai("नमस्ते"), oai(" बॉस"), "[DONE]"]), { status: 200 })));
    const s = await readSse(await call(e, await oneChat({ task: "coder" })));
    assert.equal(s.text, "नमस्ते बॉस");
    assert.equal(s.done.provider, "oneai");
    assert.equal(s.done.model, "moonshotai-kimi-k3.1-pro:free");
    const c = fx.calls.find((x) => x.url === "https://api.oneaiapikey.com/v1/chat/completions");
    assert.equal(c.init.headers.authorization, "Bearer oa-secret-1");
    assert.equal(JSON.parse(c.init.body).stream, true);
  });

  it("ऐप का चुना :free मॉडल; पैसे वाला / मना वाला चुना = अपने-आप वाला", async () => {
    fx = mockFetch((url) => (url.endsWith("/v1/models") ? models() : new Response(sse([oai("ok"), "[DONE]"]), { status: 200 })));
    await readSse(await call(oneEnv(), await oneChat({ model: "z-ai-glm-5.3-flash:free" })));
    await readSse(await call(oneEnv(), await oneChat({ model: "claude-opus-5-5" })));
    await readSse(await call(oneEnv(), await oneChat({ model: "roleplay-chat:free" })));
    assert.deepEqual(chatBody(fx.calls).map((b) => b.model), ["z-ai-glm-5.3-flash:free", "deepseek-deepseek-v4.1-flash:free", "deepseek-deepseek-v4.1-flash:free"]);
  });

  for (const [name, res] of [
    ["429", () => new Response('{"error":{"code":"daily_limit_free"}}', { status: 429 })],
    ["503", () => new Response("x", { status: 503 })],
    ["खाली", () => new Response(sse(["[DONE]"]), { status: 200 })],
    ["पैसे वाला fallback (X-Fallback-From)", () => new Response(sse([oai("paid")]), { status: 200, headers: { "x-fallback-from": "z-ai-glm-5.3:free" } })],
  ]) {
    it(`One AI ${name} → चुपचाप अगली कड़ी (Gemini), उपयोगकर्ता को गलती नहीं`, async () => {
      fx = mockFetch((url) => {
        if (url.endsWith("/v1/models")) return models();
        if (url.includes("oneaiapikey")) return res();
        return new Response(sse([{ candidates: [{ content: { parts: [{ text: "gem" }] } }] }]), { status: 200 });
      });
      const s = await readSse(await call(oneEnv({ GEMINI_API_KEY: "g-test" }), await oneChat()));
      assert.equal(s.text, "gem");
      assert.equal(s.done.provider, "gemini");
      assert.equal(s.done.tried[0].provider, "oneai");
      assert.equal(s.done.tried[0].ok, false);
    });
  }

  it("4 सेकंड में पहला शब्द नहीं → अगली कड़ी", async () => {
    fx = mockFetch((url) => {
      if (url.endsWith("/v1/models")) return models();
      if (url.includes("oneaiapikey")) return new Response(sse([oai("late")], { firstDelayMs: 300 }), { status: 200 });
      return new Response(sse([{ candidates: [{ content: { parts: [{ text: "gem" }] } }] }]), { status: 200 });
    });
    const e = oneEnv({ GEMINI_API_KEY: "g-test" });
    const oneai = AI_CHAIN.find((c) => c.id === "oneai");
    const old = oneai.firstTokenMs;
    oneai.firstTokenMs = 100; // test में 4 सेकंड की जगह 100ms
    try {
      const s = await readSse(await call(e, await oneChat()));
      assert.equal(s.done.provider, "gemini");
      assert.equal(s.done.tried[0].error, "timeout");
    } finally {
      oneai.firstTokenMs = old;
    }
  });

  it("/ai/chat: बाकी सब फेल हों तो One AI कड़ी में जवाब देता है", async () => {
    fx = mockFetch((url) => {
      if (url.endsWith("/v1/models")) return models();
      if (url.includes("oneaiapikey")) return new Response(sse([oai("oa"), "[DONE]"]), { status: 200 });
      return new Response("x", { status: 500 });
    });
    const s = await readSse(await call(oneEnv({ GEMINI_API_KEY: "g-test" }), await oneChat({ dk: false }, "/ai/chat")));
    assert.equal(s.text, "oa");
    assert.equal(s.done.provider, "oneai");
  });

  it("deep_research: सिर्फ़ खोज के नतीजों से लिखने का नियम", async () => {
    fx = mockFetch((url) => (url.endsWith("/v1/models") ? models() : new Response(sse([oai("r"), "[DONE]"]), { status: 200 })));
    await readSse(await call(oneEnv(), await oneChat({ task: "research", system: "नतीजे: ..." })));
    const b = chatBody(fx.calls)[0];
    assert.equal(b.model, "moonshotai-kimi-k3.1-pro:free");
    assert.ok(b.messages[0].content.includes(ONEAI.researchRule));
  });

  it("लाइसेंस जाँच वही: बिना लाइसेंस वाला coder = मना", async () => {
    fx = mockFetch(() => models());
    const r = await call(oneEnv(), req("/oneai/chat", { method: "POST", tok: await token({ sub: "guest-x" }), body: { task: "coder", messages: [{ role: "user", content: "hi" }] } }));
    assert.equal(r.status, 403);
  });
});

describe("कतार (एक कुंजी = एक अनुरोध)", () => {
  it("दूसरा अनुरोध पहले के ख़त्म होने तक रुकता है; दो कुंजी = दोनों साथ", async () => {
    const e = oneEnv();
    resetOneai(e);
    const a = await acquire(e, 0);
    assert.ok(a);
    assert.equal(await acquire(e, 0), null);
    const wait = acquire(e, 1_000);
    a.release();
    const b = await wait;
    assert.ok(b);
    b.release();
    const e2 = oneEnv({ ONEAI_API_KEY_2: "oa-secret-2" });
    const x = await acquire(e2, 0);
    const y = await acquire(e2, 0);
    assert.ok(x && y && x.key !== y.key);
    x.release();
    y.release();
  });

  it("इंतज़ार का समय पूरा = null (कड़ी अगली पर)", async () => {
    const e = oneEnv();
    const a = await acquire(e, 0);
    assert.equal(await acquire(e, 30), null);
    a.release();
  });
});

describe("रैंकिंग और रोज़ परखना", () => {
  it("सफल + तेज़ ऊपर; लगातार 3 बार चूका = सबसे नीचे", () => {
    const out = orderRows([
      { model: "a", ok_count: 5, fail_count: 0, fail_streak: 0, ttft_ms: 900 },
      { model: "b", ok_count: 5, fail_count: 0, fail_streak: 0, ttft_ms: 300 },
      { model: "c", ok_count: 50, fail_count: 1, fail_streak: 3, ttft_ms: 100 },
    ]);
    assert.deepEqual(out.map((r) => r.model), ["b", "a", "c"]);
  });

  it("/oneai/bench (सिर्फ़ मालिक): हर मॉडल एक बार, नतीजा D1 में, चूकने वाला नीचे", async () => {
    const e = oneEnv();
    fx = mockFetch((url, init) => {
      if (url.endsWith("/v1/models")) return models();
      const m = JSON.parse(init.body).model;
      if (m === "deepseek-deepseek-v4.1-flash:free" || m === "moonshotai-kimi-k3.1-pro:free") return new Response("x", { status: 500 });
      return new Response(sse([oai("hello"), "[DONE]"]), { status: 200 });
    });
    assert.equal((await call(e, req("/oneai/bench", { method: "POST", tok: await token({ sub: "u2" }) }))).status, 403);
    const c = ctx();
    const r = await (await call(e, req("/oneai/bench", { method: "POST", tok: await token(OWNER) }), { ctx: c })).json();
    await c.flush();
    assert.equal(r.ok, true);
    assert.equal(r.results.length, 7);
    assert.notEqual(r.rank.chat[0], "deepseek-deepseek-v4.1-flash:free");
    assert.notEqual(r.rank.coding[0], "moonshotai-kimi-k3.1-pro:free");
    const saved = rows(e, "SELECT task, model, pos FROM oneai_rank WHERE task = 'coding' ORDER BY pos");
    assert.equal(saved.length, 3);
    assert.equal(saved[2].model, "moonshotai-kimi-k3.1-pro:free");
    // आज की गिनती (100 में से)
    assert.equal(rows(e, "SELECT requests FROM provider_daily WHERE provider = 'oneai'")[0].requests, 7);
    for (const b of chatBody(fx.calls)) assert.ok(b.model.endsWith(":free") && !/uncensored|roleplay|stepaudio/.test(b.model));
  });

  it("cron: सफ़ाई के साथ परखना भी", async () => {
    const mod = await import("../src/index.js");
    const e = oneEnv();
    fx = mockFetch((url) => (url.endsWith("/v1/models") ? models() : new Response(sse([oai("hi"), "[DONE]"]), { status: 200 })));
    const jobs = [];
    await mod.default.scheduled({ scheduledTime: Date.now() }, e, { waitUntil: (p) => jobs.push(p) });
    await Promise.all(jobs);
    await Promise.all(jobs);
    assert.equal(rows(e, "SELECT COUNT(*) AS n FROM oneai_rank")[0].n, 14);
  });
});
