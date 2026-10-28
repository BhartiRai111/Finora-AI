// Component tests with a mocked fetch for the top bar, sidebar and add-expense dialog.
import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { mockApi, problem, renderWithSession, USER } from "@/test-utils";
import { Header } from "../Header";
import { AddExpenseDialog } from "../finance/AddExpenseDialog";
import { AppShell } from "./AppShell";

const nav = vi.hoisted(() => ({ push: vi.fn(), pathname: "/finance/overview" }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: nav.push }), usePathname: () => nav.pathname }));
beforeEach(() => { nav.push.mockClear(); nav.pathname = "/finance/overview"; window.localStorage.clear(); document.documentElement.dataset.theme = "dark"; });
afterEach(() => vi.unstubAllGlobals());

const me = { "GET /v1/me": () => ({ status: 200, body: USER }) };
const WS = { items: [{ id: "p1", name: "Asha", type: "PERSONAL", role: "OWNER", membership_id: "m" }, { id: "b1", name: "Acme", type: "BUSINESS", role: "VIEWER", membership_id: "m2" }] };
const note = (id: string, read: boolean) => ({ id, category: "IMPORT", severity: "INFO", title: `Import ${id}`, body: "2 committed", metadata: {}, read, created_at: "2026-03-15T10:00:00Z" });
const ACCOUNT = { id: "a1", name: "Main", type: "BANK", currency: "INR", opening_balance: "0.00", opening_date: "2026-01-01", balance: "5000.00", liability: false, archived: false, version: 0 };
const base = { ...me, "GET /v1/workspaces": () => ({ status: 200, body: WS }) };

describe("notifications in the top bar", () => {
  it("shows the unread count from the server, lists the latest, and marks all read through the server", async () => {
    let unread = 2;
    const { calls } = mockApi({
      ...base,
      "GET /v1/spaces/p1/finance-notifications?limit=5": () => ({ status: 200, body: { items: [note("n1", unread === 0), note("n2", unread === 0)], total: 2, unread, limit: 5, offset: 0 } }),
      "POST /v1/spaces/p1/finance-notifications/read-all": () => { unread = 0; return { status: 200, body: {} }; },
    });
    renderWithSession(<Header />);
    const bell = await screen.findByRole("button", { name: "Notifications, 2 unread" });
    await userEvent.click(bell);
    const panel = screen.getByRole("dialog", { name: "Notifications" });
    expect(within(panel).getByText("Import n1")).toBeInTheDocument();
    expect(within(panel).getByText(/Notifications appear in the app only/)).toBeInTheDocument();
    expect(within(panel).getByRole("link", { name: "View all" })).toHaveAttribute("href", "/finance/notifications");
    await userEvent.click(within(panel).getByRole("button", { name: "Mark all as read" }));
    await waitFor(() => expect(calls.some((c) => c.key.endsWith("/read-all"))).toBe(true));
    expect(await screen.findByRole("button", { name: "Notifications" })).toBeInTheDocument();
    expect(within(panel).getByRole("button", { name: "Mark all as read" })).toBeDisabled();
    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("dialog", { name: "Notifications" })).toBeNull();
  });

  it("explains, instead of inventing a count, when the role may not read notifications", async () => {
    nav.pathname = "/workspaces/b1/overview";
    mockApi({ ...base, "GET /v1/spaces/b1/finance-notifications?limit=5": () => problem(403, "FORBIDDEN") });
    renderWithSession(<Header />);
    await userEvent.click(await screen.findByRole("button", { name: "Notifications" }));
    expect(await screen.findByText(/does not include notifications/)).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "View all" })).toBeNull();
  });
});

describe("quick search and add expense", () => {
  it("filters the sections this person can open and navigates with Enter; Ctrl+K opens it anywhere", async () => {
    mockApi({ ...base, "GET /v1/spaces/p1/finance-notifications?limit=5": () => ({ status: 200, body: { items: [], total: 0, unread: 0, limit: 5, offset: 0 } }) });
    renderWithSession(<Header />);
    await screen.findByRole("button", { name: "Search and quick actions" });
    await userEvent.keyboard("{Control>}k{/Control}");
    const dialog = await screen.findByRole("dialog", { name: "Search and quick actions" });
    const box = within(dialog).getByRole("combobox");
    expect(box).toHaveFocus();
    await userEvent.type(box, "cale");
    const options = within(dialog).getAllByRole("option");
    expect(options).toHaveLength(1);
    expect(options[0]).toHaveTextContent("Calendar");
    await userEvent.keyboard("{Enter}");
    expect(nav.push).toHaveBeenCalledWith("/finance/calendar");
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("offers only what a restricted role can open, and says so when nothing matches", async () => {
    nav.pathname = "/workspaces/b1/workspace";
    mockApi({ ...base, "GET /v1/spaces/b1/finance-notifications?limit=5": () => problem(403, "FORBIDDEN") });
    renderWithSession(<Header />);
    await userEvent.click(await screen.findByRole("button", { name: "Search and quick actions" }));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).queryByRole("option", { name: /Invoices/ })).toBeNull();
    expect(within(dialog).getByRole("option", { name: /^Workspace\s*Workspace$/ })).toBeInTheDocument();
    await userEvent.type(within(dialog).getByRole("combobox"), "zzzz");
    expect(within(dialog).getByText(/Nothing matches/)).toBeInTheDocument();
  });

  it("opens the add-expense form from the top bar and saves through the transactions endpoint", async () => {
    const bodies: unknown[] = [];
    mockApi({
      ...base,
      "GET /v1/spaces/p1/finance-notifications?limit=5": () => ({ status: 200, body: { items: [], total: 0, unread: 0, limit: 5, offset: 0 } }),
      "GET /v1/spaces/p1/accounts": () => ({ status: 200, body: { items: [ACCOUNT] } }),
      "GET /v1/spaces/p1/categories": () => ({ status: 200, body: { items: [] } }),
      "POST /v1/spaces/p1/transactions": ({ body }) => { bodies.push(body); return { status: 201, body: {} }; },
    });
    renderWithSession(<Header />);
    await userEvent.click(await screen.findByRole("button", { name: /Add expense/ }));
    const dialog = await screen.findByRole("dialog", { name: "Add an expense" });
    await userEvent.type(await within(dialog).findByLabelText(/^Amount/), "12.50");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save expense" }));
    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toMatchObject({ kind: "EXPENSE", account_id: "a1", amount: "12.50" });
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  });
});

describe("add expense form", () => {
  const open = (extra: Record<string, () => { status: number; body?: unknown }> = {}) => {
    const api = mockApi({
      ...me,
      "GET /v1/spaces/p1/accounts": () => ({ status: 200, body: { items: [ACCOUNT] } }),
      "GET /v1/spaces/p1/categories": () => ({ status: 200, body: { items: [{ id: "c1", parent_id: null, name: "Food", kind: "EXPENSE", archived: false, version: 0 }, { id: "c2", parent_id: null, name: "Salary", kind: "INCOME", archived: false, version: 0 }] } }),
      ...extra,
    });
    renderWithSession(<AddExpenseDialog open space="p1" onClose={() => undefined} />);
    return api;
  };

  it("validates the amount and the date before anything is sent, and offers expense categories only", async () => {
    const { calls } = open();
    const dialog = await screen.findByRole("dialog");
    const amount = await within(dialog).findByLabelText(/^Amount/);
    await userEvent.click(within(dialog).getByRole("button", { name: "Save expense" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("Enter an amount like 1250.50.");
    await userEvent.type(amount, "10.999");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save expense" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("INR allows at most 2 decimal places.");
    expect(within(dialog).getByRole("option", { name: "Food" })).toBeInTheDocument();
    expect(within(dialog).queryByRole("option", { name: "Salary" })).toBeNull();
    expect(calls.some((c) => c.key.startsWith("POST"))).toBe(false);
  });

  it("reuses one idempotency key when a failed save is retried, and does not report success until the server confirms", async () => {
    const keys: string[] = [];
    let fail = true;
    mockApi({
      ...me,
      "GET /v1/spaces/p1/accounts": () => ({ status: 200, body: { items: [ACCOUNT] } }),
      "GET /v1/spaces/p1/categories": () => ({ status: 200, body: { items: [] } }),
      "POST /v1/spaces/p1/transactions": ({ headers }) => { keys.push(headers["Idempotency-Key"] ?? ""); return fail ? problem(503, "DOWNSTREAM_UNAVAILABLE") : { status: 201, body: {} }; },
    });
    renderWithSession(<AddExpenseDialog open space="p1" onClose={() => undefined} />);
    const dialog = await screen.findByRole("dialog");
    await userEvent.type(await within(dialog).findByLabelText(/^Amount/), "5.00");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save expense" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("temporarily unavailable");
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    fail = false;
    await userEvent.click(within(dialog).getByRole("button", { name: "Save expense" }));
    await waitFor(() => expect(keys).toHaveLength(2));
    expect(keys[1]).toBe(keys[0]);
  });

  it("says an account is needed first when there is none", async () => {
    mockApi({ ...me, "GET /v1/spaces/p1/accounts": () => ({ status: 200, body: { items: [] } }), "GET /v1/spaces/p1/categories": () => ({ status: 200, body: { items: [] } }) });
    renderWithSession(<AddExpenseDialog open space="p1" onClose={() => undefined} />);
    expect(await screen.findByText(/Add an account first/)).toBeInTheDocument();
  });
});

describe("sidebar", () => {
  const NAV = [{ label: "Main", items: [{ id: "overview", label: "Overview", href: "/finance/overview", icon: "dashboard" as const }, { id: "calendar", label: "Calendar", href: "/finance/calendar", icon: "calendar" as const }] }];

  it("collapses to icons with every link still named, and remembers the choice", async () => {
    const { unmount } = render(<AppShell contextLabel="Personal finance" nav={NAV} currentId="overview" title="Overview">x</AppShell>);
    const toggle = screen.getByRole("button", { name: "Collapse sidebar" });
    expect(toggle).toHaveAttribute("aria-pressed", "false");
    await userEvent.click(toggle);
    expect(screen.getByRole("button", { name: "Expand sidebar" })).toHaveAttribute("aria-pressed", "true");
    expect(document.querySelector(".shell.collapsed")).not.toBeNull();
    expect(screen.getByRole("link", { name: "Calendar" })).toHaveAttribute("title", "Calendar");
    expect(window.localStorage.getItem("finora.sidebar.collapsed")).toBe("1");
    unmount();
    render(<AppShell contextLabel="Personal finance" nav={NAV} currentId="overview" title="Overview">x</AppShell>);
    expect(screen.getByRole("button", { name: "Expand sidebar" })).toBeInTheDocument();
  });

  it("opens as a drawer on small screens, closes with Escape, the backdrop or a link", async () => {
    render(<AppShell contextLabel="Personal finance" nav={NAV} currentId="overview" title="Overview">x</AppShell>);
    await userEvent.click(screen.getByRole("button", { name: "Sections" }));
    expect(document.querySelector(".shell-nav.open")).not.toBeNull();
    await userEvent.keyboard("{Escape}");
    expect(document.querySelector(".shell-nav.open")).toBeNull();
    await userEvent.click(screen.getByRole("button", { name: "Sections" }));
    await userEvent.click(document.querySelector(".shell-backdrop.open")!);
    expect(document.querySelector(".shell-nav.open")).toBeNull();
    await userEvent.click(screen.getByRole("button", { name: "Sections" }));
    await act(async () => { await userEvent.click(screen.getByRole("link", { name: "Calendar" })); });
    expect(document.querySelector(".shell-nav.open")).toBeNull();
  });
});

describe("theme", () => {
  it("switches between dark and light from the account menu and remembers it", async () => {
    mockApi(me);
    renderWithSession(<Header />);
    await userEvent.click(await screen.findByRole("button", { name: /Asha/ }));
    await userEvent.click(screen.getByRole("menuitem", { name: "Switch to light theme" }));
    expect(document.documentElement.dataset.theme).toBe("light");
    expect(window.localStorage.getItem("finora.theme")).toBe("light");
    await userEvent.click(screen.getByRole("menuitem", { name: "Switch to dark theme" }));
    expect(document.documentElement.dataset.theme).toBe("dark");
  });
});
