// Shared helpers for the browser smoke journeys. Everything here talks to a LOCAL stack started by run.sh; the base URL is refused unless it is localhost.
const { chromium } = require("playwright");
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const B = process.env.WEB_BASE_URL || "http://localhost:13000";
if (!/^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(B)) {
  console.error(`Refusing to run against ${B}: the smoke test creates accounts and data and only runs against a local stack.`);
  process.exit(2);
}

/** Runs a journey: numbered steps, a screenshot and diagnostics on failure, and never prints cookies, CSRF tokens or passwords. */
class Journey {
  constructor(name, outDir) {
    this.name = name;
    this.out = path.join(outDir, "shots");
    fs.mkdirSync(this.out, { recursive: true });
    this.pages = [];
    this.n = 0;
    this.problems = new Map();
  }
  async browser() {
    this.b = await chromium.launch();
    return this.b;
  }
  async page(ctx, label) {
    const page = await ctx.newPage();
    const issues = [];
    page.on("console", (m) => { if (m.type() === "error") issues.push(`console: ${m.text().slice(0, 200)}`); });
    page.on("pageerror", (e) => issues.push(`pageerror: ${String(e.message).slice(0, 200)}`));
    page.__stubbed = new Set();
    page.on("response", (r) => { if (r.status() >= 500 && !page.__stubbed.has(r.url())) issues.push(`HTTP ${r.status()} ${new URL(r.url()).pathname.replace(/[0-9a-f-]{36}/g, ":id")}`); });
    this.pages.push({ page, label, issues });
    return page;
  }
  ok(message) {
    this.n += 1;
    console.log(`OK  ${this.name} ${String(this.n).padStart(2, "0")} ${message}`);
  }
  async must(cond, message) {
    if (!(await cond)) throw new Error(`FAILED: ${message}`);
    this.ok(message);
  }
  /** Waits (up to 15 s) for something to be visible, then records the check. Use this instead of an immediate visibility test, which races page loading. */
  async see(locator, message) {
    await locator.first().waitFor({ state: "visible", timeout: 15000 }).catch(() => { throw new Error(`FAILED: ${message}`); });
    this.ok(message);
  }
  async fail(error) {
    console.error(`\nFAIL ${this.name} after ${this.n} checks: ${error.message.split("\n")[0]}`);
    for (const { page, label, issues } of this.pages) {
      try {
        const file = path.join(this.out, `failure-${this.name}-${label}.png`);
        await page.screenshot({ path: file, fullPage: true });
        const text = (await page.locator("main").first().innerText({ timeout: 2000 })).replace(/\s+/g, " ").slice(0, 400);
        console.error(`  [${label}] url=${page.url().replace(/#.*/, "")}\n  [${label}] main text: ${text}\n  [${label}] screenshot: ${file}`);
        for (const i of issues.slice(-5)) console.error(`  [${label}] ${i}`);
      } catch {
        console.error(`  [${label}] page not available for diagnostics`);
      }
    }
    if (this.b) await this.b.close().catch(() => undefined);
    process.exit(1);
  }
  async done() {
    const bad = this.pages.flatMap((p) => p.issues.filter((i) => i.startsWith("pageerror") || i.startsWith("HTTP 5")));
    if (bad.length) {
      console.error(`FAIL ${this.name}: unexpected errors in the browser:\n  ${bad.slice(0, 8).join("\n  ")}`);
      await this.b.close();
      process.exit(1);
    }
    await this.b.close();
    console.log(`PASSED ${this.name} (${this.n} checks)`);
  }
}

function totp(b32) {
  const A = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const c of b32.replace(/=+$/, "")) bits += A.indexOf(c).toString(2).padStart(5, "0");
  const key = Buffer.from(bits.match(/.{8}/g).map((b) => parseInt(b, 2)));
  const ctr = Buffer.alloc(8);
  ctr.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000)));
  const h = crypto.createHmac("sha1", key).update(ctr).digest();
  const o = h[19] & 15;
  return String((h.readUInt32BE(o) & 0x7fffffff) % 1000000).padStart(6, "0");
}

const PASSWORD = "a-long-enough-password-1";

/** Registers through the UI and signs in (registration may sign in already). Test accounts only: example.test addresses. */
async function register(page, name, email) {
  await page.goto(`${B}/register`);
  await page.getByLabel("Your name").fill(name);
  await page.getByLabel("Email").fill(email);
  await page.getByLabel(/^Password/).fill(PASSWORD);
  await page.getByRole("button", { name: /create|register/i }).click();
  await page.waitForTimeout(1500);
  await page.goto(`${B}/login`);
  await page.waitForTimeout(1500);
  if (await page.getByLabel("Email").count()) {
    await page.getByLabel("Email").fill(email);
    await page.getByLabel("Password").fill(PASSWORD);
    await page.getByRole("button", { name: "Sign in" }).click();
  }
  await page.waitForURL("**/workspaces", { timeout: 15000 });
}

/** A same-origin API call made from the page (cookie + CSRF from /v1/me), exactly as the app itself does it. */
const api = (page, method, p, body) => page.evaluate(async ({ method, p, body }) => {
  const me = await (await fetch("/api/gw/v1/me")).json();
  const r = await fetch("/api/gw" + p, { method, headers: { "Content-Type": "application/json", "X-CSRF-Token": me.csrf_token, "Idempotency-Key": crypto.randomUUID() }, body: body ? JSON.stringify(body) : undefined });
  const t = await r.text();
  return { status: r.status, body: t ? JSON.parse(t) : null };
}, { method, p, body });

/** Answers matching requests with a fixed reply. A controlled test double used only to reach states the real stack does not produce on demand. */
async function stub(page, pattern, status, body) {
  const handler = (route) => { page.__stubbed?.add(route.request().url()); return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) }); };
  await page.route(pattern, handler);
  return () => page.unroute(pattern, handler);
}

const csv = (name, text) => ({ name, mimeType: "text/csv", buffer: Buffer.from(text) });

module.exports = { B, Journey, totp, register, api, stub, csv, PASSWORD };
