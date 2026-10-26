import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { mockApi, problem, renderWithSession, USER } from "@/test-utils";
import type { Member, Workspace } from "@/lib/types";
import { MembersPanel } from "./MembersPanel";

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));
afterEach(() => vi.unstubAllGlobals());

const member = (id: string, role: Member["role"], name: string): Member => ({
  membership_id: id, user_id: `u-${id}`, role, status: "ACTIVE", display_name: name, email: `${name.toLowerCase()}@example.test`, joined_at: "2026-01-01T00:00:00Z",
});
const MEMBERS = [member("m-owner", "OWNER", "Olga"), member("m-admin", "ADMIN", "Adam"), member("m-view", "VIEWER", "Vik")];

function workspace(role: Workspace["role"], membership: string): Workspace {
  return { id: "w1", name: "Acme", type: "BUSINESS", role, membership_id: membership };
}

const base = { "GET /v1/me": () => ({ status: 200, body: USER }), "GET /v1/spaces/w1/members": () => ({ status: 200, body: { items: MEMBERS } }) };

describe("MembersPanel", () => {
  it("lets an Admin manage only members below Admin and never themselves", async () => {
    mockApi(base);
    renderWithSession(<MembersPanel workspace={workspace("ADMIN", "m-admin")} />);
    expect(await screen.findByText("Vik")).toBeInTheDocument();
    expect(screen.getByLabelText("Role for Vik")).toBeInTheDocument();
    expect(screen.queryByLabelText("Role for Olga")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Role for Adam")).not.toBeInTheDocument();
    expect(screen.getByText("Adam (you)")).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "Remove" })).toHaveLength(1);
    const options = within(screen.getByLabelText("Role for Vik")).getAllByRole("option").map((o) => o.textContent);
    expect(options).not.toContain("Owner");
    expect(options).not.toContain("Admin");
  });

  it("gives an Auditor a read-only list", async () => {
    mockApi(base);
    renderWithSession(<MembersPanel workspace={workspace("AUDITOR", "m-aud")} />);
    expect(await screen.findByText("Vik")).toBeInTheDocument();
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Remove" })).not.toBeInTheDocument();
    expect(screen.queryByRole("columnheader", { name: "Actions" })).not.toBeInTheDocument();
  });

  it("points an Owner without MFA to the account page instead of failing silently", async () => {
    mockApi({ ...base, "GET /v1/spaces/w1/members": () => problem(403, "MFA_REQUIRED") });
    renderWithSession(<MembersPanel workspace={workspace("OWNER", "m-owner")} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Multi-factor authentication is required");
    expect(screen.getByRole("link", { name: "account" })).toHaveAttribute("href", "/account");
  });

  it("asks for re-authentication when the server requires step-up, then repeats the change once", async () => {
    let stepped = false;
    const { calls } = mockApi({
      ...base,
      "PATCH /v1/spaces/w1/members/m-view": () => (stepped ? { status: 200, body: member("m-view", "AUDITOR", "Vik") } : problem(403, "STEP_UP_REQUIRED")),
      "POST /v1/auth/step-up": ({ body }) => { stepped = (body as { password: string }).password === "pw-correct-horse"; return stepped ? { status: 200, body: {} } : problem(401, "INVALID_CREDENTIALS"); },
    });
    renderWithSession(<MembersPanel workspace={workspace("OWNER", "m-owner")} />);
    await userEvent.selectOptions(await screen.findByLabelText("Role for Vik"), "AUDITOR");
    expect(await screen.findByRole("form", { name: "Confirm your identity" })).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("Password"), "pw-correct-horse");
    await userEvent.type(screen.getByLabelText("Authenticator code"), "123456");
    await userEvent.click(screen.getByRole("button", { name: "Confirm" }));
    await waitFor(() => expect(calls.filter((c) => c.key.startsWith("PATCH")).length).toBe(2));
    expect(screen.queryByRole("form", { name: "Confirm your identity" })).not.toBeInTheDocument();
  });

  it("needs a second click to remove someone and reports a refusal", async () => {
    const { calls } = mockApi({ ...base, "DELETE /v1/spaces/w1/members/m-view": () => problem(409, "CONFLICT", "A workspace must keep at least one Owner") });
    renderWithSession(<MembersPanel workspace={workspace("OWNER", "m-owner")} />);
    await userEvent.click((await screen.findAllByRole("button", { name: "Remove" }))[1]!);
    expect(calls.some((c) => c.key.startsWith("DELETE"))).toBe(false);
    await userEvent.click(screen.getByRole("button", { name: "Confirm removal" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("at least one Owner");
  });
});
