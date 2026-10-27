import type { Role } from "./types";

// Shapes returned by the business-finance API. Money is always a decimal STRING with the currency's exact precision; the
// browser never parses or calculates it (all figures are computed by the backend).

export type Paged<T> = { items: T[]; total: number; limit: number; offset: number };

export type Party = {
  id: string;
  name: string;
  email: string | null;
  phone: string | null;
  gstin: string | null;
  address: string | null;
  notes: string | null;
  payment_terms_days: number | null;
  archived: boolean;
  version: number;
};

export type PartyDetail = Party & {
  balances?: { currency: string; invoiced: string; paid: string; outstanding: string; overdue: string }[] | null;
  unallocated_payments?: { currency: string; amount: string }[] | null;
  expenses?: { currency: string; amount: string }[] | null;
  invoice_count?: number;
};

export type ProjectStatus = "PLANNED" | "ACTIVE" | "ON_HOLD" | "COMPLETED" | "CANCELLED";
export const PROJECT_STATUSES: ProjectStatus[] = ["PLANNED", "ACTIVE", "ON_HOLD", "COMPLETED", "CANCELLED"];

export type Profitability = {
  project_id: string;
  project_name: string;
  currency: string;
  budget: string | null;
  costs: string;
  budget_remaining: string | null;
  budget_used_percent: string | null;
  over_budget: boolean;
  revenue_net: string;
  invoiced_total: string;
  received: string;
  outstanding: string;
  profit: string;
  margin_percent: string | null;
};

export type Project = {
  id: string;
  name: string;
  code: string | null;
  client_id: string | null;
  client_name: string | null;
  status: ProjectStatus;
  currency: string;
  budget_amount: string | null;
  start_date: string | null;
  end_date: string | null;
  description: string | null;
  archived: boolean;
  version: number;
};

export type ProjectDetail = Project & { profitability: Profitability };

export type Expense = {
  id: string;
  expense_date: string;
  amount: string;
  currency: string;
  vendor_id: string | null;
  vendor_name: string | null;
  project_id: string | null;
  project_name: string | null;
  category_id: string | null;
  category_name: string | null;
  description: string;
  reference: string | null;
  status: "POSTED" | "VOIDED";
  void_reason: string | null;
  version: number;
};

export type ExpenseCategory = { id: string; name: string; archived: boolean; version: number };

export type InvoiceStatus = "DRAFT" | "ISSUED" | "CANCELLED";
export type DisplayStatus = "DRAFT" | "CANCELLED" | "PAID" | "OVERDUE" | "PARTIALLY_PAID" | "UNPAID";

export type InvoiceLine = {
  position: number;
  description: string;
  quantity: string;
  unit_price: string;
  gross_amount: string;
  discount: string;
  net_amount: string;
  tax_percent: string;
  tax_amount: string;
  line_total: string;
};

export type Invoice = {
  id: string;
  number: string | null;
  status: InvoiceStatus;
  display_status: DisplayStatus;
  overdue: boolean;
  days_overdue: number;
  client_id: string;
  client_name: string;
  project_id: string | null;
  project_name: string | null;
  invoice_date: string;
  due_date: string;
  currency: string;
  subtotal: string;
  discount_total: string;
  tax_total: string;
  total: string;
  paid: string;
  outstanding: string;
  notes: string | null;
  cancel_reason: string | null;
  version: number;
  lines?: InvoiceLine[] | null;
  payments?: { payment_id: string; payment_date: string; amount: string; method: string; reference: string | null; payment_status: string }[] | null;
};

export type PaymentMethod = "BANK_TRANSFER" | "UPI" | "CASH" | "CHEQUE" | "CARD" | "OTHER";
export const PAYMENT_METHODS: { value: PaymentMethod; label: string }[] = [
  { value: "BANK_TRANSFER", label: "Bank transfer" },
  { value: "UPI", label: "UPI" },
  { value: "CASH", label: "Cash" },
  { value: "CHEQUE", label: "Cheque" },
  { value: "CARD", label: "Card" },
  { value: "OTHER", label: "Other" },
];

export type Payment = {
  id: string;
  client_id: string;
  client_name: string;
  payment_date: string;
  amount: string;
  currency: string;
  method: PaymentMethod;
  reference: string | null;
  note: string | null;
  status: "RECORDED" | "VOIDED";
  void_reason: string | null;
  allocated: string;
  unallocated: string;
  version: number;
  allocations?: { invoice_id: string; invoice_number: string; amount: string }[] | null;
};

export type CurrencySummary = {
  currency: string;
  invoiced_net: string;
  invoiced_tax: string;
  invoiced_total: string;
  invoice_count: number;
  received: string;
  expenses: string;
  net_cash: string;
  operating_result: string;
  receivables: string;
  overdue: string;
  aging: { label: string; amount: string }[];
  unallocated_payments: string;
  expenses_by_category: { category: string; amount: string }[];
};

export type BusinessSettings = {
  timezone: string;
  base_currency: string;
  company_name: string | null;
  invoice_prefix: string;
  invoice_padding: number;
  invoice_next_number: number;
  default_terms_days: number;
  next_invoice_number_preview: string;
  today: string;
};

export const STATUS_LABEL: Record<DisplayStatus, string> = {
  DRAFT: "Draft",
  CANCELLED: "Cancelled",
  PAID: "Paid",
  OVERDUE: "Overdue",
  PARTIALLY_PAID: "Partially paid",
  UNPAID: "Unpaid",
};

// UI HINTS ONLY (they decide what to show). The server enforces the permission matrix on every request.
const FULL_READ: readonly Role[] = ["OWNER", "ADMIN", "ACCOUNTANT", "AUDITOR"];
const WRITERS: readonly Role[] = ["OWNER", "ACCOUNTANT"];

export const businessAccess = {
  /** Can see anything in the business-finance area. */
  any: (role: Role): boolean => FULL_READ.includes(role) || role === "CONTRIBUTOR",
  read: (role: Role): boolean => FULL_READ.includes(role),
  write: (role: Role): boolean => WRITERS.includes(role),
  expensesRead: (role: Role): boolean => FULL_READ.includes(role) || role === "CONTRIBUTOR",
  expensesCreate: (role: Role): boolean => WRITERS.includes(role) || role === "CONTRIBUTOR",
  settingsWrite: (role: Role): boolean => role === "OWNER" || role === "ADMIN",
};
