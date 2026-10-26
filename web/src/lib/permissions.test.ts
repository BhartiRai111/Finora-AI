import { describe, expect, it } from "vitest";
import { canManageMembers, canManageTarget, canTransferOwnership, canViewMembers, grantableRoles, requiresMfa } from "./permissions";
import { ROLES } from "./types";

describe("permission hints mirror the documented matrix", () => {
  it("only Owner, Admin and Auditor see the member list; only Owner and Admin manage it", () => {
    for (const role of ROLES) {
      expect(canViewMembers(role)).toBe(role === "OWNER" || role === "ADMIN" || role === "AUDITOR");
      expect(canManageMembers(role)).toBe(role === "OWNER" || role === "ADMIN");
      expect(canTransferOwnership(role)).toBe(role === "OWNER");
      expect(requiresMfa(role)).toBe(role === "OWNER" || role === "ADMIN");
    }
  });

  it("an Admin can grant only up to Accountant level; an Owner anything", () => {
    expect(grantableRoles("OWNER")).toEqual(ROLES);
    expect(grantableRoles("ADMIN")).toEqual(["ACCOUNTANT", "MANAGER", "CONTRIBUTOR", "AUDITOR", "VIEWER"]);
    for (const role of ROLES.filter((r) => r !== "OWNER" && r !== "ADMIN")) expect(grantableRoles(role)).toEqual([]);
  });

  it("an Admin cannot manage Owners or other Admins", () => {
    expect(canManageTarget("ADMIN", "OWNER")).toBe(false);
    expect(canManageTarget("ADMIN", "ADMIN")).toBe(false);
    expect(canManageTarget("ADMIN", "VIEWER")).toBe(true);
    expect(canManageTarget("VIEWER", "VIEWER")).toBe(false);
  });
});
