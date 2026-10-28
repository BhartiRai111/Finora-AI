import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { mockApi, renderWithSession, USER } from "@/test-utils";
import { Header } from "../Header";
import { ReasonDialog } from "../ui/Dialog";
import { AccessDenied, EmptyState, ErrorState, Skeleton } from "../ui/States";
import { AccountMenu } from "./AccountMenu";
import { AppShell } from "./AppShell";
import { WorkspaceSwitcher } from "./WorkspaceSwitcher";

const nav = vi.hoisted(() => ({ push: vi.fn(), pathname: "/finance/budgets" }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: nav.push }), usePathname: () => nav.pathname }));
beforeEach(() => nav.push.mockClear());
afterEach(() => vi.unstubAllGlobals());

const me = { "GET /v1/me": () => ({ status: 200, body: USER }) };
const NAV = [
  { label: "Money", items: [{ id: "overview", label: "Overview", href: "/finance/overview", icon: "list" as const }, { id: "accounts", label: "Accounts", href: "/finance/accounts", icon: "list" as const }] },
  { label: "Insights", items: [{ id: "reports", label: "Reports", href: "/finance/reports", icon: "list" as const }] },
];

describe("AppShell", () => {
  it("labels the navigation, marks the current page and sets the page title and document title", () => {
    render(<AppShell contextLabel="Acme" badge="Your role: Owner" nav={NAV} currentId="accounts" title="Accounts" description="Bank accounts.">content</AppShell>);
    const navigation = screen.getByRole("navigation", { name: "Acme sections" });
    expect(navigation).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Accounts" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Overview" })).not.toHaveAttribute("aria-current");
    expect(screen.getByRole("link", { name: "Reports" })).toHaveAttribute("href", "/finance/reports");
    expect(screen.getByRole("heading", { level: 1, name: "Accounts" })).toBeInTheDocument();
    expect(screen.getByText("Bank accounts.")).toBeInTheDocument();
    expect(screen.getByRole("main")).toHaveTextContent("content");
    expect(document.title).toBe("Accounts · Acme · Finora AI");
  });

  it("has a small-screen menu button that reports and toggles its state", async () => {
    render(<AppShell contextLabel="Acme" nav={NAV} currentId="overview" title="Overview">x</AppShell>);
    const toggle = screen.getByRole("button", { name: "Sections" });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    await userEvent.click(toggle);
    expect(screen.getByRole("button", { name: "Hide sections" })).toHaveAttribute("aria-expanded", "true");
    await userEvent.click(screen.getByRole("link", { name: "Accounts" }));
    expect(screen.getByRole("button", { name: "Sections" })).toHaveAttribute("aria-expanded", "false");
  });
});

describe("AccountMenu", () => {
  it("shows the signed-in person, opens with the keyboard, moves with arrows, closes on Escape and returns focus", async () => {
    mockApi(me);
    renderWithSession(<AccountMenu />);
    const trigger = await screen.findByRole("button", { name: /Asha/ });
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    await userEvent.click(trigger);
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByText("asha@example.test")).toBeInTheDocument();
    const items = screen.getAllByRole("menuitem");
    expect(items.map((i) => i.textContent)).toEqual(["Account and security", "Settings", "Switch to dark theme", "Sign out"]);
    expect(items[0]).toHaveFocus();
    await userEvent.keyboard("{ArrowDown}");
    expect(items[1]).toHaveFocus();
    await userEvent.keyboard("{ArrowUp}{ArrowUp}");
    expect(items[3]).toHaveFocus();
    await userEvent.keyboard("{ArrowDown}");
    expect(items[0]).toHaveFocus();
    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("menu")).toBeNull();
    expect(trigger).toHaveFocus();
  });

  it("signs out through the server and renders nothing for an anonymous visitor", async () => {
    const { calls } = mockApi({ ...me, "POST /v1/auth/logout": () => ({ status: 204 }) });
    renderWithSession(<AccountMenu />);
    await userEvent.click(await screen.findByRole("button", { name: /Asha/ }));
    await userEvent.click(screen.getByRole("menuitem", { name: "Sign out" }));
    await waitFor(() => expect(calls.some((c) => c.key === "POST /v1/auth/logout")).toBe(true));
    await waitFor(() => expect(screen.queryByRole("button", { name: /Asha/ })).toBeNull());
  });
});

describe("Header", () => {
  const WS = { items: [{ id: "p1", name: "Asha", type: "PERSONAL", role: "OWNER", membership_id: "m" }, { id: "b1", name: "Acme", type: "BUSINESS", role: "OWNER", membership_id: "m" }] };

  beforeEach(() => { nav.pathname = "/workspaces"; });
  afterEach(() => { nav.pathname = "/finance/budgets"; });

  it("shows the personal profile first, and switching goes to the right area", async () => {
    mockApi({ ...me, "GET /v1/workspaces": () => ({ status: 200, body: WS }) });
    renderWithSession(<Header />);
    const select = await screen.findByLabelText("Active workspace");
    await waitFor(() => expect(select).toHaveValue("p1"));
    await userEvent.selectOptions(select, "b1");
    expect(nav.push).toHaveBeenCalledWith("/workspaces/b1");
    await userEvent.selectOptions(select, "p1");
    expect(nav.push).toHaveBeenLastCalledWith("/finance");
    expect(screen.getByRole("link", { name: "Workspaces" })).toHaveAttribute("aria-current", "page");
  });

  it("fetches the workspace list again when the page is on a workspace created after it was loaded", async () => {
    let calls = 0;
    mockApi({
      ...me,
      "GET /v1/workspaces": () => { calls += 1; return { status: 200, body: { items: calls === 1 ? [WS.items[0]] : WS.items } }; },
    });
    nav.pathname = "/workspaces/b1/assistant";
    try {
      renderWithSession(<WorkspaceSwitcher />);
      const select = await screen.findByLabelText("Active workspace");
      await waitFor(() => expect(select).toHaveValue("b1"));
      // once for the first load, once because the page is on an unknown workspace, and once more when the list changed; never an endless loop
      expect(calls).toBeGreaterThanOrEqual(2);
      expect(calls).toBeLessThanOrEqual(3);
    } finally {
      nav.pathname = "/finance/budgets";
    }
  });

  it("shows only the brand to someone who is not signed in", async () => {
    mockApi({ "GET /v1/me": () => ({ status: 401, body: { code: "AUTH_REQUIRED" } }) });
    renderWithSession(<Header />);
    expect(screen.getByRole("link", { name: "Finora AI" })).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("navigation", { name: "Primary" })).toBeNull());
  });
});

describe("ReasonDialog", () => {
  function Harness({ onConfirm }: { onConfirm: (r: string) => Promise<void> }) {
    const [open, setOpen] = useState(false);
    return (
      <>
        <button type="button" onClick={() => setOpen(true)}>Open it</button>
        <ReasonDialog open={open} title="Void this" label="Reason" required confirmLabel="Void" onConfirm={onConfirm} onClose={() => setOpen(false)} />
      </>
    );
  }

  it("is a labelled modal that takes focus, traps Tab, closes on Escape and returns focus", async () => {
    render(<Harness onConfirm={async () => undefined} />);
    expect(screen.queryByRole("dialog")).toBeNull();
    const opener = screen.getByRole("button", { name: "Open it" });
    await userEvent.click(opener);
    const dialog = screen.getByRole("dialog", { name: "Void this" });
    expect(dialog).toHaveAttribute("aria-modal", "true");
    const field = screen.getByLabelText("Reason");
    expect(field).toHaveFocus();
    await userEvent.tab();
    expect(screen.getByRole("button", { name: "Void" })).toHaveFocus();
    await userEvent.tab();
    expect(screen.getByRole("button", { name: "Cancel" })).toHaveFocus();
    await userEvent.tab();
    expect(field).toHaveFocus();
    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(opener).toHaveFocus();
  });

  it("requires a reason, shows a failure without closing, and submits once", async () => {
    const onConfirm = vi.fn()
      .mockRejectedValueOnce(new Error("The server said no."))
      .mockResolvedValueOnce(undefined);
    render(<Harness onConfirm={onConfirm} />);
    await userEvent.click(screen.getByRole("button", { name: "Open it" }));
    await userEvent.click(screen.getByRole("button", { name: "Void" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Reason is required.");
    expect(onConfirm).not.toHaveBeenCalled();
    await userEvent.type(screen.getByLabelText("Reason"), "  typo  ");
    await userEvent.click(screen.getByRole("button", { name: "Void" }));
    expect(await screen.findByText("The server said no.")).toBeInTheDocument();
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(onConfirm).toHaveBeenLastCalledWith("typo");
    await userEvent.click(screen.getByRole("button", { name: "Void" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(onConfirm).toHaveBeenCalledTimes(2);
  });

  it("ignores a second submit while the first is still running", async () => {
    let release: () => void = () => undefined;
    const onConfirm = vi.fn(() => new Promise<void>((resolve) => { release = resolve; }));
    render(<Harness onConfirm={onConfirm} />);
    await userEvent.click(screen.getByRole("button", { name: "Open it" }));
    await userEvent.type(screen.getByLabelText("Reason"), "x");
    await userEvent.click(screen.getByRole("button", { name: "Void" }));
    expect(screen.getByRole("button", { name: "Working…" })).toBeDisabled();
    await userEvent.keyboard("{Enter}");
    expect(onConfirm).toHaveBeenCalledTimes(1);
    await act(async () => release());
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  });
});

describe("shared states", () => {
  it("renders error, empty, denied and skeleton states accessibly", async () => {
    const retry = vi.fn();
    render(<><ErrorState error={new Error("boom")} onRetry={retry} /><EmptyState title="Nothing yet">Add one.</EmptyState><AccessDenied /><Skeleton label="Loading reports…" /></>);
    expect(screen.getAllByRole("alert")).toHaveLength(2);
    expect(screen.getByText("Something went wrong. Please try again.")).toBeInTheDocument();
    expect(screen.queryByText("boom")).toBeNull();
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(retry).toHaveBeenCalled();
    expect(screen.getByText("Nothing yet")).toBeInTheDocument();
    expect(screen.getByText("You do not have access to this section.")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Loading reports…");
  });
});

describe("document title", () => {
  it("puts the page title back when something else overwrites it, and stops when the page goes away", async () => {
    const { unmount } = render(<AppShell contextLabel="Acme" nav={NAV} currentId="accounts" title="Accounts">x</AppShell>);
    expect(document.title).toBe("Accounts · Acme · Finora AI");
    document.title = "Finora AI";
    await waitFor(() => expect(document.title).toBe("Accounts · Acme · Finora AI"));
    // the element itself being replaced (as a framework may do) is also undone
    document.head.querySelector("title")!.replaceWith(Object.assign(document.createElement("title"), { textContent: "Finora AI" }));
    await waitFor(() => expect(document.title).toBe("Accounts · Acme · Finora AI"));
    unmount();
    document.title = "Elsewhere";
    await new Promise((r) => setTimeout(r, 30));
    expect(document.title).toBe("Elsewhere");
  });
});
