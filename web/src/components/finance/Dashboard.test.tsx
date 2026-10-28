// Component tests with a mocked fetch: they check how the dashboard presents what the server returns. They do not exercise a real backend.
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { FinanceProvider } from "@/lib/FinanceContext";
import { mockApi, problem, renderWithSession, USER } from "@/test-utils";
import { Dashboard } from "./Dashboard";

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));
afterEach(() => vi.unstubAllGlobals());

const wrap = (ui: React.ReactElement) => renderWithSession(<FinanceProvider space="p1">{ui}</FinanceProvider>);
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

const routes = (over: Record<string, () => { status: number; body?: unknown }> = {}) => ({
  "GET /v1/me": () => ({ status: 200, body: USER }),
  "GET /v1/spaces/p1/finance-settings": () => ({ status: 200, body: { timezone: "Asia/Kolkata", today: "2026-03-15" } }),
  "GET /v1/spaces/p1/net-worth": () => ({ status: 200, body: { currencies: [{ currency: "INR", assets: "1234567.50", liabilities: "500.00", net_worth: "1234067.50", components: [] }] } }),
  "GET /v1/spaces/p1/summaries/cashflow?from=2026-03-01&to=2026-03-15": () => ({ status: 200, body: { currencies: [{ currency: "INR", income: "6000.10", gross_expense: "1600.30", refunds: "0.00", net_expense: "1600.30", net: "4399.80" }] } }),
  "GET /v1/spaces/p1/budgets?month=2026-03": () => ({ status: 200, body: { items: [budget("1", "Food", "1000.10", "800.05", "200.05", "80.00", "WARNING"), budget("2", "Fun", "100.00", "100.01", "-0.01", "100.01", "OVER")] } }),
  "GET /v1/spaces/p1/bills?due_within_days=14": () => ({ status: 200, body: { items: [{ id: "bl1", kind: "BILL", name: "Rent", amount: "900.00", currency: "INR", frequency: "MONTHLY", next_due_date: "2026-03-20", overdue: false, status: "ACTIVE", account_id: null, annualized_cost: null, monthly_equivalent: null, version: 0 }] } }),
  "GET /v1/spaces/p1/goals": () => ({ status: 200, body: { items: [{ id: "g1", name: "Trip", target_amount: "1000.00", currency: "INR", target_date: null, saved: "250.00", remaining: "750.00", percent: "25.00", status: "IN_PROGRESS", months_left: null, required_monthly: null, version: 0 }] } }),
  "GET /v1/spaces/p1/transactions?limit=6": () => ({ status: 200, body: { items: [tx("t1", "450.00", "Chai Point", "c-food")], next_cursor: null } }),
  "GET /v1/spaces/p1/categories?include_archived=true": () => ({ status: 200, body: { items: [{ id: "c-food", parent_id: null, name: "Food", kind: "EXPENSE", archived: false, version: 0 }, { id: "c-rent", parent_id: null, name: "Rent", kind: "EXPENSE", archived: false, version: 0 }] } }),
  "GET /v1/spaces/p1/finance-reports/cash-flow?from=2026-03-01&to=2026-03-15&granularity=MONTH&compare=previous": () => ({ status: 200, body: compare }),
  "GET /v1/spaces/p1/finance-reports/cash-flow?from=2025-10-01&to=2026-03-15&granularity=MONTH": () => ({ status: 200, body: series }),
  "GET /v1/spaces/p1/transactions?from=2026-03-01&to=2026-03-31&limit=200": () => ({ status: 200, body: { items: [tx("t1", "450.00", "Chai Point", "c-food"), tx("t2", "1000.10", "Landlord", "c-rent"), tx("t3", "50.20", "Other", null)], next_cursor: null } }),
  "GET /v1/spaces/p1/bills": () => ({ status: 200, body: { items: [] } }),
  "GET /v1/spaces/p1/subscriptions": () => ({ status: 200, body: { items: [] } }),
  "GET /v1/spaces/p1/recurring-rules": () => ({ status: 200, body: { items: [] } }),
  "GET /v1/spaces/p1/finance-assistant/expense-change": () => ({ status: 200, body: { status: "ANSWERED", intent: "expense_change", domain: "PERSONAL", period: { from: "2026-02-01", to: "2026-02-28", label: "February 2026", comparison_from: null, comparison_to: null, comparison_label: "January 2026" }, summary: "In INR, net spending rose by 500.00.", clarification: null, sections: [], assumptions: [], limitations: [], sources: [], notices: [], ai: { status: "NOT_CONFIGURED", reason: null, provider: "none", message: "none", explanation: null, label: null } } }),
  ...over,
});

describe("dashboard (mocked fetch)", () => {
  it("shows the five summary figures from the server, with exact sums for the budget card and the server's comparison", async () => {
    mockApi(routes());
    wrap(<Dashboard onChanged={() => undefined} />);
    const summary = await screen.findByRole("region", { name: "Financial summary" });
    expect(await within(summary).findByText("INR 12,34,067.50")).toBeInTheDocument(); // total balance (net worth) in lakh grouping
    expect(within(summary).getByText("INR 6,000.10")).toBeInTheDocument();
    expect(within(summary).getByText("INR 1,600.30")).toBeInTheDocument();
    expect(within(summary).getByText("INR 4,399.80")).toBeInTheDocument();
    // budget remaining = 200.05 + (-0.01), added exactly, never as floating point
    expect(within(summary).getByText("INR 200.04")).toBeInTheDocument();
    expect(within(summary).getByText(/INR 900\.06 spent of INR 1,100\.10 across 2 budgets · over budget|spent of INR 1,100\.10 across 2 budgets/)).toBeInTheDocument();
    // comparison is the server's, with its own period, and states direction in words
    expect(await within(summary).findByText("+10.00%")).toBeInTheDocument();
    expect(within(summary).getByText("+25.00%")).toBeInTheDocument();
    expect(within(summary).getAllByText("vs 2026-02-14 to 2026-02-28").length).toBe(3);
    expect(within(summary).getByText("+INR 200.00")).toBeInTheDocument(); // percent is null: the amount change is shown instead
  });

  it("builds the category breakdown from this month's expenses with exact totals and names uncategorised spending", async () => {
    mockApi(routes());
    wrap(<Dashboard onChanged={() => undefined} />);
    const legend = await screen.findByRole("list", { name: "Categories" });
    expect(within(legend).getByText("INR 1,000.10 · 67%")).toBeInTheDocument();
    expect(within(legend).getByText("Rent")).toBeInTheDocument();
    expect(within(legend).getByText("Uncategorised")).toBeInTheDocument();
    expect(within(legend).getByText("INR 450.00 · 30%")).toBeInTheDocument();
    expect(screen.getByRole("img", { name: /Donut chart in INR: 3 categories/ })).toBeInTheDocument();
  });

  it("draws the monthly charts from the report, marks budget states in words and shows goals and bills", async () => {
    mockApi(routes());
    wrap(<Dashboard onChanged={() => undefined} />);
    expect(await screen.findByRole("img", { name: /Bar chart in INR: income and expenses for 2 months/ })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: /Line chart in INR: expenses per month/ })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: /Line chart in INR: savings per month/ })).toBeInTheDocument();
    const budgetPanel = screen.getByRole("heading", { name: "Budget overview" }).closest("section")!;
    expect(within(budgetPanel).getByText(/Close to the limit · 80.00% used/)).toBeInTheDocument();
    expect(within(budgetPanel).getByText(/Over budget · 100.01% used/)).toBeInTheDocument();
    expect(within(budgetPanel).getAllByRole("progressbar")).toHaveLength(2);
    expect(screen.getByText("Trip")).toBeInTheDocument();
    expect(screen.getByText(/Due 2026-03-20/)).toBeInTheDocument();
    expect(screen.getByText(/Calculated by Finora from your recorded data. No AI model wrote this./)).toBeInTheDocument();
    expect(screen.getByText("In INR, net spending rose by 500.00.")).toBeInTheDocument();
  });

  it("is honest for a new workspace: an empty state and no invented figures", async () => {
    mockApi(routes({
      "GET /v1/spaces/p1/net-worth": () => ({ status: 200, body: { currencies: [] } }),
      "GET /v1/spaces/p1/summaries/cashflow?from=2026-03-01&to=2026-03-15": () => ({ status: 200, body: { currencies: [] } }),
      "GET /v1/spaces/p1/budgets?month=2026-03": () => ({ status: 200, body: { items: [] } }),
      "GET /v1/spaces/p1/bills?due_within_days=14": () => ({ status: 200, body: { items: [] } }),
      "GET /v1/spaces/p1/goals": () => ({ status: 200, body: { items: [] } }),
      "GET /v1/spaces/p1/transactions?limit=6": () => ({ status: 200, body: { items: [], next_cursor: null } }),
      "GET /v1/spaces/p1/transactions?from=2026-03-01&to=2026-03-31&limit=200": () => ({ status: 200, body: { items: [], next_cursor: null } }),
      "GET /v1/spaces/p1/finance-reports/cash-flow?from=2025-10-01&to=2026-03-15&granularity=MONTH": () => ({ status: 200, body: { ...series, currencies: [] } }),
      "GET /v1/spaces/p1/finance-reports/cash-flow?from=2026-03-01&to=2026-03-15&granularity=MONTH&compare=previous": () => ({ status: 200, body: { ...compare, comparison: null, currencies: [] } }),
    }));
    wrap(<Dashboard onChanged={() => undefined} />);
    expect(await screen.findByText("Welcome to Finora")).toBeInTheDocument();
    const summary = screen.getByRole("region", { name: "Financial summary" });
    expect(within(summary).getAllByText("—").length).toBe(5);
    expect(within(summary).getByRole("link", { name: "Set a monthly budget" })).toHaveAttribute("href", "/finance/budgets");
    expect(await screen.findByText(/Not enough history for a chart yet/)).toBeInTheDocument();
    expect(screen.getByText(/No expenses recorded this month in INR/)).toBeInTheDocument();
    expect(screen.getByText("No goals yet.")).toBeInTheDocument();
  });

  it("keeps the balances when a secondary block fails, and lets that block retry", async () => {
    let fail = true;
    mockApi(routes({ "GET /v1/spaces/p1/transactions?limit=6": () => (fail ? problem(503, "DOWNSTREAM_UNAVAILABLE") : { status: 200, body: { items: [tx("t1", "450.00", "Chai Point", "c-food")], next_cursor: null } }) }));
    wrap(<Dashboard onChanged={() => undefined} />);
    expect(await screen.findByText("INR 12,34,067.50")).toBeInTheDocument();
    const recent = screen.getByRole("heading", { name: "Recent transactions" }).closest("section")!;
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
      "GET /v1/spaces/p1/finance-reports/cash-flow?from=2026-03-01&to=2026-03-15&granularity=MONTH&compare=previous": () => ({ status: 200, body: compare }),
    }));
    wrap(<Dashboard onChanged={() => undefined} />);
    const group = await screen.findByRole("group", { name: "Currency" });
    expect(within(group).getByRole("button", { name: "INR" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getAllByText("INR 100.00").length).toBeGreaterThan(0);
    await userEvent.click(within(group).getByRole("button", { name: "USD" }));
    await waitFor(() => expect(screen.getAllByText("USD 50.00").length).toBeGreaterThan(0));
    expect(screen.queryByText("INR 150.00")).toBeNull();
  });
});
