import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useEffect } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AuthGate } from "@/components/AuthGate";
import { api } from "@/lib/api";
import { mockApi, problem, renderWithSession, USER } from "@/test-utils";
import FinancePage from "./finance/[[...section]]/page";
import WorkspacePage from "./workspaces/[id]/[[...section]]/page";

const nav = vi.hoisted(() => ({ params: {} as Record<string, unknown>, push: vi.fn(), replace: vi.fn() }));
vi.mock("next/navigation", () => ({ useParams: () => nav.params, useRouter: () => ({ push: nav.push, replace: nav.replace }), usePathname: () => "/" }));
beforeEach(() => { nav.push.mockClear(); nav.replace.mockClear(); });
afterEach(() => vi.unstubAllGlobals());

const me = { "GET /v1/me": () => ({ status: 200, body: USER }) };
const PERSONAL = { id: "p1", name: "Asha", type: "PERSONAL", role: "OWNER", membership_id: "m1" };
const business = (role: string) => ({ id: "b1", name: "Acme Traders", type: "BUSINESS", role, membership_id: "m2" });
const financeRoutes = {
  ...me,
  "GET /v1/workspaces": () => ({ status: 200, body: { items: [PERSONAL] } }),
  "GET /v1/spaces/p1/accounts?include_archived=true": () => ({ status: 200, body: { items: [] } }),
  "GET /v1/spaces/p1/categories?include_archived=true": () => ({ status: 200, body: { items: [] } }),
};

describe("/finance routes", () => {
  it("shows the requested section inside the shell with its title and the personal navigation", async () => {
    nav.params = { section: ["categories"] };
    mockApi(financeRoutes);
    renderWithSession(<FinancePage />);
    expect(await screen.findByRole("heading", { level: 1, name: "Categories" })).toBeInTheDocument();
    expect(screen.getByRole("navigation", { name: "Personal finance sections" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Categories" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Budgets" })).toHaveAttribute("href", "/finance/budgets");
    expect(screen.queryByRole("tablist")).toBeNull();
    expect(document.title).toBe("Categories · Personal finance · Finora AI");
  });

  it("says plainly when a section does not exist and makes no data request for it", async () => {
    nav.params = { section: ["nonsense"] };
    const { calls } = mockApi(me);
    renderWithSession(<FinancePage />);
    expect(await screen.findByRole("heading", { name: "Page not found" })).toBeInTheDocument();
    expect(screen.getByText("This page does not exist")).toBeInTheDocument();
    expect(calls.map((c) => c.key)).toEqual(["GET /v1/me"]);
  });

  it("sends an anonymous visitor to sign in and returns them to where they were", async () => {
    nav.params = { section: ["budgets"] };
    mockApi({ "GET /v1/me": () => problem(401, "AUTH_REQUIRED") });
    renderWithSession(<FinancePage />);
    expect(await screen.findByRole("heading", { name: "Sign in required" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "sign in" }).getAttribute("href")).toMatch(/^\/login\?next=/);
    expect(screen.queryByRole("navigation", { name: /sections/ })).toBeNull();
  });
});

describe("/workspaces/[id] routes", () => {
  it("sends a personal profile to the personal finance area", async () => {
    nav.params = { id: "p1" };
    mockApi({ ...me, "GET /v1/spaces/p1": () => ({ status: 200, body: PERSONAL }) });
    renderWithSession(<WorkspacePage />);
    await waitFor(() => expect(nav.replace).toHaveBeenCalledWith("/finance"));
  });

  it("gives a Viewer only the workspace section, and denies a finance URL typed by hand", async () => {
    nav.params = { id: "b1", section: ["invoices"] };
    const { calls } = mockApi({ ...me, "GET /v1/spaces/b1": () => ({ status: 200, body: business("VIEWER") }) });
    renderWithSession(<WorkspacePage />);
    expect(await screen.findByText("You do not have access to this section.")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: "Access denied" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Invoices" })).toBeNull();
    expect(screen.queryByRole("link", { name: "Members" })).toBeNull();
    expect(screen.getByRole("link", { name: "Workspace" })).toHaveAttribute("href", "/workspaces/b1/workspace");
    expect(screen.getByText("Your role: Viewer")).toBeInTheDocument();
    expect(calls.every((c) => !c.key.includes("/finance-") && !c.key.includes("/invoices"))).toBe(true);
  });

  it("shows an Owner the full navigation, the workspace name and the requested section", async () => {
    nav.params = { id: "b1", section: ["workspace"] };
    mockApi({ ...me, "GET /v1/spaces/b1": () => ({ status: 200, body: business("OWNER") }) });
    renderWithSession(<WorkspacePage />);
    expect(await screen.findByRole("heading", { level: 1, name: "Workspace" })).toBeInTheDocument();
    expect(screen.getByRole("navigation", { name: "Acme Traders sections" })).toBeInTheDocument();
    for (const name of ["Summary", "Invoices", "GST", "Administration", "Members", "Invitations", "Business settings"]) {
      expect(screen.getByRole("link", { name })).toBeInTheDocument();
    }
    expect(screen.getByRole("link", { name: "Invoices" })).toHaveAttribute("href", "/workspaces/b1/invoices");
    expect(screen.getByText("Your role: Owner")).toBeInTheDocument();
  });

  it("explains an unknown workspace and offers a retry for a server error", async () => {
    nav.params = { id: "zzz" };
    mockApi({ ...me, "GET /v1/spaces/zzz": () => problem(404, "NOT_FOUND") });
    renderWithSession(<WorkspacePage />);
    expect(await screen.findByText(/does not exist, or you no longer have access/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Try again" })).toBeNull();
  });

  it("retries a failed workspace load", async () => {
    nav.params = { id: "b1", section: ["workspace"] };
    let fail = true;
    mockApi({ ...me, "GET /v1/spaces/b1": () => (fail ? problem(503, "DOWNSTREAM_UNAVAILABLE") : { status: 200, body: business("OWNER") }) });
    renderWithSession(<WorkspacePage />);
    const retry = await screen.findByRole("button", { name: "Try again" });
    fail = false;
    await userEvent.click(retry);
    expect(await screen.findByRole("heading", { level: 1, name: "Workspace" })).toBeInTheDocument();
  });
});

describe("session expiry", () => {
  function Probe() {
    useEffect(() => { api("/v1/spaces/b1/invoices").catch(() => undefined); }, []);
    return <p>signed in content</p>;
  }

  it("leaves the signed-in state when a request is refused as unauthenticated, and links back to where the person was", async () => {
    mockApi({ ...me, "GET /v1/spaces/b1/invoices": () => problem(401, "AUTH_REQUIRED") });
    renderWithSession(<AuthGate><Probe /></AuthGate>);
    expect(await screen.findByRole("heading", { name: "Your session has ended" })).toBeInTheDocument();
    expect(screen.queryByText("signed in content")).toBeNull();
    expect(screen.getByRole("link", { name: "sign in" }).getAttribute("href")).toMatch(/^\/login\?next=%2F/);
  });

  it("does not end the session for a permission refusal", async () => {
    mockApi({ ...me, "GET /v1/spaces/b1/invoices": () => problem(403, "FORBIDDEN") });
    renderWithSession(<AuthGate><Probe /></AuthGate>);
    expect(await screen.findByText("signed in content")).toBeInTheDocument();
    await new Promise((r) => setTimeout(r, 50));
    expect(screen.getByText("signed in content")).toBeInTheDocument();
  });
});
