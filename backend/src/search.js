// खोज: Tavily → Exa → Linkup → Firecrawl → Google (Gemini की Google Search). नतीजे एक ही रूप में: {title, url, snippet}.

import { READ_MAX_CHARS, READ_MAX_URLS, READ_TIMEOUT_MS, RESEARCH_CHAIN, SEARCH_CHAIN, SEARCH_GOOGLE_MODEL, SEARCH_TIMEOUT_MS } from "./config.js";
import { CODES, HttpError } from "./util.js";

const clip = (s, n) => String(s ?? "").replace(/\s+/g, " ").trim().slice(0, n);

function okUrl(u) {
  return /^https?:\/\//i.test(String(u || ""));
}

function norm(list) {
  return list
    .filter((r) => okUrl(r.url))
    .map((r) => ({ title: clip(r.title || r.url, 200), url: String(r.url), snippet: clip(r.snippet, 400) }));
}

async function post(url, headers, body, signal) {
  const r = await fetch(url, { method: "POST", headers: { "content-type": "application/json", ...headers }, body: JSON.stringify(body), signal });
  if (!r.ok) {
    const e = new Error(r.status === 429 ? "limit" : `http ${r.status}`);
    e.status = r.status;
    throw e;
  }
  return r.json();
}

export const PROVIDERS = {
  tavily: {
    secret: "TAVILY_API_KEY",
    async run(key, q, n, signal) {
      const j = await post("https://api.tavily.com/search", { authorization: "Bearer " + key }, { query: q, max_results: n, include_answer: true }, signal);
      return { answer: clip(j.answer, 2000), results: norm((j.results ?? []).map((r) => ({ title: r.title, url: r.url, snippet: r.content }))) };
    },
  },
  exa: {
    secret: "EXA_API_KEY",
    async run(key, q, n, signal) {
      const j = await post("https://api.exa.ai/search", { "x-api-key": key }, { query: q, numResults: n, contents: { text: { maxCharacters: 400 } } }, signal);
      return { answer: "", results: norm((j.results ?? []).map((r) => ({ title: r.title, url: r.url, snippet: r.text || r.summary }))) };
    },
  },
  linkup: {
    secret: "LINKUP_API_KEY",
    async run(key, q, n, signal) {
      const j = await post("https://api.linkup.so/v1/search", { authorization: "Bearer " + key }, { q, depth: "standard", outputType: "searchResults", maxResults: n }, signal);
      return { answer: "", results: norm((j.results ?? []).map((r) => ({ title: r.name || r.title, url: r.url, snippet: r.content }))) };
    },
  },
  firecrawl: {
    secret: "FIRECRAWL_API_KEY",
    async run(key, q, n, signal) {
      const j = await post("https://api.firecrawl.dev/v2/search", { authorization: "Bearer " + key }, { query: q, limit: n }, signal);
      const web = Array.isArray(j.data) ? j.data : (j.data?.web ?? []);
      return { answer: "", results: norm(web.map((r) => ({ title: r.title, url: r.url, snippet: r.description || r.markdown }))) };
    },
  },
  google: {
    secret: "GEMINI_API_KEY",
    async run(key, q, n, signal) {
      const j = await post(
        `https://generativelanguage.googleapis.com/v1beta/models/${SEARCH_GOOGLE_MODEL}:generateContent`,
        { "x-goog-api-key": key },
        { contents: [{ role: "user", parts: [{ text: q }] }], tools: [{ google_search: {} }] },
        signal,
      );
      const cand = j.candidates?.[0];
      const answer = (cand?.content?.parts ?? []).map((p) => p.text ?? "").join("");
      const chunks = cand?.groundingMetadata?.groundingChunks ?? [];
      const results = norm(chunks.map((c) => ({ title: c.web?.title, url: c.web?.uri, snippet: "" }))).slice(0, n);
      return { answer: clip(answer, 2000), results };
    },
  },
};

/**
 * [deep] = डीप रिसर्च: सिर्फ़ [RESEARCH_CHAIN] (Google नहीं). [only] = सिर्फ़ यह एक कंपनी (ऐप एक साथ Tavily और Exa दोनों से माँगता है).
 */
export function chainFor(deep, only) {
  const base = deep ? RESEARCH_CHAIN : SEARCH_CHAIN;
  return only ? base.filter((id) => id === only) : base;
}

export async function search(env, query, n = 5, deep = false, only = "") {
  const q = clip(query, 500);
  if (!q) throw new HttpError(400, CODES.BAD_REQUEST, "खोजने के लिए कुछ लिखें");
  const count = Math.min(Math.max(Number(n) || 5, 1), 10);
  const tried = [];
  for (const id of chainFor(deep, only)) {
    const p = PROVIDERS[id];
    const key = env[p.secret];
    if (!key) continue;
    const ac = new AbortController();
    const t = setTimeout(() => ac.abort(), SEARCH_TIMEOUT_MS);
    try {
      const r = await p.run(key, q, count, ac.signal);
      if (!r.results.length && !r.answer) {
        tried.push({ provider: id, ok: false, error: "empty" });
        continue;
      }
      tried.push({ provider: id, ok: true });
      return { provider: id, query: q, answer: r.answer, results: r.results, tried };
    } catch (e) {
      tried.push({ provider: id, ok: false, error: e?.name === "AbortError" ? "timeout" : String(e?.message || "network").slice(0, 40) });
    } finally {
      clearTimeout(t);
    }
  }
  if (!tried.length) throw new HttpError(503, CODES.NOT_CONFIGURED, "बैकएंड में कोई खोज key नहीं डली");
  throw new HttpError(503, CODES.ALL_FAILED, "अभी खोज नहीं हो पाई। थोड़ी देर बाद कोशिश करें।", { tried });
}

/**
 * 18 नवंबर 2026: डीप रिसर्च के लिए वेबसाइटें पूरी पढ़ना (ऐप में अपनी key न हो तब). क्रम: Tavily extract → Exa contents → Firecrawl.
 * नतीजा: { pages: [{ url, text }], tried }. कोई पेज न खुले = pages खाली (ऐप फिर खोज का छोटा अंश लेता है).
 */
export async function read(env, urls) {
  const list = [...new Set((Array.isArray(urls) ? urls : []).map((u) => String(u || "").trim()).filter(okUrl))].slice(0, READ_MAX_URLS);
  if (!list.length) throw new HttpError(400, CODES.BAD_REQUEST, "पढ़ने के लिए कोई वेबसाइट नहीं");
  const got = new Map();
  const tried = [];
  const left = () => list.filter((u) => !got.has(u));
  const put = (u, t) => { const x = clip(t, READ_MAX_CHARS); if (u && x.length > 80 && list.includes(u) && !got.has(u)) got.set(u, x); };
  const steps = [
    ["tavily", "TAVILY_API_KEY", async (key, us, signal) => {
      const j = await post("https://api.tavily.com/extract", { authorization: "Bearer " + key }, { urls: us }, signal);
      for (const r of j.results ?? []) put(r.url, r.raw_content);
    }],
    ["exa", "EXA_API_KEY", async (key, us, signal) => {
      const j = await post("https://api.exa.ai/contents", { "x-api-key": key }, { urls: us, text: { maxCharacters: READ_MAX_CHARS } }, signal);
      for (const r of j.results ?? []) put(r.url || r.id, r.text);
    }],
    ["firecrawl", "FIRECRAWL_API_KEY", async (key, us, signal) => {
      for (const u of us.slice(0, 3)) {
        const j = await post("https://api.firecrawl.dev/v2/scrape", { authorization: "Bearer " + key }, { url: u, formats: ["markdown"] }, signal);
        put(u, j.data?.markdown);
      }
    }],
  ];
  for (const [id, secret, run] of steps) {
    const key = env[secret];
    if (!key || !left().length) continue;
    const ac = new AbortController();
    const t = setTimeout(() => ac.abort(), READ_TIMEOUT_MS);
    try {
      await run(key, left(), ac.signal);
      tried.push({ provider: id, ok: true });
    } catch (e) {
      tried.push({ provider: id, ok: false, error: e?.name === "AbortError" ? "timeout" : String(e?.message || "network").slice(0, 40) });
    } finally {
      clearTimeout(t);
    }
  }
  if (!tried.length) throw new HttpError(503, CODES.NOT_CONFIGURED, "बैकएंड में पढ़ने वाली कोई key नहीं डली");
  return { pages: list.filter((u) => got.has(u)).map((u) => ({ url: u, text: got.get(u) })), tried };
}
