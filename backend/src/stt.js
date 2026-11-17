// आवाज़ से लिखाई: Cloudflare Workers AI Whisper. आवाज़ कहीं रखी नहीं जाती - सीधे मॉडल को, फिर भूल जाते हैं.

import { STT_MAX_BYTES, STT_MODEL } from "./config.js";
import { CODES, HttpError } from "./util.js";

export const STT_LIMIT_MSG = "आज की आवाज़ पहचान की मुफ़्त सीमा पूरी हुई - फ़ोन की अपनी पहचान से चलाते हैं।";

function b64(bytes) {
  let s = "";
  const step = 0x8000;
  for (let i = 0; i < bytes.length; i += step) s += String.fromCharCode(...bytes.subarray(i, i + step));
  return btoa(s);
}

/** WAV header से सेकंड; न मिले तो 16 kHz/16-bit/mono मानकर */
export function audioSeconds(bytes) {
  if (bytes.length > 44 && String.fromCharCode(...bytes.subarray(0, 4)) === "RIFF") {
    const v = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const byteRate = v.getUint32(28, true);
    if (byteRate > 0) return (bytes.length - 44) / byteRate;
  }
  return bytes.length / 32_000;
}

export function isQuotaError(e) {
  const m = String(e?.message ?? e ?? "").toLowerCase();
  return m.includes("4006") || m.includes("neuron") || m.includes("quota") || m.includes("daily") || m.includes("limit") || m.includes("429");
}

/** req: कच्ची आवाज़ (audio/wav, audio/webm...) या JSON {audio: base64, language} */
export async function readAudio(req) {
  const url = new URL(req.url);
  const ct = req.headers.get("content-type") || "";
  let bytes;
  let language = url.searchParams.get("lang") || "";
  let prompt = url.searchParams.get("prompt") || "";
  if (ct.includes("application/json")) {
    const j = await req.json().catch(() => ({}));
    try {
      bytes = Uint8Array.from(atob(String(j.audio || "")), (c) => c.charCodeAt(0));
    } catch {
      throw new HttpError(400, CODES.BAD_REQUEST, "आवाज़ (base64) सही नहीं");
    }
    language = j.language || language;
    prompt = j.initial_prompt || prompt;
  } else {
    bytes = new Uint8Array(await req.arrayBuffer());
  }
  if (!bytes.length) throw new HttpError(400, CODES.BAD_REQUEST, "आवाज़ खाली");
  if (bytes.length > STT_MAX_BYTES) throw new HttpError(413, CODES.BAD_REQUEST, "आवाज़ बहुत लंबी - छोटे टुकड़े भेजें");
  return { bytes, language: String(language).slice(0, 8), prompt: String(prompt).slice(0, 300) };
}

export async function transcribe(env, { bytes, language, prompt }) {
  if (!env.AI) throw new HttpError(503, CODES.NOT_CONFIGURED, "Workers AI जुड़ा नहीं");
  const input = { audio: b64(bytes), task: "transcribe", vad_filter: true };
  if (language) input.language = language;
  if (prompt) input.initial_prompt = prompt;
  let out;
  try {
    out = await env.AI.run(STT_MODEL, input);
  } catch (e) {
    if (isQuotaError(e)) throw new HttpError(429, CODES.STT_LIMIT, STT_LIMIT_MSG);
    throw new HttpError(502, CODES.ALL_FAILED, "आवाज़ पहचान नहीं हो पाई");
  }
  const segs = Array.isArray(out?.segments) ? out.segments : [];
  const dur = Number(out?.transcription_info?.duration) || Number(segs.at(-1)?.end) || audioSeconds(bytes);
  return {
    text: String(out?.text ?? "").trim(),
    language: out?.transcription_info?.language || language || "",
    seconds: Math.max(1, Math.ceil(Math.max(dur, audioSeconds(bytes)))),
  };
}
