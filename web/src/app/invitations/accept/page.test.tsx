import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { mockApi, problem, renderWithSession, USER } from "@/test-utils";
import AcceptInvitationPage from "./page";

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState(null, "", "/");
});

describe("AcceptInvitationPage", () => {
  it("reads the token from the URL fragment, removes it from the address bar and never puts it in a URL", async () => {
    window.history.replaceState(null, "", "/invitations/accept#token=abc123");
    const { calls, fn } = mockApi({
      "GET /v1/me": () => ({ status: 200, body: USER }),
      "POST /v1/invitations/accept": () => ({ status: 200, body: { id: "w9", name: "Acme", type: "BUSINESS", role: "VIEWER", membership_id: "m9" } }),
    });
    renderWithSession(<AcceptInvitationPage />);
    await userEvent.click(await screen.findByRole("button", { name: "Accept invitation" }));
    expect(await screen.findByRole("heading", { name: "You have joined Acme" })).toBeInTheDocument();
    expect(calls.find((c) => c.key.startsWith("POST"))?.body).toEqual({ token: "abc123" });
    expect(window.location.hash).toBe("");
    for (const call of fn.mock.calls) expect(String(call[0])).not.toContain("abc123");
  });

  it("shows one neutral message for any invalid invitation", async () => {
    window.history.replaceState(null, "", "/invitations/accept#token=stale");
    mockApi({
      "GET /v1/me": () => ({ status: 200, body: USER }),
      "POST /v1/invitations/accept": () => problem(400, "BAD_REQUEST", "Invalid or expired invitation"),
    });
    renderWithSession(<AcceptInvitationPage />);
    await userEvent.click(await screen.findByRole("button", { name: "Accept invitation" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Invalid or expired invitation");
  });

  it("says so when the link carries no token", async () => {
    mockApi({ "GET /v1/me": () => ({ status: 200, body: USER }) });
    renderWithSession(<AcceptInvitationPage />);
    expect(await screen.findByRole("alert")).toHaveTextContent("missing or has already been used");
  });

  it("requires sign-in before showing anything, keeping the token out of the way", async () => {
    window.history.replaceState(null, "", "/invitations/accept#token=abc123");
    mockApi({ "GET /v1/me": () => problem(401, "AUTH_REQUIRED") });
    renderWithSession(<AcceptInvitationPage />);
    expect(await screen.findByRole("heading", { name: "Sign in required" })).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("button", { name: "Accept invitation" })).not.toBeInTheDocument());
  });
});
