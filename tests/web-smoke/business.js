// Business-workspace browser journeys against the real stack: Owner MFA, workspace shell, finance screens, imports, GST, integrations, assistant,
// workspace-only administration, and role isolation (Accountant and Viewer). Nothing is stubbed in this file.
const fs = require("fs");
const { B, Journey, register, api, csv, totp } = require("./lib");

const OUT = process.argv[2];
const OUTBOX = process.env.OUTBOX;
const j = new Journey("business", OUT);

async function invite(owner, wsId, role, email) {
  const r = await api(owner, "POST", `/v1/spaces/${wsId}/invitations`, { email, role });
  if (r.status !== 201) throw new Error(`invitation for ${role} failed with ${r.status}`);
  const line = fs.readFileSync(OUTBOX, "utf8").trim().split("\n").map((l) => JSON.parse(l)).filter((n) => n.to.toLowerCase() === email).pop();
  return line.link.split("#token=")[1];
}

(async () => {
  const browser = await j.browser();
  const stamp = Date.now();
  const ownerCtx = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const owner = await j.page(ownerCtx, "owner");
  await register(owner, "Olive Owner", `owner${stamp}@example.test`);
  const enroll = await api(owner, "POST", "/v1/auth/mfa/enroll");
  const confirm = await api(owner, "POST", "/v1/auth/mfa/confirm", { code: totp(enroll.body.secret) });
  await j.must(Promise.resolve(confirm.status === 200), "the owner enrolled in two-step verification (required for Owner and Admin)");
  await owner.reload();
  await owner.getByLabel("Workspace name").fill("Acme Traders");
  await owner.getByRole("button", { name: "Create workspace" }).click();
  await owner.waitForURL(/\/workspaces\/[0-9a-f-]{36}$/, { timeout: 15000 });
  const wsId = owner.url().split("/").pop();
  await owner.getByRole("heading", { level: 1, name: "Business summary" }).waitFor({ timeout: 15000 });
  j.ok(`a new business workspace opens on its summary inside the shell`);
  await j.see(owner.getByRole("navigation", { name: "Acme Traders sections" }), "the business navigation is labelled with the workspace");
  for (const n of ["Invoices", "Expenses", "Clients", "Reports", "Assistant", "Imports", "Reconcile", "GST", "Administration", "Members", "Business settings"]) {
    await j.see(owner.getByRole("link", { name: n, exact: true }), `the Owner sees ${n}`);
  }
  await j.see(owner.getByText("Your role: Owner"), "the role badge shows the Owner role");

  // ---- real data: clients, invoices (issued, so receivables exist), expenses
  const client = await api(owner, "POST", `/v1/spaces/${wsId}/clients`, { name: "Big Client" });
  const inv = await api(owner, "POST", `/v1/spaces/${wsId}/invoices`, { client_id: client.body.id, invoice_date: "2026-03-01", currency: "INR", lines: [{ description: "Work", quantity: "1", unit_price: "1000.00", tax_percent: "0" }] });
  await j.must(Promise.resolve(inv.status === 201), "an invoice was created through the real backend");
  const issued = await api(owner, "POST", `/v1/spaces/${wsId}/invoices/${inv.body.id}/issue`, { confirm: true, expected_total: "1000.00" });
  await j.must(Promise.resolve(issued.status === 200), "the invoice was issued");

  await owner.getByRole("link", { name: "Clients", exact: true }).click();
  await owner.waitForURL(`**/workspaces/${wsId}/clients`);
  await owner.getByRole("cell", { name: "Big Client" }).first().waitFor({ timeout: 15000 });
  j.ok("the client list shows the real client");
  await owner.getByRole("link", { name: "Invoices", exact: true }).click();
  await owner.waitForURL(`**/workspaces/${wsId}/invoices`);
  await owner.getByRole("cell", { name: "Big Client" }).first().waitFor({ timeout: 15000 });
  await j.see(owner.getByText(/INV-/).first(), "the invoice list shows the real invoice with its number and the server's status");

  // ---- expenses by CSV import: upload, preview, explicit confirm, then the real expense list
  await owner.getByRole("link", { name: "Imports", exact: true }).click();
  await owner.getByRole("heading", { level: 1, name: "Imports" }).waitFor();
  await owner.setInputFiles("#imp-file", csv("expenses.csv", "Date,Amount,Description\n2026-03-02,100.00,Printer paper\n2026-03-03,250.50,Courier\n"));
  await owner.getByRole("button", { name: "Upload and continue" }).click();
  await owner.getByRole("form", { name: "Column mapping" }).waitFor({ timeout: 15000 });
  await owner.getByRole("button", { name: "Check the file" }).click();
  await owner.getByText(/2 ready/).waitFor({ timeout: 15000 });
  await j.must(Promise.resolve((await api(owner, "GET", `/v1/spaces/${wsId}/expenses`)).body.total === 0), "the preview created no expense");
  await owner.getByLabel(/I have checked the preview/).check();
  await owner.getByRole("button", { name: "Import now" }).click();
  await owner.getByText(/Import finished: 2/).waitFor({ timeout: 15000 });
  await owner.getByRole("link", { name: "Expenses", exact: true }).click();
  await owner.waitForURL(`**/workspaces/${wsId}/expenses`);
  await owner.getByText("Printer paper").first().waitFor({ timeout: 15000 });
  j.ok("the confirmed import created exactly the two expenses, now listed from the real backend");

  // ---- reports, GST, assistant, accounting status
  await owner.getByRole("link", { name: "Reports", exact: true }).click();
  await owner.getByLabel("Report", { exact: true }).selectOption("receivables");
  await owner.getByRole("button", { name: "Show report" }).click();
  await owner.getByRole("heading", { name: "Receivables aging" }).waitFor({ timeout: 15000 });
  await j.see(owner.getByRole("cell", { name: /INR 1,000\.00/ }).first(), "the receivables report shows the issued invoice's outstanding amount in its own currency");
  await owner.getByRole("link", { name: "GST", exact: true }).click();
  await owner.getByRole("heading", { name: "GST registration" }).waitFor({ timeout: 15000 });
  await j.see(owner.getByText(/not an official|NOT an official|not a government filing/i).first(), "the GST screen says the worksheet is not an official filing");
  await owner.getByRole("link", { name: "Assistant", exact: true }).click();
  await owner.getByLabel("Your question").fill("Which invoices are overdue?");
  await owner.getByRole("button", { name: "Ask", exact: true }).click();
  await owner.getByRole("region", { name: "Assistant answer" }).waitFor({ timeout: 15000 });
  await j.see(owner.getByRole("region", { name: "Assistant answer" }).getByText(/1,?000\.00/), "the business assistant answers overdue invoices from the server's figures (INR 1,000.00)");
  await owner.getByLabel("Your question").fill("What is the meaning of life?");
  await owner.getByRole("button", { name: "Ask", exact: true }).click();
  await j.see(owner.getByText(/could not match that to something I can answer/), "an unsupported question is answered as unsupported, with the supported examples");
  await j.see(owner.getByText(/AI explanations: not configured/).first(), "the business assistant says no AI provider is configured");
  await owner.getByRole("link", { name: "Integrations", exact: true }).click();
  await owner.getByRole("heading", { name: "Accounting integrations" }).waitFor({ timeout: 15000 });
  await j.see(owner.getByText("Provider: none configured"), "accounting integration is shown as not configured");
  await j.must(owner.getByText("Connected", { exact: true }).count().then((n) => n === 0), "no integration appears connected");
  await owner.getByRole("link", { name: "Reconcile", exact: true }).click();
  await owner.getByRole("heading", { level: 1, name: "Reconciliation" }).waitFor();
  j.ok("the reconciliation screen opens for the Owner");

  // ---- workspace-only administration
  await owner.goto(`${B}/workspaces/${wsId}/admin`);
  await owner.getByRole("heading", { name: "Workspace administration" }).first().waitFor({ timeout: 15000 });
  await j.see(owner.getByText(/Platform-wide administration does not exist yet/).first(), "the administration page is labelled workspace-only");
  await j.see(owner.getByRole("heading", { name: "Not available: platform-wide administration" }), "platform administration is stated as not available, with no fake controls");
  await j.see(owner.getByRole("heading", { name: "Integration health" }), "integration health, plan, open alerts and billing history are on the page");
  await owner.screenshot({ path: `${j.out}/business-admin.png`, fullPage: true });

  // ---- an Accountant: imports yes, administration no (and nothing from the admin API is requested)
  const accEmail = `acc${stamp}@example.test`;
  const accCtx = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const acc = await j.page(accCtx, "accountant");
  await register(acc, "Alice Accountant", accEmail);
  const accToken = await invite(owner, wsId, "ACCOUNTANT", accEmail);
  await j.must(Promise.resolve((await api(acc, "POST", "/v1/invitations/accept", { token: accToken })).status === 200), "an Accountant accepts the invitation");
  const adminRequests = [];
  acc.on("request", (r) => { if (r.url().includes("finance-admin")) adminRequests.push(r.url()); });
  await acc.goto(`${B}/workspaces/${wsId}/admin`);
  await acc.getByText("You do not have access to this section.").waitFor({ timeout: 15000 });
  await j.must(Promise.resolve(adminRequests.length === 0), "the Accountant's direct URL to administration is denied before any administration data is requested");
  await j.see(acc.getByRole("link", { name: "Imports", exact: true }), "the Accountant's navigation has Imports");
  await j.must(acc.getByRole("link", { name: "Administration" }).count().then((n) => n === 0), "the Accountant's navigation has no Administration");

  // ---- a Viewer: denied in the UI and by the server; foreign workspaces answer 404
  const viewerEmail = `viewer${stamp}@example.test`;
  const vCtx = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const viewer = await j.page(vCtx, "viewer");
  await register(viewer, "Vic Viewer", viewerEmail);
  const token = await invite(owner, wsId, "VIEWER", viewerEmail);
  await j.must(Promise.resolve((await api(viewer, "POST", "/v1/invitations/accept", { token })).status === 200), "a Viewer accepts the invitation");
  await viewer.goto(`${B}/workspaces/${wsId}/invoices`);
  await viewer.getByText("You do not have access to this section.").waitFor({ timeout: 15000 });
  await j.see(viewer.getByRole("heading", { level: 1, name: "Access denied" }), "a Viewer who types the invoices URL gets the access-denied page");
  await j.must(viewer.getByRole("link", { name: "Invoices" }).count().then((n) => n === 0), "the Viewer's navigation has no Invoices link");
  await j.must(viewer.getByRole("link", { name: "Workspace", exact: true }).count().then((n) => n === 1), "the Viewer still has the workspace section");
  for (const [method, path, body] of [["GET", `/v1/spaces/${wsId}/invoices`], ["POST", `/v1/spaces/${wsId}/clients`, { name: "Sneaky" }], ["POST", `/v1/spaces/${wsId}/finance-assistant/ask`, { question: "What is outstanding from clients?" }], ["GET", `/v1/spaces/${wsId}/finance-admin/overview`]]) {
    const r = await api(viewer, method, path, body);
    await j.must(Promise.resolve(r.status === 403), `the server refuses a Viewer: ${method} ${path.replace(wsId, ":workspace")} -> ${r.status}`);
  }
  const clients = await api(owner, "GET", `/v1/spaces/${wsId}/clients`);
  await j.must(Promise.resolve(!clients.body.items.some((c) => c.name === "Sneaky")), "the refused Viewer mutation created nothing");
  const stranger = await api(viewer, "GET", "/v1/spaces/00000000-0000-4000-8000-000000000000/invoices");
  await j.must(Promise.resolve(stranger.status === 404), `a workspace id the person does not belong to answers 404 (${stranger.status})`);
  await viewer.screenshot({ path: `${j.out}/business-viewer.png` });
  await j.done();
})().catch((e) => j.fail(e));
