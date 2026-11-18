// Cloudflare Worker global scope में random / timer / fetch मना है (error 10021: deploy पर ही पकड़ा गया).
// यह test ये सब import के समय फेंकने वाले बनाकर सारी src फ़ाइलें नई प्रक्रिया में load करता है.
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { readdirSync } from "node:fs";
import { describe, it } from "node:test";
import { fileURLToPath, pathToFileURL } from "node:url";

const src = fileURLToPath(new URL("../src/", import.meta.url));
const files = readdirSync(src).filter((f) => f.endsWith(".js")).map((f) => pathToFileURL(src + f).href);

describe("Cloudflare global scope", () => {
  it("import के समय कोई random/timer/fetch नहीं", () => {
    const code = `
      const no = (n) => () => { throw new Error("global scope में मना: " + n); };
      crypto.randomUUID = no("crypto.randomUUID");
      crypto.getRandomValues = no("crypto.getRandomValues");
      Math.random = no("Math.random");
      globalThis.setTimeout = no("setTimeout");
      globalThis.setInterval = no("setInterval");
      globalThis.fetch = no("fetch");
      for (const f of ${JSON.stringify(files)}) await import(f);
      console.log("ok");
    `;
    const r = spawnSync(process.execPath, ["--input-type=module", "-e", code], { encoding: "utf8" });
    assert.equal(r.status, 0, r.stderr);
    assert.match(r.stdout, /ok/);
  });
});
