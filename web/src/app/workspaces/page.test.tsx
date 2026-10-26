import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { mockApi, problem, renderWithSession, USER } from "@/test-utils";
import type { Workspace } from "@/lib/types";
import WorkspacesPage from "./page";

const push = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ push, replace: vi.fn() }) }));

const PERSONAL: Workspace = { id: "p1", name: "Personal", type: "PERSONAL", role: "OWNER", membership_id: "m1" };
const BUSINESS: Workspace = { id: "b1", name: "Acme Traders", type: "BUSINESS", role: "ADMIN", membership_id: "m2" };

afterEach(() => {
  vi.unstubAllGlobals();
  push.mockReset();
  window.localStorage.clear();
});

describe("WorkspacesPage", () => {
  it("shows loading, then every workspace with the role and the personal profile kept separate", async () => {
    mockApi({
      "GET /v1/me": () => ({ status: 200, body: USER }),
      "GET /v1/workspaces": () => ({ status: 200, body: { items: [PERSONAL, BUSINESS] } }),
    });
    renderWithSession(<WorkspacesPage />);
    expect(await screen.findByRole("link", { name: "Personal profile" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Acme Traders" })).toHaveAttribute("href", "/workspaces/b1");
    expect(screen.getByText("Private to you")).toBeInTheDocument();
    expect(screen.getByText("Admin")).toBeInTheDocument();
  });

  it("explains a failed load and lets the user retry", async () => {
    let fail = true;
    mockApi({
      "GET /v1/me": () => ({ status: 200, body: USER }),
      "GET /v1/workspaces": () => (fail ? problem(503, "UPSTREAM_UNAVAILABLE") : { status: 200, body: { items: [PERSONAL] } }),
    });
    renderWithSession(<WorkspacesPage />);
    expect(await screen.findByRole("alert")).toHaveTextContent("temporarily unavailable");
    fail = false;
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("link", { name: "Personal profile" })).toBeInTheDocument();
  });

  it("validates the name, creates the workspace with the CSRF token and opens it", async () => {
    const { calls } = mockApi({
      "GET /v1/me": () => ({ status: 200, body: USER }),
      "GET /v1/workspaces": () => ({ status: 200, body: { items: [PERSONAL] } }),
      "POST /v1/workspaces": ({ body }) => ({ status: 201, body: { ...BUSINESS, name: (body as { name: string }).name } }),
    });
    renderWithSession(<WorkspacesPage />);
    const create = await screen.findByRole("button", { name: "Create workspace" });
    await userEvent.click(create);
    expect(await screen.findByRole("alert")).toHaveTextContent("Enter a workspace name.");
    await userEvent.type(screen.getByLabelText("Workspace name"), "  Sharma & Sons ");
    await userEvent.click(create);
    await waitFor(() => expect(push).toHaveBeenCalledWith("/workspaces/b1"));
    const post = calls.find((c) => c.key === "POST /v1/workspaces");
    expect(post?.body).toEqual({ name: "Sharma & Sons" });
    expect(post?.headers["X-CSRF-Token"]).toBe("csrf-abc");
    expect(window.localStorage.getItem("finora.activeWorkspace")).toBe("b1");
  });

  it("shows the server's refusal when creation fails", async () => {
    mockApi({
      "GET /v1/me": () => ({ status: 200, body: USER }),
      "GET /v1/workspaces": () => ({ status: 200, body: { items: [PERSONAL] } }),
      "POST /v1/workspaces": () => problem(409, "CONFLICT", "Workspace limit reached"),
    });
    renderWithSession(<WorkspacesPage />);
    await userEvent.type(await screen.findByLabelText("Workspace name"), "One too many");
    await userEvent.click(screen.getByRole("button", { name: "Create workspace" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Workspace limit reached");
    expect(push).not.toHaveBeenCalled();
  });
});
