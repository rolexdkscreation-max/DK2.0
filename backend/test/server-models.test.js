// 28 नवंबर 2026 (मालिक): ऐप के मॉडल-चिप में सर्वर के सारे मॉडल ("DK AI ...") - GET /ai/models, और /ai/chat में
// चुना मॉडल (body.first) सबसे पहले, न चले = चुपचाप बाकी कड़ी.
import assert from "node:assert/strict";
import { afterEach, describe, it } from "node:test";
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
const hi = { messages: [{ role: "user", content: "hi" }] };
const both = (url) => {
  if (url.includes("generativelanguage")) return new Response(sse([gem("जी")]), { status: 200 });
  if (url.includes("mistral")) return new Response(sse([oai("हाँ"), "[DONE]"]), { status: 200 });
  throw new Error("x");
};

let fx;
afterEach(() => {
  fx?.restore();
  fx = null;
  clearLevelCache();
});

describe("/ai/models", () => {
  it("लॉगिन ज़रूरी; चलने लायक कड़ियाँ (id + मॉडल), कोई key नहीं", async () => {
    assert.equal((await call(env(), req("/ai/models"))).status, 401);
    const r = await (await call(env(), req("/ai/models", { tok: await token(OWNER) }))).json();
    assert.equal(r.server_ai, true);
    assert.deepEqual(r.models, [
      { provider: "gemini", model: "gemini-3.8-flash" },
      { provider: "gemini-lite", model: "gemini-3.5-flash-lite" },
      { provider: "mistral", model: "mistral-small-latest" },
    ]);
    assert.ok(!JSON.stringify(r).includes("g-test") && !JSON.stringify(r).includes("m-test"));
  });

  it("बंद कंपनी (AI_OFF) सूची में नहीं", async () => {
    const r = await (await call(env({ AI_OFF: "mistral" }), req("/ai/models", { tok: await token(OWNER) }))).json();
    assert.deepEqual(r.models.map((m) => m.provider), ["gemini", "gemini-lite"]);
  });

  it("मुफ़्त वाले को सर्वर की AI नहीं = खाली सूची (गलती नहीं)", async () => {
    const res = await call(env({ FREE_SERVER_AI: "" }), req("/ai/models", { tok: await token({ sub: "u-free" }) }));
    assert.equal(res.status, 200);
    const r = await res.json();
    assert.equal(r.server_ai, false);
    assert.deepEqual(r.models, []);
  });
});

describe("/ai/chat: चुना सर्वर मॉडल (first)", () => {
  it("first = mistral -> Mistral पहले", async () => {
    fx = mockFetch(both);
    const s = await readSse(await call(env(), req("/ai/chat", { method: "POST", tok: await token(OWNER), body: { ...hi, first: "mistral" } })));
    assert.equal(s.done.provider, "mistral");
    assert.ok(fx.calls[0].url.includes("mistral"));
  });

  it("चुना न चले = चुपचाप बाकी कड़ी", async () => {
    fx = mockFetch((url) => (url.includes("mistral") ? new Response("{}", { status: 429 }) : both(url)));
    const s = await readSse(await call(env(), req("/ai/chat", { method: "POST", tok: await token(OWNER), body: { ...hi, first: "mistral" } })));
    assert.equal(s.text, "जी");
    assert.equal(s.done.tried[0].provider, "mistral");
  });

  it("अनजान first = पहले जैसा क्रम", async () => {
    fx = mockFetch(both);
    const s = await readSse(await call(env(), req("/ai/chat", { method: "POST", tok: await token(OWNER), body: { ...hi, first: "nahi-hai" } })));
    assert.equal(s.done.provider, "gemini");
  });
});
