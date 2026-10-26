import type { Role } from "./types";

// UI HINTS ONLY. They decide what to show; the server decides what is allowed on every request. Mirrors the documented
// permission matrix (PRD 4.2 "Members/roles/invitations") and the Admin grant limit (DEC-15).

const ADMIN_MANAGEABLE: readonly Role[] = ["ACCOUNTANT", "MANAGER", "CONTRIBUTOR", "AUDITOR", "VIEWER"];

export const canViewMembers = (role: Role): boolean => role === "OWNER" || role === "ADMIN" || role === "AUDITOR";

export const canManageMembers = (role: Role): boolean => role === "OWNER" || role === "ADMIN";

export const canTransferOwnership = (role: Role): boolean => role === "OWNER";

export function grantableRoles(role: Role): readonly Role[] {
  if (role === "OWNER") return ["OWNER", "ADMIN", ...ADMIN_MANAGEABLE];
  if (role === "ADMIN") return ADMIN_MANAGEABLE;
  return [];
}

export const canManageTarget = (actor: Role, target: Role): boolean => grantableRoles(actor).includes(target);

export const requiresMfa = (role: Role): boolean => role === "OWNER" || role === "ADMIN";

export const roleLabel = (role: Role): string => role.charAt(0) + role.slice(1).toLowerCase();
