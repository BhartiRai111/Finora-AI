// Component tests with a mocked fetch for the redesigned charts, budget planner and transaction list.
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { FinanceProvider } from "@/lib/FinanceContext";
import type { Account, Category } from "@/lib/finance";
import { mockApi, renderWithSession, USER } from "@/test-utils";
import { BudgetsPanel } from "./finance/BudgetsPanel";
import { TransactionsPanel } from "./finance/TransactionsPanel";
import { BarChart, DonutChart, LineChart, ProgressBar } from "./ui/BarChart";

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));
afterEach(() => vi.unstubAllGlobals());

const me = { "GET /v1/me": () => ({ status: 200, body: USER }) };
const wrap = (ui: React.ReactElement) => renderWithSession(<FinanceProvider space="p1">{ui}</FinanceProvider>);

describe("charts", () => {
  const series = [{ key: "a", label: "Income", values: ["1000.10", "2000.20"] }, { key: "b", label: "Expenses", values: ["300.00", "-50.00"] }];

  it("show a tooltip with the exact figures of the hovered period, and hide it again", () => {
    render(<BarChart title="T" currency="INR" labels={["Jan", "Feb"]} series={series} summary="Bars" />);
    const hits = document.querySelectorAll("rect[data-hit]");
    fireEvent.mouseEnter(hits[1]!.parentElement!);
    const tip = document.querySelector(".chart-tip")!;
    expect(tip).toHaveTextContent("Feb");
    expect(tip).toHaveTextContent("Income: INR 2,000.20");
    expect(tip).toHaveTextContent("Expenses: INR -50.00");
    fireEvent.mouseLeave(document.querySelector("svg")!);
    expect(document.querySelector(".chart-tip")).toBeNull();
  });

  it("line chart has one labelled point per period, exact titles and a description for assistive technology", () => {
    render(<LineChart title="Trend" currency="INR" labels={["Jan", "Feb", "Mar"]} series={[{ key: "x", label: "Expenses", values: ["10.00", "20.50", "15.25"] }]} summary="Line chart in INR: expenses per month." />);
    expect(screen.getByRole("img", { name: "Line chart in INR: expenses per month." })).toBeInTheDocument();
    const points = document.querySelectorAll("circle[data-point]");
    expect(points).toHaveLength(3);
    expect(points[1]!.querySelector("title")!.textContent).toBe("Feb: Expenses INR 20.50");
    expect(screen.getByRole("list", { name: "Legend" })).toHaveTextContent("Expenses");
  });

  it("donut lists each slice's exact amount and rounded share and shows the exact total in the middle", () => {
    render(<DonutChart title="Cats" currency="INR" total="400.00" summary="Donut" slices={[{ key: "a", label: "Food", amount: "300.00" }, { key: "b", label: "Fun", amount: "100.00" }]} />);
    const list = screen.getByRole("list", { name: "Categories" });
    expect(within(list).getByText("INR 300.00 · 75%")).toBeInTheDocument();
    expect(within(list).getByText("INR 100.00 · 25%")).toBeInTheDocument();
    expect(screen.getByText("INR 400.00")).toBeInTheDocument();
    expect(document.querySelectorAll("circle[data-slice]")).toHaveLength(2);
  });

  it("progress bar is a labelled progressbar clamped to 0-100", () => {
    render(<><ProgressBar percent={140} tone="over" label="Food budget used" /><ProgressBar percent={-5} label="Empty" /></>);
    const bars = screen.getAllByRole("progressbar");
    expect(bars[0]).toHaveAttribute("aria-valuenow", "100");
    expect(bars[0]).toHaveAccessibleName("Food budget used");
    expect(bars[1]).toHaveAttribute("aria-valuenow", "0");
  });
});

describe("budget planner (mocked fetch)", () => {
  const item = (id: string, name: string | null, amount: string, spent: string, remaining: string, pct: string, status: string) => ({ id, category_id: name ? `c${id}` : null, category_name: name, amount, currency: "INR", spent, remaining, percent_used: pct, status, version: 0 });
  const CATS: Category[] = [{ id: "c1", parent_id: null, name: "Food", kind: "EXPENSE", archived: false, version: 0 }];

  it("totals the month exactly, states each budget's state in words and moves between months", async () => {
    const months: string[] = [];
    const month = new Date().toISOString().slice(0, 7);
    const next = (() => { const [y, m] = month.split("-").map(Number); const d = new Date(Date.UTC(y!, m!, 1)); return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, "0")}`; })();
    mockApi({
      ...me,
      [`GET /v1/spaces/p1/budgets?month=${month}`]: () => { months.push(month); return { status: 200, body: { month, items: [item("1", "Food", "1000.10", "800.05", "200.05", "80.00", "WARNING"), item("2", "Fun", "100.00", "100.01", "-0.01", "100.01", "OVER")] } }; },
      [`GET /v1/spaces/p1/budgets?month=${next}`]: () => { months.push(next); return { status: 200, body: { month: next, items: [] } }; },
    });
    wrap(<BudgetsPanel categories={CATS} />);
    const totals = await screen.findByLabelText("Budget totals");
    expect(within(totals).getByText("INR 200.04 left")).toBeInTheDocument();
    expect(within(totals).getByText("INR 900.06 spent of INR 1,100.10")).toBeInTheDocument();
    expect(screen.getByText(/Close to the limit — INR 200.05 left/)).toBeInTheDocument();
    expect(screen.getByText(/Over budget — INR -0.01 left/)).toBeInTheDocument();
    expect(screen.getAllByRole("progressbar").length).toBe(3);
    fireEvent.change(screen.getByLabelText("Month"), { target: { value: next } });
    expect(await screen.findByText(/No budgets yet/)).toBeInTheDocument();
    expect(months).toEqual([month, next]);
  });
});

describe("transaction list (mocked fetch)", () => {
  const ACCOUNT: Account = { id: "a1", name: "Main", type: "BANK", currency: "INR", opening_balance: "0.00", opening_date: "2026-01-01", balance: "0.00", liability: false, archived: false, version: 0 };
  const tx = (id: string, date: string, amount: string, payee: string) => ({ id, kind: "EXPENSE", status: "POSTED", account_id: "a1", to_account_id: null, amount, currency: "INR", to_amount: null, to_currency: null, direction: null, category_id: null, refund_of: null, occurred_on: date, payee, note: null, tags: [], version: 0, void_reason: null });
  const rows = [tx("t1", "2026-03-10", "100.00", "Cafe"), tx("t2", "2026-03-09", "9.50", "Bus"), tx("t3", "2026-03-08", "2000.00", "Rent")];

  it("sorts the loaded rows by amount without comparing them as floating point, and shows the payee", async () => {
    mockApi({ ...me, "GET /v1/spaces/p1/transactions?limit=25": () => ({ status: 200, body: { items: rows, next_cursor: null } }) });
    wrap(<TransactionsPanel accounts={[ACCOUNT]} categories={[]} onChanged={() => undefined} />);
    expect(await screen.findByText("Cafe")).toBeInTheDocument();
    const order = () => within(screen.getByRole("table")).getAllByRole("row").slice(1).map((r) => r.querySelector("strong")?.textContent);
    expect(order()).toEqual(["Cafe", "Bus", "Rent"]);
    await userEvent.click(screen.getByRole("button", { name: /^Amount/ }));
    expect(order()).toEqual(["Rent", "Cafe", "Bus"]);
    expect(screen.getByRole("columnheader", { name: /Amount/ })).toHaveAttribute("aria-sort", "descending");
    await userEvent.click(screen.getByRole("button", { name: /^Amount/ }));
    expect(order()).toEqual(["Bus", "Cafe", "Rent"]);
    await userEvent.click(screen.getByRole("button", { name: /^Date/ }));
    expect(screen.getByRole("columnheader", { name: /Date/ })).toHaveAttribute("aria-sort", "descending");
    expect(order()).toEqual(["Cafe", "Bus", "Rent"]);
  });

  it("voids only after a confirmation dialog, sends the reason, and keeps the row if the server refuses", async () => {
    const bodies: unknown[] = [];
    let refuse = true;
    mockApi({
      ...me,
      "GET /v1/spaces/p1/transactions?limit=25": () => ({ status: 200, body: { items: rows, next_cursor: null } }),
      "POST /v1/spaces/p1/transactions/t1/void": ({ body }) => { bodies.push(body); return refuse ? { status: 409, body: { code: "CONFLICT", detail: "Already changed" } } : { status: 200, body: {} }; },
    });
    wrap(<TransactionsPanel accounts={[ACCOUNT]} categories={[]} onChanged={() => undefined} />);
    await screen.findByText("Cafe");
    await userEvent.click(within(screen.getAllByRole("row")[1]!).getByRole("button", { name: "Void" }));
    expect(bodies).toHaveLength(0);
    const dialog = screen.getByRole("dialog", { name: "Void this transaction" });
    await userEvent.type(within(dialog).getByLabelText(/Reason/), "duplicate");
    await userEvent.click(within(dialog).getByRole("button", { name: "Void transaction" }));
    expect(await within(dialog).findByText("Already changed")).toBeInTheDocument();
    expect(screen.getByText("Cafe")).toBeInTheDocument();
    refuse = false;
    await userEvent.click(within(dialog).getByRole("button", { name: "Void transaction" }));
    await waitFor(() => expect(bodies).toHaveLength(2));
    expect(bodies[1]).toEqual({ reason: "duplicate" });
  });
});
