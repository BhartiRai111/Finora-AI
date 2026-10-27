// Shapes returned by the finance-assistant API. Every figure is a string computed by the backend; the browser never calculates or edits them.

export type AssistantKind = "VERIFIED" | "ESTIMATE" | "HYPOTHETICAL" | "OBSERVATION" | "CONVERTED";
export type AssistantSection = { title: string; kind: AssistantKind; currency: string | null; columns: string[]; rows: string[][]; notes: string[] };
export type AssistantAi = {
  status: "NOT_CONFIGURED" | "CONFIGURED" | "UNAVAILABLE" | "FAILED" | "EXECUTED" | "SKIPPED" | "BLOCKED";
  reason: string | null;
  provider: string | null;
  message: string | null;
  explanation: string | null;
  label: string | null;
};
export type AssistantAnswer = {
  status: "ANSWERED" | "NO_DATA" | "NEEDS_CLARIFICATION" | "UNSUPPORTED";
  intent: string;
  domain: string;
  period: { from: string | null; to: string | null; label: string | null; comparison_from: string | null; comparison_to: string | null; comparison_label: string | null } | null;
  summary: string | null;
  clarification: string | null;
  sections: AssistantSection[];
  assumptions: string[];
  limitations: string[];
  sources: { type: string; name: string; from: string | null; to: string | null; ref: string | null }[];
  notices: string[];
  ai: AssistantAi;
};
export type AssistantStatus = {
  domain: string;
  ai: AssistantAi;
  supported: { tool: string; example: string }[];
  limits: { question_max_chars: number; questions_per_hour: number; provider_calls_per_day: number; provider_timeout_ms: number; read_only: boolean };
};

export const KIND_LABEL: Record<AssistantKind, string> = {
  VERIFIED: "Verified from your records",
  ESTIMATE: "Estimate (not an actual result)",
  HYPOTHETICAL: "Hypothetical (your assumptions, not saved)",
  OBSERVATION: "Potential anomaly (for you to review)",
  CONVERTED: "Converted from verified figures at the stated rates",
};

/** What the person typed for a percentage: a number from -100 to 1000 with at most 2 decimals, or an error text. */
export function percentProblem(value: string, label: string): string | null {
  const v = value.trim();
  if (v === "") return null;
  if (!/^-?\d+(\.\d{1,2})?$/.test(v)) return `${label} must be a number such as 10 or -20 (at most 2 decimals).`;
  const n = Number(v);
  return n < -100 || n > 1000 ? `${label} must be between -100 and 1000.` : null;
}
