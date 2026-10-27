// Types and helpers for personal finance. Amounts are decimal STRINGS end to end: they are never converted to JavaScript
// numbers, so no floating-point error can creep into what the user sees or sends.

export type AccountType = "CASH" | "BANK" | "WALLET" | "INVESTMENT" | "OTHER_ASSET" | "CREDIT_CARD" | "LOAN" | "OTHER_LIABILITY";

export const ACCOUNT_TYPES: { value: AccountType; label: string }[] = [
  { value: "BANK", label: "Bank account" },
  { value: "CASH", label: "Cash" },
  { value: "WALLET", label: "Wallet" },
  { value: "INVESTMENT", label: "Investment" },
  { value: "OTHER_ASSET", label: "Other asset" },
  { value: "CREDIT_CARD", label: "Credit card" },
  { value: "LOAN", label: "Loan" },
  { value: "OTHER_LIABILITY", label: "Other liability" },
];

export const CURRENCIES = ["INR", "USD", "EUR", "GBP", "AED", "SGD", "AUD", "CAD", "CHF", "NZD", "HKD", "SAR", "ZAR", "JPY", "KRW", "KWD", "BHD", "OMR"];

export type Account = {
  id: string;
  name: string;
  type: AccountType;
  currency: string;
  opening_balance: string;
  opening_date: string;
  balance: string;
  liability: boolean;
  archived: boolean;
  version: number;
};

export type TxKind = "INCOME" | "EXPENSE" | "TRANSFER" | "REFUND" | "ADJUSTMENT";

export type Transaction = {
  id: string;
  kind: TxKind;
  status: "DRAFT" | "POSTED" | "VOIDED";
  account_id: string;
  to_account_id: string | null;
  amount: string;
  currency: string;
  to_amount: string | null;
  to_currency: string | null;
  direction: "INCREASE" | "DECREASE" | null;
  category_id: string | null;
  refund_of: string | null;
  occurred_on: string;
  payee: string | null;
  note: string | null;
  tags: string[];
  version: number;
  void_reason: string | null;
};

export type TransactionPage = { items: Transaction[]; next_cursor: string | null };

export type Category = { id: string; parent_id: string | null; name: string; kind: "INCOME" | "EXPENSE"; archived: boolean; version: number };

export type BudgetItem = {
  id: string;
  category_id: string | null;
  category_name: string | null;
  amount: string;
  currency: string;
  spent: string;
  remaining: string;
  percent_used: string;
  status: "UNDER" | "WARNING" | "OVER";
  version: number;
};

export type Goal = {
  id: string;
  name: string;
  target_amount: string;
  currency: string;
  target_date: string | null;
  saved: string;
  remaining: string;
  percent: string;
  status: "IN_PROGRESS" | "ACHIEVED" | "ARCHIVED";
  months_left: number | null;
  required_monthly: string | null;
  version: number;
  contributions?: { id: string; amount: string; contributed_on: string; note: string | null }[];
};

export type Commitment = {
  id: string;
  kind: "BILL" | "SUBSCRIPTION";
  name: string;
  amount: string;
  currency: string;
  frequency: "ONCE" | "WEEKLY" | "MONTHLY" | "QUARTERLY" | "YEARLY";
  next_due_date: string | null;
  overdue: boolean;
  status: "ACTIVE" | "PAUSED" | "CANCELLED" | "PAID";
  account_id: string | null;
  annualized_cost: string | null;
  monthly_equivalent: string | null;
  version: number;
};

export type RecurringRule = {
  id: string;
  kind: "INCOME" | "EXPENSE";
  account_id: string;
  amount: string;
  currency: string;
  frequency: "DAILY" | "WEEKLY" | "MONTHLY" | "YEARLY";
  interval_count: number;
  start_date: string;
  end_date: string | null;
  next_occurrence: string | null;
  auto_post: boolean;
  active: boolean;
  version: number;
};

export type CurrencyNetWorth = {
  currency: string;
  assets: string;
  liabilities: string;
  net_worth: string;
  components: { account_id: string; name: string; balance: string; liability: boolean }[];
};

export type CurrencyCashflow = { currency: string; income: string; gross_expense: string; refunds: string; net_expense: string; net: string };

/** A fresh idempotency key for one user intent (one click on "Save"). */
export function newKey(): string {
  return crypto.randomUUID();
}

/**
 * Groups the integer digits of a decimal string (Indian lakh/crore grouping for INR, thousands otherwise) without ever
 * parsing it as a number. "-1234567.50" -> "-12,34,567.50" for INR.
 */
export function groupAmount(amount: string, currency: string): string {
  const match = /^(-?)(\d+)(\.\d+)?$/.exec(amount);
  if (!match) return amount;
  const [, sign = "", integer = "", fraction = ""] = match;
  let grouped: string;
  if (currency === "INR" && integer.length > 3) {
    const head = integer.slice(0, -3);
    grouped = head.replace(/\B(?=(\d{2})+(?!\d))/g, ",") + "," + integer.slice(-3);
  } else {
    grouped = integer.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  }
  return `${sign}${grouped}${fraction}`;
}

export function formatMoney(amount: string, currency: string): string {
  return `${currency} ${groupAmount(amount, currency)}`;
}

/** Decimal places a currency allows (so forms can reject over-precise input before the server does). */
export function currencyDecimals(currency: string): number {
  if (currency === "JPY" || currency === "KRW") return 0;
  if (currency === "KWD" || currency === "BHD" || currency === "OMR") return 3;
  return 2;
}

/** Client-side check only; the server is authoritative. Returns an error message or null. */
export function validateAmountText(text: string, currency: string, options: { allowZero?: boolean; allowNegative?: boolean } = {}): string | null {
  const t = text.trim();
  const pattern = options.allowNegative ? /^-?\d{1,15}(\.\d+)?$/ : /^\d{1,15}(\.\d+)?$/;
  if (!pattern.test(t)) return "Enter an amount like 1250.50.";
  const decimals = t.includes(".") ? (t.split(".")[1]?.length ?? 0) : 0;
  if (decimals > currencyDecimals(currency)) return `${currency} allows at most ${currencyDecimals(currency)} decimal places.`;
  const zero = /^-?0+(\.0+)?$/.test(t);
  if (zero && !options.allowZero) return "The amount must be greater than zero.";
  return null;
}

export function todayLocal(): string {
  const d = new Date();
  const month = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${d.getFullYear()}-${month}-${day}`;
}

export function currentMonth(): string {
  return todayLocal().slice(0, 7);
}
