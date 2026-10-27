import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { mockApi, problem, renderWithSession, USER } from "@/test-utils";
import { FinanceApp } from "./FinanceApp";

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));
afterEach(() => vi.unstubAllGlobals());

const PERSONAL = { id: "p1", name: "Asha", type: "PERSONAL", role: "OWNER", membership_id: "m1" };
const ACCOUNT = { id: "a1", name: "Salary", type: "BANK", currency: "INR", opening_balance: "0.00", opening_date: "2026-01-01", balance: "1234567.50", liability: false, archived: false, version: 0 };
const CARD = { ...ACCOUNT, id: "a2", name: "Card", type: "CREDIT_CARD", balance: "500.00", liability: true };
const tx = (id: string, amount: string) => ({
  id, kind: "EXPENSE", status: "POSTED", account_id: "a1", to_account_id: null, amount, currency: "INR", to_amount: null, to_currency: null,
  direction: null, category_id: null, refund_of: null, occurred_on: "2026-03-10", payee: "Cafe", note: null, tags: [], version: 0, void_reason: null,
});

const base = {
  "GET /v1/me": () => ({ status: 200, body: USER }),
  "GET /v1/workspaces": () => ({ status: 200, body: { items: [{ id: "b1", name: "Acme", type: "BUSINESS", role: "ADMIN", membership_id: "m0" }, PERSONAL] } }),
  "GET /v1/spaces/p1/accounts?include_archived=true": () => ({ status: 200, body: { items: [ACCOUNT, CARD] } }),
  "GET /v1/spaces/p1/categories?include_archived=true": () => ({ status: 200, body: { items: [] } }),
};

describe("FinanceApp", () => {
  it("tells a user without a personal profile that finance is unavailable", async () => {
    mockApi({ ...base, "GET /v1/workspaces": () => ({ status: 200, body: { items: [{ id: "b1", name: "Acme", type: "BUSINESS", role: "ADMIN", membership_id: "m0" }] } }) });
    renderWithSession(<FinanceApp />);
    expect(await screen.findByText(/no personal profile/i)).toBeInTheDocument();
  });

  it("uses the personal workspace, never a business one, and shows liabilities as amounts owed", async () => {
    const { calls } = mockApi({
      ...base,
      "GET /v1/spaces/p1/finance-settings": () => ({ status: 200, body: { timezone: "Asia/Kolkata", today: "2026-03-15" } }),
      "GET /v1/spaces/p1/net-worth": () => ({ status: 200, body: { currencies: [{ currency: "INR", assets: "1234567.50", liabilities: "500.00", net_worth: "1234067.50", components: [] }] } }),
      "GET /v1/spaces/p1/summaries/cashflow?from=2026-03-01&to=2026-03-15": () => ({ status: 200, body: { currencies: [] } }),
      "GET /v1/spaces/p1/budgets?month=2026-03": () => ({ status: 200, body: { items: [] } }),
      "GET /v1/spaces/p1/bills?due_within_days=14": () => ({ status: 200, body: { items: [] } }),
      "GET /v1/spaces/p1/goals": () => ({ status: 200, body: { items: [] } }),
    });
    renderWithSession(<FinanceApp />);
    expect(await screen.findByText("INR 12,34,067.50")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("tab", { name: "Accounts" }));
    expect(await screen.findByText(/you owe/i)).toBeInTheDocument();
    expect(calls.some((c) => c.key.includes("/b1/"))).toBe(false);
  });

  it("lists transactions, loads more with the cursor, and reuses the idempotency key after a failed create", async () => {
    const posted: string[] = [];
    let fail = true;
    mockApi({
      ...base,
      "GET /v1/spaces/p1/transactions?limit=25": () => ({ status: 200, body: { items: [tx("t1", "100.10")], next_cursor: "c1" } }),
      "GET /v1/spaces/p1/transactions?limit=25&cursor=c1": () => ({ status: 200, body: { items: [tx("t2", "200.20")], next_cursor: null } }),
      "POST /v1/spaces/p1/transactions": ({ headers }) => {
        posted.push(headers["Idempotency-Key"] ?? "");
        if (fail) return problem(503, "DOWNSTREAM_UNAVAILABLE");
        return { status: 201, body: tx("t3", "50.00") };
      },
    });
    renderWithSession(<FinanceApp />);
    await userEvent.click(await screen.findByRole("tab", { name: "Transactions" }));
    expect(await screen.findByText(/INR 100\.10/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /load more/i }));
    expect(await screen.findByText(/INR 200\.20/)).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText(/^Amount \(INR\)/), "50.00");
    await userEvent.click(screen.getByRole("button", { name: "Save transaction" }));
    await waitFor(() => expect(posted).toHaveLength(1));
    fail = false;
    await userEvent.click(screen.getByRole("button", { name: "Save transaction" }));
    await waitFor(() => expect(posted).toHaveLength(2));
    expect(posted[0]).toBeTruthy();
    expect(posted[1]).toBe(posted[0]);
  });
});
