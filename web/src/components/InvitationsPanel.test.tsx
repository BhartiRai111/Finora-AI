import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { mockApi, renderWithSession, USER } from "@/test-utils";
import type { Workspace } from "@/lib/types";
import { deliveryMessage, InvitationsPanel } from "./InvitationsPanel";

afterEach(() => vi.unstubAllGlobals());
const ws = (role: Workspace["role"]): Workspace => ({ id: "w1", name: "Acme", type: "BUSINESS", role, membership_id: "m1" });
const base = { "GET /v1/me": () => ({ status: 200, body: USER }), "GET /v1/spaces/w1/invitations": () => ({ status: 200, body: { items: [] } }) };

describe("InvitationsPanel", () => {
  it("never claims an email was sent unless a provider actually handed it off", () => {
    expect(deliveryMessage("SENT").text).toContain("handed to the email provider");
    expect(deliveryMessage("NOT_CONFIGURED").text).toContain("NO email was sent");
    expect(deliveryMessage("DEV_CAPTURED").text).toContain("NO email was sent");
    expect(deliveryMessage(undefined).text).toContain("NO email was sent");
    expect(deliveryMessage("FAILED").kind).toBe("error");
  });

  it("offers an Admin only the roles an Admin may grant", async () => {
    mockApi(base);
    renderWithSession(<InvitationsPanel workspace={ws("ADMIN")} />);
    const select = await screen.findByLabelText("Role");
    const options = Array.from(select.querySelectorAll("option")).map((o) => o.textContent);
    expect(options).toEqual(["Accountant", "Manager", "Contributor", "Auditor", "Viewer"]);
  });

  it("hides the invite form from roles that cannot invite", async () => {
    mockApi(base);
    renderWithSession(<InvitationsPanel workspace={ws("AUDITOR")} />);
    expect(await screen.findByText("No invitations yet.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Send invitation" })).not.toBeInTheDocument();
  });

  it("validates the email, sends the invitation and reports honest delivery status", async () => {
    const { calls } = mockApi({
      ...base,
      "POST /v1/spaces/w1/invitations": () => ({ status: 201, body: { id: "i1", email: "new@example.test", role: "VIEWER", status: "PENDING", created_at: "", expires_at: "", delivery: "NOT_CONFIGURED" } }),
    });
    renderWithSession(<InvitationsPanel workspace={ws("OWNER")} />);
    const send = await screen.findByRole("button", { name: "Send invitation" });
    await userEvent.click(send);
    expect(await screen.findByRole("alert")).toHaveTextContent("valid email");
    await userEvent.type(screen.getByLabelText("Email"), "new@example.test");
    await userEvent.click(send);
    expect(await screen.findByRole("status")).toHaveTextContent("NO email was sent");
    expect(calls.find((c) => c.key.startsWith("POST"))?.body).toEqual({ email: "new@example.test", role: "VIEWER" });
  });
});
