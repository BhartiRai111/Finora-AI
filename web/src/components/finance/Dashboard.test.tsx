// Component tests with a mocked fetch: they check how the dashboard presents what the server returns. They do not exercise a real backend.
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { FinanceProvider } from "@/lib/FinanceContext";
import { MonthProvider } from "@/lib/month";
import { MonthSelect } from "../shell/MonthSelect";
import { mockApi, problem, renderWithSession, USER } from "@/test-utils";
import { Dashboard } from "./Dashboard";

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));
afterEach(() => vi.unstubAllGlobals());

const wrap = (ui: React.ReactElement) => renderWithSession(<FinanceProvider space="p1"><MonthProvider initial="2026-03">{ui}</MonthProvider></FinanceProvider>);
const budget = (id: string, name: string | null, amount: string, spent: string, remaining: string, pct: string, status: string, currency = "INR") =>
  ({ id, category_id: name ? `c-${id}` : null, category_name: name, amount, currency, spent, remaining, percent_used: pct, status, version: 0 });
const tx = (id: string, amount: string, payee: string, cat: string | null, kind = "EXPENSE", date = "2026-03-10") => ({
  id, kind, status: "POSTED", account_id: "a1", to_account_id: null, amount, currency: "INR", to_amount: null, to_currency: null, direction: null, category_id: cat,
  refund_of: null, occurred_on: date, payee, note: null, tags: [], version: 0, void_reason: null,
});
const series = { report: "cash-flow", title: "t", domain: "PERSONAL", from: "2025-10-01", to: "2026-03-15", granularity: "MONTH", definitions: [], notes: [], comparison: null,
  currencies: [{ currency: "INR", columns: ["income", "net_expense", "net"], totals: {}, rows: [
    { period: "2026-01", start: null, end: null, activity: true, values: { income: "5000.00", net_expense: "1000.00", net: "4000.00" } },
    { period: "2026-02", start: null, end: null, activity: true, values: { income: "6000.00", net_expense: "1500.00", net: "4500.00" } },
  ] }] };
const compare = { ...series, from: "2026-03-01", to: "2026-03-15", comparison: { from: "2026-02-14", to: "2026-02-28", currencies: [{ currency: "INR", current: {}, previous: {},
  change: { income: { amount: "500.00", percent: "10.00" }, net_expense: { amount: "300.00", percent: "25.00" }, net: { amount: "200.00", percent: null } } }] } };

const ACCOUNT = { id: "a1", name: "Salary account", type: "BANK", currency: "INR", opening_balance: "0.00", opening_date: "2026-01-01", balance: "100.00", liability: false, archived: false, version: 0 };
const MARCH = "from=2026-03-01&to=2026-03-15";
const routes = (over: Record<string, () => { status: number; body?: unknown }> = {}) => ({
  "GET /v1/me": () => ({ status: 200, body: USER }),
  "GET /v1/spaces/p1/finance-settings": () => ({ status: 200, body: { timezone: "Asia/Kolkata", today: "2026-03-15" } }),
  "GET /v1/spaces/p1/net-worth": () => ({ status: 200, body: { currencies: [{ currency: "INR", assets: "1234567.50", liabilities: "500.00", net_worth: "1234067.50", components: [] }] } }),
  [`GET /v1/spaces/p1/summaries/cashflow?${MARCH}`]: () => ({ status: 200, body: { currencies: [{ currency: "INR", income: "6000.10", gross_expense: "1600.30", refunds: "0.00", net_expense: "1600.30", net: "4399.80" }] } }),
  "GET /v1/spaces/p1/budgets?month=2026-03": () => ({ status: 200, body: { items: [budget("1", "Food", "1000.10", "800.05", "200.05", "80.00", "WARNING"), budget("2", "Fun", "100.00", "100.01", "-0.01", "100.01", "OVER")] } }),
  "GET /v1/spaces/p1/bills?due_within_days=14": () => ({ status: 200, body: { items: [{ id: "bl1", kind: "BILL", name: "Rent", amount: "900.00", currency: "INR", frequency: "MONTHLY", next_due_date: "2026-03-20", overdue: false, status: "ACTIVE", account_id: null, annualized_cost: null, monthly_equivalent: null, version: 0 }] } }),
  "GET /v1/spaces/p1/goals": () => ({ status: 200, body: { items: [{ id: "g1", name: "Trip", target_amount: "1000.00", currency: "INR", target_date: null, saved: "250.00", remaining: "750.00", percent: "25.00", status: "IN_PROGRESS", months_left: null, required_monthly: null, version: 0 }] } }),
  [`GET /v1/spaces/p1/transactions?${MARCH}&limit=8`]: () => ({ status: 200, body: { items: [tx("t1", "450.00", "Chai Point", "c-food")], next_cursor: null } }),
  [`GET /v1/spaces/p1/transactions?${MARCH}&limit=8&kind=INCOME`]: () => ({ status: 200, body: { items: [tx("t5", "6000.00", "Pay day", null, "INCOME")], next_cursor: null } }),
  [`GET /v1/spaces/p1/transactions?${MARCH}&limit=8&q=chai`]: () => ({ status: 200, body: { items: [tx("t1", "450.00", "Chai Point", "c-food")], next_cursor: null } }),
  "GET /v1/spaces/p1/categories?include_archived=true": () => ({ status: 200, body: { items: [{ id: "c-food", parent_id: null, name: "Food", kind: "EXPENSE", archived: false, version: 0 }] } }),
  "GET /v1/spaces/p1/accounts": () => ({ status: 200, body: { items: [ACCOUNT] } }),
  "GET /v1/spaces/p1/accounts?include_archived=true": () => ({ status: 200, body: { items: [ACCOUNT] } }),
  [`GET /v1/spaces/p1/finance-reports/cash-flow?${MARCH}&granularity=MONTH&compare=previous`]: () => ({ status: 200, body: compare }),
  "GET /v1/spaces/p1/finance-reports/cash-flow?from=2025-10-01&to=2026-03-15&granularity=MONTH": () => ({ status: 200, body: series }),
  "GET /v1/spaces/p1/finance-reports/cash-flow?from=2026-01-01&to=2026-03-15&granularity=MONTH": () => ({ status: 200, body: series }),
  "GET /v1/spaces/p1/finance-reports/cash-flow?from=2025-04-01&to=2026-03-15&granularity=MONTH": () => ({ status: 200, body: series }),
  "GET /v1/spaces/p1/finance-assistant/expense-change": () => ({ status: 200, body: { status: "ANSWERED", intent: "expense_change", domain: "PERSONAL", period: { from: "2026-02-01", to: "2026-02-28", label: "February 2026", comparison_from: null, comparison_to: null, comparison_label: "January 2026" }, summary: "In INR, net spending rose by 500.00.", clarification: null, sections: [], assumptions: [], limitations: [], sources: [], notices: [], ai: { status: "NOT_CONFIGURED", reason: null, provider: "none", message: "none", explanation: null, label: null } } }),
  ...over,
});

describe("dashboard (mocked fetch)", () => {
  it("shows the four summary cards from the server, with the server's comparison", async () => {
    mockApi(routes());
    wrap(<Dashboard onChanged={() => undefined} />);
    const summary = await screen.findByRole("region", { name: "Financial summary" });
    const balance = await screen.findByRole("region", { name: "Total balance" });
    expect(await within(balance).findByText("INR 12,34,067.50")).toBeInTheDocument(); // balance (net worth) in lakh grouping
    expect(await within(balance).findByText("Salary account · bank")).toBeInTheDocument(); // the person's own account, with its balance
    expect(within(balance).getByText("Added by you; not linked to a bank.")).toBeInTheDocument();
    expect(within(balance).getByRole("link", { name: "Transfer" })).toHaveAttribute("href", "/finance/transactions");
    expect(within(summary).getByText("INR 200.04")).toBeInTheDocument(); // budget remaining: 200.05 + (-0.01), added exactly
    expect(within(summary).getByText("INR 6,000.10")).toBeInTheDocument();
    expect(within(summary).getByText("INR 1,600.30")).toBeInTheDocument();
    expect(within(summary).getByText("INR 4,399.80")).toBeInTheDocument();
    expect(await within(summary).findByText("+10.00%")).toBeInTheDocument();
    expect(within(summary).getByText("+25.00%")).toBeInTheDocument();
    expect(within(summary).getAllByText("vs 2026-02-14 to 2026-02-28").length).toBe(3);
    expect(within(summary).getByText("+INR 200.00")).toBeInTheDocument(); // percent is null: the amount change is shown instead
  });

  it("draws the cash flow with exact averages and a savings rate, and the range buttons ask the server for that range", async () => {
    const { calls } = mockApi(routes());
    wrap(<Dashboard onChanged={() => undefined} />);
    expect(await screen.findByRole("img", { name: /Chart in INR: income, spending and net savings for 2 months/ })).toBeInTheDocument();
    const panel = screen.getByRole("heading", { name: "Cash flow" }).closest("section")!;
    expect(within(panel).getByText("INR 5,500.00")).toBeInTheDocument(); // (5000.00 + 6000.00) / 2
    expect(within(panel).getByText("INR 1,250.00")).toBeInTheDocument(); // (1000.00 + 1500.00) / 2
    expect(within(panel).getByText("77%")).toBeInTheDocument(); // 8500 / 11000
    expect(within(panel).getByRole("button", { name: "6M" })).toHaveAttribute("aria-pressed", "true");
    await userEvent.click(within(panel).getByRole("button", { name: "1Y" }));
    await waitFor(() => expect(calls.some((c) => c.key.includes("from=2025-04-01&to=2026-03-15"))).toBe(true));
    expect(within(panel).getByRole("button", { name: "1Y" })).toHaveAttribute("aria-pressed", "true");
  });

  it("shows the spending plan from the budgets with totals added exactly and states in words", async () => {
    mockApi(routes());
    wrap(<Dashboard onChanged={() => undefined} />);
    const plan = (await screen.findByRole("heading", { name: "Spending limit" })).closest("section")!;
    expect((await within(plan).findAllByText("INR 900.06")).length).toBeGreaterThan(0); // 800.05 + 100.01 (summary and gauge)
    expect(within(plan).getByText("INR 1,100.10")).toBeInTheDocument();
    expect(within(plan).getByText("INR 200.04")).toBeInTheDocument(); // 200.05 + (-0.01)
    expect(within(plan).getByText(/Approaching the limit · 80\.00% used/)).toBeInTheDocument();
    expect(within(plan).getByText(/Over budget · 100\.01% used/)).toBeInTheDocument();
    expect(within(plan).getAllByRole("progressbar")).toHaveLength(3);
    expect(within(plan).getByRole("img", { name: /INR 900\.06 spent of a INR 1,100\.10 limit, 82 percent/ })).toBeInTheDocument();
  });

  it("shows goals, upcoming payments and the insight that Finora calculated", async () => {
    mockApi(routes());
    wrap(<Dashboard onChanged={() => undefined} />);
    const goals = (await screen.findByRole("heading", { name: "Savings goals" })).closest("section")!;
    expect(within(goals).getByText("Trip")).toBeInTheDocument();
    expect(within(goals).getByText("25.00% complete")).toBeInTheDocument();
    expect(within(goals).getByText(/INR 250\.00 of INR 1,000\.00 · INR 750\.00 to go/)).toBeInTheDocument();
    expect(within(goals).getByRole("link", { name: /Add goal/ })).toHaveAttribute("href", "/finance/goals");
    const bills = screen.getByRole("heading", { name: "Upcoming payments" }).closest("section")!;
    expect(within(bills).getByText("Rent")).toBeInTheDocument();
    expect(within(bills).getByText(/Due 2026-03-20 · Monthly/)).toBeInTheDocument();
    expect(within(bills).getByRole("link", { name: "Manage bills" })).toHaveAttribute("href", "/finance/bills");
    expect(await screen.findByText(/Calculated by Finora from your recorded data. No AI model wrote this./)).toBeInTheDocument();
    expect(screen.getByText("In INR, net spending rose by 500.00.")).toBeInTheDocument();
  });

  it("lists recent transactions as a table and filters by type and search words through the server", async () => {
    const { calls } = mockApi(routes());
    wrap(<Dashboard onChanged={() => undefined} />);
    const table = await screen.findByRole("table", { name: "Recent transactions" });
    expect(within(table).getByText("Chai Point")).toBeInTheDocument();
    expect(within(table).getByText("Salary account")).toBeInTheDocument();
    expect(within(table).getByText("Posted")).toBeInTheDocument();
    expect(within(table).getByText("−INR 450.00")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Income" }));
    expect(await screen.findByText("Pay day")).toBeInTheDocument();
    expect(calls.some((c) => c.key.endsWith("limit=8&kind=INCOME"))).toBe(true);
    await userEvent.click(screen.getByRole("button", { name: "All" }));
    await userEvent.type(screen.getByRole("searchbox", { name: "Search recent transactions" }), "chai{Enter}");
    await waitFor(() => expect(calls.some((c) => c.key.endsWith("limit=8&q=chai"))).toBe(true));
    expect(await screen.findByText("Chai Point")).toBeInTheDocument();
  });

  it("is honest for a new workspace: an empty state and no invented figures", async () => {
    const empty = { status: 200, body: { items: [], next_cursor: null } };
    mockApi(routes({
      "GET /v1/spaces/p1/net-worth": () => ({ status: 200, body: { currencies: [] } }),
      [`GET /v1/spaces/p1/summaries/cashflow?${MARCH}`]: () => ({ status: 200, body: { currencies: [] } }),
      "GET /v1/spaces/p1/budgets?month=2026-03": () => ({ status: 200, body: { items: [] } }),
      "GET /v1/spaces/p1/bills?due_within_days=14": () => ({ status: 200, body: { items: [] } }),
      "GET /v1/spaces/p1/goals": () => ({ status: 200, body: { items: [] } }),
      [`GET /v1/spaces/p1/transactions?${MARCH}&limit=8`]: () => empty,
      "GET /v1/spaces/p1/finance-reports/cash-flow?from=2025-10-01&to=2026-03-15&granularity=MONTH": () => ({ status: 200, body: { ...series, currencies: [] } }),
      [`GET /v1/spaces/p1/finance-reports/cash-flow?${MARCH}&granularity=MONTH&compare=previous`]: () => ({ status: 200, body: { ...compare, comparison: null, currencies: [] } }),
    }));
    wrap(<Dashboard onChanged={() => undefined} />);
    expect(await screen.findByText("Welcome to Finora")).toBeInTheDocument();
    const summary = screen.getByRole("region", { name: "Financial summary" });
    expect(within(summary).getAllByText("—").length).toBe(4);
    expect(await screen.findByText(/No income or spending recorded in INR for this period yet/)).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: "Set a monthly budget" })[0]).toHaveAttribute("href", "/finance/budgets");
    expect(screen.getByText(/No goals yet/)).toBeInTheDocument();
    expect(await screen.findByText(/No transactions in this month yet/)).toBeInTheDocument();
  });

  it("keeps the balances when a secondary block fails, and lets that block retry", async () => {
    let fail = true;
    mockApi(routes({ [`GET /v1/spaces/p1/transactions?${MARCH}&limit=8`]: () => (fail ? problem(503, "DOWNSTREAM_UNAVAILABLE") : { status: 200, body: { items: [tx("t1", "450.00", "Chai Point", "c-food")], next_cursor: null } }) }));
    wrap(<Dashboard onChanged={() => undefined} />);
    expect(await screen.findByText("INR 12,34,067.50")).toBeInTheDocument();
    const recent = screen.getByRole("heading", { name: "Recent activities" }).closest("section")!;
    expect(await within(recent).findByRole("alert")).toHaveTextContent("temporarily unavailable");
    fail = false;
    await userEvent.click(within(recent).getByRole("button", { name: "Try again" }));
    expect((await within(recent).findAllByText("Chai Point")).length).toBeGreaterThan(0);
  });

  it("offers a currency switch only when there is more than one, and never adds currencies together", async () => {
    mockApi(routes({
      "GET /v1/spaces/p1/net-worth": () => ({ status: 200, body: { currencies: [
        { currency: "INR", assets: "100.00", liabilities: "0.00", net_worth: "100.00", components: [] },
        { currency: "USD", assets: "50.00", liabilities: "0.00", net_worth: "50.00", components: [] }] } }),
    }));
    wrap(<Dashboard onChanged={() => undefined} />);
    const select = await screen.findByRole("combobox", { name: "Currency" });
    expect(select).toHaveValue("INR");
    expect(await screen.findAllByText("INR 100.00")).not.toHaveLength(0);
    await userEvent.selectOptions(select, "USD");
    await waitFor(() => expect(screen.getAllByText("USD 50.00").length).toBeGreaterThan(0));
    expect(screen.queryByText("INR 150.00")).toBeNull();
  });

  it("follows the month chosen in the header: another month asks the server for that month's figures", async () => {
    const APRIL = "from=2026-04-01&to=2026-04-30";
    const { calls } = mockApi(routes({
      [`GET /v1/spaces/p1/summaries/cashflow?${APRIL}`]: () => ({ status: 200, body: { currencies: [{ currency: "INR", income: "10.00", gross_expense: "5.00", refunds: "0.00", net_expense: "5.00", net: "5.00" }] } }),
      "GET /v1/spaces/p1/budgets?month=2026-04": () => ({ status: 200, body: { items: [] } }),
      [`GET /v1/spaces/p1/finance-reports/cash-flow?${APRIL}&granularity=MONTH&compare=previous`]: () => ({ status: 200, body: { ...compare, comparison: null } }),
      "GET /v1/spaces/p1/finance-reports/cash-flow?from=2025-11-01&to=2026-04-30&granularity=MONTH": () => ({ status: 200, body: series }),
      [`GET /v1/spaces/p1/transactions?${APRIL}&limit=8`]: () => ({ status: 200, body: { items: [], next_cursor: null } }),
    }));
    wrap(<><MonthSelect /><Dashboard onChanged={() => undefined} /></>);
    expect(await screen.findByText("INR 6,000.10")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Next month" }));
    expect(await screen.findByText("INR 10.00")).toBeInTheDocument();
    expect(screen.queryByText("INR 6,000.10")).toBeNull();
    expect(calls.some((c) => c.key.includes("budgets?month=2026-04"))).toBe(true);
    expect(screen.getByText(/No budgets are set for April 2026/)).toBeInTheDocument();
  });
});
