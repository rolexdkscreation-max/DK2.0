// 21 नवंबर 2026 (मालिक): Groq + Cohere + Cloudflare (Llama नहीं; Cohere = Command A+), सबसे तेज़ पहले (EWMA, D1),
// 10% दूसरी कंपनी, 8 सेकंड, 60 सेकंड आराम, रोज़ की सीमा = कल तक, 90% = सबसे नीचे, /health, 7 दिन की सफ़ाई.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { afterEach, describe, it } from "node:test";
import { fitContext, planChain } from "../src/ai.js";
import { AI_CHAIN, CHAT_CHAIN, MODEL_TIMEOUT_MS, SECRET_NAMES, SPEED, WORKERS_AI_CHAT } from "../src/config.js";
import { cleanup, handle } from "../src/index.js";
import { clearLevelCache } from "../src/license.js";
import { nextUtcMidnight, utcDay } from "../src/speed.js";
import { errorResponse, istDay } from "../src/util.js";
import { KEYS, OWNER, ctx, env, gem, mockFetch, oai, readSse, req, sse, token } from "./helpers.js";

const call = async (e, r, o = {}) => {
  try {
    return await handle(r, e, o.ctx ?? ctx(), { auth: { keys: KEYS }, ...o });
  } catch (err) {
    return errorResponse(err);
  }
};
const chat = async (b = {}) => req("/ai/chat", { method: "POST", tok: await token(OWNER), body: { messages: [{ role: "user", content: "hi" }], ...b } });
const rows = (e, sql) => {
  const r = e.DB.db.exec(sql);
  if (!r.length) return [];
  return r[0].values.map((v) => Object.fromEntries(r[0].columns.map((c, i) => [c, v[i]])));
};
/** सिर्फ़ Groq + Cohere (Gemini/Mistral नहीं) */
const fastEnv = (extra = {}) => env({ GEMINI_API_KEY: "", MISTRAL_API_KEY: "", GROQ_API_KEY: "gq", COHERE_API_KEY: "cb", ...extra });
const okFetch = () => mockFetch((url) => new Response(sse([oai(url.includes("cohere") ? "cb" : url.includes("groq") ? "gq" : "x"), "[DONE]"]), { status: 200 }));

let fx;
afterEach(() => {
  fx?.restore();
  fx = null;
  clearLevelCache();
});

describe("नई कंपनियाँ: Groq, Cohere (Command A+), Cloudflare (gpt-oss-120b)", () => {
  it("क्रम और secrets; Llama कहीं नहीं (नई तीनों में)", () => {
    const g = AI_CHAIN.find((c) => c.id === "groq");
    const c = AI_CHAIN.find((x) => x.id === "cohere");
    assert.equal(g.model, "openai/gpt-oss-120b");
    assert.equal(g.base, "https://api.groq.com/openai/v1");
    assert.equal(c.model, "command-a-plus-05-2026");
    assert.equal(c.base, "https://api.cohere.ai/compatibility/v1");
    assert.equal(c.secret, "COHERE_API_KEY");
    assert.ok(SECRET_NAMES.includes("COHERE_API_KEY"));
    assert.equal(WORKERS_AI_CHAT.model, "@cf/openai/gpt-oss-120b");
    for (const x of [g, c, WORKERS_AI_CHAT]) assert.ok(!/llama/i.test(x.model), x.id);
    const ids = CHAT_CHAIN.map((x) => x.id);
    assert.ok(ids.indexOf("cohere") < ids.indexOf("cloudflare"));
    assert.ok(ids.indexOf("cloudflare") < ids.indexOf("deepseek"));
    assert.equal(MODEL_TIMEOUT_MS, 8_000);
  });

  it("Cohere: सही पता, Bearer key, Command A+, सोच बंद, stream", async () => {
    fx = okFetch();
    const s = await readSse(await call(env({ GEMINI_API_KEY: "", MISTRAL_API_KEY: "", COHERE_API_KEY: "cb-key" }), await chat()));
    assert.equal(s.text, "cb");
    assert.equal(s.done.provider, "cohere");
    assert.equal(fx.calls[0].url, "https://api.cohere.ai/compatibility/v1/chat/completions");
    assert.equal(fx.calls[0].init.headers.authorization, "Bearer cb-key");
    const b = JSON.parse(fx.calls[0].init.body);
    assert.equal(b.model, "command-a-plus-05-2026");
    assert.equal(b.stream, true);
    assert.equal(b.reasoning_effort, "none");
  });

  it("Cloudflare: key नहीं, env.AI.run(gpt-oss-120b), stream; दोनों format", async () => {
    fx = mockFetch(() => {
      throw new Error("बाहर नहीं");
    });
    let got;
    const ai = { run: async (model, input) => ((got = { model, input }), sse([oai("नम"), { response: "स्ते" }, "[DONE]"])) };
    const s = await readSse(await call(env({ GEMINI_API_KEY: "", MISTRAL_API_KEY: "", AI: ai }), await chat({ system: "DK", dk: false })));
    assert.equal(s.text, "नमस्ते");
    assert.equal(s.done.provider, "cloudflare");
    assert.equal(got.model, "@cf/openai/gpt-oss-120b");
    assert.equal(got.input.stream, true);
    assert.equal(got.input.messages[0].content, "DK");
    assert.equal(fx.calls.length, 0);
  });

  it("models/health में Cloudflare: AI binding = configured", async () => {
    const j = await (await call(env({ AI: { run: async () => null } }), req("/models/health", { tok: await token(OWNER) }))).json();
    assert.equal(j.chain.find((c) => c.provider === "cloudflare").configured, true);
    assert.equal(j.chain.find((c) => c.provider === "cohere").configured, false);
  });
});

describe("सबसे तेज़ पहले (EWMA, D1)", () => {
  it("D1 में कम औसत समय वाला पहले", async () => {
    fx = okFetch();
    const e = fastEnv();
    e.DB.db.run("INSERT INTO provider_stats (provider, ewma_ms, samples, updated_at) VALUES ('groq', 900, 5, 1), ('cohere', 150, 5, 1)");
    const s = await readSse(await call(e, await chat()));
    assert.equal(s.done.provider, "cohere");
    assert.deepEqual(s.done.tried.map((t) => t.provider), ["cohere"]);
  });

  it("EWMA: पुराना x 0.7 + नया x 0.3, D1 में लिखा", async () => {
    fx = okFetch();
    const e = fastEnv({ GROQ_API_KEY: "" });
    e.DB.db.run("INSERT INTO provider_stats (provider, ewma_ms, samples, updated_at) VALUES ('cohere', 1000, 3, 1)");
    const c = ctx();
    await readSse(await call(e, await chat(), { ctx: c }));
    await c.flush();
    const r = rows(e, "SELECT * FROM provider_stats WHERE provider = 'cohere'")[0];
    assert.equal(r.samples, 4);
    assert.ok(r.ewma_ms < 1000 * 0.7 + 50 && r.ewma_ms >= 700, String(r.ewma_ms));
  });

  it("हर 10वीं रिक्वेस्ट दूसरी कंपनी (रफ़्तार नापने को)", async () => {
    fx = okFetch();
    const e = fastEnv();
    e.DB.db.run("INSERT INTO provider_stats (provider, ewma_ms, samples, updated_at) VALUES ('groq', 900, 5, 1), ('cohere', 100, 5, 1)");
    const firsts = [];
    for (let i = 0; i < 10; i++) firsts.push((await readSse(await call(e, await chat()))).done.provider);
    assert.equal(SPEED.explore, 0.1);
    assert.equal(firsts.filter((p) => p === "groq").length, 1);
    assert.equal(firsts[9], "groq");
  });

  it("AI_ORDER=fixed = config का क्रम; AI_OFF = बंद", async () => {
    fx = okFetch();
    const e = fastEnv({ AI_ORDER: "fixed" });
    e.DB.db.run("INSERT INTO provider_stats (provider, ewma_ms, samples, updated_at) VALUES ('groq', 900, 5, 1), ('cohere', 100, 5, 1)");
    assert.equal((await readSse(await call(e, await chat()))).done.provider, "groq");
    const e2 = fastEnv({ AI_OFF: "groq" });
    const s = await readSse(await call(e2, await chat()));
    assert.deepEqual(s.done.tried.map((t) => t.provider), ["cohere"]);
  });
});

describe("फॉलबैक: आराम, रोज़ की सीमा, 90%", () => {
  it("429 = अगली कंपनी + 60 सेकंड आराम (अगली रिक्वेस्ट उसे पहले नहीं), D1 में cool_until", async () => {
    let n = 0;
    fx = mockFetch((url) => {
      if (url.includes("groq") && n++ === 0) return new Response(JSON.stringify({ error: { message: "Rate limit reached: requests per minute" } }), { status: 429 });
      return new Response(sse([oai(url.includes("groq") ? "gq" : "cb")]), { status: 200 });
    });
    const e = fastEnv();
    const c = ctx();
    const s1 = await readSse(await call(e, await chat(), { ctx: c }));
    assert.deepEqual(s1.done.tried.map((t) => [t.provider, t.error ?? "ok"]), [["groq", "limit"], ["cohere", "ok"]]);
    await c.flush();
    const st = rows(e, "SELECT * FROM provider_stats WHERE provider = 'groq'")[0];
    assert.ok(st.cool_until > Date.now() + 50_000);
    assert.equal(st.off_until, 0);
    assert.match(st.last_error, /^limit/);
    const s2 = await readSse(await call(e, await chat()));
    assert.equal(s2.done.tried[0].provider, "cohere");
    // ऐप का तय क्रम भी: आराम वाला पीछे; 60 सेकंड बाद फिर आगे
    const s3 = await readSse(await call(e, await chat({ chain: ["groq", "cohere"] })));
    assert.equal(s3.done.tried[0].provider, "cohere");
    const s4 = await readSse(await call(e, await chat({ chain: ["groq", "cohere"] }), { now: () => Date.now() + 61_000 }));
    assert.equal(s4.done.provider, "groq");
  });

  it("5xx और timeout पर भी आराम", async () => {
    fx = mockFetch((url) => (url.includes("groq") ? new Response("{}", { status: 503 }) : new Response(sse([oai("cb")], { firstDelayMs: 300 }), { status: 200 })));
    const e = fastEnv();
    const r = await call(e, await chat(), { timeoutMs: 100 });
    assert.equal(r.status, 503);
    const b = await r.json();
    assert.equal(b.code, "ALL_FAILED");
    assert.match(b.error, /अभी कोई AI जवाब नहीं दे पाया/);
    assert.deepEqual(b.tried.map((t) => t.error), ["http 503", "timeout"]);
    assert.ok(!JSON.stringify(b).includes("marks"));
  });

  it("रोज़ की सीमा (Groq: per day) = अगली 00:00 UTC तक बंद", async () => {
    fx = mockFetch((url) =>
      url.includes("groq")
        ? new Response(JSON.stringify({ error: { message: "Rate limit reached for model on requests per day (RPD): Limit 1000" } }), { status: 429 })
        : new Response(sse([oai("cb")]), { status: 200 }),
    );
    const e = fastEnv();
    const c = ctx();
    await readSse(await call(e, await chat(), { ctx: c }));
    await c.flush();
    const st = rows(e, "SELECT off_until FROM provider_stats WHERE provider = 'groq'")[0];
    assert.equal(st.off_until, nextUtcMidnight(Date.now()));
    // 2 मिनट बाद भी (आराम खत्म) Groq पहले नहीं
    const s = await readSse(await call(e, await chat(), { now: () => Date.now() + 120_000 }));
    assert.equal(s.done.tried[0].provider, "cohere");
  });

  it("Cloudflare 4006 (Neurons खत्म) = आज बंद, अगली कंपनी", async () => {
    fx = okFetch();
    const ai = { run: async () => { throw new Error("AiError: 4006: you have used up your daily free allocation of 10,000 neurons"); } };
    const e = env({ GEMINI_API_KEY: "", MISTRAL_API_KEY: "", COHERE_API_KEY: "cb", AI: ai, AI_ORDER: "fixed" });
    const c = ctx();
    const s = await readSse(await call(e, await chat({ chain: ["cloudflare", "cohere"] }), { ctx: c }));
    assert.equal(s.done.provider, "cohere");
    await c.flush();
    assert.equal(rows(e, "SELECT off_until FROM provider_stats WHERE provider = 'cloudflare'")[0].off_until, nextUtcMidnight(Date.now()));
  });

  it("आज की सीमा का 90% = क्रम में सबसे नीचे (तेज़ होने पर भी)", async () => {
    fx = okFetch();
    const e = fastEnv();
    e.DB.db.run("INSERT INTO provider_stats (provider, ewma_ms, samples, updated_at) VALUES ('groq', 100, 5, 1), ('cohere', 900, 5, 1)");
    e.DB.db.run("INSERT INTO provider_daily (day, provider, requests, tokens, neurons) VALUES (?, 'groq', 950, 0, 0)", [utcDay(Date.now())]);
    const s = await readSse(await call(e, await chat()));
    assert.equal(s.done.provider, "cohere");
  });

  it("सब आराम पर हों तो भी कोशिश (खाली कभी नहीं)", async () => {
    fx = okFetch();
    const e = fastEnv({ COHERE_API_KEY: "" });
    e.DB.db.run("INSERT INTO provider_stats (provider, cool_until, updated_at) VALUES ('groq', ?, 1)", [Date.now() + 30_000]);
    const s = await readSse(await call(e, await chat()));
    assert.equal(s.done.provider, "groq");
  });
});

describe("रोज़ का हिसाब (provider_daily)", () => {
  it("हर जवाब पर रिक्वेस्ट +1 और token का अंदाज़ा; Cloudflare के Neurons", async () => {
    fx = okFetch();
    const e = fastEnv({ GROQ_API_KEY: "" });
    const c = ctx();
    await readSse(await call(e, await chat({ messages: [{ role: "user", content: "क".repeat(300) }] }), { ctx: c }));
    await c.flush();
    const r = rows(e, "SELECT * FROM provider_daily WHERE provider = 'cohere'")[0];
    assert.equal(r.day, utcDay(Date.now()));
    assert.equal(r.requests, 1);
    assert.ok(r.tokens >= 100);
    const ai = { run: async () => sse([oai("ठीक")]) };
    const e2 = env({ GEMINI_API_KEY: "", MISTRAL_API_KEY: "", AI: ai });
    const c2 = ctx();
    await readSse(await call(e2, await chat({ messages: [{ role: "user", content: "क".repeat(3000) }] }), { ctx: c2 }));
    await c2.flush();
    assert.ok(rows(e2, "SELECT neurons FROM provider_daily WHERE provider = 'cloudflare'")[0].neurons > 30);
  });
});

describe("लंबी बातचीत काटना", () => {
  it("सीमा में आने तक सबसे पुरानी लाइनें हटें, आख़िरी सवाल बचे", () => {
    const msgs = [];
    for (let i = 0; i < 40; i++) msgs.push({ role: i % 2 ? "assistant" : "user", content: String(i).padEnd(1000, "x") });
    msgs.push({ role: "user", content: "आख़िरी सवाल" });
    const out = fitContext("sys", msgs, 3_000);
    assert.ok(out.length < msgs.length);
    assert.equal(out.at(-1).content, "आख़िरी सवाल");
    assert.equal(out[0].role, "user");
    assert.ok(out.reduce((n, m) => n + m.content.length, 0) <= 3_000 * 3);
    assert.equal(fitContext("s", msgs, 0), msgs);
    const huge = fitContext("", [{ role: "user", content: "y".repeat(50_000) }], 1_000);
    assert.equal(huge.length, 1);
    assert.ok(huge[0].content.length < 50_000);
  });

  it("Groq को लंबी बातचीत कटी हुई जाती है", async () => {
    fx = okFetch();
    const msgs = [];
    for (let i = 0; i < 60; i++) msgs.push({ role: i % 2 ? "assistant" : "user", content: "z".repeat(1000) });
    msgs.push({ role: "user", content: "अब?" });
    await readSse(await call(fastEnv({ COHERE_API_KEY: "" }), await chat({ messages: msgs })));
    const sent = JSON.parse(fx.calls[0].init.body).messages;
    assert.ok(sent.length < msgs.length);
    assert.equal(sent.at(-1).content, "अब?");
  });
});

describe("/health", () => {
  it("बिना लॉगिन = वही पुराना; मालिक = हर कंपनी की हालत (key नहीं)", async () => {
    fx = okFetch();
    const e = fastEnv();
    assert.deepEqual(await (await call(e, req("/health"))).json(), { ok: true, service: "dk-backend" });
    assert.deepEqual(await (await call(e, req("/health", { tok: await token({ sub: "uid-x" }) }))).json(), { ok: true, service: "dk-backend" });
    const c = ctx();
    await readSse(await call(e, await chat(), { ctx: c }));
    await c.flush();
    const j = await (await call(e, req("/health", { tok: await token(OWNER) }))).json();
    assert.equal(j.ok, true);
    assert.equal(j.ai.order, "fast");
    const g = j.ai.providers.find((p) => p.provider === "groq");
    assert.equal(g.status, "ready");
    assert.equal(g.today.requests, 1);
    assert.ok(g.ewma_ms >= 0);
    assert.deepEqual(g.limit, { requests: 1000, tokens: 200000 });
    assert.equal(j.ai.providers.find((p) => p.provider === "gemini").status, "no_key");
    assert.equal(j.ai.providers.find((p) => p.provider === "cloudflare").status, "no_key");
    assert.ok(!JSON.stringify(j).includes("gq"));
    assert.ok(!JSON.stringify(j).includes("cb\""));
  });
});

describe("7 दिन की सफ़ाई (cron)", () => {
  it("पुराना हिसाब मिटे, नया और लाइसेंस बचे", async () => {
    const e = env();
    const now = Date.now();
    const old = now - 8 * 86_400_000;
    const db = e.DB.db;
    db.run("INSERT INTO usage VALUES ('u', ?, 1, 1), ('u', ?, 1, 1)", [istDay(old), istDay(now)]);
    db.run("INSERT INTO image_usage VALUES (?, 1, 0), (?, 1, 0)", [utcDay(old), utcDay(now)]);
    db.run("INSERT INTO provider_daily VALUES (?, 'groq', 1, 1, 0), (?, 'groq', 1, 1, 0)", [utcDay(old), utcDay(now)]);
    db.run("INSERT INTO model_health (model, ok_count, fail_count, last_ok) VALUES ('a/old', 1, 0, ?), ('b/new', 1, 0, ?)", [old, now]);
    db.run("INSERT INTO live_grants VALUES ('u1', ?), ('u2', ?)", [old, now + 60_000]);
    db.run("INSERT INTO provider_stats (provider, ewma_ms, samples, last_error, last_error_at, updated_at) VALUES ('groq', 200, 3, 'limit', ?, ?)", [old, old]);
    db.run("INSERT INTO licenses VALUES ('id1', 'h', 'basic', 1, ?, 1, NULL, ?)", [old, old]);
    db.run("INSERT INTO tasks (id, owner, title, created_at, updated_at) VALUES ('t1', 'u', 'काम', ?, ?)", [old, old]);
    const out = await cleanup(e, now);
    assert.equal(out.usage, 1);
    assert.equal(rows(e, "SELECT COUNT(*) AS n FROM usage")[0].n, 1);
    assert.equal(rows(e, "SELECT COUNT(*) AS n FROM image_usage")[0].n, 1);
    assert.equal(rows(e, "SELECT COUNT(*) AS n FROM provider_daily")[0].n, 1);
    assert.deepEqual(rows(e, "SELECT model FROM model_health").map((r) => r.model), ["b/new"]);
    assert.deepEqual(rows(e, "SELECT uid FROM live_grants").map((r) => r.uid), ["u2"]);
    const st = rows(e, "SELECT * FROM provider_stats")[0];
    assert.equal(st.ewma_ms, 200);
    assert.equal(st.last_error, null);
    assert.equal(rows(e, "SELECT COUNT(*) AS n FROM licenses")[0].n, 1);
    assert.equal(rows(e, "SELECT COUNT(*) AS n FROM tasks")[0].n, 1);
  });

  it("wrangler.toml: रोज़ का cron, [ai] binding, बिना key सर्वर से जवाब, सबसे तेज़ पहले", () => {
    const w = readFileSync(new URL("../wrangler.toml", import.meta.url), "utf8");
    assert.match(w, /\[triggers\]\s*\ncrons = \["30 0 \* \* \*"\]/);
    assert.match(w, /binding = "AI"/);
    assert.match(w, /FREE_SERVER_AI = "1"/);
    assert.match(w, /AI_ORDER = "fast"/);
  });

  it("scheduled() cleanup चलाता है", async () => {
    const mod = await import("../src/index.js");
    const e = env();
    e.DB.db.run("INSERT INTO usage VALUES ('u', '2000-01-01', 1, 1)");
    const jobs = [];
    await mod.default.scheduled({ scheduledTime: Date.now() }, e, { waitUntil: (p) => jobs.push(p) });
    await Promise.all(jobs);
    assert.equal(rows(e, "SELECT COUNT(*) AS n FROM usage")[0].n, 0);
  });
});

describe("बिना key वाला ऐप: सर्वर से जवाब", () => {
  it("/license/status बताता है server_ai (मुफ़्त + FREE_SERVER_AI)", async () => {
    const on = await (await call(env(), req("/license/status", { tok: await token({ sub: "uid-f" }) }))).json();
    assert.equal(on.server_ai, true);
    const off = await (await call(env({ FREE_SERVER_AI: "" }), req("/license/status", { tok: await token({ sub: "uid-f" }) }))).json();
    assert.equal(off.server_ai, false);
    const own = await (await call(env({ FREE_SERVER_AI: "" }), req("/license/status", { tok: await token(OWNER) }))).json();
    assert.equal(own.server_ai, true);
  });

  it("planChain: Cloudflare सिर्फ़ AI binding होने पर", () => {
    assert.ok(!planChain(env()).some((c) => c.id === "cloudflare"));
    assert.ok(planChain(env({ AI: {} })).some((c) => c.id === "cloudflare"));
  });

  it("Gemini पहले जैसा (बदला नहीं)", async () => {
    fx = mockFetch(() => new Response(sse([gem("जी")]), { status: 200 }));
    const s = await readSse(await call(env(), await chat()));
    assert.equal(s.done.provider, "gemini");
    assert.match(fx.calls[0].url, /gemini-3\.8-flash:streamGenerateContent/);
  });
});

describe("DK LLM (कदम 1) के साथ", () => {
  it("आम बातचीत: DK LLM फिर भी सबसे आगे, बाकी तेज़ी से; Neurons Cloudflare में जुड़ें", async () => {
    fx = okFetch();
    const runs = [];
    const ai = { run: async (model) => (runs.push(model), sse([oai("dk")])) };
    const e = fastEnv({ AI: ai });
    e.DB.db.run("INSERT INTO provider_stats (provider, ewma_ms, samples, updated_at) VALUES ('groq', 50, 5, 1), ('dk', 3000, 5, 1)");
    const c = ctx();
    const s = await readSse(await call(e, await chat(), { ctx: c }));
    assert.equal(s.done.provider, "dk");
    assert.equal(runs[0], "@cf/google/gemma-4-26b-a4b-it");
    await c.flush();
    assert.ok(rows(e, "SELECT neurons FROM provider_daily WHERE provider = 'dk'")[0].neurons > 0);
  });
});
