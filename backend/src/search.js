// खोज: Tavily → Exa → Linkup → Firecrawl → Google (Gemini की Google Search). नतीजे एक ही रूप में: {title, url, snippet}.

import { SEARCH_CHAIN, SEARCH_GOOGLE_MODEL, SEARCH_TIMEOUT_MS } from "./config.js";
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

export async function search(env, query, n = 5) {
  const q = clip(query, 500);
  if (!q) throw new HttpError(400, CODES.BAD_REQUEST, "खोजने के लिए कुछ लिखें");
  const count = Math.min(Math.max(Number(n) || 5, 1), 10);
  const tried = [];
  for (const id of SEARCH_CHAIN) {
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
