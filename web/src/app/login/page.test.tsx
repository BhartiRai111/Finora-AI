import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { mockApi, problem, renderWithSession, USER } from "@/test-utils";
import LoginPage from "./page";

const replace = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace, push: vi.fn() }) }));

afterEach(() => {
  vi.unstubAllGlobals();
  replace.mockReset();
});

describe("LoginPage", () => {
  it("asks for both fields before calling the server", async () => {
    const { fn } = mockApi({ "GET /v1/me": () => problem(401, "AUTH_REQUIRED") });
    renderWithSession(<LoginPage />);
    await userEvent.click(await screen.findByRole("button", { name: "Sign in" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Enter your email and password.");
    expect(fn).toHaveBeenCalledTimes(1); // only the initial session check
  });

  it("shows a safe message and re-enables the form after a failed sign-in", async () => {
    mockApi({ "GET /v1/me": () => problem(401, "AUTH_REQUIRED"), "POST /v1/auth/login": () => problem(401, "INVALID_CREDENTIALS") });
    renderWithSession(<LoginPage />);
    await userEvent.type(await screen.findByLabelText("Email"), "asha@example.test");
    await userEvent.type(screen.getByLabelText("Password"), "wrong-password");
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("That did not work");
    expect(screen.getByRole("button", { name: "Sign in" })).toBeEnabled();
  });

  it("shows a busy state while signing in and then goes to the workspaces", async () => {
    let release: () => void = () => {};
    const gate = new Promise<void>((r) => { release = r; });
    let signedIn = false;
    mockApi({
      "GET /v1/me": () => (signedIn ? { status: 200, body: USER } : problem(401, "AUTH_REQUIRED")),
      "POST /v1/auth/login": async () => { await gate; signedIn = true; return { status: 200, body: { csrf_token: "csrf-abc", mfa_required: false } }; },
    });
    renderWithSession(<LoginPage />);
    await userEvent.type(await screen.findByLabelText("Email"), "asha@example.test");
    await userEvent.type(screen.getByLabelText("Password"), "a-long-enough-password");
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));
    expect(await screen.findByRole("button", { name: "Signing in…" })).toBeDisabled();
    release();
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/workspaces"));
  });

  it("moves to the two-step screen when the server requires a second factor", async () => {
    mockApi({
      "GET /v1/me": () => problem(401, "AUTH_REQUIRED"),
      "POST /v1/auth/login": () => ({ status: 200, body: { csrf_token: "c", mfa_required: true } }),
      "POST /v1/auth/mfa/verify": ({ body }) => ((body as { code?: string }).code === "123456" ? { status: 200, body: { csrf_token: "c2" } } : problem(401, "INVALID_CREDENTIALS")),
    });
    renderWithSession(<LoginPage />);
    await userEvent.type(await screen.findByLabelText("Email"), "asha@example.test");
    await userEvent.type(screen.getByLabelText("Password"), "a-long-enough-password");
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));
    expect(await screen.findByRole("heading", { name: "Two-step verification" })).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("6-digit code"), "000000");
    await userEvent.click(screen.getByRole("button", { name: "Verify" }));
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Use a recovery code" }));
    expect(screen.getByLabelText("Recovery code")).toBeInTheDocument();
  });

  async function signInWith(search: string) {
    window.history.pushState({}, "", `/login${search}`);
    let signedIn = false;
    mockApi({
      "GET /v1/me": () => (signedIn ? { status: 200, body: USER } : problem(401, "AUTH_REQUIRED")),
      "POST /v1/auth/login": () => { signedIn = true; return { status: 200, body: { csrf_token: "c", mfa_required: false } }; },
    });
    renderWithSession(<LoginPage />);
    await userEvent.type(await screen.findByLabelText("Email"), "asha@example.test");
    await userEvent.type(screen.getByLabelText("Password"), "a-long-enough-password");
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));
  }

  it("returns to the page the person was on after signing in", async () => {
    await signInWith("?next=%2Ffinance%2Fbudgets");
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/finance/budgets"));
    window.history.pushState({}, "", "/");
  });

  it("never follows a return address that leaves the site", async () => {
    await signInWith("?next=https%3A%2F%2Fevil.example%2Fphish");
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/workspaces"));
    expect(replace).not.toHaveBeenCalledWith(expect.stringContaining("evil"));
    window.history.pushState({}, "", "/");
  });
});
