// Component tests with a mocked fetch. They check what the workspace administration page shows for given server answers; the server's authorization is covered by the Java end-to-end suite.
import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { FinanceProvider } from "@/lib/FinanceContext";
import { mockApi, problem, renderWithSession, USER } from "@/test-utils";
import { AdminPanel } from "./AdminPanel";

afterEach(() => vi.unstubAllGlobals());
const me = { "GET /v1/me": () => ({ status: 200, body: USER }) };
const wrap = (ui: React.ReactElement) => renderWithSession(<FinanceProvider space="b1">{ui}</FinanceProvider>);

const overview = { scope: "WORKSPACE", note: "Administration of this workspace only. Platform-wide administration does not exist yet.", subscription: { plan: "standard", plan_name: "Standard", status: "ACTIVE", provisional: false },
  integrations_by_state: { NOT_CONFIGURED: 5, FAILED: 1 }, open_alerts_by_severity: { WARNING: 2 }, unread_notifications: 3, billing: { provider_status: "NOT_CONFIGURED", open_records: 0, failed_payment_records: 0 } };
const sub = { status: "ACTIVE", plan: { plan_key: "standard", name: "Standard", description: "", provisional: true, requires_payment: false, status: "ACTIVE", entitlements: [] }, started_at: null, current_period_end: null, implicit_default: true, cancel_reason: null,
  effective: [{ key: "assistant", enabled: true, limit: null, reason: null }, { key: "documents", enabled: true, limit: 500, reason: null }, { key: "alerts", enabled: false, limit: null, reason: "subscription not active" }], notices: [] };
const integrations = { items: [
  { key: "ai", name: "AI assistant", kind: "AI", state: "NOT_CONFIGURED", message: "No AI provider is configured.", health: {}, pending_decisions: [] },
  { key: "accounting", name: "Accounting", kind: "ACCOUNTING", state: "FAILED", message: "The last synchronisation failed.", health: {}, pending_decisions: [] },
] };
const alert = (n: number) => ({ id: `a${n}`, alert_type: "IMPORT_ISSUE", severity: "WARNING", title: `Import problem ${n}`, details: { secret_figure: "999999.99" }, window_start: null, window_end: null, status: "OPEN", generated_at: "2026-03-15T10:00:00Z", acknowledged_at: null, resolved_at: null, resolved_reason: null });
const record = (n: number) => ({ id: `r${n}`, kind: "APPLICATION_RECORD", legal_tax_invoice: false, status: "OPEN", payment_confirmed: false, currency: "INR", amount: "100.00", description: `Fee ${n}`, provider: null, provider_reference: null, paid_at: null, created_at: "2026-03-01T00:00:00Z", payment_attempts: [] });

const routes = {
  ...me,
  "GET /v1/spaces/b1/finance-admin/overview": () => ({ status: 200, body: overview }),
  "GET /v1/spaces/b1/finance-subscription": () => ({ status: 200, body: sub }),
  "GET /v1/spaces/b1/finance-integrations": () => ({ status: 200, body: integrations }),
  "GET /v1/spaces/b1/finance-alerts?status=OPEN&limit=5": () => ({ status: 200, body: { items: [alert(1), alert(2)], total: 7, open: 7, limit: 5, offset: 0 } }),
  "GET /v1/spaces/b1/finance-billing/records?limit=10": () => ({ status: 200, body: { items: [record(1)], total: 12 } }),
  "GET /v1/spaces/b1/finance-billing/records?limit=10&offset=10": () => ({ status: 200, body: { items: [record(11)], total: 12 } }),
};

describe("workspace administration page (mocked fetch)", () => {
  it("shows plan, integration health, open alerts and billing history from the server and labels the page as workspace-only", async () => {
    mockApi(routes);
    wrap(<AdminPanel />);
    expect(await screen.findByText(/Administration of this workspace only/)).toBeInTheDocument();
    expect(await screen.findByText(/This plan is provisional and has no price/)).toBeInTheDocument();
    expect(screen.getByText(/The workspace has not chosen a plan/)).toBeInTheDocument();
    expect(screen.getByText("Documents: available, limit 500")).toBeInTheDocument();
    expect(screen.getByText("Alerts: not available (subscription not active)")).toBeInTheDocument();
    const table = await screen.findByRole("table", { name: /State of each external service/ });
    expect(within(table).getByRole("row", { name: /Accounting Failed The last synchronisation failed/ })).toBeInTheDocument();
    expect(within(table).getByRole("row", { name: /AI assistant Not configured/ })).toBeInTheDocument();
    expect(within(table).queryByText("Connected")).toBeNull();
    expect(await screen.findByText("Import problem 1")).toBeInTheDocument();
    expect(screen.getByText(/Showing 2 of 7 open alerts/)).toBeInTheDocument();
    expect(screen.queryByText("999999.99")).toBeNull(); // alert figures are not repeated on the administration page
    expect(await screen.findByText("Fee 1")).toBeInTheDocument();
    expect(screen.getByText(/not tax invoices or payment receipts/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "All alerts and their settings" })).toHaveAttribute("href", "/workspaces/b1/alerts");
    expect(screen.getByRole("link", { name: "Manage the plan" })).toHaveAttribute("href", "/workspaces/b1/plan");
  });

  it("says plainly that platform-wide administration does not exist, with what it needs, and offers no fake controls", async () => {
    mockApi(routes);
    wrap(<AdminPanel />);
    expect(await screen.findByRole("heading", { name: "Not available: platform-wide administration" })).toBeInTheDocument();
    expect(screen.getByText(/platform-staff identity and authorization model/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /impersonat|suspend|support access|delete workspace/i })).toBeNull();
  });

  it("pages the billing history with the server's offset", async () => {
    mockApi(routes);
    wrap(<AdminPanel />);
    await screen.findByText("Fee 1");
    expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(await screen.findByText("Fee 11")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
  });

  it("keeps the other sections working when one fails, and lets that one retry", async () => {
    let fail = true;
    mockApi({ ...routes, "GET /v1/spaces/b1/finance-integrations": () => (fail ? problem(503, "DOWNSTREAM_UNAVAILABLE") : { status: 200, body: integrations }) });
    wrap(<AdminPanel />);
    expect(await screen.findByText("Import problem 1")).toBeInTheDocument();
    expect(await screen.findByText("Fee 1")).toBeInTheDocument();
    const section = screen.getByRole("heading", { name: "Integration health" }).closest("section")!;
    expect(await within(section).findByRole("alert")).toHaveTextContent("temporarily unavailable");
    fail = false;
    await userEvent.click(within(section).getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("table", { name: /State of each external service/ })).toBeInTheDocument();
  });

  it("shows a refusal as a refusal, not as an empty page", async () => {
    mockApi({ ...routes, "GET /v1/spaces/b1/finance-subscription": () => problem(403, "FORBIDDEN"), "GET /v1/spaces/b1/finance-billing/records?limit=10": () => problem(403, "FORBIDDEN") });
    wrap(<AdminPanel />);
    expect((await screen.findAllByText("You do not have permission to do that.")).length).toBeGreaterThanOrEqual(2);
  });

  it("explains an empty alert list without claiming nothing needs attention", async () => {
    mockApi({ ...routes, "GET /v1/spaces/b1/finance-alerts?status=OPEN&limit=5": () => ({ status: 200, body: { items: [], total: 0, open: 0, limit: 5, offset: 0 } }) });
    wrap(<AdminPanel />);
    expect(await screen.findByText(/not a guarantee that nothing needs attention/)).toBeInTheDocument();
  });
});
