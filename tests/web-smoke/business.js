const { chromium } = require("playwright");
const crypto = require("crypto");
const fs = require("fs");
const S = process.argv[2];
const OUTBOX = process.env.OUTBOX;
const B = "http://localhost:13000";
const log = (m) => console.log("OK  " + m);
const must = async (cond, msg) => { if (!(await cond)) throw new Error("FAIL " + msg); log(msg); };
function totp(b32) {
  const A = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"; let bits = ""; for (const c of b32.replace(/=+$/, "")) bits += A.indexOf(c).toString(2).padStart(5, "0");
  const key = Buffer.from(bits.match(/.{8}/g).map((b) => parseInt(b, 2)));
  const ctr = Buffer.alloc(8); ctr.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000)));
  const h = crypto.createHmac("sha1", key).update(ctr).digest(); const o = h[19] & 15;
  return String(((h.readUInt32BE(o) & 0x7fffffff) % 1000000)).padStart(6, "0");
}
async function register(page, name, email, password) {
  await page.goto(`${B}/register`);
  await page.getByLabel("Your name").fill(name); await page.getByLabel("Email").fill(email); await page.getByLabel(/^Password/).fill(password);
  await page.getByRole("button", { name: /create|register/i }).click(); await page.waitForTimeout(1500);
  await page.goto(`${B}/login`);
  await page.waitForTimeout(1500);
  if (await page.getByLabel("Email").count()) {
    await page.getByLabel("Email").fill(email); await page.getByLabel("Password").fill(password);
    await page.getByRole("button", { name: "Sign in" }).click();
  }
  await page.waitForURL("**/workspaces", { timeout: 15000 });
}
const api = (page, method, path, body) => page.evaluate(async ({ method, path, body }) => {
  const me = await (await fetch("/api/gw/v1/me")).json();
  const r = await fetch("/api/gw" + path, { method, headers: { "Content-Type": "application/json", "X-CSRF-Token": me.csrf_token, "Idempotency-Key": crypto.randomUUID() }, body: body ? JSON.stringify(body) : undefined });
  const t = await r.text(); return { status: r.status, body: t ? JSON.parse(t) : null };
}, { method, path, body });
(async () => {
  const browser = await chromium.launch();
  const stamp = Date.now(); const pw = "a-long-enough-password-1";
  const ownerCtx = await browser.newContext({ viewport: { width: 1280, height: 800 } }); const owner = await ownerCtx.newPage();
  await register(owner, "Olive Owner", `owner${stamp}@example.test`, pw);
  const enroll = await api(owner, "POST", "/v1/auth/mfa/enroll");
  const confirm = await api(owner, "POST", "/v1/auth/mfa/confirm", { code: totp(enroll.body.secret) });
  await must(confirm.status === 200, "owner enrolled in MFA (required for Owner/Admin)");
  await owner.reload();
  await owner.getByLabel("Workspace name").fill("Acme Traders");
  await owner.getByRole("button", { name: "Create workspace" }).click();
  await owner.waitForURL(/\/workspaces\/[0-9a-f-]{36}$/, { timeout: 15000 });
  const wsId = owner.url().split("/").pop();
  await owner.getByRole("heading", { level: 1, name: "Business summary" }).waitFor({ timeout: 15000 });
  log("new business workspace opens on its summary inside the shell: " + owner.url());
  await must(await owner.getByRole("navigation", { name: "Acme Traders sections" }).isVisible(), "business navigation labelled with the workspace");
  for (const n of ["Invoices", "Expenses", "Clients", "GST", "Administration", "Members", "Business settings"]) await must(await owner.getByRole("link", { name: n }).isVisible(), `Owner sees ${n}`);
  await must(await owner.getByText("Your role: Owner").isVisible(), "role badge shows the Owner role");

  const client = await api(owner, "POST", `/v1/spaces/${wsId}/clients`, { name: "Big Client" });
  const inv = await api(owner, "POST", `/v1/spaces/${wsId}/invoices`, { client_id: client.body.id, invoice_date: "2026-03-01", currency: "INR", lines: [{ description: "Work", quantity: "1", unit_price: "1000.00", tax_percent: "0" }] });
  await must(inv.status === 201, "invoice created through the real backend");
  await owner.getByRole("link", { name: "Invoices" }).click();
  await owner.waitForURL(`**/workspaces/${wsId}/invoices`);
  await owner.getByRole("heading", { level: 1, name: "Invoices" }).waitFor();
  await owner.getByRole("cell", { name: "Big Client" }).first().waitFor({ timeout: 15000 }); log("the invoice list shows the real invoice");
  await owner.goto(`${B}/workspaces/${wsId}/admin`);
  await owner.getByRole("heading", { name: "Workspace administration" }).first().waitFor({ timeout: 15000 });
  await owner.getByText(/Platform-wide administration does not exist yet/).first().waitFor({ timeout: 15000 });
  log("workspace administration is labelled as this workspace only");
  await owner.goto(`${B}/workspaces/${wsId}/integrations`);
  await owner.getByRole("heading", { name: "Integration status" }).waitFor();
  await must((await owner.getByText("Connected", { exact: true }).count()) === 0, "no integration appears connected");
  await owner.screenshot({ path: `${S}/shots/business.png` });

  // a Viewer: separate session, invited by the owner
  const viewerEmail = `viewer${stamp}@example.test`;
  const vCtx = await browser.newContext({ viewport: { width: 1280, height: 800 } }); const viewer = await vCtx.newPage();
  await register(viewer, "Vic Viewer", viewerEmail, pw);
  const invite = await api(owner, "POST", `/v1/spaces/${wsId}/invitations`, { email: viewerEmail, role: "VIEWER" });
  await must(invite.status === 201, "owner invites a Viewer");
  const outbox = fs.readFileSync(OUTBOX, "utf8").trim().split("\n").map((l) => JSON.parse(l)).filter((n) => n.to.toLowerCase() === viewerEmail).pop();
  const token = outbox.link.split("#token=")[1];
  const accepted = await api(viewer, "POST", "/v1/invitations/accept", { token });
  await must(accepted.status === 200, "viewer accepts the invitation");
  await viewer.goto(`${B}/workspaces/${wsId}/invoices`);
  await viewer.getByText("You do not have access to this section.").waitFor({ timeout: 15000 });
  log("Viewer typing the invoices URL gets the access-denied state");
  await must((await viewer.getByRole("link", { name: "Invoices" }).count()) === 0, "Viewer's navigation has no Invoices link");
  await must((await viewer.getByRole("link", { name: "Workspace", exact: true }).count()) === 1, "Viewer still has the workspace section");
  const direct = await api(viewer, "GET", `/v1/spaces/${wsId}/invoices`);
  await must(direct.status === 403, "and the server itself refuses the Viewer on the invoices API: " + direct.status);
  // workspace separation: the viewer cannot read another workspace by id
  const stranger = await api(viewer, "GET", `/v1/spaces/00000000-0000-4000-8000-000000000000/invoices`);
  await must(stranger.status === 404, "an unknown/foreign workspace id is 404: " + stranger.status);
  await viewer.screenshot({ path: `${S}/shots/viewer.png` });
  await browser.close(); console.log("BUSINESS SMOKE PASSED");
})().catch((e) => { console.error(e.message); process.exit(1); });
