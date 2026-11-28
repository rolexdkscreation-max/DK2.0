// 28 नवंबर 2026 (मालिक): सिर्फ़ DK चैट की fallback कड़ी (body.chat_chain = true) - क्रम, हर Groq मॉडल का अलग आराम,
// 5xx, 4 सेकंड वाली सीमा, हाल का सबसे तेज़ पहले, छोटा history, सब विफल वाला संदेश, log में संदेश/keys नहीं,
// और flag न हो / आवाज़ हो = पुरानी कड़ी जैसी की तैसी. लॉगिन जाँच की याद (cache) भी.
import assert from "node:assert/strict";
import { afterEach, describe, it } from "node:test";
import { authenticate, resetLoginCache } from "../src/auth.js";
import { CHAT_FALLBACK } from "../src/config.js";
import { chatStepState, lastMessages, orderSteps } from "../src/chatfallback.js";
import { handle } from "../src/index.js";
import { clearLevelCache } from "../src/license.js";
import { KEYS, OWNER, ctx, env, gem, mockFetch, oai, readSse, req, sse, token } from "./helpers.js";

const SECRET = "groq-secret-should-never-be-logged";
const USER_TEXT = "मेरा गुप्त सवाल 4242";

const ai = (fn) => ({ run: fn });
const fullEnv = (extra = {}) =>
  env({
    GROQ_API_KEY: SECRET,
    OPENROUTER_API_KEY: "or-test",
    AI: ai(async () => sse([{ response: "CF " }, { response: "जवाब" }])),
    ...extra,
  });

let fx;
let logs;
afterEach(() => {
  fx?.restore();
  fx = null;
  resetLoginCache();
  clearLevelCache?.();
});

const call = async (e, body, o = {}) => {
  logs = [];
  const opts = { auth: { keys: KEYS }, chatLog: (l) => logs.push(l), ...o };
  try {
    return await handle(req("/ai/chat", { method: "POST", tok: o.tok ?? (await token(OWNER)), body }), e, ctx(), opts);
  } catch (err) {
    const { errorResponse } = await import("../src/util.js");
    return errorResponse(err);
  }
};

const chatBody = (extra = {}) => ({ messages: [{ role: "user", content: USER_TEXT }], chat_chain: true, ...extra });
const modelOf = (c) => {
  if (c.url.includes("generativelanguage")) return "gemini";
  try {
    return JSON.parse(c.init.body).model;
  } catch {
    return "?";
  }
};

/** हर मॉडल के लिए अपना जवाब: rules[model] = "ok" | 429 | 500 | {delay} */
function route(rules = {}) {
  return mockFetch(async (url, init) => {
    const model = url.includes("generativelanguage") ? "gemini" : JSON.parse(init.body).model;
    const r = rules[model] ?? "ok";
    if (typeof r === "number") return new Response("{}", { status: r });
    if (r?.delay) {
      await new Promise((res, rej) => {
        const t = setTimeout(res, r.delay);
        init.signal?.addEventListener("abort", () => (clearTimeout(t), rej(new Error("aborted"))));
      });
    }
    const body = model === "gemini" ? sse([gem("G "), gem("ठीक")]) : sse([oai(model + " "), oai("ठीक")]);
    return new Response(body, { status: 200, headers: { "content-type": "text/event-stream" } });
  });
}

describe("DK चैट fallback कड़ी (chat_chain)", () => {
  it("क्रम और ID: Groq 8B → gpt-oss-20b → 70B → Gemini Flash-Lite → Workers AI Llama → OpenRouter :free; Cerebras/Mistral नहीं", () => {
    assert.deepEqual(
      CHAT_FALLBACK.steps.map((s) => s.model),
      ["llama-3.1-8b-instant", "openai/gpt-oss-20b", "llama-3.3-70b-versatile", "gemini-3.5-flash-lite", "@cf/meta/llama-3.2-3b-instruct", "google/gemma-4-31b-it:free"],
    );
    for (const s of CHAT_FALLBACK.steps) assert.ok(!/mistral|cerebras/i.test(s.provider + s.model + (s.base ?? "")), s.id);
    for (const s of CHAT_FALLBACK.steps.filter((x) => x.provider === "groq")) assert.equal(s.base, "https://api.groq.com/openai/v1");
    assert.equal(new Set(CHAT_FALLBACK.steps.map((s) => s.id)).size, CHAT_FALLBACK.steps.length);
    assert.equal(CHAT_FALLBACK.stepTimeoutMs, 4000);
    assert.equal(CHAT_FALLBACK.cooldownMs, 60_000);
    assert.equal(CHAT_FALLBACK.failMessage, "अभी जवाब नहीं आ पाया, थोड़ी देर बाद कोशिश करें।");
  });

  it("सब ठीक = पहले Groq llama-3.1-8b-instant, stream में जवाब, Groq का OpenAI जैसा endpoint", async () => {
    const e = fullEnv();
    fx = route();
    const res = await call(e, chatBody());
    assert.equal(res.status, 200);
    const { text, done } = await readSse(res);
    assert.equal(text, "llama-3.1-8b-instant ठीक");
    assert.equal(done.provider, "groq");
    assert.equal(done.model, "llama-3.1-8b-instant");
    assert.equal(done.lane, "chat");
    assert.equal(fx.calls.length, 1);
    assert.equal(fx.calls[0].url, "https://api.groq.com/openai/v1/chat/completions");
    assert.equal(fx.calls[0].init.headers.authorization, "Bearer " + SECRET);
  });

  it("एक Groq मॉडल पर 429 = सिर्फ़ वही 60 सेकंड बाहर, बाकी Groq मॉडल चलते रहें", async () => {
    const e = fullEnv();
    let now = Date.now();
    fx = route({ "llama-3.1-8b-instant": 429 });
    const r1 = await call(e, chatBody(), { now: () => now });
    assert.equal((await readSse(r1)).done.model, "openai/gpt-oss-20b");
    assert.ok(chatStepState(e, "groq-llama-8b").until > now);
    assert.equal(chatStepState(e, "groq-gpt-oss-20b").until, 0);
    assert.equal(chatStepState(e, "groq-llama-70b").until, 0);
    // अगले संदेश पर 8B आज़माया ही नहीं जाता
    fx.restore();
    fx = route();
    now += 30_000;
    const r2 = await call(e, chatBody(), { now: () => now });
    assert.equal((await readSse(r2)).done.model, "openai/gpt-oss-20b");
    assert.ok(!fx.calls.some((c) => modelOf(c) === "llama-3.1-8b-instant"));
    // 60 सेकंड बाद 8B फिर कड़ी में
    now += 31_000;
    assert.ok(orderSteps(e, now).some((s) => s.id === "groq-llama-8b"));
  });

  it("5xx = उस चरण को आराम; तीनों Groq 500 = Gemini Flash-Lite", async () => {
    const e = fullEnv();
    const now = Date.now();
    fx = route({ "llama-3.1-8b-instant": 500, "openai/gpt-oss-20b": 503, "llama-3.3-70b-versatile": 502 });
    const res = await call(e, chatBody(), { now: () => now });
    const { done, text } = await readSse(res);
    assert.equal(done.provider, "gemini");
    assert.equal(done.model, "gemini-3.5-flash-lite");
    assert.equal(text, "G ठीक");
    assert.ok(fx.calls.at(-1).url.includes("/models/gemini-3.5-flash-lite:streamGenerateContent"));
    for (const id of ["groq-llama-8b", "groq-gpt-oss-20b", "groq-llama-70b"]) assert.ok(chatStepState(e, id).until > now, id);
  });

  it("400 जैसी गलती = बस अगला चरण, आराम नहीं", async () => {
    const e = fullEnv();
    fx = route({ "llama-3.1-8b-instant": 400 });
    const res = await call(e, chatBody());
    assert.equal((await readSse(res)).done.model, "openai/gpt-oss-20b");
    assert.equal(chatStepState(e, "groq-llama-8b").until, 0);
  });

  it("समय-सीमा पर पहला शब्द नहीं = बिना रुके अगला चरण (आराम नहीं)", async () => {
    const e = fullEnv();
    fx = route({ "llama-3.1-8b-instant": { delay: 400 } });
    const t0 = Date.now();
    const res = await call(e, chatBody(), { chatStepTimeoutMs: 80 });
    const { done } = await readSse(res);
    assert.equal(done.model, "openai/gpt-oss-20b");
    assert.ok(Date.now() - t0 < 400, "पहले चरण का इंतज़ार नहीं किया");
    assert.deepEqual(done.tried[0], { provider: "groq", model: "llama-3.1-8b-instant", ok: false, error: "timeout" });
    assert.equal(chatStepState(e, "groq-llama-8b").until, 0);
  });

  it("हाल में सफल रहा चरण पहले; बहुत पुराना हो जाए तो फिर डिफ़ॉल्ट क्रम", async () => {
    const e = fullEnv();
    let now = Date.now();
    fx = route({ "llama-3.1-8b-instant": 429 });
    await readSse(await call(e, chatBody(), { now: () => now }));
    fx.restore();
    fx = route();
    now += 61_000; // 8B का आराम खत्म, पर हाल का विजेता gpt-oss-20b
    const r = await call(e, chatBody(), { now: () => now });
    assert.equal((await readSse(r)).done.model, "openai/gpt-oss-20b");
    assert.equal(modelOf(fx.calls[0]), "openai/gpt-oss-20b");
    now += CHAT_FALLBACK.recentMs + 1;
    assert.equal(orderSteps(e, now)[0].id, "groq-llama-8b");
  });

  it("पुरानी चैट के सिर्फ़ आख़िरी कुछ संदेश जाते हैं", async () => {
    const e = fullEnv();
    fx = route();
    const messages = [];
    for (let i = 0; i < 30; i++) messages.push({ role: i % 2 ? "assistant" : "user", content: "संदेश " + i });
    messages.push({ role: "user", content: "आख़िरी सवाल" });
    await readSse(await call(e, { messages, system: "DK", chat_chain: true }));
    const sent = JSON.parse(fx.calls[0].init.body).messages;
    assert.equal(sent[0].role, "system");
    assert.ok(sent.length - 1 <= CHAT_FALLBACK.historyMessages);
    assert.equal(sent.at(-1).content, "आख़िरी सवाल");
    assert.equal(sent[1].role, "user");
    assert.deepEqual(lastMessages([{ role: "assistant", content: "a" }, { role: "user", content: "b" }], 2), [{ role: "user", content: "b" }]);
  });

  it("Groq + Gemini न चलें = Workers AI Llama (env.AI), फिर OpenRouter :free", async () => {
    let gotModel;
    const e = fullEnv({ AI: ai(async (m) => ((gotModel = m), sse([{ response: "CF " }, { response: "जवाब" }]))) });
    fx = route({ "llama-3.1-8b-instant": 429, "openai/gpt-oss-20b": 429, "llama-3.3-70b-versatile": 429, gemini: 429 });
    const { done, text } = await readSse(await call(e, chatBody()));
    assert.equal(done.provider, "cloudflare");
    assert.equal(gotModel, "@cf/meta/llama-3.2-3b-instruct");
    assert.equal(text, "CF जवाब");

    const e2 = fullEnv({ AI: ai(async () => { throw new Error("AiError: 4006: daily free allocation of 10,000 neurons"); }) });
    fx.restore();
    fx = route({ "llama-3.1-8b-instant": 429, "openai/gpt-oss-20b": 429, "llama-3.3-70b-versatile": 429, gemini: 429 });
    const d2 = (await readSse(await call(e2, chatBody()))).done;
    assert.equal(d2.provider, "openrouter");
    assert.equal(d2.model, "google/gemma-4-31b-it:free");
    assert.equal(fx.calls.at(-1).url, "https://openrouter.ai/api/v1/chat/completions");
    assert.ok(chatStepState(e2, "cloudflare-llama").until > 0, "Workers AI की सीमा = आराम");
  });

  it("सब चरण विफल = सिर्फ़ तभी तय हिंदी संदेश", async () => {
    const e = fullEnv({ AI: ai(async () => { throw new Error("boom"); }) });
    fx = route({ "llama-3.1-8b-instant": 500, "openai/gpt-oss-20b": 500, "llama-3.3-70b-versatile": 500, gemini: 500, "google/gemma-4-31b-it:free": 500 });
    const res = await call(e, chatBody());
    assert.equal(res.status, 503);
    const j = await res.json();
    assert.equal(j.code, "ALL_FAILED");
    assert.equal(j.error, "अभी जवाब नहीं आ पाया, थोड़ी देर बाद कोशिश करें।");
    assert.equal(j.tried.length, 6);
  });

  it("log में सिर्फ़ मॉडल और समय - keys या यूज़र का संदेश कभी नहीं", async () => {
    const e = fullEnv();
    fx = route({ "llama-3.1-8b-instant": 429 });
    const seen = [];
    const orig = console.log;
    console.log = (...a) => seen.push(a.join(" "));
    try {
      await readSse(await call(e, chatBody()));
    } finally {
      console.log = orig;
    }
    assert.equal(logs.length, 1);
    assert.match(logs[0], /^DK chat: groq\/openai\/gpt-oss-20b \d+ ms$/);
    for (const l of [...logs, ...seen]) {
      assert.ok(!l.includes(SECRET), "key log में");
      assert.ok(!l.includes(USER_TEXT), "यूज़र का संदेश log में");
    }
  });

  it("chat_chain न हो / आवाज़ / ऐप का अपना क्रम = पुरानी कड़ी (Groq वाली नई कड़ी नहीं)", async () => {
    const e = fullEnv();
    fx = route();
    const old = await readSse(await call(e, { messages: [{ role: "user", content: "नमस्ते" }] }));
    assert.notEqual(old.done.lane, "chat");
    const voice = await readSse(await call(e, chatBody({ mode: "voice" })));
    assert.notEqual(voice.done.lane, "chat");
    const own = await readSse(await call(e, chatBody({ chain: ["gemini"] })));
    assert.notEqual(own.done.lane, "chat");
    const code = await readSse(await call(e, chatBody({ task: "code" })));
    assert.notEqual(code.done.lane, "chat");
  });

  it("stream:false भी इसी कड़ी से", async () => {
    const e = fullEnv();
    fx = route();
    const res = await call(e, chatBody({ stream: false }));
    const j = await res.json();
    assert.equal(j.ok, true);
    assert.equal(j.model, "llama-3.1-8b-instant");
  });
});

describe("लॉगिन जाँच की याद (सिर्फ़ /ai/chat)", () => {
  it("cache चालू = कुछ मिनट दोबारा दस्तख़त नहीं; मियाद / 5 मिनट बाद फिर पूरी जाँच", async () => {
    const e = env();
    const tok = await token(OWNER);
    const r = () => req("/ai/chat", { method: "POST", tok, body: {} });
    const now = Date.now();
    const a = await authenticate(r(), e, { keys: KEYS, cache: true, now });
    assert.equal(a.owner, true);
    // keys खाली = असली जाँच नामुमकिन; याद से चलना चाहिए
    const b = await authenticate(r(), e, { keys: {}, cache: true, now: now + 60_000 });
    assert.equal(b.uid, "uid-owner");
    await assert.rejects(authenticate(r(), e, { keys: {}, now: now + 60_000 }), /key पहचानी नहीं/);
    await assert.rejects(authenticate(r(), e, { keys: {}, cache: true, now: now + 5 * 60_000 + 1 }), /key पहचानी नहीं/);
  });

  it("गलत token याद नहीं होता", async () => {
    const e = env();
    const bad = await token(OWNER, { wrongKey: true });
    const r = () => req("/ai/chat", { method: "POST", tok: bad, body: {} });
    await assert.rejects(authenticate(r(), e, { keys: KEYS, cache: true }), /दस्तख़त गलत/);
    await assert.rejects(authenticate(r(), e, { keys: KEYS, cache: true }), /दस्तख़त गलत/);
  });
});
