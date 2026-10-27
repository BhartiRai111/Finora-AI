// Shapes of the notification, alert, subscription, billing, integration and workspace-administration APIs.

export type Severity = "INFO" | "WARNING" | "CRITICAL";
export type NotificationItem = { id: string; category: string; severity: Severity; title: string; body: string | null; metadata: Record<string, string>; read: boolean; created_at: string };
export type NotificationPage = { items: NotificationItem[]; total: number; unread: number; limit: number; offset: number };
export const NOTIFICATION_CATEGORIES = ["IMPORT", "RECONCILIATION", "ALERT", "SUBSCRIPTION", "BILLING", "INTEGRATION", "SECURITY", "OPERATIONS"] as const;

export type AlertItem = {
  id: string;
  alert_type: string;
  severity: Severity;
  title: string;
  details: Record<string, string | number | boolean | null>;
  window_start: string | null;
  window_end: string | null;
  status: "OPEN" | "ACKNOWLEDGED" | "RESOLVED";
  generated_at: string;
  acknowledged_at: string | null;
  resolved_at: string | null;
  resolved_reason: string | null;
};
export type AlertPage = { items: AlertItem[]; total: number; open: number; limit: number; offset: number };
export type AlertSetting = { alert_type: string; enabled: boolean; threshold: string | null; days: number | null; currency: string | null; is_default: boolean; threshold_meaning: string | null; trigger: string; severity: string };
export type EvaluationResult = { evaluated_on: string; created: Record<string, number>; resolved: Record<string, number>; skipped: { type: string; reason: string }[]; note: string };

export type PlanEntitlement = { key: string; enabled: boolean; limit: number | null };
export type Plan = { plan_key: string; name: string; description: string; provisional: boolean; requires_payment: boolean; status: string; entitlements: PlanEntitlement[] };
export type Subscription = {
  status: "ACTIVE" | "PAST_DUE" | "CANCELLED" | "EXPIRED";
  plan: Plan;
  started_at: string | null;
  current_period_end: string | null;
  implicit_default: boolean;
  cancel_reason: string | null;
  effective: { key: string; enabled: boolean; limit: number | null; reason: string | null }[];
  notices: string[];
};
export type SubscriptionEvent = { id: string; from_status: string | null; to_status: string; from_plan: string | null; to_plan: string; source: string; reason: string | null; created_at: string };
export type BillingOverview = { provider: { status: string; approved: boolean }; message: string; payments_enabled: boolean; contact: { contact_name: string | null; contact_email: string | null }; notes: string[] };
export type BillingRecord = {
  id: string;
  kind: string;
  legal_tax_invoice: boolean;
  status: "OPEN" | "PAID" | "PAYMENT_FAILED" | "VOID";
  payment_confirmed: boolean;
  currency: string;
  amount: string;
  description: string;
  provider: string | null;
  provider_reference: string | null;
  paid_at: string | null;
  created_at: string;
  payment_attempts: { status: string; provider: string; failure_code: string; event_at: string }[];
};

export type Integration = { key: string; name: string; kind: string; state: "NOT_CONFIGURED" | "CONFIGURED" | "CONNECTED" | "FAILED" | "UNAVAILABLE" | "DISCONNECTED"; message: string; health: Record<string, unknown>; pending_decisions: string[] };
export type AdminOverview = {
  scope: string;
  note: string;
  subscription: { plan: string; plan_name: string; status: string; provisional: boolean };
  integrations_by_state: Record<string, number>;
  open_alerts_by_severity: Record<string, number>;
  unread_notifications: number;
  billing: { provider_status: string; open_records: number; failed_payment_records: number };
};

export const SEVERITY_TEXT: Record<Severity, string> = { INFO: "Information", WARNING: "Warning", CRITICAL: "Critical" };
export const STATE_TEXT: Record<Integration["state"], string> = {
  NOT_CONFIGURED: "Not configured",
  CONFIGURED: "Configured (not checked)",
  CONNECTED: "Connected",
  FAILED: "Failed",
  UNAVAILABLE: "Unavailable",
  DISCONNECTED: "Disconnected",
};
export const humanizeType = (t: string): string => t.toLowerCase().replace(/_/g, " ").replace(/^./, (c) => c.toUpperCase());
