const { chromium } = require("playwright");
const S = process.argv[2];
const B = "http://localhost:13000";
const log = (m) => console.log("OK  " + m);
const must = async (cond, msg) => { if (!(await cond)) { throw new Error("FAIL " + msg); } log(msg); };
(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await ctx.newPage();
  const email = `smoke${Date.now()}@example.test`;
  const password = "a-long-enough-password-1";

  // 1. anonymous deep link -> sign-in prompt keeps the destination
  await page.goto(`${B}/finance/budgets`);
  await page.getByRole("heading", { name: "Sign in required" }).waitFor();
  log("anonymous visitor to /finance/budgets sees the sign-in prompt");
  const href = await page.getByRole("link", { name: "sign in" }).getAttribute("href");
  await must(href.includes("next=%2Ffinance%2Fbudgets"), "sign-in link carries the destination: " + href);

  // 2. register, then sign in with next
  await page.goto(`${B}/register`);
  await page.getByLabel("Your name").fill("Smoke Tester");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel(/^Password/).fill(password);
  await page.getByRole("button", { name: /create|register/i }).click();
  await page.waitForTimeout(1500);
  await page.goto(`${B}/login?next=%2Ffinance%2Fbudgets`);
  if (await page.getByLabel("Email").count()) {
    await page.getByLabel("Email").fill(email);
    await page.getByLabel("Password").fill(password);
    await page.getByRole("button", { name: "Sign in" }).click();
  }
  await page.waitForURL("**/finance/budgets", { timeout: 15000 });
  await must(page.getByRole("heading", { level: 1, name: "Budgets" }).isVisible(), "after sign-in the person lands on /finance/budgets inside the shell");
  await must(page.getByRole("navigation", { name: "Personal finance sections" }).isVisible(), "section navigation is present");
  await must((await page.getByRole("link", { name: "Budgets" }).getAttribute("aria-current")) === "page", "current section is marked");
  await page.waitForTimeout(1500);
  await must((await page.title()) === "Budgets · Personal finance · Finora AI", "document title: " + (await page.title()));
  await page.screenshot({ path: `${S}/shots/budgets.png` });

  // 3. data setup through the real API (csrf from /v1/me), then verify through the UI
  const setup = await page.evaluate(async () => {
    const me = await (await fetch("/api/gw/v1/me")).json();
    const h = (k) => ({ "Content-Type": "application/json", "X-CSRF-Token": me.csrf_token, "Idempotency-Key": k });
    const ws = await (await fetch("/api/gw/v1/workspaces")).json();
    const p = ws.items.find((w) => w.type === "PERSONAL").id;
    const acc = await (await fetch(`/api/gw/v1/spaces/${p}/accounts`, { method: "POST", headers: h("a1" + Math.random()), body: JSON.stringify({ name: "Salary", type: "BANK", currency: "INR", opening_balance: "1000", opening_date: "2026-01-01" }) })).json();
    const out = [];
    for (const [i, [date, kind, amount]] of [["2026-01-10", "INCOME", "5000.00"], ["2026-02-10", "INCOME", "6500.50"], ["2026-01-12", "EXPENSE", "700.00"], ["2026-02-12", "EXPENSE", "900.25"]].entries()) {
      const r = await fetch(`/api/gw/v1/spaces/${p}/transactions`, { method: "POST", headers: h("t" + i + Math.random()), body: JSON.stringify({ kind, account_id: acc.id, amount, currency: "INR", occurred_on: date, payee: "Smoke" }) });
      out.push(r.status);
    }
    return { acc: acc.id, out };
  });
  await must(setup.out.every((s) => s === 201), "four transactions created through the real backend: " + setup.out.join(","));

  // 4. navigate with the shell, see real data, chart and table
  await page.getByRole("link", { name: "Reports" }).click();
  await page.waitForURL("**/finance/reports");
  await page.locator("#rep-from").fill("2026-01-01");
  await page.locator("#rep-to").fill("2026-02-28");
  await page.getByRole("button", { name: "Show report" }).click();
  await page.getByRole("img", { name: /Bar chart in INR/ }).waitFor({ timeout: 15000 });
  log("cash-flow chart rendered from backend data");
  await must(await page.getByRole("cell", { name: "INR 6,500.50" }).first().isVisible(), "table shows the exact backend figure INR 6,500.50");
  await page.screenshot({ path: `${S}/shots/reports.png`, fullPage: true });

  // 5. deep link survives a reload (session restoration)
  await page.reload();
  await page.getByRole("heading", { level: 1, name: "Reports and forecast" }).waitFor();
  log("reload on /finance/reports restores the session and the page");

  // 6. account menu, keyboard
  await page.getByRole("button", { name: "Smoke Tester" }).click();
  await must(await page.getByRole("menuitem", { name: "Sign out" }).isVisible(), "account menu opens with Sign out");
  await page.keyboard.press("Escape");
  await must((await page.getByRole("menu").count()) === 0, "Escape closes the account menu");

  // 7. notifications / alerts / plan honest states
  await page.getByRole("link", { name: "Notifications", exact: true }).click();
  await page.getByText(/Notifications appear here only/).waitFor();
  log("notification centre states that nothing is delivered outside the app (from the server)");
  await page.getByRole("link", { name: "Integrations" }).click();
  await page.getByRole("heading", { name: "Integration status" }).waitFor();
  await must((await page.getByText("Connected", { exact: true }).count()) === 0, "no integration is shown as connected");
  await page.getByRole("link", { name: "Plan & billing" }).click();
  await page.getByText(/No payment provider is configured|There are no billing records/).first().waitFor();
  log("plan & billing shows the unconfigured payment state");
  await page.screenshot({ path: `${S}/shots/plan.png` });

  // 8. unknown section
  await page.goto(`${B}/finance/nonsense`);
  await page.getByRole("heading", { name: "Page not found" }).waitFor();
  log("unknown section shows a not-found state in the shell");

  // 9. small screen
  await page.setViewportSize({ width: 390, height: 800 });
  await page.goto(`${B}/finance/overview`);
  await page.getByRole("button", { name: "Sections" }).click();
  await page.getByRole("link", { name: "Accounts" }).click();
  await page.waitForURL("**/finance/accounts");
  await page.screenshot({ path: `${S}/shots/mobile.png` });
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
  log("mobile layout: horizontal page overflow = " + overflow);

  // 10. session ends elsewhere -> app leaves the signed-in state
  await ctx.clearCookies();
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.getByRole("button", { name: /Sections/ }).count();
  await page.evaluate(() => document.querySelector('a[href="/finance/goals"]')?.click());
  await page.getByRole("heading", { name: "Your session has ended" }).waitFor({ timeout: 15000 });
  log("a request refused as unauthenticated ends the signed-in state with a clear message");
  await browser.close();
  console.log("SMOKE PASSED");
})().catch((e) => { console.error(e.message); process.exit(1); });
