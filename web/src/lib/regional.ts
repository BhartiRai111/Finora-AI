// Shapes of the regional-settings, exchange-rate, conversion, GST and accounting-integration APIs. Money is a decimal STRING computed by the server;
// the browser never converts, rounds or adds amounts. Regional settings only change how values are DISPLAYED.

export type RegionalSettings = {
  domain: "PERSONAL" | "BUSINESS";
  timezone: string;
  today: string;
  display_currency: string | null;
  base_currency: string | null;
  locale: string | null;
  date_format: "ISO" | "DMY" | "MDY" | null;
  fiscal_year_start_month: number;
  reporting_period: "MONTH" | "QUARTER" | "FISCAL_YEAR";
  rate_stale_after_days: number;
  supported: { currencies: string[]; locales: string[]; date_formats: string[]; reporting_periods: string[] };
  notes: string[];
};

export type RateView = {
  id: string;
  base_currency: string;
  quote_currency: string;
  rate: string;
  rate_date: string;
  source: string;
  source_note: string | null;
  status: "ACTIVE" | "VOIDED";
  void_reason: string | null;
};
export type RateStatus = {
  automatic_source: { status: string; message: string };
  manual_entry: { available: boolean; source: string; can_write: boolean };
  stale_after_days: number;
  notes: string[];
};

export type RateRef = { status: string; rate: string | null; rate_date: string | null; age_days: number | null; source: string | null; message: string | null };
export type ConvertedRow = { period: string; rate_as_of: string; rate: RateRef; values: Record<string, string> };
export type CurrencyConversion = {
  currency: string;
  status: "SAME" | "CONVERTED" | "CONVERTED_WITH_STALE_RATES" | "UNAVAILABLE";
  message: string | null;
  rows: ConvertedRow[];
  totals: Record<string, string>;
  totals_rate: RateRef | null;
};
export type Conversion = {
  reporting_currency: string;
  allow_stale: boolean;
  method: string;
  currencies: CurrencyConversion[];
  combined_totals: Record<string, string> | null;
  combined_unavailable_reason: string | null;
  notes: string[];
};

export type GstRegistration = { gst_registered: boolean; gstin: string | null; state_code: string | null; state_name: string | null; gstin_visible: boolean };
export type GstBreakdown = { tax_percent: string; taxable_amount: string; cgst: string; sgst: string; igst: string; total_tax: string };
export type InvoiceGst = {
  status: "NOT_RECORDED" | "RECORDED" | "PREVIEW";
  invoice_id: string;
  supplier_state: string | null;
  recipient_gstin: string | null;
  place_of_supply_state: string | null;
  place_of_supply_name: string | null;
  supply_type: "INTRA_STATE" | "INTER_STATE" | null;
  snapshot_stale: boolean;
  invoice_total: string;
  invoice_tax_total: string;
  breakdown: GstBreakdown[];
  warnings: string[];
};
export type ExpenseGst = {
  status: "NOT_RECORDED" | "RECORDED";
  expense_id: string;
  vendor_gstin: string | null;
  place_of_supply_state: string | null;
  supply_type: string | null;
  taxable_amount: string | null;
  cgst: string | null;
  sgst: string | null;
  igst: string | null;
  total_tax: string | null;
  warnings: string[];
};
type Side = { taxable_amount: string; cgst: string; sgst: string; igst: string; total_tax: string };
export type GstSummary = {
  format: string;
  official_filing_format: boolean;
  notice: string;
  period: { from: string; to: string };
  registration: { gst_registered: boolean; gstin_masked: string | null; state_name: string | null };
  sales: Side & { invoices_included: number; excluded_invoices: Record<string, number>; excluded_non_inr_invoices: Record<string, number> };
  purchases: Side & { expenses_included: number; excluded_expenses: Record<string, number>; note: string };
  reconciliation: Record<string, string | boolean>;
  flags: string[];
  limitations: string[];
};
export type GstReference = { states: Record<string, string>; supported: string[]; unsupported: string[] };

export type AccountingStatus = {
  provider: { key: string; status: string; approved: boolean };
  message: string;
  capabilities: { oauth_connect: boolean; webhooks: boolean; sync: boolean };
  token_vault: { available: boolean };
  connections: { provider: string; status: string; token_expires_at: string; scopes: string }[];
  pending_decisions: string[];
};

const MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
export const monthName = (m: number): string => MONTHS[m - 1] ?? String(m);

/** A decimal amount with the workspace's grouping and decimal separator, built from the exact string (no floating point). */
export function formatAmountLocale(amount: string, currency: string, locale: string | null): string {
  const match = /^(-?)(\d+)(\.\d+)?$/.exec(amount);
  if (!match) return `${currency} ${amount}`;
  const [, sign = "", integer = "", fraction = ""] = match;
  const loc = locale ?? "en-IN";
  let grouped = integer;
  let decimal = ".";
  try {
    grouped = new Intl.NumberFormat(loc, { useGrouping: true, maximumFractionDigits: 0 }).format(BigInt(integer));
    decimal = new Intl.NumberFormat(loc, { minimumFractionDigits: 1 }).formatToParts(1.1).find((p) => p.type === "decimal")?.value ?? ".";
  } catch {
    grouped = integer;
  }
  return `${currency} ${sign}${grouped}${fraction ? decimal + fraction.slice(1) : ""}`;
}

/** An ISO date (YYYY-MM-DD) written in the workspace's preferred order; anything else is returned unchanged. */
export function formatDateText(iso: string, format: "ISO" | "DMY" | "MDY" | null): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
  if (!m || !format || format === "ISO") return iso;
  const [, y, mo, d] = m;
  return format === "DMY" ? `${d}/${mo}/${y}` : `${mo}/${d}/${y}`;
}

/** Client-side check of a rate the way the server checks it; the server stays authoritative. */
export function rateProblem(text: string): string | null {
  const t = text.trim();
  if (!/^\d+(\.\d+)?$/.test(t)) return "Enter the rate as a positive number such as 83.2150.";
  if (Number(t) <= 0) return "The rate must be greater than zero.";
  if ((t.split(".")[1] ?? "").replace(/0+$/, "").length > 10) return "A rate can have at most 10 decimal places.";
  if (Number(t) > 1_000_000_000) return "That rate is too large.";
  return null;
}

export const STATUS_TEXT: Record<CurrencyConversion["status"], string> = {
  SAME: "Same as the reporting currency",
  CONVERTED: "Converted",
  CONVERTED_WITH_STALE_RATES: "Converted using stale rates you accepted",
  UNAVAILABLE: "Conversion unavailable",
};
