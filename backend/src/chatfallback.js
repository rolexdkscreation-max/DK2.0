// 28 नवंबर 2026 (मालिक): सिर्फ़ DK चैट के जवाबों की fallback कड़ी (ऐप ने body.chat_chain = true भेजा हो तब).
// क्रम config.js की CHAT_FALLBACK.steps: Groq 8B → Groq gpt-oss-20b → Groq 70B → Gemini Flash-Lite → Workers AI Llama → OpenRouter :free.
// हर चरण: 4 सेकंड में पहला शब्द नहीं = बिना रुके अगला. 429 / 5xx = सिर्फ़ उसी चरण (उसी मॉडल) को 60 सेकंड आराम.
// हाल में सबसे तेज़ और सफल चरण सबसे आगे; बाकी डिफ़ॉल्ट क्रम में. log में सिर्फ़ मॉडल + समय (keys / संदेश कभी नहीं).

import { deltas, failWhy, fitContext, geminiRequest, openaiRequest, withTimeout, workersRequest } from "./ai.js";
import { CHAT_FALLBACK } from "./config.js";
import { settings as speedSettings } from "./speed.js";
import { CODES, HttpError } from "./util.js";

/** हर D1 (= हर Worker / हर test) की अपनी याद: चरण id → { until, ms, okAt } */
const memo = new WeakMap();
let shared = new Map();

function stateOf(env) {
  const k = env?.DB && typeof env.DB === "object" ? env.DB : null;
  if (!k) return shared;
  let s = memo.get(k);
  if (!s) {
    s = new Map();
    memo.set(k, s);
  }
  return s;
}

function stepState(s, id) {
  let x = s.get(id);
  if (!x) {
    x = { until: 0, ms: null, okAt: 0 };
    s.set(id, x);
  }
  return x;
}

/** tests के लिए: सारी याद साफ़ */
export function resetChatFallback(env) {
  if (env?.DB && typeof env.DB === "object") memo.delete(env.DB);
  shared = new Map();
}

/** किसी चरण का आराम / रफ़्तार (tests और /models/health जैसे देखने के लिए) */
export function chatStepState(env, id) {
  const x = stateOf(env).get(id);
  return x ? { ...x } : { until: 0, ms: null, okAt: 0 };
}

/** यह चरण इस Worker में चल सकता है? (key / AI binding हो, और AI_OFF में बंद न हो) */
function ready(env, c, off) {
  if (off.has(c.provider) || off.has(c.id)) return false;
  return c.kind === "workers" ? !!env?.AI : !!env?.[c.secret];
}

/**
 * आज़माने का क्रम: डिफ़ॉल्ट क्रम, आराम वाले बाहर; हाल में (recentMs) सफल चरणों में सबसे तेज़ = सबसे आगे.
 * सब आराम में हों = आख़िरी उपाय: आराम वाले भी डिफ़ॉल्ट क्रम में (ताकि यूज़र खाली हाथ न लौटे).
 */
export function orderSteps(env, now = Date.now(), cfg = CHAT_FALLBACK) {
  const s = stateOf(env);
  const off = speedSettings(env).off;
  const all = cfg.steps.filter((c) => ready(env, c, off));
  const open = all.filter((c) => !(stepState(s, c.id).until > now));
  if (!open.length) return all;
  let best = null;
  for (const c of open) {
    const x = stepState(s, c.id);
    if (!x.okAt || now - x.okAt > cfg.recentMs || x.ms == null) continue;
    if (!best || x.ms < stepState(s, best.id).ms) best = c;
  }
  return best ? [best, ...open.filter((c) => c !== best)] : open;
}

/** पुरानी चैट के सिर्फ़ आख़िरी n संदेश (पूरा history नहीं); शुरू में assistant न रहे */
export function lastMessages(messages, n = CHAT_FALLBACK.historyMessages) {
  const out = messages.slice(-Math.max(1, n));
  while (out.length > 1 && out[0].role === "assistant") out.shift();
  return out;
}

function send(c, env, sys, msgs, signal) {
  const m = fitContext(sys, msgs, c.maxContextTokens);
  if (c.kind === "workers") return workersRequest(c, env, sys, m);
  const req = c.kind === "gemini" ? geminiRequest : openaiRequest;
  return req(c, env[c.secret], sys, m, signal);
}

/**
 * कड़ी खोलो: पहला शब्द देने वाले पहले चरण पर रुको. openChain जैसा ही नतीजा, ताकि streamResponse / collect वैसे ही चलें.
 * @returns {{provider, model, step, tried, marks, first, rest, abort}}
 */
export async function openChatFallback(env, { system, messages }, opts = {}) {
  const cfg = opts.config ?? CHAT_FALLBACK;
  const clock = () => opts.now?.() ?? Date.now();
  const timeout = opts.stepTimeoutMs ?? cfg.stepTimeoutMs;
  const log = opts.log ?? ((line) => console.log(line));
  const s = stateOf(env);
  const plan = orderSteps(env, clock(), cfg);
  if (!plan.length) throw new HttpError(503, CODES.ALL_FAILED, cfg.failMessage, { tried: [] });
  const msgs = lastMessages(messages, cfg.historyMessages);
  const tried = [];
  const rest = (c) => {
    stepState(s, c.id).until = clock() + cfg.cooldownMs;
  };
  for (const c of plan) {
    const ac = new AbortController();
    const started = Date.now();
    try {
      const r = await withTimeout(send(c, env, system, msgs, ac.signal), timeout, () => ac.abort());
      if (!r.ok) {
        // 429 (सीमा) / 5xx (सर्वर की गड़बड़ी) = सिर्फ़ इसी चरण को आराम. बाकी (400/401/404) = बस अगला चरण
        const why = r.status === 429 ? "limit" : `http ${r.status}`;
        if (r.status === 429 || r.status >= 500) rest(c);
        try {
          await r.body?.cancel();
        } catch {
          /* body पहले ही बंद */
        }
        tried.push({ provider: c.provider, model: c.model, ok: false, error: why });
        continue;
      }
      const it = deltas(c.kind, r.body)[Symbol.asyncIterator]();
      const left = Math.max(1, timeout - (Date.now() - started));
      const first = await withTimeout(it.next(), left, () => ac.abort());
      if (first.done || !first.value) {
        tried.push({ provider: c.provider, model: c.model, ok: false, error: "empty" });
        ac.abort();
        continue;
      }
      const ms = Date.now() - started;
      const x = stepState(s, c.id);
      x.ms = x.ms == null ? ms : x.ms * (1 - cfg.alpha) + ms * cfg.alpha;
      x.okAt = clock();
      x.until = 0;
      tried.push({ provider: c.provider, model: c.model, ok: true });
      // सिर्फ़ मॉडल और समय - keys / यूज़र का संदेश कभी नहीं
      log(`DK chat: ${c.provider}/${c.model} ${ms} ms`);
      return { provider: c.provider, model: c.model, step: c.id, tried, marks: [], first: first.value, rest: it, abort: () => ac.abort() };
    } catch (e) {
      ac.abort();
      const why = failWhy(e);
      // Workers AI की सीमा (4006 / 429) = आराम; timeout / नेटवर्क = बस अगला चरण
      if (why === "limit") rest(c);
      tried.push({ provider: c.provider, model: c.model, ok: false, error: why });
    }
  }
  log("DK chat: सब चरण विफल");
  throw new HttpError(503, CODES.ALL_FAILED, cfg.failMessage, { tried });
}
