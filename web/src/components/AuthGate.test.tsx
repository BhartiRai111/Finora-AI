import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { mockApi, problem, renderWithSession, USER } from "@/test-utils";
import { AuthGate } from "./AuthGate";

afterEach(() => vi.unstubAllGlobals());

describe("AuthGate", () => {
  it("shows a loading state, then the protected content for a signed-in user", async () => {
    mockApi({ "GET /v1/me": () => ({ status: 200, body: USER }) });
    renderWithSession(<AuthGate><p>secret area</p></AuthGate>);
    expect(screen.getByRole("status")).toHaveTextContent("Checking your session");
    expect(await screen.findByText("secret area")).toBeInTheDocument();
  });

  it("never renders protected content for an anonymous visitor", async () => {
    mockApi({ "GET /v1/me": () => problem(401, "AUTH_REQUIRED") });
    renderWithSession(<AuthGate><p>secret area</p></AuthGate>);
    expect(await screen.findByRole("heading", { name: "Sign in required" })).toBeInTheDocument();
    expect(screen.queryByText("secret area")).not.toBeInTheDocument();
  });

  it("asks for the second factor instead of the content when MFA is pending", async () => {
    mockApi({ "GET /v1/me": () => problem(403, "MFA_REQUIRED") });
    renderWithSession(<AuthGate><p>secret area</p></AuthGate>);
    expect(await screen.findByRole("heading", { name: "Two-step verification" })).toBeInTheDocument();
    expect(screen.queryByText("secret area")).not.toBeInTheDocument();
  });

  it("treats an unreachable server as an error with a retry, not as signed-out or signed-in", async () => {
    let up = false;
    mockApi({ "GET /v1/me": () => (up ? { status: 200, body: USER } : { status: 503, body: { code: "UPSTREAM_UNAVAILABLE" } }) });
    renderWithSession(<AuthGate><p>secret area</p></AuthGate>);
    expect(await screen.findByRole("alert")).toHaveTextContent("could not check your session");
    up = true;
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText("secret area")).toBeInTheDocument();
  });
});
