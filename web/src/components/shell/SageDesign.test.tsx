// Component tests with a mocked fetch for the header tools and sidebar summary added by the sage redesign.
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SidebarSummary } from "@/components/finance/SidebarSummary";
import { FinanceProvider } from "@/lib/FinanceContext";
import { MonthProvider } from "@/lib/month";
import { mockApi, renderWithSession, USER } from "@/test-utils";
import HelpPage from "@/app/help/page";
import { MonthSelect } from "./MonthSelect";

const push = vi.hoisted(() => vi.fn());
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }), usePathname: () => "/finance/overview" }));
beforeEach(() => push.mockClear());
afterEach(() => vi.unstubAllGlobals());

describe("month selector", () => {
  it("moves the shared month back and forward and names it", async () => {
    render(<MonthProvider initial="2026-01"><MonthSelect /></MonthProvider>);
    expect(screen.getByText("January 2026")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Previous month" }));
    expect(screen.getByText("December 2025")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Next month" }));
    await userEvent.click(screen.getByRole("button", { name: "Next month" }));
    expect(screen.getByText("February 2026")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /This month/ }));
    expect(screen.queryByText("February 2026")).toBeNull();
  });
});

describe("sidebar summary", () => {
  it("lists the person's own accounts without claiming a bank link, and counts budget states", async () => {
    mockApi({
      "GET /v1/me": () => ({ status: 200, body: USER }),
      "GET /v1/spaces/p1/accounts": () => ({ status: 200, body: { items: [{ id: "a1", name: "Salary account", type: "BANK", currency: "INR", opening_balance: "0.00", opening_date: "2026-01-01", balance: "100.00", liability: false, archived: false, version: 0 }] } }),
      "GET /v1/spaces/p1/budgets?month=2026-03": () => ({ status: 200, body: { items: [
        { id: "1", category_id: "c", category_name: "Food", amount: "10.00", currency: "INR", spent: "9.00", remaining: "1.00", percent_used: "90.00", status: "WARNING", version: 0 },
        { id: "2", category_id: "d", category_name: "Fun", amount: "10.00", currency: "INR", spent: "1.00", remaining: "9.00", percent_used: "10.00", status: "UNDER", version: 0 }] } }),
    });
    renderWithSession(<FinanceProvider space="p1"><MonthProvider initial="2026-03"><SidebarSummary /></MonthProvider></FinanceProvider>);
    expect(await screen.findByText("Salary account")).toBeInTheDocument();
    expect(screen.getByText("INR 100.00")).toBeInTheDocument();
    expect(screen.getByText("Added by you; not linked to a bank.")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText("Approaching the limit").nextSibling).toHaveTextContent("1"));
    expect(screen.getByText("On track").nextSibling).toHaveTextContent("1");
    expect(screen.getByText("Over budget").nextSibling).toHaveTextContent("0");
  });
});

describe("help center", () => {
  it("is real guidance with links, and says what Finora does not do", () => {
    render(<HelpPage />);
    expect(screen.getByRole("heading", { level: 1, name: "How Finora AI works" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open the budget planner" })).toHaveAttribute("href", "/finance/budgets");
    expect(screen.getByText(/does not connect to banks/i)).toBeInTheDocument();
  });
});
