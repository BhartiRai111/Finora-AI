import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { mockApi, problem, renderWithSession, USER } from "@/test-utils";
import type { Role, Workspace } from "@/lib/types";
import { BusinessApp } from "./BusinessApp";

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));
afterEach(() => vi.unstubAllGlobals());

const ws = (role: Role, type: Workspace["type"] = "BUSINESS"): Workspace => ({ id: "b1", name: "Acme", type, role, membership_id: "m1" });
const SETTINGS = {
  timezone: "Asia/Kolkata", base_currency: "INR", company_name: null, invoice_prefix: "INV-", invoice_padding: 4, invoice_next_number: 3,
  default_terms_days: 30, next_invoice_number_preview: "INV-0003", today: "2026-03-15",
};
const CLIENT = { id: "c1", name: "Acme Traders", email: null, phone: null, gstin: null, address: null, notes: null, payment_terms_days: null, archived: false, version: 0 };
const empty = { items: [], total: 0, limit: 20, offset: 0 };
const invoice = (over: Record<string, unknown> = {}) => ({
  id: "i1", number: "INV-0001", status: "ISSUED", display_status: "UNPAID", overdue: false, days_overdue: 0, client_id: "c1", client_name: "Acme Traders",
  project_id: null, project_name: null, invoice_date: "2026-03-01", due_date: "2026-03-31", currency: "INR", subtotal: "100.00", discount_total: "0.00",
  tax_total: "0.00", total: "100.00", paid: "0.00", outstanding: "100.00", notes: null, cancel_reason: null, version: 1, lines: null, payments: null, ...over,
});

const base = {
  "GET /v1/me": () => ({ status: 200, body: USER }),
  "GET /v1/spaces/b1/clients?limit=100": () => ({ status: 200, body: { items: [CLIENT], total: 1, limit: 100, offset: 0 } }),
  "GET /v1/spaces/b1/vendors?limit=100": () => ({ status: 200, body: empty }),
  "GET /v1/spaces/b1/projects?limit=100": () => ({ status: 200, body: empty }),
  "GET /v1/spaces/b1/expense-categories": () => ({ status: 200, body: { items: [] } }),
  "GET /v1/spaces/b1/business-settings": () => ({ status: 200, body: SETTINGS }),
};

describe("BusinessApp access", () => {
  it("is only for business workspaces", () => {
    mockApi({});
    renderWithSession(<BusinessApp workspace={ws("OWNER", "PERSONAL")} />);
    expect(screen.getByText(/only available in business workspaces/i)).toBeInTheDocument();
  });

  it("shows nothing to a Viewer or Manager and never calls the API", () => {
    const { fn } = mockApi({});
    for (const role of ["VIEWER", "MANAGER"] as Role[]) {
      const { unmount } = renderWithSession(<BusinessApp workspace={ws(role)} />);
      expect(screen.getByText(/does not have access/i)).toBeInTheDocument();
      unmount();
    }
    expect(fn).not.toHaveBeenCalledWith(expect.stringContaining("/v1/spaces/b1"), expect.anything());
  });

  it("gives a Contributor only expenses, and no clients or invoices tabs", async () => {
    mockApi({
      ...base,
      "GET /v1/spaces/b1/expenses?limit=20&offset=0": () => ({ status: 200, body: empty }),
    });
    renderWithSession(<BusinessApp workspace={ws("CONTRIBUTOR")} />);
    expect(await screen.findByRole("tab", { name: "Expenses" })).toBeInTheDocument();
    expect(screen.queryByRole("tab", { name: "Invoices" })).not.toBeInTheDocument();
    expect(screen.queryByRole("tab", { name: "Clients" })).not.toBeInTheDocument();
    expect(await screen.findByText(/No expenses yet/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Record expense" })).toBeInTheDocument();
  });

  it("gives an Auditor read-only lists without create buttons", async () => {
    mockApi({ ...base, "GET /v1/spaces/b1/invoices?limit=20&offset=0": () => ({ status: 200, body: { items: [invoice()], total: 1, limit: 20, offset: 0 } }) });
    renderWithSession(<BusinessApp workspace={ws("AUDITOR")} />);
    await userEvent.click(await screen.findByRole("tab", { name: "Invoices" }));
    expect(await screen.findByText("INV-0001")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "New invoice" })).not.toBeInTheDocument();
  });
});

describe("invoices", () => {
  const listKey = "GET /v1/spaces/b1/invoices?limit=20&offset=0";

  it("lists invoices with written statuses, an empty state and an error state with retry", async () => {
    let fail = true;
    mockApi({
      ...base,
      [listKey]: () => (fail ? problem(503, "UPSTREAM_UNAVAILABLE") : { status: 200, body: { items: [invoice({ display_status: "OVERDUE", overdue: true, days_overdue: 4 }), invoice({ id: "i2", number: "INV-0002", display_status: "PARTIALLY_PAID", paid: "40.00", outstanding: "60.00" })], total: 2, limit: 20, offset: 0 } }),
    });
    renderWithSession(<BusinessApp workspace={ws("ACCOUNTANT")} />);
    await userEvent.click(await screen.findByRole("tab", { name: "Invoices" }));
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    fail = false;
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    const table = within(await screen.findByRole("table"));
    expect(table.getByText("Overdue")).toBeInTheDocument();
    expect(table.getByText("Partially paid")).toBeInTheDocument();
    expect(screen.getByText(/4 days late/)).toBeInTheDocument();
    expect(screen.getByText("INR 60.00")).toBeInTheDocument();
  });

  it("says so when there are no invoices and when filters match nothing", async () => {
    mockApi({ ...base, [listKey]: () => ({ status: 200, body: empty }), "GET /v1/spaces/b1/invoices?status=PAID&limit=20&offset=0": () => ({ status: 200, body: empty }) });
    renderWithSession(<BusinessApp workspace={ws("ACCOUNTANT")} />);
    await userEvent.click(await screen.findByRole("tab", { name: "Invoices" }));
    expect(await screen.findByText(/No invoices yet/)).toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText("Status"), "PAID");
    expect(await screen.findByText(/No invoices match these filters/)).toBeInTheDocument();
  });

  it("pages through results with the offset", async () => {
    const calls: string[] = [];
    mockApi({
      ...base,
      [listKey]: () => { calls.push("p1"); return { status: 200, body: { items: [invoice()], total: 25, limit: 20, offset: 0 } }; },
      "GET /v1/spaces/b1/invoices?limit=20&offset=20": () => ({ status: 200, body: { items: [invoice({ id: "i9", number: "INV-0009" })], total: 25, limit: 20, offset: 20 } }),
    });
    renderWithSession(<BusinessApp workspace={ws("OWNER")} />);
    await userEvent.click(await screen.findByRole("tab", { name: "Invoices" }));
    expect(await screen.findByText("Showing 1–20 of 25")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(await screen.findByText("INV-0009")).toBeInTheDocument();
    expect(screen.getByText("Showing 21–25 of 25")).toBeInTheDocument();
  });

  it("validates a draft in the browser, then sends exact strings with an idempotency key kept across a failed try", async () => {
    const keys: string[] = [];
    const bodies: unknown[] = [];
    let fail = true;
    mockApi({
      ...base,
      [listKey]: () => ({ status: 200, body: empty }),
      "POST /v1/spaces/b1/invoices": ({ headers, body }) => {
        keys.push(headers["Idempotency-Key"] ?? "");
        bodies.push(body);
        return fail ? problem(503, "UPSTREAM_UNAVAILABLE") : { status: 201, body: invoice({ status: "DRAFT", display_status: "DRAFT", number: null, lines: [] }) };
      },
    });
    renderWithSession(<BusinessApp workspace={ws("OWNER")} />);
    await userEvent.click(await screen.findByRole("tab", { name: "Invoices" }));
    await userEvent.click(await screen.findByRole("button", { name: "New invoice" }));
    const form = within(screen.getByRole("form", { name: "New invoice" }));
    await userEvent.click(screen.getByRole("button", { name: "Save draft" }));
    expect(await screen.findByText("Choose a client.")).toBeInTheDocument();
    await userEvent.selectOptions(form.getByLabelText("Client"), "c1");
    await userEvent.click(screen.getByRole("button", { name: "Save draft" }));
    expect(await screen.findByText(/Line 1: add a description/)).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("Description"), "Design work");
    await userEvent.type(screen.getByLabelText(/Unit price/), "10.001");
    await userEvent.click(screen.getByRole("button", { name: "Save draft" }));
    expect(await screen.findByText(/at most 2 decimal places/)).toBeInTheDocument();
    expect(keys).toHaveLength(0);
    await userEvent.clear(screen.getByLabelText(/Unit price/));
    await userEvent.type(screen.getByLabelText(/Unit price/), "33.33");
    await userEvent.click(screen.getByRole("button", { name: "Save draft" }));
    await waitFor(() => expect(keys).toHaveLength(1));
    fail = false;
    await userEvent.click(await screen.findByRole("button", { name: "Save draft" }));
    await waitFor(() => expect(keys).toHaveLength(2));
    expect(keys[1]).toBe(keys[0]);
    expect(bodies[1]).toMatchObject({ client_id: "c1", currency: "INR", lines: [{ description: "Design work", quantity: "1", unit_price: "33.33", discount: "0", tax_percent: "0" }] });
    expect(await screen.findByText(/Draft saved/)).toBeInTheDocument();
  });

  it("issues only after an explicit confirmation and sends the expected total", async () => {
    let issueBody: unknown = null;
    mockApi({
      ...base,
      [listKey]: () => ({ status: 200, body: { items: [invoice({ status: "DRAFT", display_status: "DRAFT", number: null, outstanding: "0.00" })], total: 1, limit: 20, offset: 0 } }),
      "GET /v1/spaces/b1/invoices/i1": () => ({ status: 200, body: invoice({ status: "DRAFT", display_status: "DRAFT", number: null, outstanding: "0.00", lines: [] }) }),
      "POST /v1/spaces/b1/invoices/i1/issue": ({ body }) => { issueBody = body; return { status: 200, body: invoice({ lines: [] }) }; },
    });
    renderWithSession(<BusinessApp workspace={ws("ACCOUNTANT")} />);
    await userEvent.click(await screen.findByRole("tab", { name: "Invoices" }));
    await userEvent.click(await screen.findByRole("button", { name: /Draft/ }));
    await userEvent.click(await screen.findByRole("button", { name: "Issue invoice" }));
    await userEvent.click(screen.getByRole("button", { name: "Issue invoice now" }));
    expect(await screen.findByText(/Tick the box to confirm/)).toBeInTheDocument();
    expect(issueBody).toBeNull();
    await userEvent.click(screen.getByLabelText(/I confirm I want to issue/));
    await userEvent.click(screen.getByRole("button", { name: "Issue invoice now" }));
    await waitFor(() => expect(issueBody).toEqual({ confirm: true, expected_total: "100.00" }));
    expect(await screen.findByText(/Invoice INV-0001 issued/)).toBeInTheDocument();
  });

  it("records a payment against an invoice and shows the server's refusal when it is too large", async () => {
    let attempt = 0;
    mockApi({
      ...base,
      [listKey]: () => ({ status: 200, body: { items: [invoice()], total: 1, limit: 20, offset: 0 } }),
      "GET /v1/spaces/b1/invoices/i1": () => ({ status: 200, body: invoice({ lines: [], payments: [] }) }),
      "POST /v1/spaces/b1/payments": ({ body }) => {
        attempt += 1;
        if (attempt === 1) return { status: 422, body: { code: "ALLOCATION_EXCEEDS", detail: "Invoice INV-0001 has only 100.00 INR outstanding", request_id: "r" } };
        expect(body).toMatchObject({ client_id: "c1", amount: "40.00", currency: "INR", allocations: [{ invoice_id: "i1", amount: "40.00" }] });
        return { status: 201, body: { id: "p1" } };
      },
    });
    renderWithSession(<BusinessApp workspace={ws("OWNER")} />);
    await userEvent.click(await screen.findByRole("tab", { name: "Invoices" }));
    await userEvent.click(await screen.findByRole("button", { name: /INV-0001/ }));
    await userEvent.click(await screen.findByRole("button", { name: "Record payment" }));
    const form = screen.getByRole("form", { name: /Record a payment for INV-0001/ });
    await userEvent.clear(within(form).getByLabelText("Amount received"));
    await userEvent.type(within(form).getByLabelText("Amount received"), "140.00");
    await userEvent.click(within(form).getByRole("button", { name: "Record payment" }));
    expect(await screen.findByText(/only 100.00 INR outstanding/)).toBeInTheDocument();
    await userEvent.clear(within(form).getByLabelText("Amount received"));
    await userEvent.type(within(form).getByLabelText("Amount received"), "40.00");
    await userEvent.clear(within(form).getByLabelText(/Amount to apply/));
    await userEvent.type(within(form).getByLabelText(/Amount to apply/), "40.00");
    await userEvent.click(within(form).getByRole("button", { name: "Record payment" }));
    await waitFor(() => expect(attempt).toBe(2));
  });
});

describe("summary, clients and settings", () => {
  it("shows backend figures per currency, the aging table and an empty state", async () => {
    mockApi({
      ...base,
      "GET /v1/spaces/b1/business-summaries/overview": () => ({
        status: 200,
        body: {
          from: "2026-03-01", to: "2026-03-15", as_of: "2026-03-15",
          currencies: [{
            currency: "INR", invoiced_net: "1200.00", invoiced_tax: "180.00", invoiced_total: "1380.00", invoice_count: 2, received: "525.00", expenses: "350.00",
            net_cash: "175.00", operating_result: "850.00", receivables: "980.00", overdue: "300.00", unallocated_payments: "25.00",
            aging: [{ label: "Not yet due", amount: "680.00" }, { label: "1-30 days overdue", amount: "300.00" }], expenses_by_category: [{ category: "Materials", amount: "300.00" }],
          }],
        },
      }),
      "GET /v1/spaces/b1/business-summaries/projects": () => ({ status: 200, body: { items: [] } }),
    });
    renderWithSession(<BusinessApp workspace={ws("OWNER")} />);
    expect(await screen.findByText("INR 1,200.00")).toBeInTheDocument();
    expect(screen.getByText(/Net cash INR 175.00/)).toBeInTheDocument();
    expect(screen.getByText("INR 680.00")).toBeInTheDocument();
    expect(screen.getByText("No projects yet.")).toBeInTheDocument();
    expect(screen.getByText(/nothing is converted/i)).toBeInTheDocument();
  });

  it("validates and creates a client with a key, and shows the duplicate-name refusal", async () => {
    let attempt = 0;
    mockApi({
      ...base,
      "GET /v1/spaces/b1/clients?limit=20&offset=0": () => ({ status: 200, body: empty }),
      "POST /v1/spaces/b1/clients": ({ headers }) => {
        attempt += 1;
        expect(headers["Idempotency-Key"]).toBeTruthy();
        return problem(409, "CONFLICT", "A client with this name already exists");
      },
    });
    renderWithSession(<BusinessApp workspace={ws("ACCOUNTANT")} />);
    await userEvent.click(await screen.findByRole("tab", { name: "Clients" }));
    expect(await screen.findByText(/No clients yet/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Add client" }));
    expect(await screen.findByText(/Enter the client's name/)).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("Name"), "Acme");
    await userEvent.click(screen.getByRole("button", { name: "Add client" }));
    expect(await screen.findByText(/already exists/)).toBeInTheDocument();
    expect(attempt).toBe(1);
  });

  it("lets only an Owner or Admin change settings", async () => {
    mockApi({ ...base });
    renderWithSession(<BusinessApp workspace={ws("ACCOUNTANT")} />);
    await userEvent.click(await screen.findByRole("tab", { name: "Settings" }));
    expect(await screen.findByText(/Next invoice number:/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Invoice number prefix/)).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Save settings" })).not.toBeInTheDocument();
  });
});
