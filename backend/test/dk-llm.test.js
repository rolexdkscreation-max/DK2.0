// 21 नवंबर 2026 (मालिक): DK LLM कदम 1 - रोज़ की आम बातचीत DK के अपने सर्वर-मॉडल (Workers AI) पर,
// भारी / ताज़ा जानकारी वाले सवाल सीधे बड़ी कड़ी पर, DK LLM न चले तो अपने-आप बड़ी कड़ी.
import assert from "node:assert/strict";
import { afterEach, describe, it } from "node:test";
import { dkSystem, pickLane, planChain } from "../src/ai.js";
import { DK_LLM, DK_LLM_MAX_CHARS } from "../src/config.js";
import { handle } from "../src/index.js";
import { clearLevelCache } from "../src/license.js";
import { errorResponse } from "../src/util.js";
import { KEYS, OWNER, ctx, env, gem, mockFetch, oai, readSse, req, sse, token } from "./helpers.js";

const call = async (e, r, o = {}) => {
  try {
    return await handle(r, e, o.ctx ?? ctx(), { auth: { keys: KEYS }, ...o });
  } catch (err) {
    return errorResponse(err);
  }
};
const u = (content) => [{ role: "user", content }];
const chat = async (b) => req("/ai/chat", { method: "POST", tok: await token(OWNER), body: b });
/** नकली Workers AI: OpenAI जैसा stream */
const dkAi = (parts, seen = []) => ({
  run: async (model, input) => {
    seen.push({ model, input });
    return sse(parts.map((t) => oai(t)).concat(["[DONE]"]));
  },
});

let fx;
afterEach(() => {
  fx?.restore();
  fx = null;
  clearLevelCache();
});

describe("DK LLM: रास्ता चुनना (pickLane)", () => {
  it("रोज़ की बातचीत = dk", () => {
    for (const q of ["कैसे हो?", "hi DK", "मुझे एक अच्छा सा शायरी सुनाओ", "मैं थक गया हूँ, क्या करूँ", "आज कौन सा दिन है"]) {
      assert.equal(pickLane({ task: "chat", messages: u(q) }), "dk", q);
    }
    assert.equal(pickLane({ task: "voice", messages: u("नमस्ते") }), "dk");
    assert.equal(pickLane({ task: "live", messages: u("नमस्ते") }), "dk");
  });

  it("खबर, खोज, कीमत, कोड, लंबा लेख, link = big", () => {
    for (const q of [
      "आज की खबर बताओ", "latest news", "सोने का भाव क्या है", "bitcoin price", "python code लिखो",
      "इस पर एक निबंध लिखो", "india vs australia match score", "https://example.com खोलो", "मौसम कैसा है",
    ]) {
      assert.equal(pickLane({ task: "chat", messages: u(q) }), "big", q);
    }
  });

  it("भारी task, लंबा सवाल, लंबी बातचीत, ऐप का अपना क्रम, dk:false = big", () => {
    for (const task of ["search", "research", "coder", "computer", "heavy"]) assert.equal(pickLane({ task, messages: u("hi") }), "big", task);
    assert.equal(pickLane({ task: "chat", messages: u("अ".repeat(DK_LLM_MAX_CHARS + 1)) }), "big");
    const long = Array.from({ length: 30 }, () => ({ role: "assistant", content: "क".repeat(500) })).concat(u("ok"));
    assert.equal(pickLane({ task: "chat", messages: long }), "big");
    assert.equal(pickLane({ task: "chat", messages: u("hi"), chain: ["gemini"] }), "big");
    assert.equal(pickLane({ task: "chat", messages: u("hi"), dk: false }), "big");
  });

  it("सिर्फ़ आख़िरी सवाल देखा जाता है", () => {
    const m = [{ role: "user", content: "आज की खबर" }, { role: "assistant", content: "..." }, { role: "user", content: "धन्यवाद" }];
    assert.equal(pickLane({ task: "chat", messages: m }), "dk");
  });
});

describe("DK LLM: कड़ी (planChain)", () => {
  it("dk रास्ता + AI binding = DK LLM सबसे आगे, फिर बड़ी कड़ी", () => {
    const ids = planChain(env({ AI: dkAi([]) }), undefined, "dk").map((c) => c.id);
    assert.deepEqual(ids.slice(0, 3), ["dk", "gemini", "gemini-lite"]);
  });
  it("AI binding नहीं / DK_LLM=off / big रास्ता = DK LLM नहीं", () => {
    assert.ok(!planChain(env(), undefined, "dk").some((c) => c.id === "dk"));
    assert.ok(!planChain(env({ AI: dkAi([]), DK_LLM: "off" }), undefined, "dk").some((c) => c.id === "dk"));
    assert.ok(!planChain(env({ AI: dkAi([]) }), undefined, "big").some((c) => c.id === "dk"));
    assert.ok(!planChain(env({ AI: dkAi([]) })).some((c) => c.id === "dk"));
  });
  it("LoRA (कदम 2) Worker var से", () => {
    assert.equal(planChain(env({ AI: dkAi([]), DK_LLM_LORA: "dk-v1" }), undefined, "dk")[0].lora, "dk-v1");
  });
  it("system में DK का स्वभाव और भारत की तारीख", () => {
    const s = dkSystem("ऐप वाला system", Date.UTC(2026, 10, 21, 6, 0));
    assert.match(s, /DK/);
    assert.match(s, /2026/);
    assert.match(s, /ऐप वाला system$/);
  });
});

describe("DK LLM: /ai/chat", () => {
  it("आम बात: DK LLM जवाब देता है, बाहर कोई fetch नहीं", async () => {
    fx = mockFetch(() => {
      throw new Error("बाहर नहीं जाना था");
    });
    const seen = [];
    const r = await call(env({ AI: dkAi(["नमस्ते", " मालिक"], seen) }), await chat({ messages: u("कैसे हो?"), system: "DK app" }));
    assert.equal(r.headers.get("x-dk-provider"), "dk");
    assert.equal(r.headers.get("x-dk-lane"), "dk");
    const s = await readSse(r);
    assert.equal(s.text, "नमस्ते मालिक");
    assert.equal(s.done.provider, "dk");
    assert.equal(s.done.lane, "dk");
    assert.equal(fx.calls.length, 0);
    assert.equal(seen[0].model, DK_LLM.model);
    assert.equal(seen[0].input.stream, true);
    assert.equal(seen[0].input.messages[0].role, "system");
    assert.match(seen[0].input.messages[0].content, /DK app/);
    assert.equal(seen[0].input.lora, undefined);
  });

  it("पुराना Workers AI format ({response}) भी चलता है", async () => {
    const e = env({ AI: { run: async () => sse([{ response: "हाँ" }, { response: " जी" }, "[DONE]"]) } });
    const s = await readSse(await call(e, await chat({ messages: u("hi") })));
    assert.equal(s.text, "हाँ जी");
  });

  it("खबर वाला सवाल: DK LLM को छुआ तक नहीं, सीधे Gemini", async () => {
    fx = mockFetch(() => new Response(sse([gem("खबर")])));
    const seen = [];
    const r = await call(env({ AI: dkAi(["x"], seen) }), await chat({ messages: u("आज की खबर बताओ") }));
    const s = await readSse(r);
    assert.equal(s.done.provider, "gemini");
    assert.equal(s.done.lane, "big");
    assert.equal(seen.length, 0);
  });

  it("DK LLM की सीमा पूरी (4006): अपने-आप Gemini", async () => {
    fx = mockFetch(() => new Response(sse([gem("Gemini से")])));
    const e = env({ AI: { run: async () => { throw new Error("AiError: 4006: you have used up your daily free allocation of 10,000 neurons"); } } });
    const s = await readSse(await call(e, await chat({ messages: u("hi") })));
    assert.equal(s.text, "Gemini से");
    assert.equal(s.done.provider, "gemini");
    assert.deepEqual(s.done.tried[0], { provider: "dk", model: DK_LLM.model, ok: false, error: "limit" });
  });

  it("DK LLM खाली जवाब / धीमा: अपने-आप Gemini", async () => {
    fx = mockFetch(() => new Response(sse([gem("ok")])));
    const empty = await readSse(await call(env({ AI: dkAi([]) }), await chat({ messages: u("hi") })));
    assert.equal(empty.done.provider, "gemini");
    assert.equal(empty.done.tried[0].error, "empty");
    const slow = { run: async () => sse([oai("देर")], { firstDelayMs: 300 }) };
    const s = await readSse(await call(env({ AI: slow }), await chat({ messages: u("hi") }), { timeoutMs: 100 }));
    assert.equal(s.done.provider, "gemini");
    assert.equal(s.done.tried[0].error, "timeout");
  });

  it("stream:false में भी lane और provider", async () => {
    const r = await call(env({ AI: dkAi(["हाँ"]) }), await chat({ messages: u("hi"), stream: false }));
    const j = await r.json();
    assert.equal(j.text, "हाँ");
    assert.equal(j.provider, "dk");
    assert.equal(j.lane, "dk");
  });

  it("models/health में DK LLM की हालत", async () => {
    const r = await call(env({ AI: dkAi([]) }), req("/models/health", { tok: await token(OWNER) }));
    const j = await r.json();
    assert.deepEqual(j.dk_llm, { on: true, model: DK_LLM.model, lora: false });
  });
});
