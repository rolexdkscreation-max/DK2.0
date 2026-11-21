// 21 नवंबर 2026 (मालिक): "सबसे तेज़ पहले" - हर AI कंपनी की रफ़्तार (पहला शब्द), आराम (60 सेकंड), रोज़ की गिनती.
// आँकड़े D1 में (provider_stats, provider_daily) ताकि Worker दोबारा चले तो भी बचें; हर रिक्वेस्ट पर D1 न पढ़ें,
// इसलिए SPEED.cacheMs तक Worker की याद में. SQL सिर्फ़ db.js में.

import { CHARS_PER_TOKEN, NEURONS_PER_IMAGE, PAID_PROVIDERS, PROVIDER_LIMITS, PROVIDER_OFF, SPEED, WORKERS_NEURONS_PER_1K } from "./config.js";
import { Db } from "./db.js";

/** हर D1 (= हर Worker / हर test) की अपनी याद */
const memo = new WeakMap();
const noDb = {};

function state(env) {
  const k = env?.DB ?? noDb;
  let s = memo.get(k);
  if (!s) {
    s = { at: 0, day: "", stats: new Map(), counts: new Map(), images: 0, n: 0 };
    memo.set(k, s);
  }
  return s;
}

export function utcDay(now) {
  return new Date(now).toISOString().slice(0, 10);
}

/** अगली 00:00 UTC (भारत सुबह 5:30) - रोज़ की सीमा वहीं नई */
export function nextUtcMidnight(now) {
  const d = new Date(now);
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate() + 1);
}

/** सेटिंग: क्रम (fast / fixed) और बंद कंपनियाँ - config, फिर Worker vars AI_ORDER / AI_OFF */
export function settings(env) {
  const order = String(env?.AI_ORDER || SPEED.order).toLowerCase() === "fixed" ? "fixed" : "fast";
  const off = new Set([...PROVIDER_OFF, ...String(env?.AI_OFF || "").split(",").map((x) => x.trim()).filter(Boolean)]);
  return { order, off };
}

/** D1 से आँकड़े (cacheMs में एक बार). D1 न चले = पुरानी याद से काम */
export async function load(env, now) {
  const s = state(env);
  const day = utcDay(now);
  if (s.at && now - s.at < SPEED.cacheMs && s.day === day) return s;
  if (!env?.DB) return s;
  try {
    const db = new Db(env.DB);
    const [stats, counts, img] = await Promise.all([db.providerStats(), db.providerDay(day), db.getImageUsage(day)]);
    // याद में जो ताज़ा है (इसी Worker ने अभी लिखा) वह D1 के पुराने से न दबे
    for (const r of stats) {
      const m = s.stats.get(r.provider);
      if (!m || (r.updated_at ?? 0) >= (m.updated_at ?? 0)) s.stats.set(r.provider, { ...r });
    }
    if (s.day !== day) s.counts = new Map();
    for (const r of counts) {
      const m = s.counts.get(r.provider);
      if (!m || r.requests >= m.requests) s.counts.set(r.provider, { requests: r.requests, tokens: r.tokens, neurons: r.neurons });
    }
    s.images = img?.images ?? 0;
    s.day = day;
    s.at = now;
  } catch {
    s.at = now;
  }
  return s;
}

/** आज की सीमा का कितना हिस्सा खर्च (0..1+). सीमा पता नहीं = 0 */
export function usedShare(env, id) {
  const lim = PROVIDER_LIMITS[id];
  if (!lim) return 0;
  const s = state(env);
  const c = s.counts.get(id) ?? { requests: 0, tokens: 0, neurons: 0 };
  const neurons = c.neurons + (id === "cloudflare" ? s.images * NEURONS_PER_IMAGE : 0);
  let share = 0;
  if (lim.requests) share = Math.max(share, c.requests / lim.requests);
  if (lim.tokens) share = Math.max(share, c.tokens / lim.tokens);
  if (lim.neurons) share = Math.max(share, neurons / lim.neurons);
  return share;
}

/** आराम पर / आज बंद / सीमा पूरी */
export function resting(env, id, now) {
  const st = state(env).stats.get(id);
  return (st?.cool_until ?? 0) > now || (st?.off_until ?? 0) > now || usedShare(env, id) >= 1;
}

/**
 * क्रम: [तेज़ से धीमा] → [सीमा 90%+] → [paid] → [आराम पर / आज बंद] (आख़िरी सहारा - खाली कभी नहीं).
 * हर 10वीं रिक्वेस्ट (SPEED.explore) तेज़ वालों में से किसी दूसरे को पहले - उसकी रफ़्तार भी नपती रहे.
 * fixed = config का क्रम (आराम वाले फिर भी पीछे).
 */
export function orderPlan(plan, env, now, { fixed = false } = {}) {
  const set = settings(env);
  const on = plan.filter((c) => !set.off.has(c.id));
  const list = on.length ? on : plan;
  const s = state(env);
  const fast = [];
  const low = [];
  const paid = [];
  const rest = [];
  for (const c of list) {
    if (resting(env, c.id, now)) rest.push(c);
    else if (PAID_PROVIDERS.includes(c.id)) paid.push(c);
    else if (usedShare(env, c.id) >= SPEED.demoteAt) low.push(c);
    else fast.push(c);
  }
  let explored = null;
  if (!fixed && set.order === "fast") {
    const ms = (c) => s.stats.get(c.id)?.ewma_ms ?? Number.POSITIVE_INFINITY;
    fast.sort((a, b) => ms(a) - ms(b));
    s.n += 1;
    const every = Math.max(1, Math.round(1 / SPEED.explore));
    if (fast.length > 1 && s.n % every === 0) {
      const j = 1 + (Math.floor(s.n / every) % (fast.length - 1));
      const [c] = fast.splice(j, 1);
      fast.unshift(c);
      explored = c.id;
    }
  }
  return { list: [...fast, ...low, ...paid, ...rest], explored };
}

/** रोज़ की सीमा वाली गलती? (Groq: "per day"/RPD/TPD, Cloudflare: 4006 / daily) */
export function isDailyLimit(text) {
  return /per day|\brpd\b|\btpd\b|daily|4006|neurons/i.test(String(text ?? ""));
}

/**
 * एक कोशिश का नतीजा याद में (तुरंत) - D1 में लिखना [persist] करता है.
 * mark = {provider, ok, ms, error, detail}. error: limit / http 5xx / timeout / network = 60 सेकंड आराम; रोज़ की सीमा = कल तक.
 */
export function note(env, mark, now) {
  const s = state(env);
  const old = s.stats.get(mark.provider) ?? { provider: mark.provider, ewma_ms: null, samples: 0, cool_until: 0, off_until: 0 };
  const st = { ...old, updated_at: now };
  if (mark.ok) {
    st.ewma_ms = old.ewma_ms == null ? mark.ms : old.ewma_ms * (1 - SPEED.alpha) + mark.ms * SPEED.alpha;
    st.samples = (old.samples ?? 0) + 1;
    st.cool_until = 0;
  } else {
    const e = String(mark.error ?? "");
    if (e === "limit" && isDailyLimit(mark.detail)) st.off_until = nextUtcMidnight(now);
    if (e === "limit" || e === "timeout" || e === "network" || /^http 5\d\d$/.test(e)) st.cool_until = now + SPEED.cooldownMs;
    st.last_error = (e + (mark.detail ? ": " + String(mark.detail).replace(/\s+/g, " ").slice(0, 120) : "")).slice(0, 200);
    st.last_error_at = now;
  }
  s.stats.set(mark.provider, st);
  return st;
}

/** [note] के नतीजे D1 में (waitUntil से) */
export function persist(env, mark, st, now) {
  if (!env?.DB) return Promise.resolve();
  return new Db(env.DB).saveProvider(
    mark.provider,
    {
      ewma: mark.ok ? st.ewma_ms : null,
      samples: st.samples,
      cool: st.cool_until,
      off: st.off_until,
      error: mark.ok ? null : st.last_error,
    },
    now,
  );
}

/** जवाब पूरा: आज की गिनती (रिक्वेस्ट, token का अंदाज़ा, Cloudflare के Neurons) */
export function countUse(env, provider, inChars, outChars, now) {
  const s = state(env);
  const day = utcDay(now);
  if (s.day && s.day !== day) {
    s.counts = new Map();
    s.images = 0;
  }
  s.day = day;
  const tin = Math.ceil(inChars / CHARS_PER_TOKEN);
  const tout = Math.ceil(outChars / CHARS_PER_TOKEN);
  const neurons = provider === "cloudflare" ? (tin / 1000) * WORKERS_NEURONS_PER_1K.input + (tout / 1000) * WORKERS_NEURONS_PER_1K.output : 0;
  const c = s.counts.get(provider) ?? { requests: 0, tokens: 0, neurons: 0 };
  s.counts.set(provider, { requests: c.requests + 1, tokens: c.tokens + tin + tout, neurons: c.neurons + neurons });
  if (!env?.DB) return Promise.resolve();
  return new Db(env.DB).addProviderDay(day, provider, 1, tin + tout, Math.round(neurons * 10) / 10);
}

/** /health: हर कंपनी की हालत */
export async function health(env, chain, available, now) {
  await load(env, now);
  const s = state(env);
  const set = settings(env);
  const { list } = orderPlan(chain.filter((c) => available(env, c)), env, now, { fixed: true });
  return {
    order: set.order,
    next: list.map((c) => c.id),
    providers: chain.map((c) => {
      const st = s.stats.get(c.id) ?? {};
      const cnt = s.counts.get(c.id) ?? { requests: 0, tokens: 0, neurons: 0 };
      let status = "ready";
      if (!available(env, c)) status = "no_key";
      else if (set.off.has(c.id)) status = "disabled";
      else if ((st.off_until ?? 0) > now || usedShare(env, c.id) >= 1) status = "off_today";
      else if ((st.cool_until ?? 0) > now) status = "cooling";
      else if (usedShare(env, c.id) >= SPEED.demoteAt) status = "near_limit";
      return {
        provider: c.id,
        model: c.model,
        status,
        ewma_ms: st.ewma_ms == null ? null : Math.round(st.ewma_ms),
        samples: st.samples ?? 0,
        today: { requests: cnt.requests, tokens: cnt.tokens, neurons: Math.round(cnt.neurons + (c.id === "cloudflare" ? s.images * NEURONS_PER_IMAGE : 0)) },
        limit: PROVIDER_LIMITS[c.id] ?? null,
        used_pct: Math.round(usedShare(env, c.id) * 100),
        cool_until: st.cool_until || null,
        off_until: st.off_until || null,
        last_error: st.last_error ?? null,
        last_error_at: st.last_error_at ?? null,
      };
    }),
  };
}

/** याद में किसी कंपनी का ताज़ा हाल ([persist] के लिए) */
export function stat(env, provider) {
  return state(env).stats.get(provider) ?? { provider, ewma_ms: null, samples: 0, cool_until: 0, off_until: 0 };
}
