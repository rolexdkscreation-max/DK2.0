// 20 नवंबर 2026 (मालिक): /image - सिर्फ़ Cloudflare FLUX-1 schnell, सिर्फ़ मालिक, लिमिट पर हिंदी संदेश, कोई दूसरी कंपनी नहीं.
import assert from "node:assert/strict";
import { afterEach, describe, it } from "node:test";
import { IMAGE_MODEL, IMAGE_STEPS, NEURONS_PER_IMAGE } from "../src/config.js";
import { IMAGES_PER_DAY, IMAGE_LIMIT_MSG, imagesLeft, utcDay } from "../src/image.js";
import { handle } from "../src/index.js";
import { clearLevelCache } from "../src/license.js";
import { errorResponse } from "../src/util.js";
import { KEYS, OWNER, ctx, env, mockFetch, req, token } from "./helpers.js";

const call = async (e, r, o = {}) => {
  try {
    return await handle(r, e, o.ctx ?? ctx(), { auth: { keys: KEYS }, ...o });
  } catch (err) {
    return errorResponse(err);
  }
};
const body = async (r) => ({ status: r.status, ...(await r.json()) });
const ai = (fn) => ({ run: fn });
const img = (tok, prompt) => req("/image", { method: "POST", tok, body: { prompt } });

let fx;
afterEach(() => {
  fx?.restore();
  fx = null;
  clearLevelCache();
});

describe("/image (FLUX)", () => {
  it("मालिक: सही मॉडल, steps 4, base64 इमेज लौटती है; बाहर कोई fetch नहीं", async () => {
    fx = mockFetch(async () => new Response("{}"));
    let got;
    const e = env({ AI: ai(async (model, input) => ((got = { model, input }), { image: "/9j/AAAA" })) });
    const c = ctx();
    const r = await body(await call(e, img(await token(OWNER), "a red fox in snow, photorealistic"), { ctx: c }));
    await c.flush();
    assert.equal(r.status, 200);
    assert.equal(r.image, "/9j/AAAA");
    assert.equal(r.mime, "image/jpeg");
    assert.equal(got.model, "@cf/black-forest-labs/flux-1-schnell");
    assert.equal(IMAGE_MODEL, "@cf/black-forest-labs/flux-1-schnell");
    assert.deepEqual(got.input, { prompt: "a red fox in snow, photorealistic", steps: 4 });
    assert.equal(IMAGE_STEPS, 4);
    assert.equal(r.left_approx, IMAGES_PER_DAY - 1);
    assert.equal(fx.calls.length, 0);
    const row = await e.DB.prepare("SELECT images FROM image_usage WHERE day = ?").bind(utcDay(Date.now())).first();
    assert.equal(row.images, 1);
  });

  it("मालिक नहीं (मेहमान / दूसरा Google अकाउंट) = 403, मॉडल नहीं चलता", async () => {
    let n = 0;
    const e = env({ AI: ai(async () => (n++, { image: "x" })) });
    const guest = await body(await call(e, img(await token(), "a cat")));
    assert.equal(guest.status, 403);
    const other = await token({ ...OWNER, sub: "u2", email: "other@example.com" });
    assert.equal((await call(e, img(other, "a cat"))).status, 403);
    assert.equal((await call(e, req("/image/status", { tok: other }))).status, 403);
    assert.equal((await call(e, img(undefined, "a cat"))).status, 401);
    assert.equal(n, 0);
  });

  it("खाली / बहुत लंबा prompt = 400", async () => {
    const e = env({ AI: ai(async () => ({ image: "x" })) });
    const t = await token(OWNER);
    assert.equal((await call(e, img(t, "  "))).status, 400);
    assert.equal((await call(e, img(t, "a".repeat(2049)))).status, 400);
  });

  it("Cloudflare की रोज़ की सीमा = 429 IMAGE_LIMIT + ठीक वही हिंदी संदेश, फिर बची गिनती 0", async () => {
    fx = mockFetch(async () => new Response("{}"));
    const e = env({ AI: ai(async () => { throw new Error("AiError: 4006: you have used up your daily free allocation of 10,000 neurons"); }) });
    const t = await token(OWNER);
    const r = await body(await call(e, img(t, "a cat")));
    assert.equal(r.status, 429);
    assert.equal(r.code, "IMAGE_LIMIT");
    assert.equal(r.error, "आज की इमेज लिमिट पूरी हो गई, कल सुबह 5:30 बजे के बाद फिर बनेगी।");
    assert.equal(r.error, IMAGE_LIMIT_MSG);
    assert.equal(fx.calls.length, 0); // किसी दूसरी सेवा पर नहीं गए
    const s = await body(await call(e, req("/image/status", { tok: t })));
    assert.equal(s.left_approx, 0);
  });

  it("दूसरी गलती = 502, कोई fallback नहीं", async () => {
    fx = mockFetch(async () => new Response("{}"));
    const e = env({ AI: ai(async () => { throw new Error("model crashed"); }) });
    const r = await body(await call(e, img(await token(OWNER), "a cat")));
    assert.equal(r.status, 502);
    assert.equal(fx.calls.length, 0);
  });

  it("गिनती का हिसाब: 172.8 Neurons/इमेज => लगभग 57 रोज़; दिन UTC से (भारत 5:30)", () => {
    assert.equal(NEURONS_PER_IMAGE, 172.8);
    assert.equal(IMAGES_PER_DAY, 57);
    assert.equal(imagesLeft({ images: 10, limit_hit: 0 }), 47);
    assert.equal(imagesLeft({ images: 99, limit_hit: 0 }), 0);
    assert.equal(imagesLeft({ images: 1, limit_hit: 1 }), 0);
    // 20 नवंबर 05:29 IST = 19 नवंबर 23:59 UTC; 05:30 IST = नया दिन
    assert.equal(utcDay(Date.parse("2026-11-19T23:59:00Z")), "2026-11-19");
    assert.equal(utcDay(Date.parse("2026-11-20T00:00:00Z")), "2026-11-20");
  });

  it("/image/status: मालिक को आज की लगभग बची गिनती", async () => {
    const e = env({ AI: ai(async () => ({ image: "x" })) });
    const r = await body(await call(e, req("/image/status", { tok: await token(OWNER) })));
    assert.equal(r.status, 200);
    assert.equal(r.left_approx, 57);
  });
});
