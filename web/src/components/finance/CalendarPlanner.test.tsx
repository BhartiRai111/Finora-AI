// Component tests with a mocked fetch: calendar behaviour for given server answers (month navigation, day selection, totals, due markers, adding).
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { FinanceProvider } from "@/lib/FinanceContext";
import { mockApi, problem, renderWithSession, USER } from "@/test-utils";
import { MonthProvider } from "@/lib/month";
import { MonthSelect } from "../shell/MonthSelect";
import { CalendarPlanner, resolveSelected } from "./CalendarPlanner";

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));
afterEach(() => vi.unstubAllGlobals());

const wrap = (ui: React.ReactElement) => renderWithSession(<FinanceProvider space="p1">{ui}</FinanceProvider>);
const tx = (id: string, date: string, amount: string, payee: string, kind = "EXPENSE", currency = "INR", cat: string | null = null) => ({
  id, kind, status: "POSTED", account_id: "a1", to_account_id: null, amount, currency, to_amount: null, to_currency: null, direction: null, category_id: cat,
  refund_of: null, occurred_on: date, payee, note: null, tags: [], version: 0, void_reason: null,
});
const ACCOUNT = { id: "a1", name: "Main", type: "BANK", currency: "INR", opening_balance: "0.00", opening_date: "2026-01-01", balance: "5000.00", liability: false, archived: false, version: 0 };
const commitment = (id: string, name: string, due: string, kind: "BILL" | "SUBSCRIPTION") => ({ id, kind, name, amount: "900.00", currency: "INR", frequency: "MONTHLY", next_due_date: due, overdue: false, status: "ACTIVE", account_id: null, annualized_cost: null, monthly_equivalent: null, version: 0 });

const march = [tx("t1", "2026-03-10", "100.10", "Cafe"), tx("t2", "2026-03-10", "200.20", "Taxi", "EXPENSE", "INR", "c1"), tx("t3", "2026-03-10", "5000.00", "Pay", "INCOME"), tx("t4", "2026-03-12", "9.99", "Snack", "EXPENSE", "USD")];
const routes = (over: Record<string, (i: { body: unknown; headers: Record<string, string> }) => { status: number; body?: unknown }> = {}) => ({
  "GET /v1/me": () => ({ status: 200, body: USER }),
  "GET /v1/spaces/p1/finance-settings": () => ({ status: 200, body: { timezone: "Asia/Kolkata", today: "2026-03-15" } }),
  "GET /v1/spaces/p1/transactions?from=2026-03-01&to=2026-03-31&limit=200": () => ({ status: 200, body: { items: march, next_cursor: null } }),
  "GET /v1/spaces/p1/transactions?from=2026-04-01&to=2026-04-30&limit=200": () => ({ status: 200, body: { items: [tx("t9", "2026-04-02", "40.00", "April thing")], next_cursor: null } }),
  "GET /v1/spaces/p1/categories?include_archived=true": () => ({ status: 200, body: { items: [{ id: "c1", parent_id: null, name: "Transport", kind: "EXPENSE", archived: false, version: 0 }] } }),
  "GET /v1/spaces/p1/bills": () => ({ status: 200, body: { items: [commitment("b1", "Rent", "2026-03-20", "BILL")] } }),
  "GET /v1/spaces/p1/subscriptions": () => ({ status: 200, body: { items: [commitment("s1", "Streaming", "2026-03-20", "SUBSCRIPTION")] } }),
  "GET /v1/spaces/p1/recurring-rules": () => ({ status: 200, body: { items: [] } }),
  ...over,
});

describe("calendar (mocked fetch)", () => {
  it("shows the month from today, exact day totals per currency, and marks days with income and payments due", async () => {
    mockApi(routes());
    wrap(<CalendarPlanner />);
    expect(await screen.findByRole("heading", { name: "March 2026" })).toBeInTheDocument();
    const group = screen.getByRole("group", { name: "Calendar for March 2026" });
    const tenth = await within(group).findByRole("button", { name: /Tuesday, 10 March 2026, 3 transactions, spent INR 300\.30/ });
    expect(tenth).toHaveTextContent("INR 300.30"); // 100.10 + 200.20, added exactly
    expect(within(group).getByRole("button", { name: /12 March 2026, 1 transaction, spent USD 9\.99/ })).toBeInTheDocument(); // currencies are never mixed
    expect(within(group).getByRole("button", { name: /20 March 2026, no transactions, 2 payments due/ })).toBeInTheDocument();
    expect(within(group).getByRole("button", { name: /15 March 2026/ })).toHaveAttribute("aria-pressed", "true"); // today is selected at first
  });

  it("selecting a date lists that day's transactions with categories, and the due items", async () => {
    mockApi(routes());
    wrap(<CalendarPlanner />);
    await userEvent.click(await screen.findByRole("button", { name: /Tuesday, 10 March 2026/ }));
    const panel = screen.getByRole("heading", { name: "Tuesday, 10 March 2026" }).closest("section")!;
    expect(within(panel).getByText(/3 transactions\./)).toBeInTheDocument();
    expect(within(panel).getByText("INR 300.30")).toBeInTheDocument();
    const list = within(panel).getByRole("list", { name: "Transactions on this day" });
    expect(within(list).getByText("Cafe")).toBeInTheDocument();
    expect(within(list).getByText(/Expense · Transport/)).toBeInTheDocument();
    expect(within(list).getByText("+INR 5,000.00")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /20 March 2026/ }));
    const due = screen.getByRole("heading", { name: "Friday, 20 March 2026" }).closest("section")!;
    expect(within(due).getByText("No transactions on this day.")).toBeInTheDocument();
    expect(within(due).getByText(/Rent · INR 900\.00/)).toBeInTheDocument();
    expect(within(due).getByText(/Streaming · INR 900\.00/)).toBeInTheDocument();
  });

  it("changing the month asks the server for that month and replaces the dates and data; Today goes back", async () => {
    const asked: string[] = [];
    mockApi(routes({
      "GET /v1/spaces/p1/transactions?from=2026-04-01&to=2026-04-30&limit=200": () => { asked.push("april"); return { status: 200, body: { items: [tx("t9", "2026-04-02", "40.00", "April thing")], next_cursor: null } }; },
    }));
    wrap(<CalendarPlanner />);
    await screen.findByRole("button", { name: /10 March 2026, 3 transactions/ });
    await userEvent.click(screen.getByRole("button", { name: "Next month" }));
    expect(await screen.findByRole("heading", { name: "April 2026" })).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: /2 April 2026, 1 transaction, spent INR 40\.00/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /10 March 2026, 3 transactions/ })).toBeNull();
    expect(asked).toEqual(["april"]);
    await userEvent.click(screen.getByRole("button", { name: "Today" }));
    expect(await screen.findByRole("heading", { name: "March 2026" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Previous month" }));
    expect(await screen.findByRole("heading", { name: "February 2026" })).toBeInTheDocument();
  });

  it("has an agenda view listing only the days with something on them", async () => {
    mockApi(routes());
    wrap(<CalendarPlanner />);
    await screen.findByRole("button", { name: /10 March 2026, 3 transactions/ });
    await userEvent.click(screen.getByRole("button", { name: "Agenda" }));
    const agenda = screen.getByRole("list", { name: "Agenda for March 2026" });
    expect(within(agenda).getByRole("button", { name: "Tuesday, 10 March 2026" })).toBeInTheDocument();
    expect(within(agenda).getByRole("button", { name: "Thursday, 12 March 2026" })).toBeInTheDocument();
    expect(within(agenda).getByRole("button", { name: "Friday, 20 March 2026" })).toBeInTheDocument();
    expect(within(agenda).queryByRole("button", { name: "Wednesday, 11 March 2026" })).toBeNull();
    expect(within(agenda).getAllByText(/Due/).length).toBeGreaterThanOrEqual(2);
  });

  it("adds an expense dated to the selected day through the real endpoint, then shows it after refetching", async () => {
    let saved = false;
    const bodies: unknown[] = [];
    mockApi(routes({
      "GET /v1/spaces/p1/transactions?from=2026-03-01&to=2026-03-31&limit=200": () => ({ status: 200, body: { items: saved ? [...march, tx("t10", "2026-03-10", "75.00", "New lunch")] : march, next_cursor: null } }),
      "GET /v1/spaces/p1/accounts": () => ({ status: 200, body: { items: [ACCOUNT] } }),
      "GET /v1/spaces/p1/categories": () => ({ status: 200, body: { items: [{ id: "c1", parent_id: null, name: "Transport", kind: "EXPENSE", archived: false, version: 0 }] } }),
      "POST /v1/spaces/p1/transactions": ({ body }) => { bodies.push(body); saved = true; return { status: 201, body: tx("t10", "2026-03-10", "75.00", "New lunch") }; },
    }));
    wrap(<CalendarPlanner />);
    await userEvent.click(await screen.findByRole("button", { name: /Tuesday, 10 March 2026/ }));
    await userEvent.click(screen.getByRole("button", { name: "Add transaction on this date" }));
    const dialog = await screen.findByRole("dialog", { name: "Add a transaction" });
    expect(await within(dialog).findByLabelText("Date")).toHaveValue("2026-03-10");
    await userEvent.type(within(dialog).getByLabelText(/^Amount/), "75.00");
    await userEvent.type(within(dialog).getByLabelText("Description"), "New lunch");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save transaction" }));
    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toMatchObject({ kind: "EXPENSE", account_id: "a1", amount: "75.00", occurred_on: "2026-03-10", payee: "New lunch" });
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(await screen.findByRole("button", { name: /Tuesday, 10 March 2026, 4 transactions, spent INR 375\.30/ })).toBeInTheDocument();
  });

  it("says so when the month cannot be loaded or is too large, and when upcoming payments are unavailable", async () => {
    let fail = true;
    mockApi(routes({
      "GET /v1/spaces/p1/transactions?from=2026-03-01&to=2026-03-31&limit=200": () => (fail ? problem(503, "DOWNSTREAM_UNAVAILABLE") : { status: 200, body: { items: march, next_cursor: null } }),
      "GET /v1/spaces/p1/bills": () => problem(403, "FORBIDDEN"),
    }));
    wrap(<CalendarPlanner />);
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("temporarily unavailable");
    expect(screen.getByText(/Upcoming payments could not be loaded/)).toBeInTheDocument();
    fail = false;
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("button", { name: /10 March 2026, 3 transactions/ })).toBeInTheDocument();
  });

  it("warns when a month has more transactions than were read", async () => {
    mockApi(routes({ "GET /v1/spaces/p1/transactions?from=2026-03-01&to=2026-03-31&limit=200": () => ({ status: 200, body: { items: march, next_cursor: "more" } }), "GET /v1/spaces/p1/transactions?from=2026-03-01&to=2026-03-31&limit=200&cursor=more": () => ({ status: 200, body: { items: [], next_cursor: "more" } }) }));
    wrap(<CalendarPlanner />);
    expect(await screen.findByText(/more transactions than can be shown here/)).toBeInTheDocument();
  });
});

describe("selected day stays in the month on screen", () => {
  const withHeader = () => renderWithSession(<FinanceProvider space="p1"><MonthProvider initial="2026-03"><MonthSelect /><CalendarPlanner /></MonthProvider></FinanceProvider>);
  const headerMonth = () => within(screen.getByRole("group", { name: "Month" }));
  const april = { "GET /v1/spaces/p1/transactions?from=2026-04-01&to=2026-04-30&limit=200": () => ({ status: 200, body: { items: [tx("t9", "2026-04-10", "40.00", "April thing")], next_cursor: null } }) };

  it("moves the selection into the new month when the header selector changes it, and lists that day's transactions", async () => {
    mockApi(routes(april));
    withHeader();
    await userEvent.click(await screen.findByRole("button", { name: /Tuesday, 10 March 2026/ }));
    expect(screen.getByRole("heading", { name: "Tuesday, 10 March 2026" })).toBeInTheDocument();
    await userEvent.click(headerMonth().getByRole("button", { name: "Next month" }));
    expect(await screen.findByRole("heading", { name: "April 2026" })).toBeInTheDocument(); // calendar title
    expect(headerMonth().getByText("April 2026")).toBeInTheDocument(); // header month
    expect(screen.getByRole("group", { name: "Calendar for April 2026" })).toBeInTheDocument(); // grid
    const details = (await screen.findByRole("heading", { name: "Friday, 10 April 2026" })).closest("section")!; // same day number, in April
    expect(await within(details).findByText("April thing")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /10 April 2026/ })).toHaveAttribute("aria-pressed", "true");
    expect(screen.queryByRole("heading", { name: /March 2026$/ })).toBeNull();
  });

  it("uses the last day of a shorter month and carries the day number back, with the calendar arrows and Today in step with the header", async () => {
    mockApi(routes(april));
    withHeader();
    await userEvent.click(await screen.findByRole("button", { name: /Tuesday, 31 March 2026/ }));
    await userEvent.click(headerMonth().getByRole("button", { name: "Next month" }));
    expect(await screen.findByRole("heading", { name: "Thursday, 30 April 2026" })).toBeInTheDocument(); // April has no 31st
    const arrows = screen.getAllByRole("button", { name: "Previous month" }).find((b) => b.closest(".cal-head"))!;
    await userEvent.click(arrows);
    expect(await screen.findByRole("heading", { name: "Tuesday, 31 March 2026" })).toBeInTheDocument();
    expect(headerMonth().getByText("March 2026")).toBeInTheDocument();
    await userEvent.click(headerMonth().getByRole("button", { name: "Next month" }));
    await screen.findByRole("heading", { name: "April 2026" });
    await userEvent.click(screen.getByRole("button", { name: "Today" }));
    expect(await screen.findByRole("heading", { name: "Sunday, 15 March 2026" })).toBeInTheDocument();
    expect(headerMonth().getByText("March 2026")).toBeInTheDocument();
    await userEvent.click(headerMonth().getByRole("button", { name: /This month/ }));
    await waitFor(() => expect(screen.getByRole("group", { name: /^Calendar for / })).toHaveAccessibleName(/Calendar for (?!March 2026)/)); // "This month" is the browser's real current month
  });
});

describe("resolveSelected", () => {
  const cells = (month: string, last: number) => Array.from({ length: last }, (_, i) => ({ date: `${month}-${String(i + 1).padStart(2, "0")}`, inMonth: true }));
  it("keeps a day that is in the month, prefers today in the current month, carries the day number, and clamps to the last day", () => {
    expect(resolveSelected("2026-04-10", "2026-04", "2026-03-15", cells("2026-04", 30))).toBe("2026-04-10");
    expect(resolveSelected("2026-04-10", "2026-03", "2026-03-15", cells("2026-03", 31))).toBe("2026-03-15");
    expect(resolveSelected("2026-03-12", "2026-05", "2026-03-15", cells("2026-05", 31))).toBe("2026-05-12");
    expect(resolveSelected("2026-03-31", "2026-02", "2026-03-15", cells("2026-02", 28))).toBe("2026-02-28");
  });
});
