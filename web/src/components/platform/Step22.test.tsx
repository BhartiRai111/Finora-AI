import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { FinanceProvider } from "@/lib/FinanceContext";
import { mockApi, problem, renderWithSession, USER } from "@/test-utils";
import { AlertsPanel } from "./AlertsPanel";
import { AdminOverviewPanel, IntegrationRegistry } from "./IntegrationRegistry";
import { NotificationStrip, NotificationsPanel } from "./NotificationsPanel";
import { SubscriptionPanel } from "./SubscriptionPanel";

afterEach(() => vi.unstubAllGlobals());

const me = { "GET /v1/me": () => ({ status: 200, body: USER }) };
const wrap = (ui: React.ReactElement) => renderWithSession(<FinanceProvider space="b1">{ui}</FinanceProvider>);

const note = (id: string, read: boolean) => ({ id, category: "IMPORT", severity: "WARNING", title: `Import ${id}`, body: "Some rows failed.", metadata: {}, read, created_at: "2026-03-15T10:00:00Z" });

describe("notifications", () => {
  it("shows unread state in words, marks one and all as read, and says nothing is sent externally", async () => {
    let items = [note("n1", false), note("n2", true)];
    const { calls } = mockApi({
      ...me,
      "GET /v1/spaces/b1/finance-notifications?limit=20&offset=0": () => ({ status: 200, body: { items, total: 2, unread: items.filter((i) => !i.read).length, limit: 20, offset: 0 } }),
      "POST /v1/spaces/b1/finance-notifications/n1/read": () => { items = [note("n1", true), note("n2", true)]; return { status: 200, body: {} }; },
    });
    wrap(<NotificationsPanel />);
    expect(await screen.findByText(/No email, SMS or push delivery is configured/)).toBeInTheDocument();
    expect(await screen.findByText(/· unread\)/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: 'Mark "Import n1" as read' }));
    await waitFor(() => expect(calls.some((c) => c.key.endsWith("/n1/read"))).toBe(true));
    await waitFor(() => expect(screen.getByRole("button", { name: "Mark all as read" })).toBeDisabled());
  });

  it("marks everything read with one request", async () => {
    let unread = 2;
    const { calls } = mockApi({
      ...me,
      "GET /v1/spaces/b1/finance-notifications?limit=20&offset=0": () => ({ status: 200, body: { items: [note("n1", unread === 0), note("n2", unread === 0)], total: 2, unread, limit: 20, offset: 0 } }),
      "POST /v1/spaces/b1/finance-notifications/read-all": () => { unread = 0; return { status: 200, body: {} }; },
    });
    wrap(<NotificationsPanel />);
    await userEvent.click(await screen.findByRole("button", { name: "Mark all as read" }));
    await waitFor(() => expect(calls.filter((c) => c.key.includes("read-all"))).toHaveLength(1));
  });

  it("shows an empty state and an error with retry", async () => {
    let fail = true;
    mockApi({
      ...me,
      "GET /v1/spaces/b1/finance-notifications?limit=20&offset=0": () => fail ? problem(500, "INTERNAL") : { status: 200, body: { items: [], total: 0, unread: 0, limit: 20, offset: 0 } },
    });
    wrap(<NotificationsPanel />);
    const retry = await screen.findByRole("button", { name: "Try again" });
    fail = false;
    await userEvent.click(retry);
    expect(await screen.findByText("You have no notifications.")).toBeInTheDocument();
  });

  it("pages forward with the offset", async () => {
    const seen: string[] = [];
    mockApi({
      ...me,
      "GET /v1/spaces/b1/finance-notifications?limit=20&offset=0": () => { seen.push("0"); return { status: 200, body: { items: [note("n1", true)], total: 25, unread: 0, limit: 20, offset: 0 } }; },
      "GET /v1/spaces/b1/finance-notifications?limit=20&offset=20": () => { seen.push("20"); return { status: 200, body: { items: [note("n2", true)], total: 25, unread: 0, limit: 20, offset: 20 } }; },
    });
    wrap(<NotificationsPanel />);
    await userEvent.click(await screen.findByRole("button", { name: "Next" }));
    expect(await screen.findByText("Import n2")).toBeInTheDocument();
    expect(seen).toEqual(["0", "20"]);
  });

  it("strip appears only with unread notifications", async () => {
    mockApi({ ...me, "GET /v1/spaces/b1/finance-notifications/unread-count": () => ({ status: 200, body: { unread: 3 } }) });
    wrap(<NotificationStrip onOpen={() => undefined} />);
    expect(await screen.findByText(/You have 3 unread notifications/)).toBeInTheDocument();
  });
});

const setting = (type: string, extra: object = {}) => ({ alert_type: type, enabled: true, threshold: null, days: null, currency: null, is_default: true, threshold_meaning: null, trigger: "a fixed rule", severity: "WARNING", ...extra });
const alert = { id: "a1", alert_type: "BUDGET_THRESHOLD", severity: "CRITICAL", title: "Food budget is over", details: { spent: "120.00" }, window_start: "2026-03-01", window_end: "2026-03-31", status: "OPEN", generated_at: "2026-03-15T10:00:00Z", acknowledged_at: null, resolved_at: null, resolved_reason: null };

describe("alerts", () => {
  it("lists alerts with the reason, acknowledges one, and explains an empty list honestly", async () => {
    let status = "OPEN";
    const { calls } = mockApi({
      ...me,
      "GET /v1/spaces/b1/finance-alerts/settings": () => ({ status: 200, body: { items: [setting("LOW_CASH", { enabled: false })] } }),
      "GET /v1/spaces/b1/finance-alerts?limit=20&offset=0": () => ({ status: 200, body: { items: [{ ...alert, status }], total: 1, open: status === "OPEN" ? 1 : 0, limit: 20, offset: 0 } }),
      "POST /v1/spaces/b1/finance-alerts/a1/acknowledge": () => { status = "ACKNOWLEDGED"; return { status: 200, body: {} }; },
    });
    wrap(<AlertsPanel canEditSettings />);
    expect(await screen.findByText("Food budget is over")).toBeInTheDocument();
    expect(screen.getByText(/Critical · Budget threshold · open/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Acknowledge Food budget is over" }));
    await waitFor(() => expect(calls.some((c) => c.key.endsWith("/a1/acknowledge"))).toBe(true));
    expect(await screen.findByText(/acknowledged/)).toBeInTheDocument();
  });

  it("runs an evaluation on request and reports what was skipped", async () => {
    mockApi({
      ...me,
      "GET /v1/spaces/b1/finance-alerts/settings": () => ({ status: 200, body: { items: [] } }),
      "GET /v1/spaces/b1/finance-alerts?limit=20&offset=0": () => ({ status: 200, body: { items: [], total: 0, open: 0, limit: 20, offset: 0 } }),
      "POST /v1/spaces/b1/finance-alerts/evaluate": () => ({ status: 200, body: { evaluated_on: "2026-03-15", created: { BUDGET_THRESHOLD: 1 }, resolved: {}, skipped: [{ type: "LOW_CASH", reason: "no threshold set" }], note: "Checked only now." } }),
    });
    wrap(<AlertsPanel canEditSettings />);
    expect(await screen.findByText(/No alerts\. That means no rule has fired/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Check for alerts now" }));
    expect(await screen.findByText(/1 new, 0 resolved/)).toBeInTheDocument();
    expect(screen.getByText(/Low cash \(no threshold set\)/)).toBeInTheDocument();
  });

  it("refuses an enabled low-cash alert without an explicit threshold and currency, and sends nothing", async () => {
    const { calls } = mockApi({
      ...me,
      "GET /v1/spaces/b1/finance-alerts/settings": () => ({ status: 200, body: { items: [setting("LOW_CASH", { threshold_meaning: "minimum balance" })] } }),
      "GET /v1/spaces/b1/finance-alerts?limit=20&offset=0": () => ({ status: 200, body: { items: [], total: 0, open: 0, limit: 20, offset: 0 } }),
    });
    wrap(<AlertsPanel canEditSettings />);
    await userEvent.click(await screen.findByRole("button", { name: "Save Low cash settings" }));
    expect(await screen.findByText(/Nothing is assumed/)).toBeInTheDocument();
    expect(calls.some((c) => c.key.startsWith("PUT"))).toBe(false);
  });

  it("hides the save controls from a user who cannot change settings", async () => {
    mockApi({
      ...me,
      "GET /v1/spaces/b1/finance-alerts/settings": () => ({ status: 200, body: { items: [setting("IMPORT_ISSUE", { days: 14 })] } }),
      "GET /v1/spaces/b1/finance-alerts?limit=20&offset=0": () => ({ status: 200, body: { items: [], total: 0, open: 0, limit: 20, offset: 0 } }),
    });
    wrap(<AlertsPanel canEditSettings={false} />);
    expect(await screen.findByText(/view the alert settings but not change them/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Save/ })).toBeNull();
  });
});

const plan = (key: string, name: string) => ({ plan_key: key, name, description: `${name} plan`, provisional: true, requires_payment: false, status: "ACTIVE", entitlements: [] });
const sub = (status: string, notices: string[] = []) => ({
  status, plan: plan("standard", "Standard"), started_at: null, current_period_end: null, implicit_default: false, cancel_reason: null,
  effective: [{ key: "assistant", enabled: status === "ACTIVE", limit: null, reason: status === "ACTIVE" ? null : "subscription not active" }],
  notices,
});
const billing = { provider: { status: "NOT_CONFIGURED", approved: false }, message: "No payment provider is configured.", payments_enabled: false, contact: { contact_name: null, contact_email: null }, notes: [] };
const rec = (over: object) => ({ id: "r1", kind: "APPLICATION_RECORD", legal_tax_invoice: false, status: "OPEN", payment_confirmed: false, currency: "INR", amount: "100.00", description: "Plan fee", provider: null, provider_reference: null, paid_at: null, created_at: "2026-03-01T00:00:00Z", payment_attempts: [], ...over });

function subRoutes(s: object, records: object[] = []) {
  return {
    ...me,
    "GET /v1/spaces/b1/finance-subscription": () => ({ status: 200, body: s }),
    "GET /v1/spaces/b1/finance-subscription/plans": () => ({ status: 200, body: { items: [plan("standard", "Standard"), plan("limited", "Limited")] } }),
    "GET /v1/spaces/b1/finance-subscription/history": () => ({ status: 200, body: { items: [] } }),
    "GET /v1/spaces/b1/finance-billing": () => ({ status: 200, body: billing }),
    "GET /v1/spaces/b1/finance-billing/records?limit=20": () => ({ status: 200, body: { items: records, total: records.length } }),
  };
}

describe("plan and billing", () => {
  it("lets the owner switch plan and tells a non-owner only the owner can", async () => {
    const { calls } = mockApi({ ...subRoutes(sub("ACTIVE")), "POST /v1/spaces/b1/finance-subscription/change": () => ({ status: 200, body: {} }) });
    wrap(<SubscriptionPanel isOwner canSeeAdmin />);
    await userEvent.selectOptions(await screen.findByLabelText("Switch plan"), "limited");
    await userEvent.click(screen.getByRole("button", { name: "Switch plan" }));
    await waitFor(() => expect(calls.find((c) => c.key.endsWith("/change"))?.body).toEqual({ plan_key: "limited" }));
  });

  it("shows no plan controls to a non-owner", async () => {
    mockApi(subRoutes(sub("ACTIVE")));
    wrap(<SubscriptionPanel isOwner={false} canSeeAdmin={false} />);
    expect(await screen.findByText(/Only the workspace Owner can change the plan/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Cancel subscription" })).toBeNull();
    expect(screen.getByLabelText("Billing contact email")).toBeDisabled();
  });

  it("shows a past-due notice and that features are unavailable, and offers reactivation when cancelled", async () => {
    mockApi(subRoutes(sub("PAST_DUE", ["Your subscription payment is overdue; premium features are paused. Your records are untouched."])));
    wrap(<SubscriptionPanel isOwner canSeeAdmin />);
    expect(await screen.findByText(/payment is overdue/)).toBeInTheDocument();
    expect(screen.getByText(/No: subscription not active/)).toBeInTheDocument();
  });

  it("offers reactivation for a cancelled subscription", async () => {
    mockApi(subRoutes(sub("CANCELLED")));
    wrap(<SubscriptionPanel isOwner canSeeAdmin />);
    expect(await screen.findByRole("button", { name: "Reactivate" })).toBeInTheDocument();
  });

  it("validates the billing contact email before sending", async () => {
    const { calls } = mockApi(subRoutes(sub("ACTIVE")));
    wrap(<SubscriptionPanel isOwner canSeeAdmin />);
    await userEvent.type(await screen.findByLabelText("Billing contact email"), "not-an-email");
    await userEvent.click(screen.getByRole("button", { name: "Save contact" }));
    expect(await screen.findByText(/valid email address/)).toBeInTheDocument();
    expect(calls.some((c) => c.key.startsWith("PUT"))).toBe(false);
  });

  it("distinguishes a billing record from a confirmed payment and never calls a record a tax invoice", async () => {
    mockApi(subRoutes(sub("ACTIVE"), [
      rec({ id: "r1" }),
      rec({ id: "r2", status: "PAID", payment_confirmed: true, provider: "acme", provider_reference: "ref-9", description: "Paid fee" }),
      rec({ id: "r3", status: "PAYMENT_FAILED", description: "Failed fee", payment_attempts: [{ status: "FAILED", provider: "acme", failure_code: "card_declined", event_at: "2026-03-02T00:00:00Z" }] }),
    ]));
    wrap(<SubscriptionPanel isOwner canSeeAdmin />);
    expect(await screen.findByText(/not tax invoices or payment receipts/)).toBeInTheDocument();
    expect(screen.getByText("Not confirmed")).toBeInTheDocument();
    expect(screen.getByText(/Confirmed by acme \(reference ref-9\)/)).toBeInTheDocument();
    expect(screen.getByText(/Payment failed \(card_declined\)/)).toBeInTheDocument();
  });

  it("states honestly that no records exist when no provider is configured", async () => {
    mockApi(subRoutes(sub("ACTIVE")));
    wrap(<SubscriptionPanel isOwner canSeeAdmin />);
    expect(await screen.findByText(/No payment provider is configured, so none is created/)).toBeInTheDocument();
  });

  it("shows the server's entitlement refusal text, not a generic one", async () => {
    mockApi({ ...subRoutes(sub("ACTIVE")), "POST /v1/spaces/b1/finance-subscription/change": () => problem(403, "FORBIDDEN", "Your workspace role cannot change the plan.") });
    wrap(<SubscriptionPanel isOwner canSeeAdmin />);
    await userEvent.selectOptions(await screen.findByLabelText("Switch plan"), "limited");
    await userEvent.click(screen.getByRole("button", { name: "Switch plan" }));
    expect(await screen.findByText(/Your workspace role cannot change the plan/)).toBeInTheDocument();
  });
});

const integ = (key: string, name: string, state: string, message: string, extra: object = {}) => ({ key, name, kind: "X", state, message, health: {}, pending_decisions: [], ...extra });

describe("integrations and administration", () => {
  it("shows unconfigured providers as not configured and failures as failed, with error history", async () => {
    mockApi({
      ...me,
      "GET /v1/spaces/b1/finance-integrations": () => ({ status: 200, body: { items: [
        integ("ai", "AI assistant", "NOT_CONFIGURED", "No AI provider key is set.", { pending_decisions: ["Choose an AI provider"] }),
        integ("accounting", "Accounting", "FAILED", "The last sync failed.", { health: { last_successful_operation_at: null, last_operation_status: "FAILED" } }),
      ] } }),
      "GET /v1/spaces/b1/finance-integrations/accounting/errors": () => ({ status: 200, body: { items: [{ run_id: "x", provider: "ledgerly", status: "FAILED", error_code: "AUTH", error_message: "Token rejected", finished_at: "2026-03-14T00:00:00Z" }] } }),
    });
    wrap(<IntegrationRegistry />);
    expect(await screen.findByText("Not configured")).toBeInTheDocument();
    expect(screen.getByText("Failed")).toBeInTheDocument();
    expect(screen.queryByText("Connected")).toBeNull();
    expect(screen.getByText(/Choose an AI provider/)).toBeInTheDocument();
    expect(await screen.findByText(/AUTH: Token rejected/)).toBeInTheDocument();
  });

  it("shows an error with retry when the registry cannot load", async () => {
    mockApi({
      ...me,
      "GET /v1/spaces/b1/finance-integrations": () => problem(500, "INTERNAL"),
      "GET /v1/spaces/b1/finance-integrations/accounting/errors": () => ({ status: 200, body: { items: [] } }),
    });
    wrap(<IntegrationRegistry />);
    expect(await screen.findByRole("button", { name: "Try again" })).toBeInTheDocument();
  });

  it("labels the admin overview as this workspace only", async () => {
    mockApi({
      ...me,
      "GET /v1/spaces/b1/finance-admin/overview": () => ({ status: 200, body: {
        scope: "WORKSPACE", note: "This is administration of this workspace only; it is not platform administration.",
        subscription: { plan: "standard", plan_name: "Standard", status: "ACTIVE", provisional: true },
        integrations_by_state: { NOT_CONFIGURED: 5 }, open_alerts_by_severity: {}, unread_notifications: 2,
        billing: { provider_status: "NOT_CONFIGURED", open_records: 0, failed_payment_records: 0 },
      } }),
    });
    wrap(<AdminOverviewPanel />);
    expect(await screen.findByText(/not platform administration/)).toBeInTheDocument();
    expect(screen.getByText(/Your unread notifications: 2/)).toBeInTheDocument();
    expect(screen.getByText(/Plan: Standard \(active\), provisional/)).toBeInTheDocument();
  });
});
