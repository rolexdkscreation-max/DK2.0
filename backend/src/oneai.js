// 28 नवंबर 2026 (मालिक): One AI API Key (oneaiapikey.com) - OpenAI जैसी gateway, एक कुंजी से कई मॉडल.
// कुंजी सिर्फ़ Worker secret (ONEAI_API_KEY, ONEAI_API_KEY_2) में - कभी जवाब, लॉग या ऐप में नहीं.
// यहाँ: असली मॉडल-सूची (10 मिनट याद), काम के हिसाब से रैंकिंग (D1 oneai_rank), कुंजी की कतार (एक समय में एक),
// रोज़ का परखना (cron + /oneai/bench).

import { ONEAI } from "./config.js";
import { Db } from "./db.js";

const memo = new WeakMap();
const noDb = {};

function state(env) {
  const k = env?.DB ?? noDb;
  let s = memo.get(k);
  if (!s) {
    s = { models: null, modelsAt: 0, rank: null, rankAt: 0, slots: [] };
    memo.set(k, s);
  }
  return s;
}

/** भरी हुई कुंजियाँ (क्रम से) */
export function oneaiKeys(env) {
  return ONEAI.secrets.map((n) => env?.[n]).filter((k) => typeof k === "string" && k.trim());
}

/** "मुफ़्त" = ID के अंत में :free (बस यही नियम) */
export function isFree(id) {
  return /:free$/i.test(String(id ?? ""));
}

export function isBanned(id) {
  const low = String(id ?? "").toLowerCase();
  return ONEAI.banned.some((w) => low.includes(w));
}

/**
 * DK अपने-आप कौन-सा मॉडल चुन सकता है: :free, मना वाले शब्द नहीं, और पैसे वाला जोड़ीदार (वही नाम बिना :free) सूची में नहीं
 * (docs: जोड़ीदार वाले :free पर क्रेडिट कटता है). ids = असली सूची (null = पता नहीं, तब जोड़ीदार वाली जाँच नहीं).
 */
export function autoOk(id, ids) {
  if (!isFree(id) || isBanned(id)) return false;
  if (!ids) return true;
  return ids.has(id) && !ids.has(String(id).replace(/:free$/i, ""));
}

/** ऐप का task → chat / phone / coding / deep_research */
export function oneaiTask(task) {
  return ONEAI.taskMap[String(task ?? "chat")] ?? "chat";
}

/** असली मॉडल-सूची (GET /v1/models, मुफ़्त, किसी सीमा में नहीं गिनता). 10 मिनट याद; न मिले = पुरानी याद या null */
export async function listModels(env, now = Date.now()) {
  const s = state(env);
  if (s.models && now - s.modelsAt < ONEAI.modelsCacheMs) return s.models;
  const key = oneaiKeys(env)[0];
  if (!key) return s.models;
  try {
    const r = await fetch(ONEAI.base + "/models", { headers: { authorization: "Bearer " + key } });
    if (!r.ok) {
      await r.body?.cancel();
      return s.models;
    }
    const j = await r.json();
    const data = Array.isArray(j?.data) ? j.data : Array.isArray(j?.models) ? j.models : Array.isArray(j) ? j : [];
    const ids = data.map((m) => (typeof m === "string" ? m : m?.id)).filter((x) => typeof x === "string" && x);
    if (ids.length) {
      s.models = ids;
      s.modelsAt = now;
    }
  } catch {
    /* नेटवर्क - पुरानी याद से काम */
  }
  return s.models;
}

/** D1 की रैंकिंग (1 मिनट याद). खाली = शुरुआती क्रम */
async function loadRank(env, now) {
  const s = state(env);
  if (s.rank && now - s.rankAt < ONEAI.rankCacheMs) return s.rank;
  const out = {};
  for (const t of Object.keys(ONEAI.defaults)) out[t] = [];
  try {
    if (env?.DB) for (const r of await new Db(env.DB).oneaiRank()) (out[r.task] ??= []).push(r);
  } catch {
    /* D1 न चले - शुरुआती क्रम */
  }
  for (const t of Object.keys(ONEAI.defaults)) {
    if (!out[t].length) out[t] = ONEAI.defaults[t].map((model, pos) => ({ task: t, model, pos, ok_count: 0, fail_count: 0, fail_streak: 0, ttft_ms: null }));
  }
  s.rank = out;
  s.rankAt = now;
  return out;
}

/** एक काम के चलने लायक मॉडल, क्रम से */
export async function rankedModels(env, task, now = Date.now()) {
  const rank = await loadRank(env, now);
  const list = await listModels(env, now);
  const ids = list ? new Set(list) : null;
  return (rank[oneaiTask(task)] ?? rank.chat).map((r) => r.model).filter((m) => autoOk(m, ids));
}

/** सूची के लिए: हर काम का क्रम (नाप के साथ) */
export async function rankTable(env, now = Date.now()) {
  return loadRank(env, now);
}

/**
 * कतार: एक कुंजी पर एक समय में एक अनुरोध. waitMs तक कोई कुंजी खाली न हो = null (कड़ी अगली पर जाती है).
 * @returns {Promise<{key: string, release: () => void} | null>}
 */
export async function acquire(env, waitMs = ONEAI.queueWaitMs) {
  const keys = oneaiKeys(env);
  if (!keys.length) return null;
  const s = state(env);
  while (s.slots.length < keys.length) s.slots.push({ busy: false, waiters: [] });
  const take = (i) => {
    s.slots[i].busy = true;
    let done = false;
    return {
      key: keys[i],
      release: () => {
        if (done) return;
        done = true;
        const next = s.slots[i].waiters.shift();
        if (next) next(take(i));
        else s.slots[i].busy = false;
      },
    };
  };
  const free = s.slots.findIndex((x, i) => i < keys.length && !x.busy);
  if (free >= 0) return take(free);
  if (waitMs <= 0) return null;
  // सबसे छोटी कतार वाली कुंजी पर रुको
  let best = 0;
  for (let i = 1; i < keys.length; i++) if (s.slots[i].waiters.length < s.slots[best].waiters.length) best = i;
  return new Promise((resolve) => {
    let t = null;
    const w = (slot) => {
      clearTimeout(t);
      resolve(slot);
    };
    s.slots[best].waiters.push(w);
    t = setTimeout(() => {
      const q = s.slots[best].waiters;
      const at = q.indexOf(w);
      if (at >= 0) q.splice(at, 1);
      resolve(null);
    }, waitMs);
  });
}

/** stream के ख़त्म / रद्द होते ही कुंजी खाली */
function releasing(body, release) {
  if (!body) {
    release();
    return body;
  }
  const reader = body.getReader();
  return new ReadableStream({
    async pull(ctrl) {
      try {
        const { value, done } = await reader.read();
        if (done) {
          release();
          ctrl.close();
        } else ctrl.enqueue(value);
      } catch (e) {
        release();
        ctrl.error(e);
      }
    },
    async cancel(r) {
      release();
      try {
        await reader.cancel(r);
      } catch {
        /* पहले ही बंद */
      }
    },
  });
}

/**
 * One AI को एक अनुरोध (stream). कतार में जगह न मिले = 503 जैसा जवाब (चुपचाप अगली कड़ी).
 * पैसे वाले मॉडल ने जवाब दिया (X-Fallback-From) = वह जवाब नहीं - 503.
 */
export async function oneaiRequest(env, { model, system, messages, signal, waitMs, extra = {} }) {
  const slot = await acquire(env, waitMs);
  if (!slot) return new Response(null, { status: 503, statusText: "oneai busy" });
  // इंतज़ार में ही समय ख़त्म (कड़ी आगे बढ़ चुकी) = कुंजी तुरंत खाली
  if (signal?.aborted) {
    slot.release();
    return new Response(null, { status: 503, statusText: "oneai busy" });
  }
  let r;
  try {
    const msgs = system ? [{ role: "system", content: system }, ...messages] : messages;
    r = await fetch(ONEAI.base + "/chat/completions", {
      method: "POST",
      headers: { "content-type": "application/json", authorization: "Bearer " + slot.key },
      body: JSON.stringify({ model, messages: msgs, stream: true, ...extra }),
      signal,
    });
  } catch (e) {
    slot.release();
    throw e;
  }
  signal?.addEventListener?.("abort", () => slot.release());
  if (!r.ok || r.headers.get("x-fallback-from")) {
    slot.release();
    if (r.ok) {
      try {
        await r.body?.cancel();
      } catch {
        /* बंद */
      }
      return new Response(null, { status: 503, statusText: "paid fallback" });
    }
    return r;
  }
  return new Response(releasing(r.body, slot.release), { status: r.status, headers: r.headers });
}

/** SSE से पहला text का टुकड़ा (OpenAI format) */
async function firstText(body) {
  const reader = body.getReader();
  const dec = new TextDecoder();
  let buf = "";
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) return "";
      buf += dec.decode(value, { stream: true });
      let i;
      while ((i = buf.indexOf("\n")) >= 0) {
        const line = buf.slice(0, i).trim();
        buf = buf.slice(i + 1);
        if (!line.startsWith("data:")) continue;
        const d = line.slice(5).trim();
        if (!d || d === "[DONE]") continue;
        try {
          const j = JSON.parse(d);
          const t = j?.choices?.[0]?.delta?.content;
          if (typeof t === "string" && t) return t;
        } catch {
          /* अधूरी line */
        }
      }
    }
  } finally {
    try {
      await reader.cancel();
    } catch {
      /* बंद */
    }
  }
}

/**
 * क्रम बनाओ: पहले सफल + तेज़ (ttft), फिर कम-सफल; लगातार failDown बार चूके = सबसे नीचे. सिर्फ़ असली सूची वाले.
 * rows = [{model, ttft_ms, ok_count, fail_count, fail_streak}]
 */
export function orderRows(rows) {
  const rate = (r) => (r.ok_count + 1) / (r.ok_count + r.fail_count + 2);
  const down = (r) => (r.fail_streak >= ONEAI.failDown ? 1 : 0);
  return [...rows].sort((a, b) => down(a) - down(b) || rate(b) - rate(a) || (a.ttft_ms ?? Infinity) - (b.ttft_ms ?? Infinity));
}

/**
 * परखो: हर मॉडल को वही छोटा सवाल, पहला शब्द आने का समय और सफलता नापो, D1 में नया क्रम.
 * एक-एक करके (कतार), हर मॉडल एक बार (रोज़ के 100 में से ~7 अनुरोध). onUse(model) = गिनती के लिए.
 */
export async function bench(env, now = Date.now(), { onUse } = {}) {
  if (!oneaiKeys(env).length) return { ok: false, reason: "no_key", results: [] };
  const list = await listModels(env, now);
  const ids = list ? new Set(list) : null;
  const rank = await loadRank(env, now);
  const models = [...new Set(Object.values(rank).flat().map((r) => r.model))].filter((m) => autoOk(m, ids));
  const results = [];
  for (const model of models) {
    const ac = new AbortController();
    const t0 = Date.now();
    let ok = false;
    let error = null;
    let timer = null;
    try {
      const run = (async () => {
        const r = await oneaiRequest(env, { model, messages: [{ role: "user", content: ONEAI.benchPrompt }], signal: ac.signal, waitMs: ONEAI.benchTimeoutMs, extra: { max_tokens: 16 } });
        if (!r.ok) {
          try {
            await r.body?.cancel();
          } catch {
            /* बंद */
          }
          throw new Error("http " + r.status);
        }
        const t = await firstText(r.body);
        if (!t) throw new Error("empty");
      })();
      await Promise.race([run, new Promise((_, rej) => (timer = setTimeout(() => rej(new Error("timeout")), ONEAI.benchTimeoutMs)))]);
      ok = true;
    } catch (e) {
      error = String(e?.message ?? "error").slice(0, 80);
    } finally {
      clearTimeout(timer);
      ac.abort();
    }
    onUse?.(model);
    results.push({ model, ok, ms: ok ? Date.now() - t0 : null, error });
  }
  const byModel = new Map(results.map((r) => [r.model, r]));
  const out = {};
  for (const [task, rows] of Object.entries(rank)) {
    const next = rows.map((r) => {
      const b = byModel.get(r.model);
      if (!b) return { ...r };
      const ttft = b.ok ? (r.ttft_ms == null ? b.ms : r.ttft_ms * 0.7 + b.ms * 0.3) : r.ttft_ms;
      return {
        ...r,
        ttft_ms: ttft,
        ok_count: (r.ok_count ?? 0) + (b.ok ? 1 : 0),
        fail_count: (r.fail_count ?? 0) + (b.ok ? 0 : 1),
        fail_streak: b.ok ? 0 : (r.fail_streak ?? 0) + 1,
        last_error: b.ok ? null : b.error,
      };
    });
    out[task] = orderRows(next);
    if (env?.DB) {
      try {
        await new Db(env.DB).saveOneaiRank(task, out[task], now);
      } catch {
        /* D1 की गलती - अगले दिन फिर */
      }
    }
  }
  const s = state(env);
  s.rank = out;
  s.rankAt = now;
  return { ok: true, results, rank: Object.fromEntries(Object.entries(out).map(([t, rows]) => [t, rows.map((r) => r.model)])) };
}

/** test के लिए: याद साफ़ */
export function resetOneai(env) {
  memo.delete(env?.DB ?? noDb);
}
