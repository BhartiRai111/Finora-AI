import { describe, expect, it } from "vitest";
import { validateInvite, validateRegistration, validateWorkspaceName } from "./validation";

describe("client validation", () => {
  it("checks registration fields", () => {
    expect(validateRegistration({ name: "", email: "a@b.co", password: "x".repeat(12) })).toMatch(/name/);
    expect(validateRegistration({ name: "A", email: "nope", password: "x".repeat(12) })).toMatch(/email/);
    expect(validateRegistration({ name: "A", email: "a@b.co", password: "short" })).toMatch(/12/);
    expect(validateRegistration({ name: "A", email: "a@b.co", password: "x".repeat(12) })).toBeNull();
  });

  it("checks workspace names and invitation emails", () => {
    expect(validateWorkspaceName("  ")).not.toBeNull();
    expect(validateWorkspaceName("x".repeat(101))).not.toBeNull();
    expect(validateWorkspaceName("Acme")).toBeNull();
    expect(validateInvite({ email: "bad" })).not.toBeNull();
    expect(validateInvite({ email: "ok@example.test" })).toBeNull();
  });
});
