// test की मदद: sql.js पर नकली D1, अपनी RSA key से बने Firebase जैसे token, नकली fetch.
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { b64urlEncode } from "../src/util.js";

const require = createRequire(import.meta.url);
const initSqlJs = require("sql.js");
const SQL = await initSqlJs();
export const SCHEMA = readFileSync(new URL("../schema.sql", import.meta.url), "utf8");

export class FakeD1 {
  constructor() {
    this.db = new SQL.Database();
    this.db.exec(SCHEMA);
    this.failWith = null; // जैसे "D1_ERROR: Exceeded maximum daily rows limit"
    this.queries = 0;
  }
  prepare(sql) {
    const self = this;
    let args = [];
    const go = (fn) => {
      self.queries++;
      if (self.failWith) throw new Error(self.failWith);
      return fn();
    };
    const rows = () => {
      const st = self.db.prepare(sql);
      st.bind(args);
      const out = [];
      while (st.step()) out.push(st.getAsObject());
      st.free();
      return out;
    };
    return {
      bind(...a) {
        args = a.map((v) => (v === undefined ? null : v));
        return this;
      },
      async first() {
        return go(() => rows()[0] ?? null);
      },
      async all() {
        return go(() => ({ results: rows() }));
      },
      async run() {
        return go(() => {
          self.db.run(sql, args);
          return { meta: { changes: self.db.getRowsModified() } };
        });
      },
    };
  }
}

export const PID = "dk-test-project";
const kp = await crypto.subtle.generateKey(
  { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
  true,
  ["sign", "verify"],
);
const other = await crypto.subtle.generateKey(
  { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
  true,
  ["sign", "verify"],
);
export const KEYS = { k1: kp.publicKey };
export const PUBLIC_JWK = await crypto.subtle.exportKey("jwk", kp.publicKey);

const j64 = (o) => b64urlEncode(new TextEncoder().encode(JSON.stringify(o)));

export async function token(claims = {}, { kid = "k1", wrongKey = false, header = {} } = {}) {
  const now = Math.floor(Date.now() / 1000);
  const body = {
    iss: "https://securetoken.google.com/" + PID,
    aud: PID,
    sub: "uid-guest",
    iat: now - 10,
    auth_time: now - 10,
    exp: now + 3600,
    firebase: { sign_in_provider: "anonymous" },
    ...claims,
  };
  const h = j64({ alg: "RS256", kid, typ: "JWT", ...header });
  const p = j64(body);
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", wrongKey ? other.privateKey : kp.privateKey, new TextEncoder().encode(h + "." + p));
  return h + "." + p + "." + b64urlEncode(new Uint8Array(sig));
}

export const OWNER = { sub: "uid-owner", email: "Owner@Example.com", email_verified: true, firebase: { sign_in_provider: "google.com" } };

export function env(extra = {}) {
  return {
    FIREBASE_PROJECT_ID: PID,
    ALLOWED_EMAILS: "owner@example.com",
    WEBHOOK_SECRET: "test-webhook-secret-123",
    GEMINI_API_KEY: "g-test",
    MISTRAL_API_KEY: "m-test",
    DB: new FakeD1(),
    // पुराने test सर्वर-key वाली मुफ़्त सीमा जाँचते हैं - वह रास्ता चालू रखो. बंद वाला (असली default) test/voice.test.js में
    FREE_SERVER_AI: "1",
    ...extra,
  };
}

export function ctx() {
  const jobs = [];
  return { jobs, waitUntil: (p) => jobs.push(p), flush: () => Promise.all(jobs) };
}

/** SSE जवाब बनाओ (नकली कंपनी के लिए) */
export function sse(chunks, { delayMs = 0, firstDelayMs = 0 } = {}) {
  const enc = new TextEncoder();
  return new ReadableStream({
    async start(c) {
      if (firstDelayMs) await new Promise((r) => setTimeout(r, firstDelayMs));
      for (const ch of chunks) {
        c.enqueue(enc.encode("data: " + (typeof ch === "string" ? ch : JSON.stringify(ch)) + "\n\n"));
        if (delayMs) await new Promise((r) => setTimeout(r, delayMs));
      }
      c.close();
    },
  });
}

export const gem = (t) => ({ candidates: [{ content: { parts: [{ text: t }] } }] });
export const oai = (t) => ({ choices: [{ delta: { content: t } }] });

/** global fetch को नियम से बदलो; हर बुलावा calls में */
export function mockFetch(route) {
  const calls = [];
  const orig = globalThis.fetch;
  globalThis.fetch = async (url, init = {}) => {
    calls.push({ url: String(url), init });
    return route(String(url), init, calls);
  };
  return { calls, restore: () => (globalThis.fetch = orig) };
}

export function req(path, { method = "GET", tok, body, headers = {} } = {}) {
  const h = { ...headers };
  if (tok) h.authorization = "Bearer " + tok;
  let b = body;
  if (body && typeof body === "object" && !(body instanceof Uint8Array)) {
    h["content-type"] = "application/json";
    b = JSON.stringify(body);
  }
  return new Request("https://dk.test" + path, { method, headers: h, body: b });
}

export async function readSse(res) {
  const txt = await res.text();
  const evs = txt.split("\n\n").filter(Boolean).map((l) => JSON.parse(l.replace(/^data: /, "")));
  return { text: evs.filter((e) => e.t).map((e) => e.t).join(""), done: evs.find((e) => e.done), evs };
}
