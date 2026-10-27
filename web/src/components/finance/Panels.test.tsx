import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { FinanceProvider } from "@/lib/FinanceContext";
import { mockApi, renderWithSession, USER } from "@/test-utils";
import { BudgetsPanel } from "./BudgetsPanel";
import { CommitmentsPanel } from "./CommitmentsPanel";

afterEach(() => vi.unstubAllGlobals());
vi.useRealTimers();

const ACCOUNT = { id: "a1", name: "Salary", type: "BANK", currency: "INR", opening_balance: "0.00", opening_date: "2026-01-01", balance: "5000.00", liability: false, archived: false, version: 0 };
const month = new Date().toISOString().slice(0, 7);

describe("finance panels", () => {
  it("shows budget status in words, not only colour, and the exact spent amounts", async () => {
    mockApi({
      "GET /v1/me": () => ({ status: 200, body: USER }),
      [`GET /v1/spaces/p1/budgets?month=${month}`]: () => ({
        status: 200,
        body: { month, items: [
          { id: "b1", category_id: "c1", category_name: "Food", amount: "1000.00", currency: "INR", spent: "800.00", remaining: "200.00", percent_used: "80.00", status: "WARNING", version: 0 },
          { id: "b2", category_id: "c2", category_name: "Fun", amount: "100.00", currency: "INR", spent: "100.01", remaining: "-0.01", percent_used: "100.01", status: "OVER", version: 0 },
        ] },
      }),
    });
    renderWithSession(<FinanceProvider space="p1"><BudgetsPanel categories={[]} /></FinanceProvider>);
    expect(await screen.findByText(/Close to the limit/)).toBeInTheDocument();
    expect(screen.getByText(/Over budget/)).toBeInTheDocument();
  });

  it("marks a bill paid from a chosen account with an idempotency key", async () => {
    const keys: string[] = [];
    const bills = { items: [{ id: "x1", kind: "BILL", name: "Rent", amount: "900.00", currency: "INR", frequency: "MONTHLY", next_due_date: "2026-03-20", overdue: false, status: "ACTIVE", account_id: null, annualized_cost: "10800.00", monthly_equivalent: "900.00", version: 0 }], totals: [] };
    mockApi({
      "GET /v1/me": () => ({ status: 200, body: USER }),
      "GET /v1/spaces/p1/bills": () => ({ status: 200, body: bills }),
      "POST /v1/spaces/p1/bills/x1/pay": ({ headers, body }) => {
        keys.push(headers["Idempotency-Key"] ?? "");
        expect(body).toMatchObject({ account_id: "a1" });
        return { status: 201, body: { transaction_id: "t" } };
      },
    });
    renderWithSession(<FinanceProvider space="p1"><CommitmentsPanel kind="BILL" accounts={[ACCOUNT as never]} /></FinanceProvider>);
    await userEvent.click(await screen.findByRole("button", { name: "Mark paid" }));
    await userEvent.selectOptions(screen.getByLabelText("Paid from"), "a1");
    await userEvent.click(screen.getByRole("button", { name: "Confirm payment" }));
    await waitFor(() => expect(keys).toHaveLength(1));
    expect(keys[0]).toBeTruthy();
  });
});
