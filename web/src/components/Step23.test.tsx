import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { FinanceProvider } from "@/lib/FinanceContext";
import { mockApi, problem, renderWithSession, USER } from "@/test-utils";
import type { Role, Workspace } from "@/lib/types";
import { AccountingPanel } from "./accounting/AccountingPanel";
import { BusinessApp } from "./business/BusinessApp";
import { FinanceApp } from "./finance/FinanceApp";
import { BarChart } from "./ui/BarChart";
import { ReportsPanel } from "./insights/ReportsPanel";
import { AlertsPanel } from "./platform/AlertsPanel";
import { NotificationsPanel } from "./platform/NotificationsPanel";
import { SubscriptionPanel } from "./platform/SubscriptionPanel";
import { RegionalPanel } from "./regional/RegionalPanel";

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));
afterEach(() => vi.unstubAllGlobals());

const me = { "GET /v1/me": () => ({ status: 200, body: USER }) };
const wrap = (ui: React.ReactElement, space = "p1") => renderWithSession(<FinanceProvider space={space}>{ui}</FinanceProvider>);

const row = (period: string, values: Record<string, string>, activity = true) => ({ period, start: null, end: null, activity, values });
const report = (name: string, title: string, columns: string[], rows: ReturnType<typeof row>[], granularity: string | null = "MONTH") => ({
  report: name, title, domain: "PERSONAL", from: "2026-01-01", to: "2026-03-31", granularity, definitions: ["d"], notes: ["n"],
  currencies: [{ currency: "INR", columns, rows, totals: {} }], comparison: null,
});
const FORECAST = { metric: "net", kind: "ESTIMATE", label: "Estimate", method: "m", history_from: "2025-09-01", history_to: "2026-02-28", lookback_months: 6, horizon_months: 3, assumptions: [], ai_explanation: { status: "UNAVAILABLE", message: "none" }, currencies: [] };

describe("report charts", () => {
  const reportRoutes = (body: unknown) => ({ ...me, "GET /v1/spaces/p1/finance-reports/cash-flow?granularity=MONTH": () => ({ status: 200, body }), "GET /v1/spaces/p1/finance-forecast?metric=net&lookback_months=6&horizon_months=3": () => ({ status: 200, body: FORECAST }) });

  it("draws a chart from the returned figures with a text description, a legend and the full table beside it", async () => {
    mockApi(reportRoutes(report("cash-flow", "Income, expenses and net cash flow", ["income", "net_expense"], [
      row("2026-01", { income: "1000.00", net_expense: "400.00" }), row("2026-02", { income: "1500.50", net_expense: "700.25" }),
    ])));
    wrap(<ReportsPanel domain="PERSONAL" canExport />);
    const chart = await screen.findByRole("img", { name: /Bar chart in INR: Income and Net expense for 2 periods, from 2026-01 to 2026-02/ });
    expect(chart).toBeInTheDocument();
    const legend = screen.getByRole("list", { name: "Legend" });
    expect(within(legend).getByText("Income")).toBeInTheDocument();
    expect(within(legend).getByText("Net expense")).toBeInTheDocument();
    // the top-of-axis label is the backend's own string, never a computed value
    expect(chart.querySelectorAll("text")[0]).toHaveTextContent("INR 1,500.50");
    expect(chart.querySelectorAll("rect")).toHaveLength(4);
    expect(screen.getAllByText("INR 700.25", { selector: "td" }).length).toBeGreaterThanOrEqual(1);
    expect(screen.getByRole("table", { name: "INR" })).toBeInTheDocument();
  });

  it("draws no chart when there is nothing to compare: one active period, or all zeros", async () => {
    mockApi(reportRoutes(report("cash-flow", "t", ["income", "net_expense"], [row("2026-01", { income: "5.00", net_expense: "0.00" }), row("2026-02", { income: "0.00", net_expense: "0.00" }, false)])));
    wrap(<ReportsPanel domain="PERSONAL" canExport />);
    await screen.findByRole("table", { name: "INR" });
    expect(screen.queryByRole("img")).toBeNull();
  });

  it("draws no chart when every value is zero", async () => {
    mockApi(reportRoutes(report("cash-flow", "t", ["income", "net_expense"], [row("2026-01", { income: "0.00", net_expense: "0.00" }), row("2026-02", { income: "0.00", net_expense: "0.00" })])));
    wrap(<ReportsPanel domain="PERSONAL" canExport />);
    await screen.findByRole("table", { name: "INR" });
    expect(screen.queryByRole("img")).toBeNull();
  });

  it("handles negative values with a labelled baseline and keeps currencies apart", () => {
    render(<BarChart title="Net worth" currency="USD" labels={["a", "b"]} series={[{ key: "n", label: "Net worth", values: ["-50.00", "20.00"] }]} summary="s" />);
    const texts = Array.from(document.querySelectorAll("svg text")).map((t) => t.textContent);
    expect(texts).toContain("USD 20.00");
    expect(texts).toContain("USD -50.00");
    expect(texts).toContain("0");
    expect(screen.getByText(/USD; the table below has every figure/)).toBeInTheDocument();
  });
});

describe("notifications delivery statement", () => {
  const list = { "GET /v1/spaces/p1/finance-notifications?limit=20&offset=0": () => ({ status: 200, body: { items: [], total: 0, unread: 0, limit: 20, offset: 0 } }) };

  it("claims nothing about delivery when the server's channel report cannot be read", async () => {
    mockApi({ ...me, ...list, "GET /v1/spaces/p1/finance-notifications/channels": () => problem(500, "INTERNAL") });
    wrap(<NotificationsPanel />);
    expect(await screen.findByText(/could not be loaded/)).toBeInTheDocument();
    expect(screen.queryByText(/nothing is sent outside/i)).toBeNull();
  });

  it("reports external delivery as active only when the server says so", async () => {
    mockApi({ ...me, ...list, "GET /v1/spaces/p1/finance-notifications/channels": () => ({ status: 200, body: { in_app: { status: "AVAILABLE" }, external: { status: "AVAILABLE" } } }) });
    wrap(<NotificationsPanel />);
    expect(await screen.findByText(/External delivery: available/)).toBeInTheDocument();
  });
});

describe("accessible confirmations replace browser prompts", () => {
  it("resolves an alert through a dialog and sends the reason", async () => {
    const alert = { id: "a1", alert_type: "IMPORT_ISSUE", severity: "WARNING", title: "Import failed", details: {}, window_start: null, window_end: null, status: "OPEN", generated_at: "2026-03-15T10:00:00Z", acknowledged_at: null, resolved_at: null, resolved_reason: null };
    const prompt = vi.fn();
    vi.stubGlobal("prompt", prompt);
    const api = mockApi({
      ...me,
      "GET /v1/spaces/p1/finance-alerts/settings": () => ({ status: 200, body: { items: [] } }),
      "GET /v1/spaces/p1/finance-alerts?limit=20&offset=0": () => ({ status: 200, body: { items: [alert], total: 1, open: 1, limit: 20, offset: 0 } }),
      "POST /v1/spaces/p1/finance-alerts/a1/resolve": () => ({ status: 200, body: { ...alert, status: "RESOLVED" } }),
    });
    wrap(<AlertsPanel canEditSettings />);
    await userEvent.click(await screen.findByRole("button", { name: "Resolve Import failed" }));
    const dialog = screen.getByRole("dialog", { name: "Resolve this alert" });
    await userEvent.type(within(dialog).getByLabelText(/Why is this alert resolved/), "fixed the file");
    await userEvent.click(within(dialog).getByRole("button", { name: "Resolve alert" }));
    await waitFor(() => expect(api.calls.find((c) => c.key.endsWith("/a1/resolve"))?.body).toEqual({ reason: "fixed the file" }));
    expect(prompt).not.toHaveBeenCalled();
  });

  it("cancels a subscription only after the dialog is confirmed, and a refusal keeps the dialog open", async () => {
    const sub = { status: "ACTIVE", plan: { plan_key: "standard", name: "Standard", description: "", provisional: true, requires_payment: false, status: "ACTIVE", entitlements: [] }, started_at: null, current_period_end: null, implicit_default: false, cancel_reason: null, effective: [], notices: [] };
    let refuse = true;
    const api = mockApi({
      ...me,
      "GET /v1/spaces/p1/finance-subscription": () => ({ status: 200, body: sub }),
      "GET /v1/spaces/p1/finance-subscription/plans": () => ({ status: 200, body: { items: [] } }),
      "GET /v1/spaces/p1/finance-subscription/history": () => ({ status: 200, body: { items: [] } }),
      "GET /v1/spaces/p1/finance-billing": () => ({ status: 200, body: { provider: { status: "NOT_CONFIGURED", approved: false }, message: "m", payments_enabled: false, contact: { contact_name: null, contact_email: null }, notes: [] } }),
      "GET /v1/spaces/p1/finance-billing/records?limit=20": () => ({ status: 200, body: { items: [], total: 0 } }),
      "POST /v1/spaces/p1/finance-subscription/cancel": () => (refuse ? problem(403, "FORBIDDEN") : { status: 200, body: { ...sub, status: "CANCELLED" } }),
    });
    wrap(<SubscriptionPanel isOwner canSeeAdmin />);
    await userEvent.click(await screen.findByRole("button", { name: "Cancel subscription" }));
    expect(api.calls.some((c) => c.key.endsWith("/cancel"))).toBe(false);
    await userEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel subscription" }));
    expect(await screen.findByText("You do not have permission to do that.")).toBeInTheDocument();
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    refuse = false;
    await userEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel subscription" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  });
});

describe("accounting history and fiscal periods", () => {
  it("lists real synchronisation runs and states plainly when none have run", async () => {
    const status = { provider: { status: "NOT_CONFIGURED", key: null }, capabilities: { oauth_connect: false, webhooks: false, sync: false }, token_vault: { available: false }, connections: [], pending_decisions: ["Which provider?"], message: "Nothing is connected." };
    mockApi({ ...me, "GET /v1/spaces/p1/finance-accounting": () => ({ status: 200, body: status }), "GET /v1/spaces/p1/finance-accounting/sync-runs": () => ({ status: 200, body: { items: [] } }) });
    wrap(<AccountingPanel />);
    expect(await screen.findByText("No synchronisation has run.")).toBeInTheDocument();
  });

  it("shows the server's fiscal periods next to the regional settings", async () => {
    const settings = { domain: "BUSINESS", timezone: "UTC", today: "2026-03-15", display_currency: null, base_currency: "INR", locale: null, date_format: null, fiscal_year_start_month: 4, reporting_period: "MONTH", rate_stale_after_days: 7,
      supported: { currencies: ["INR"], locales: ["en-IN"], date_formats: ["ISO"], reporting_periods: ["MONTH"] }, notes: [] };
    mockApi({
      ...me,
      "GET /v1/spaces/p1/finance-regional": () => ({ status: 200, body: settings }),
      "GET /v1/spaces/p1/finance-rates/status": () => ({ status: 200, body: { automatic_source: { status: "NOT_CONFIGURED", message: "No provider." }, manual_entry: { available: true, source: "USER_SUPPLIED", can_write: false }, stale_after_days: 7, notes: [] } }),
      "GET /v1/spaces/p1/finance-rates?include_voided=true&limit=100": () => ({ status: 200, body: { items: [] } }),
      "GET /v1/spaces/p1/finance-regional/periods": () => ({ status: 200, body: { today: "2026-03-15", fiscal_year_start_month: 4, periods: { THIS_FISCAL_YEAR: { from: "2025-04-01", to: "2026-03-15", end: "2026-03-31" } }, notes: ["'to' is capped at today."] } }),
    });
    wrap(<RegionalPanel />);
    const table = await screen.findByRole("table", { name: /fiscal year starting in April/ });
    expect(within(table).getByText("2025-04-01")).toBeInTheDocument();
    expect(within(table).getByText("This fiscal year")).toBeInTheDocument();
  });
});

describe("controlled sections", () => {
  const ws = (role: Role): Workspace => ({ id: "b1", name: "Acme", type: "BUSINESS", role, membership_id: "m1" });
  const empty = { items: [], total: 0, limit: 20, offset: 0 };
  const settings = { timezone: "UTC", base_currency: "INR", company_name: null, invoice_prefix: "INV-", invoice_padding: 4, invoice_next_number: 1, default_terms_days: 30, next_invoice_number_preview: "INV-0001", today: "2026-03-15" };
  const base = {
    ...me,
    "GET /v1/spaces/b1/clients?limit=100": () => ({ status: 200, body: { ...empty, limit: 100 } }),
    "GET /v1/spaces/b1/vendors?limit=100": () => ({ status: 200, body: { ...empty, limit: 100 } }),
    "GET /v1/spaces/b1/projects?limit=100": () => ({ status: 200, body: { ...empty, limit: 100 } }),
    "GET /v1/spaces/b1/expense-categories": () => ({ status: 200, body: { items: [] } }),
    "GET /v1/spaces/b1/business-settings": () => ({ status: 200, body: settings }),
  };

  it("renders only the requested business section, without its own tabs, and navigates through the shell callback", async () => {
    const go = vi.fn();
    mockApi({ ...base, "GET /v1/spaces/b1/expenses?limit=20&offset=0": () => ({ status: 200, body: empty }) });
    renderWithSession(<BusinessApp workspace={ws("OWNER")} section="expenses" onSectionChange={go} />);
    expect(await screen.findByText(/No expenses yet/)).toBeInTheDocument();
    expect(screen.queryByRole("tablist")).toBeNull();
  });

  it("denies a section the role does not have, even when asked for directly, and never loads its data", async () => {
    const { calls } = mockApi({ ...base, "GET /v1/spaces/b1/expenses?limit=20&offset=0": () => ({ status: 200, body: empty }) });
    renderWithSession(<BusinessApp workspace={ws("CONTRIBUTOR")} section="invoices" />);
    expect(await screen.findByText("You do not have access to this section.")).toBeInTheDocument();
    expect(calls.some((c) => c.key.includes("/invoices"))).toBe(false);
  });

  it("says a business section does not exist rather than showing a blank page", async () => {
    mockApi({ ...base });
    renderWithSession(<BusinessApp workspace={ws("OWNER")} section="nonsense" />);
    expect(await screen.findByText("This page does not exist")).toBeInTheDocument();
  });

  it("renders a personal section without tabs and reports an unknown one", async () => {
    const PERSONAL = { id: "p1", name: "Asha", type: "PERSONAL", role: "OWNER", membership_id: "m1" };
    mockApi({
      ...me,
      "GET /v1/workspaces": () => ({ status: 200, body: { items: [PERSONAL] } }),
      "GET /v1/spaces/p1/accounts?include_archived=true": () => ({ status: 200, body: { items: [] } }),
      "GET /v1/spaces/p1/categories?include_archived=true": () => ({ status: 200, body: { items: [] } }),
    });
    const { unmount } = renderWithSession(<FinanceApp section="categories" />);
    await waitFor(() => expect(screen.queryByText(/Loading/)).toBeNull());
    expect(screen.queryByRole("tablist")).toBeNull();
    unmount();
    renderWithSession(<FinanceApp section="nonsense" />);
    expect(await screen.findByText("This page does not exist")).toBeInTheDocument();
  });
});
