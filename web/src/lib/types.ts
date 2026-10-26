export type Role = "OWNER" | "ADMIN" | "ACCOUNTANT" | "MANAGER" | "CONTRIBUTOR" | "AUDITOR" | "VIEWER";

export const ROLES: readonly Role[] = ["OWNER", "ADMIN", "ACCOUNTANT", "MANAGER", "CONTRIBUTOR", "AUDITOR", "VIEWER"];

export type User = {
  id: string;
  email: string;
  display_name: string;
  email_verified: boolean;
  created_at: string;
  mfa_enabled?: boolean;
  csrf_token?: string;
};

export type Workspace = { id: string; name: string; type: "PERSONAL" | "BUSINESS"; role: Role; membership_id: string };

export type Member = {
  membership_id: string;
  user_id: string;
  role: Role;
  status: string;
  display_name: string;
  email: string;
  joined_at: string;
};

export type Invitation = {
  id: string;
  email: string;
  role: Role;
  status: "PENDING" | "ACCEPTED" | "DECLINED" | "REVOKED" | "EXPIRED";
  created_at: string;
  expires_at: string;
  delivery?: "SENT" | "NOT_CONFIGURED" | "DEV_CAPTURED" | "FAILED" | null;
};
