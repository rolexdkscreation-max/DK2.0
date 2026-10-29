// DK AI - dk-brain Cloudflare Worker
// v3.68: Gemini TTS की आवाज़ यहाँ से - Gemini API key सिर्फ़ इस Worker के secret में (ऐप में नहीं).
//
//   GET  /ping                          -> "ok" (ऐप कनेक्शन गर्म रखता है)
//   POST /tts  {text, voice, style, model?} -> 16-bit LE PCM, 24 kHz, mono (Content-Type: audio/l16; rate=24000), stream
//
// Secrets:  GEMINI_API_KEY (ज़रूरी), APP_TOKEN (वैकल्पिक - हो तो ऐप "Authorization: Bearer <token>" भेजे)
// Vars:     TTS_MODELS (क्रम से आज़माए जाते हैं; जो चला वही आगे पहले)

const API = "https://generativelanguage.googleapis.com/v1beta/interactions";
const API_REVISION = "2026-05-20";
const DEFAULT_MODELS = "gemini-3.8-flash-lite-tts,gemini-3.8-flash-tts,gemini-3.1-flash-tts-preview";
const VOICES = new Set(["Kore", "Leda", "Aoede", "Sulafat", "Vindemiatrix", "Zephyr", "Puck", "Charon", "Fenrir", "Callirrhoe", "Despina", "Achernar"]);
const MAX_TEXT = 600;

let goodModel = null; // isolate में याद: जो मॉडल चला

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (env.APP_TOKEN) {
      const auth = req.headers.get("Authorization") || "";
      if (auth !== `Bearer ${env.APP_TOKEN}`) return text(401, "unauthorized");
    }
    if (url.pathname === "/ping") return text(200, "ok");
    if (url.pathname === "/tts" && req.method === "POST") return tts(req, env);
    return text(404, "not found");
  },
};

function text(status, body) {
  return new Response(body, { status, headers: { "Content-Type": "text/plain; charset=utf-8" } });
}

/** 3.8 मॉडल: स्टाइल अलग (speech_metadata). पुराने: स्टाइल text से पहले - हर टुकड़े में एक ही निर्देश */
export function buildBody(model, text, voice, style) {
  const s = (style || "").trim();
  const v38 = /^gemini-3\.8/.test(model);
  let input;
  if (v38 && s) {
    input = [{ type: "user_input", content: [{ type: "text", text, annotations: [{ type: "speech_metadata", style: s }] }] }];
  } else {
    input = s ? `${s} ${text}` : text;
  }
  return {
    model,
    input,
    response_format: { type: "audio" },
    generation_config: { speech_config: [{ voice }] },
    stream: true,
  };
}

async function tts(req, env) {
  if (!env.GEMINI_API_KEY) return text(500, "GEMINI_API_KEY secret नहीं डाली");
  let j;
  try { j = await req.json(); } catch { return text(400, "JSON चाहिए"); }
  const t = String(j.text || "").trim().slice(0, MAX_TEXT);
  if (!t) return text(400, "text खाली");
  const voice = VOICES.has(j.voice) ? j.voice : "Sulafat"; // v4.3: DK की एक ही आवाज़ (Live जैसी)
  const style = String(j.style || "").slice(0, 600); // v3.80: कोमल आवाज़ का पूरा निर्देश (पहले 300 पर कट जाता था)

  const list = (env.TTS_MODELS || DEFAULT_MODELS).split(",").map((s) => s.trim()).filter(Boolean);
  let models = goodModel && list.includes(goodModel) ? [goodModel, ...list.filter((m) => m !== goodModel)] : list;
  // v3.80: ऐप ने मॉडल चुना (सूची में हो तो) -> वही पहले, न चले तो बाकी
  const want = String(j.model || "").trim();
  if (want && list.includes(want)) models = [want, ...models.filter((m) => m !== want)];

  let last = "";
  for (const model of models) {
    const r = await fetch(API, {
      method: "POST",
      headers: {
        "x-goog-api-key": env.GEMINI_API_KEY,
        "Content-Type": "application/json",
        "Api-Revision": API_REVISION,
      },
      body: JSON.stringify(buildBody(model, t, voice, style)),
    });
    if (!r.ok) {
      last = `${model} ${r.status}: ${(await r.text()).slice(0, 200)}`;
      if (r.status === 404 || r.status === 400 || r.status === 429 || r.status >= 500) { if (goodModel === model) goodModel = null; continue; }
      return text(r.status, last);
    }
    goodModel = model;
    const ct = r.headers.get("Content-Type") || "";
    const body = ct.includes("event-stream") ? sseToPcm(r.body) : jsonToPcm(r);
    return new Response(body, {
      headers: { "Content-Type": "audio/l16; rate=24000", "Cache-Control": "no-store", "X-DK-Model": model },
    });
  }
  return text(502, `कोई TTS मॉडल नहीं चला: ${last}`);
}

/** SSE (data: {...}) -> audio delta -> PCM bytes, आते ही आगे */
export function sseToPcm(stream) {
  const dec = new TextDecoder();
  let buf = "";
  const { readable, writable } = new TransformStream();
  const w = writable.getWriter();
  (async () => {
    const rd = stream.getReader();
    try {
      for (;;) {
        const { value, done } = await rd.read();
        if (done) break;
        buf += dec.decode(value, { stream: true });
        let i;
        while ((i = buf.indexOf("\n")) >= 0) {
          const line = buf.slice(0, i).trim();
          buf = buf.slice(i + 1);
          const pcm = pcmFromLine(line);
          if (pcm) await w.write(pcm);
        }
      }
      const pcm = pcmFromLine(buf.trim());
      if (pcm) await w.write(pcm);
    } catch (e) {
      // बीच में टूटा - जितना आया उतना
    } finally {
      try { await w.close(); } catch {}
    }
  })();
  return readable;
}

export function pcmFromLine(line) {
  if (!line.startsWith("data:")) return null;
  const d = line.slice(5).trim();
  if (!d || d === "[DONE]") return null;
  let ev;
  try { ev = JSON.parse(d); } catch { return null; }
  const delta = ev.delta;
  if ((ev.event_type === "step.delta" || ev.type === "step.delta") && delta && delta.type === "audio" && delta.data) {
    return stripWav(b64(delta.data));
  }
  return null;
}

/** stream न मिले (पूरा JSON) - उसमें से audio data निकालो */
async function jsonToPcm(r) {
  const j = await r.json();
  const out = [];
  (function walk(o) {
    if (!o || typeof o !== "object") return;
    if (o.type === "audio" && typeof o.data === "string") out.push(stripWav(b64(o.data)));
    for (const k of Object.keys(o)) walk(o[k]);
  })(j);
  const n = out.reduce((a, b) => a + b.length, 0);
  const all = new Uint8Array(n);
  let off = 0;
  for (const b of out) { all.set(b, off); off += b.length; }
  return all;
}

function b64(s) {
  const bin = atob(s);
  const u = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i);
  return u;
}

/** RIFF/WAV header हो तो हटाओ (raw PCM ही आगे) */
export function stripWav(u) {
  if (u.length > 44 && u[0] === 0x52 && u[1] === 0x49 && u[2] === 0x46 && u[3] === 0x46) {
    // "data" chunk खोजो
    for (let i = 12; i + 8 <= u.length; ) {
      const id = String.fromCharCode(u[i], u[i + 1], u[i + 2], u[i + 3]);
      const size = u[i + 4] | (u[i + 5] << 8) | (u[i + 6] << 16) | (u[i + 7] << 24);
      if (id === "data") return u.subarray(i + 8);
      i += 8 + size;
    }
    return u.subarray(44);
  }
  return u;
}
