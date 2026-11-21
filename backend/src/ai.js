// AI की कड़ी: Gemini → हल्का Gemini → Mistral → (जो-जो key Worker में हो) Groq, OpenRouter, Z.ai, NVIDIA.
// अगली पर तब जाते हैं: सीमा (429), 5xx/कोई भी गलती, खाली जवाब, या 4 सेकंड में पहला शब्द नहीं.

import {
  AI_CHAIN, DK_HEAVY_WORDS, DK_LLM, DK_LLM_MAX_CHARS, DK_LLM_MAX_CONTEXT, DK_LLM_PERSONA, DK_LLM_TASKS, MODEL_TIMEOUT_MS,
} from "./config.js";
import { CODES, HttpError } from "./util.js";

/** text/event-stream से हर `data:` का हिस्सा */
export async function* sseData(body) {
  const reader = body.getReader();
  const dec = new TextDecoder();
  let buf = "";
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buf += dec.decode(value, { stream: true });
      let i;
      while ((i = buf.indexOf("\n")) >= 0) {
        const line = buf.slice(0, i).replace(/\r$/, "");
        buf = buf.slice(i + 1);
        if (line.startsWith("data:")) yield line.slice(5).trim();
      }
    }
    if (buf.startsWith("data:")) yield buf.slice(5).trim();
  } finally {
    reader.releaseLock?.();
  }
}

function textOf(c) {
  if (typeof c === "string") return c;
  if (Array.isArray(c)) return c.map((p) => (typeof p === "string" ? p : p?.text ?? "")).join("");
  return "";
}

/** ऐप के messages को साफ़ करो: user/assistant, खाली हटाओ, system अलग */
export function cleanMessages(messages, system) {
  if (!Array.isArray(messages) || messages.length === 0) throw new HttpError(400, CODES.BAD_REQUEST, "messages ज़रूरी");
  if (messages.length > 200) throw new HttpError(400, CODES.BAD_REQUEST, "बहुत सारे messages");
  const sys = [typeof system === "string" ? system : ""];
  const out = [];
  for (const m of messages) {
    const t = textOf(m?.content).slice(0, 100_000);
    if (!t.trim()) continue;
    if (m.role === "system") sys.push(t);
    else out.push({ role: m.role === "assistant" || m.role === "model" ? "assistant" : "user", content: t });
  }
  if (!out.length) throw new HttpError(400, CODES.BAD_REQUEST, "messages खाली");
  return { system: sys.filter(Boolean).join("\n\n"), messages: out };
}

/**
 * कौन-सा रास्ता: "dk" = रोज़ की आम बातचीत (DK LLM पहले), "big" = भारी / ताज़ा जानकारी (सीधे बड़ी कड़ी).
 * ऐप ने अपना क्रम (chain) भेजा हो या `dk: false` - तो "big".
 */
export function pickLane({ task = "chat", messages = [], chain, dk } = {}) {
  if (dk === false) return "big";
  if (Array.isArray(chain) && chain.length) return "big";
  if (!DK_LLM_TASKS.includes(task)) return "big";
  const users = messages.filter((m) => m.role === "user");
  const last = String(users[users.length - 1]?.content ?? "");
  if (!last.trim() || last.length > DK_LLM_MAX_CHARS) return "big";
  const total = messages.reduce((n, m) => n + String(m.content ?? "").length, 0);
  if (total > DK_LLM_MAX_CONTEXT) return "big";
  const low = last.toLowerCase();
  if (DK_HEAVY_WORDS.some((w) => low.includes(w))) return "big";
  return "dk";
}

/** DK LLM चालू है? `AI` binding हो और Worker var DK_LLM "off" न हो */
export function dkReady(env) {
  return !!env.AI && String(env.DK_LLM ?? "").toLowerCase() !== "off";
}

/** कौन-कौन सी कड़ी आज़मानी है. chain = ऐप की पसंद का क्रम (सिर्फ़ ids, keys नहीं). lane "dk" = DK LLM सबसे आगे */
export function planChain(env, chain, lane = "big") {
  let list = AI_CHAIN;
  if (Array.isArray(chain) && chain.length) {
    const want = chain.map(String);
    list = want.map((id) => AI_CHAIN.find((c) => c.id === id)).filter(Boolean);
  }
  const big = list.filter((c) => !!env[c.secret]);
  if (lane !== "dk" || !dkReady(env)) return big;
  return [{ ...DK_LLM, lora: String(env.DK_LLM_LORA || DK_LLM.lora || "") }, ...big];
}

/** DK LLM का system: DK का स्वभाव + आज की तारीख/समय (भारत) + ऐप का system */
export function dkSystem(system, now = Date.now()) {
  const when = new Intl.DateTimeFormat("hi-IN", {
    timeZone: "Asia/Kolkata", weekday: "long", year: "numeric", month: "long", day: "numeric", hour: "2-digit", minute: "2-digit",
  }).format(new Date(now));
  return [DK_LLM_PERSONA, "अभी (भारत का समय): " + when + ".", system].filter(Boolean).join("\n\n");
}

/** Cloudflare Workers AI (DK LLM) - stream को Response जैसा लौटाओ, ताकि बाकी कड़ी जैसा ही चले */
async function workersRequest(c, env, sys, msgs) {
  const input = {
    messages: [{ role: "system", content: dkSystem(sys) }, ...msgs],
    stream: true,
    max_tokens: c.maxTokens,
  };
  if (c.lora) input.lora = c.lora;
  const out = await env.AI.run(c.model, input);
  if (out instanceof Response) return out;
  return new Response(out, { headers: { "content-type": "text/event-stream" } });
}

function geminiRequest(c, key, sys, msgs, signal) {
  const body = {
    contents: msgs.map((m) => ({ role: m.role === "assistant" ? "model" : "user", parts: [{ text: m.content }] })),
  };
  if (sys) body.systemInstruction = { parts: [{ text: sys }] };
  return fetch(`https://generativelanguage.googleapis.com/v1beta/models/${c.model}:streamGenerateContent?alt=sse`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-goog-api-key": key },
    body: JSON.stringify(body),
    signal,
  });
}

function openaiRequest(c, key, sys, msgs, signal) {
  const messages = sys ? [{ role: "system", content: sys }, ...msgs] : msgs;
  return fetch(c.base + "/chat/completions", {
    method: "POST",
    headers: { "content-type": "application/json", authorization: "Bearer " + key, "x-title": "DK Assistant" },
    body: JSON.stringify({ model: c.model, messages, stream: true }),
    signal,
  });
}

/** एक कंपनी के stream से सिर्फ़ text के टुकड़े */
export async function* deltas(kind, body) {
  for await (const d of sseData(body)) {
    if (!d || d === "[DONE]") continue;
    let j;
    try {
      j = JSON.parse(d);
    } catch {
      continue;
    }
    if (j.error) throw new Error(String(j.error.message || "stream error").slice(0, 200));
    const t =
      kind === "gemini"
        ? (j.candidates?.[0]?.content?.parts ?? []).filter((p) => !p.thought).map((p) => p.text ?? "").join("")
        : textOf(j.choices?.[0]?.delta?.content) || (kind === "workers" && typeof j.response === "string" ? j.response : "");
    if (t) yield t;
  }
}

function sendTo(c, env, system, messages, signal) {
  if (c.kind === "workers") return workersRequest(c, env, system, messages);
  const req = c.kind === "gemini" ? geminiRequest : openaiRequest;
  return req(c, env[c.secret], system, messages, signal);
}

/** गलती का छोटा नाम: timeout / limit (Workers AI 4006 / 429) / network */
function failWhy(e) {
  const m = String(e?.message ?? "");
  if (m === "timeout") return "timeout";
  if (/4006|neuron|429|quota/i.test(m)) return "limit";
  return "network";
}

function withTimeout(p, ms, onTimeout) {
  let t;
  return Promise.race([
    p,
    new Promise((_, rej) => {
      t = setTimeout(() => {
        onTimeout?.();
        rej(new Error("timeout"));
      }, ms);
    }),
  ]).finally(() => clearTimeout(t));
}

/**
 * कड़ी खोलो: पहला शब्द देने वाली पहली कंपनी पर रुको.
 * @returns {{provider, model, tried, first, rest: AsyncIterator<string>, abort}}
 * tried = [{provider, model, ok, error}]
 */
export async function openChain(env, { system, messages, chain, lane }, opts = {}) {
  const timeout = opts.timeoutMs ?? MODEL_TIMEOUT_MS;
  const plan = planChain(env, chain, lane);
  if (!plan.length) throw new HttpError(503, CODES.NOT_CONFIGURED, "बैकएंड में कोई AI key नहीं डली");
  const tried = [];
  for (const c of plan) {
    const ac = new AbortController();
    const started = Date.now();
    try {
      const r = await withTimeout(sendTo(c, env, system, messages, ac.signal), timeout, () => ac.abort());
      if (!r.ok) {
        const why = r.status === 429 ? "limit" : `http ${r.status}`;
        try {
          await r.body?.cancel();
        } catch {
          /* body पहले ही बंद */
        }
        tried.push({ provider: c.id, model: c.model, ok: false, error: why });
        continue;
      }
      const it = deltas(c.kind, r.body)[Symbol.asyncIterator]();
      const left = Math.max(1, timeout - (Date.now() - started));
      const first = await withTimeout(it.next(), left, () => ac.abort());
      if (first.done || !first.value) {
        tried.push({ provider: c.id, model: c.model, ok: false, error: "empty" });
        ac.abort();
        continue;
      }
      tried.push({ provider: c.id, model: c.model, ok: true });
      return { provider: c.id, model: c.model, tried, first: first.value, rest: it, abort: () => ac.abort() };
    } catch (e) {
      ac.abort();
      tried.push({ provider: c.id, model: c.model, ok: false, error: failWhy(e) });
    }
  }
  throw new HttpError(503, CODES.ALL_FAILED, "अभी कोई AI जवाब नहीं दे पाया। थोड़ी देर बाद कोशिश करें।", { tried });
}

/**
 * ऐप के लिए SSE: `data: {"t":"..."}` बार-बार, आख़िर में `data: {"done":true, provider, model, tried}`.
 * onEnd(result) - जवाब पूरा होने के बाद (usage/model_health लिखने के लिए).
 */
export function streamResponse(open, headers = {}, onEnd, extra = {}) {
  const enc = new TextEncoder();
  const send = (ctrl, o) => ctrl.enqueue(enc.encode("data: " + JSON.stringify(o) + "\n\n"));
  let text = "";
  const stream = new ReadableStream({
    async start(ctrl) {
      let error = null;
      try {
        text += open.first;
        send(ctrl, { t: open.first });
        for (;;) {
          const n = await open.rest.next();
          if (n.done) break;
          text += n.value;
          send(ctrl, { t: n.value });
        }
      } catch (e) {
        error = "stream_broken";
        open.abort();
      }
      send(ctrl, { done: true, provider: open.provider, model: open.model, tried: open.tried, ...extra, ...(error ? { error } : {}) });
      ctrl.close();
      onEnd?.({ text, error });
    },
    cancel() {
      open.abort();
    },
  });
  return new Response(stream, {
    headers: { "content-type": "text/event-stream; charset=utf-8", "cache-control": "no-store", ...headers },
  });
}

/** बिना stream (stream:false) - पूरा जवाब JSON में */
export async function collect(open) {
  let text = open.first;
  for (;;) {
    const n = await open.rest.next();
    if (n.done) break;
    text += n.value;
  }
  return { text, provider: open.provider, model: open.model, tried: open.tried };
}
