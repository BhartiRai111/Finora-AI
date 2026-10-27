// Shapes returned by the reports, forecast, import, document and reconciliation APIs. Money is a decimal STRING; nothing here is calculated
// in the browser.

export type ReportRow = { period: string; start: string | null; end: string | null; activity: boolean; values: Record<string, string> };
export type CurrencyReport = { currency: string; columns: string[]; rows: ReportRow[]; totals: Record<string, string> };
export type Comparison = {
  from: string;
  to: string;
  currencies: { currency: string; current: Record<string, string>; previous: Record<string, string>; change: Record<string, { amount: string; percent: string | null }> }[];
};
export type Report = {
  report: string;
  title: string;
  domain: string;
  from: string | null;
  to: string | null;
  granularity: string | null;
  definitions: string[];
  notes: string[];
  currencies: CurrencyReport[];
  comparison: Comparison | null;
};

export type ForecastCurrency = {
  currency: string;
  status: "ESTIMATE" | "INSUFFICIENT_HISTORY";
  message: string | null;
  history: { month: string; value: string; activity: boolean; outlier: boolean }[];
  coverage: { months_in_window: number; months_with_data: number; ratio: string; missing_months: string[] };
  estimate_per_month: string | null;
  median: string | null;
  low: string | null;
  high: string | null;
  confidence: "LOW" | "MODERATE" | null;
  warnings: string[];
  projection: { month: string; estimate: string; low: string; high: string }[];
};
export type Forecast = {
  metric: string;
  kind: string;
  label: string;
  method: string;
  history_from: string;
  history_to: string;
  lookback_months: number;
  horizon_months: number;
  assumptions: string[];
  ai_explanation: { status: string; message: string };
  currencies: ForecastCurrency[];
};

export type ImportJob = {
  id: string;
  kind: "STATEMENT" | "TRANSACTIONS" | "EXPENSES";
  status: "NEEDS_MAPPING" | "READY" | "COMMITTING" | "COMPLETED" | "PARTIAL" | "FAILED" | "CANCELLED";
  filename: string;
  account_id: string | null;
  currency: string | null;
  total_rows: number;
  valid_rows: number;
  invalid_rows: number;
  duplicate_rows: number;
  possible_duplicate_rows: number;
  committed_rows: number;
  skipped_rows: number;
  rejected_rows: number;
  possible_duplicate_policy: string | null;
  headers: string[];
  mapping: Record<string, string>;
  options: Record<string, string>;
  required_fields: string[];
  optional_fields: string[];
  suggested_mapping: Record<string, string> | null;
  sample: string[][] | null;
  created_at: string;
  completed_at: string | null;
};
export type ImportRow = {
  row_number: number;
  status: string;
  errors: { field: string | null; message: string }[];
  raw: string[];
  normalized: Record<string, string>;
  possible_duplicate_of: string | null;
};
export type Listing<T> = { items: T[]; total: number; limit: number; offset: number };

export type DocStatus = "UPLOADED" | "PROCESSING" | "EXTRACTED" | "FAILED" | "UNAVAILABLE" | "CONFIRMED";
export type FinanceDocument = {
  id: string;
  kind: "RECEIPT" | "INVOICE" | "OTHER";
  filename: string;
  content_type: string;
  size_bytes: number;
  status: DocStatus;
  failure_reason: string | null;
  extraction: { provider: string; fields: Record<string, string>; warnings: string[]; untrusted: boolean } | null;
  linked_type: string | null;
  linked_id: string | null;
  ocr_available: boolean;
  already_uploaded: boolean;
  uploaded_at: string;
};

export type Candidate = {
  target_type: "TRANSACTION" | "PAYMENT" | "EXPENSE";
  target_id: string;
  date: string;
  amount: string;
  currency: string;
  label: string | null;
  reference: string | null;
  has_document: boolean;
  score: number;
  signals: string[];
};
export type StatementLine = {
  id: string;
  date: string;
  amount: string;
  currency: string;
  direction: "IN" | "OUT";
  description: string | null;
  reference: string | null;
  status: "UNMATCHED" | "MATCHED" | "IGNORED";
  ignore_reason: string | null;
  suggestion: "NONE" | "WEAK" | "STRONG" | "AMBIGUOUS" | null;
  candidates: Candidate[] | null;
  match: { id: string; target_type: string; target_id: string; target_label: string | null; target_still_valid: boolean; score: number; signals: string[] } | null;
};
export type UnmatchedRecord = { target_type: string; target_id: string; date: string; amount: string; currency: string; label: string | null; reference: string | null };

const MONEY_SKIP = new Set(["status", "percent_used", "margin_percent", "budget_used_percent", "invoices"]);
export const isMoneyColumn = (column: string): boolean => !MONEY_SKIP.has(column);
export const isPercentColumn = (column: string): boolean => column.endsWith("percent") || column === "percent_used";
export const humanize = (column: string): string => column.replace(/_/g, " ").replace(/^./, (c) => c.toUpperCase());

/** Saves a downloaded Blob through the browser (an object URL that is revoked straight away). */
export function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
