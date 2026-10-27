import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { FinanceProvider } from "@/lib/FinanceContext";
import type { AccountingStatus, Conversion, GstSummary, InvoiceGst, RateStatus, RateView, RegionalSettings } from "@/lib/regional";
import { formatAmountLocale, formatDateText, rateProblem } from "@/lib/regional";
import { mockApi, problem, renderWithSession, USER } from "@/test-utils";
import type { Role, Workspace } from "@/lib/types";
import { AccountingPanel } from "../accounting/AccountingPanel";
import { BusinessApp } from "../business/BusinessApp";
import { GstPanel } from "../gst/GstPanel";
import { ReportsPanel } from "../insights/ReportsPanel";
import { RegionalPanel } from "./RegionalPanel";

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));
afterEach(() => vi.unstubAllGlobals());

const me = { "GET /v1/me": () => ({ status: 200, body: USER }) };
const wrap = (ui: React.ReactElement) => renderWithSession(<FinanceProvider space="p1">{ui}</FinanceProvider>);

const SETTINGS: RegionalSettings = {
  domain: "PERSONAL", timezone: "UTC", today: "2026-03-15", display_currency: null, base_currency: null, locale: null, date_format: null, fiscal_year_start_month: 1, reporting_period: "MONTH",
  rate_stale_after_days: 7, supported: { currencies: ["EUR", "INR", "USD"], locales: ["en-IN", "en-US"], date_formats: ["ISO", "DMY", "MDY"], reporting_periods: ["MONTH", "QUARTER", "FISCAL_YEAR"] }, notes: [],
};
const RATE_STATUS = (canWrite: boolean): RateStatus => ({ automatic_source: { status: "NOT_CONFIGURED", message: "No exchange-rate provider has been approved or configured, so no rate is fetched automatically." },
  manual_entry: { available: true, source: "USER_SUPPLIED", can_write: canWrite }, stale_after_days: 7, notes: [] });
const RATE: RateView = { id: "r1", base_currency: "USD", quote_currency: "INR", rate: "83.215", rate_date: "2026-03-10", source: "USER_SUPPLIED", source_note: "bank card", status: "ACTIVE", void_reason: null };
const regionalRoutes = (canWrite = true, rates: RateView[] = []) => ({
  ...me,
  "GET /v1/spaces/p1/finance-regional": () => ({ status: 200, body: SETTINGS }),
  "GET /v1/spaces/p1/finance-rates/status": () => ({ status: 200, body: RATE_STATUS(canWrite) }),
  "GET /v1/spaces/p1/finance-rates?include_voided=true&limit=100": () => ({ status: 200, body: { items: rates } }),
});

describe("regional helpers", () => {
  it("formats exact amounts with the locale's separators and never floats", () => {
    expect(formatAmountLocale("1234567.50", "INR", "en-IN")).toBe("INR 12,34,567.50");
    expect(formatAmountLocale("1234567.50", "USD", "en-US")).toBe("USD 1,234,567.50");
    expect(formatAmountLocale("-1234.05", "EUR", "de-DE")).toBe("EUR -1.234,05");
    expect(formatAmountLocale("12345678901234567890.12", "USD", "en-US")).toBe("USD 12,345,678,901,234,567,890.12");
    expect(formatAmountLocale("1500", "JPY", "en-US")).toBe("JPY 1,500");
    expect(formatAmountLocale("not a number", "INR", "en-IN")).toBe("INR not a number");
  });

  it("writes dates in the chosen order and leaves unknown text alone", () => {
    expect(formatDateText("2026-03-15", "DMY")).toBe("15/03/2026");
    expect(formatDateText("2026-03-15", "MDY")).toBe("03/15/2026");
    expect(formatDateText("2026-03-15", "ISO")).toBe("2026-03-15");
    expect(formatDateText("2026-03-15", null)).toBe("2026-03-15");
    expect(formatDateText("March", "DMY")).toBe("March");
  });

  it("checks rates the way the server does", () => {
    expect(rateProblem("83.2150")).toBeNull();
    expect(rateProblem("0.0000000001")).toBeNull();
    for (const bad of ["", "abc", "0", "0.0", "-1", "1e3", "83,5", "0.12345678901", "1000000001"]) expect(rateProblem(bad), bad).not.toBeNull();
  });
});

describe("regional settings", () => {
  it("shows defaults, validates, saves the chosen preferences and confirms", async () => {
    const api = mockApi({ ...regionalRoutes(), "PUT /v1/spaces/p1/finance-regional": () => ({ status: 200, body: { ...SETTINGS, locale: "en-IN" } }) });
    wrap(<RegionalPanel />);
    expect(await screen.findByRole("form", { name: "Regional settings" })).toBeInTheDocument();
    expect(screen.getByText(/never changed/)).toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText("Number format (locale)"), "en-IN");
    await userEvent.selectOptions(screen.getByLabelText("Date format"), "DMY");
    await userEvent.selectOptions(screen.getByLabelText("Fiscal year starts in"), "4");
    await userEvent.selectOptions(screen.getByLabelText("Display currency"), "USD");
    await userEvent.clear(screen.getByLabelText("Treat a rate as stale after (days)"));
    await userEvent.type(screen.getByLabelText("Treat a rate as stale after (days)"), "999");
    await userEvent.click(screen.getByRole("button", { name: "Save settings" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("0 to 365");
    expect(api.calls.some((c) => c.key.startsWith("PUT"))).toBe(false);
    await userEvent.clear(screen.getByLabelText("Treat a rate as stale after (days)"));
    await userEvent.type(screen.getByLabelText("Treat a rate as stale after (days)"), "3");
    await userEvent.click(screen.getByRole("button", { name: "Save settings" }));
    expect(await screen.findByText("Settings saved.")).toBeInTheDocument();
    const body = api.calls.find((c) => c.key.startsWith("PUT"))!.body as Record<string, unknown>;
    expect(body).toMatchObject({ timezone: "UTC", display_currency: "USD", locale: "en-IN", date_format: "DMY", fiscal_year_start_month: 4, reporting_period: "MONTH", rate_stale_after_days: 3 });
  });

  it("rejects an unknown time zone before calling the server and shows server refusals", async () => {
    let n = 0;
    mockApi({ ...regionalRoutes(), "PUT /v1/spaces/p1/finance-regional": () => (n++ === 0 ? problem(422, "VALIDATION_FAILED", "locale must be one of [en-IN]") : { status: 200, body: SETTINGS }) });
    wrap(<RegionalPanel />);
    const tz = await screen.findByLabelText("Time zone");
    await userEvent.clear(tz);
    await userEvent.type(tz, "not a zone");
    await userEvent.click(screen.getByRole("button", { name: "Save settings" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Enter a time zone name");
    await userEvent.clear(tz);
    await userEvent.type(tz, "Asia/Kolkata");
    await userEvent.click(screen.getByRole("button", { name: "Save settings" }));
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("locale must be one of"));
  });

  it("is read-only for a role that cannot change settings or rates", async () => {
    mockApi(regionalRoutes(false, [RATE]));
    wrap(<RegionalPanel />);
    expect(await screen.findByText(/view these settings but not change them/)).toBeInTheDocument();
    expect(screen.getByLabelText("Time zone")).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Save settings" })).not.toBeInTheDocument();
    expect(screen.queryByRole("form", { name: "Add an exchange rate" })).not.toBeInTheDocument();
    expect(screen.getByText(/view exchange rates but not enter them/)).toBeInTheDocument();
    expect(await screen.findByText("1 USD = INR")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Void" })).not.toBeInTheDocument();
  });

  it("reports a failed load and retries", async () => {
    let n = 0;
    mockApi({ ...regionalRoutes(), "GET /v1/spaces/p1/finance-regional": () => (n++ === 0 ? problem(403, "FORBIDDEN") : { status: 200, body: SETTINGS }) });
    wrap(<RegionalPanel />);
    expect(await screen.findByRole("alert")).toHaveTextContent("do not have permission");
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("form", { name: "Regional settings" })).toBeInTheDocument();
  });
});

describe("exchange rates", () => {
  it("says plainly that there is no automatic source and that nothing is converted without rates", async () => {
    mockApi(regionalRoutes());
    wrap(<RegionalPanel />);
    expect(await screen.findByText(/No exchange-rate provider has been approved or configured/)).toBeInTheDocument();
    expect(await screen.findByText(/nothing is converted/)).toBeInTheDocument();
  });

  it("validates a rate, saves it as user-supplied and refreshes the list", async () => {
    let saved = false;
    const api = mockApi({
      ...regionalRoutes(),
      "GET /v1/spaces/p1/finance-rates?include_voided=true&limit=100": () => ({ status: 200, body: { items: saved ? [RATE] : [] } }),
      "POST /v1/spaces/p1/finance-rates": () => {
        saved = true;
        return { status: 201, body: RATE };
      },
    });
    wrap(<RegionalPanel />);
    await screen.findByRole("form", { name: "Add an exchange rate" });
    await userEvent.selectOptions(screen.getByLabelText("To (equals)"), "USD");
    await userEvent.type(screen.getByLabelText("Rate"), "83");
    await userEvent.click(screen.getByRole("button", { name: "Add rate" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("must be different");
    await userEvent.selectOptions(screen.getByLabelText("To (equals)"), "INR");
    await userEvent.clear(screen.getByLabelText("Rate"));
    await userEvent.type(screen.getByLabelText("Rate"), "0");
    await userEvent.click(screen.getByRole("button", { name: "Add rate" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("greater than zero");
    await userEvent.clear(screen.getByLabelText("Rate"));
    await userEvent.type(screen.getByLabelText("Rate"), "83.2150");
    await userEvent.click(screen.getByRole("button", { name: "Add rate" }));
    expect(await screen.findByText(/recorded as user-supplied/)).toBeInTheDocument();
    expect(api.calls.find((c) => c.key === "POST /v1/spaces/p1/finance-rates")!.body).toMatchObject({ base_currency: "USD", quote_currency: "INR", rate: "83.2150", rate_date: "2026-03-15" });
    expect(await screen.findByText("1 USD = INR")).toBeInTheDocument();
    expect(screen.getByText(/User-supplied \(bank card\)/)).toBeInTheDocument();
  });

  it("shows a duplicate-rate refusal and voids a rate with a reason", async () => {
    vi.stubGlobal("prompt", vi.fn(() => "typo"));
    window.prompt = vi.fn(() => "typo");
    const api = mockApi({
      ...regionalRoutes(true, [RATE]),
      "POST /v1/spaces/p1/finance-rates": () => problem(409, "CONFLICT", "An active rate for USD/INR on 2026-03-15 already exists; void it first to enter a corrected one"),
      "POST /v1/spaces/p1/finance-rates/r1/void": () => ({ status: 200, body: { ...RATE, status: "VOIDED", void_reason: "typo" } }),
    });
    wrap(<RegionalPanel />);
    await userEvent.type(await screen.findByLabelText("Rate"), "84");
    await userEvent.click(screen.getByRole("button", { name: "Add rate" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("already exists");
    await userEvent.click(screen.getByRole("button", { name: "Void" }));
    await waitFor(() => expect(api.calls.some((c) => c.key.endsWith("/void"))).toBe(true));
    expect(api.calls.find((c) => c.key.endsWith("/void"))!.body).toEqual({ reason: "typo" });
  });
});

const CASH = { report: "cash-flow", title: "Income, expenses and net cash flow", domain: "PERSONAL", from: "2026-01-01", to: "2026-02-28", granularity: "MONTH", definitions: [], notes: [],
  currencies: [{ currency: "USD", columns: ["income"], rows: [{ period: "2026-01", start: "2026-01-01", end: "2026-01-31", activity: true, values: { income: "100.10" } }], totals: { income: "100.10" } }], comparison: null };
const FORECAST = { metric: "net", kind: "ESTIMATE", label: "Estimate", method: "m", history_from: "2025-09-01", history_to: "2026-02-28", lookback_months: 6, horizon_months: 3, assumptions: [], ai_explanation: { status: "UNAVAILABLE", message: "x" }, currencies: [] };
const CONVERTED = (status: Conversion["currencies"][number]["status"]): { report: typeof CASH; conversion: Conversion } => ({
  report: CASH,
  conversion: {
    reporting_currency: "INR", allow_stale: false, method: "per-period conversion", notes: ["Original amounts are shown unchanged."],
    currencies: [status === "UNAVAILABLE"
      ? { currency: "USD", status, message: "Not converted: 2026-01: No USD/INR rate dated on or before 2026-01-31 has been entered. The original USD figures above are unaffected.", rows: [], totals: {}, totals_rate: null }
      : { currency: "USD", status, message: null, rows: [{ period: "2026-01", rate_as_of: "2026-01-31", rate: { status: "APPLIED", rate: "83", rate_date: "2026-01-31", age_days: 0, source: "USER_SUPPLIED", message: null }, values: { income: "8308.30" } }], totals: { income: "8308.30" }, totals_rate: null }],
    combined_totals: status === "UNAVAILABLE" ? null : { income: "8308.30" },
    combined_unavailable_reason: status === "UNAVAILABLE" ? "At least one currency could not be converted, so no combined total is shown. Currencies are never added together without a valid rate." : null,
  },
});
const reportRoutes = (conv: ReturnType<typeof CONVERTED> | "error") => ({
  ...me,
  "GET /v1/spaces/p1/finance-regional": () => ({ status: 200, body: { ...SETTINGS, locale: "en-US" } }),
  "GET /v1/spaces/p1/finance-reports/cash-flow?granularity=MONTH": () => ({ status: 200, body: CASH }),
  "GET /v1/spaces/p1/finance-forecast?metric=net&lookback_months=6&horizon_months=3": () => ({ status: 200, body: FORECAST }),
  "GET /v1/spaces/p1/finance-reports/cash-flow/converted?granularity=MONTH&reporting_currency=INR&allow_stale=false": () => (conv === "error" ? problem(500, "INTERNAL") : { status: 200, body: conv }),
});

describe("multi-currency reports", () => {
  it("shows the original amounts and, beside them, the converted view with each rate, its date and its source", async () => {
    mockApi(reportRoutes(CONVERTED("CONVERTED")));
    wrap(<ReportsPanel domain="PERSONAL" canExport />);
    await screen.findAllByText("USD 100.10", { selector: "td" });
    await userEvent.selectOptions(await screen.findByLabelText("Also show in"), "INR");
    await userEvent.click(screen.getByRole("button", { name: "Show report" }));
    const converted = await screen.findByRole("region", { name: "Converted to INR" });
    expect(within(converted).getByText(/original amounts above are unchanged/)).toBeInTheDocument();
    expect(within(converted).getByText("USD → INR: Converted")).toBeInTheDocument();
    expect(within(converted).getByText("INR 8,308.30", { selector: "td" })).toBeInTheDocument();
    expect(within(converted).getByText("83")).toBeInTheDocument();
    expect(within(converted).getByText("User-supplied")).toBeInTheDocument();
    expect(within(converted).getByRole("group", { name: "Combined total" })).toHaveTextContent("INR 8,308.30");
    expect(screen.getAllByText("USD 100.10", { selector: "td" }).length).toBeGreaterThanOrEqual(1);
  });

  it("states a missing rate honestly, keeps the originals and shows no combined total", async () => {
    mockApi(reportRoutes(CONVERTED("UNAVAILABLE")));
    wrap(<ReportsPanel domain="PERSONAL" canExport />);
    await userEvent.selectOptions(await screen.findByLabelText("Also show in"), "INR");
    await userEvent.click(screen.getByRole("button", { name: "Show report" }));
    const unavailable = await screen.findByRole("group", { name: "USD converted to INR" });
    expect(unavailable).toHaveTextContent("Conversion unavailable");
    expect(within(unavailable).getByRole("alert")).toHaveTextContent("No USD/INR rate dated on or before 2026-01-31");
    expect(screen.queryByRole("group", { name: "Combined total" })).not.toBeInTheDocument();
    expect(screen.getByText(/never added together without a valid rate/)).toBeInTheDocument();
    expect(screen.getAllByText("USD 100.10", { selector: "td" }).length).toBeGreaterThanOrEqual(1);
  });

  it("reports a conversion failure and can retry; the original report stays visible", async () => {
    mockApi(reportRoutes("error"));
    wrap(<ReportsPanel domain="PERSONAL" canExport />);
    await userEvent.selectOptions(await screen.findByLabelText("Also show in"), "INR");
    await userEvent.click(screen.getByRole("button", { name: "Show report" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("temporarily unavailable");
    expect(screen.getAllByText("USD 100.10", { selector: "td" }).length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByRole("button", { name: "Try again" }).length).toBeGreaterThanOrEqual(1);
  });

  it("only offers the stale-rate choice once a reporting currency is chosen", async () => {
    mockApi(reportRoutes(CONVERTED("CONVERTED")));
    wrap(<ReportsPanel domain="PERSONAL" canExport />);
    await screen.findAllByText("USD 100.10", { selector: "td" });
    expect(screen.queryByLabelText("Accept stale exchange rates")).not.toBeInTheDocument();
    await userEvent.selectOptions(await screen.findByLabelText("Also show in"), "INR");
    expect(screen.getByLabelText("Accept stale exchange rates")).not.toBeChecked();
  });
});

describe("GST", () => {
  const REG = (over = {}) => ({ gst_registered: true, gstin: "27AAPFU0939F1ZV", state_code: "27", state_name: "Maharashtra", gstin_visible: true, ...over });
  const SUMMARY: GstSummary = {
    format: "FINORA_GST_PREPARATION_WORKSHEET_V1", official_filing_format: false, notice: "Preparation worksheet in Finora's own format. It is NOT an official GST return or any government filing format, and it has not been validated by a qualified professional.",
    period: { from: "2026-02-01", to: "2026-02-28" }, registration: { gst_registered: true, gstin_masked: "27**********1ZV", state_name: "Maharashtra" },
    sales: { invoices_included: 2, taxable_amount: "3103.00", cgst: "90.07", sgst: "90.08", igst: "365.00", total_tax: "545.15", excluded_invoices: { EXCLUDED_NO_GST_DETAILS: 1 }, excluded_non_inr_invoices: {} },
    purchases: { expenses_included: 1, taxable_amount: "1000.00", cgst: "90.00", sgst: "90.00", igst: "0.00", total_tax: "180.00", excluded_expenses: {}, note: "Whether any of it is claimable as input tax credit is NOT determined here." },
    reconciliation: { sales_reconciled: true, purchases_reconciled: true, included_invoice_tax_total: "545.15", excluded_invoice_tax_total: "72.00", issued_inr_invoice_tax_total: "617.15" },
    flags: ["1 issued invoice(s) excluded: EXCLUDED_NO_GST_DETAILS"], limitations: ["Tax rates are the percentages typed on invoice lines; no rate table, HSN/SAC or exemption logic is applied."],
  };
  const REFERENCE = { states: { "27": "Maharashtra", "29": "Karnataka", "97": "Other Territory" }, supported: ["IGST for inter-state supply"], unsupported: ["UTGST", "reverse charge"] };
  const INVOICE = { id: "i1", number: "INV-0001", status: "DRAFT", client_name: "Acme", currency: "INR", total: "1180.00" };
  const gstRoutes = (extra = {}) => ({
    ...me,
    "GET /v1/spaces/p1/finance-gst/reference": () => ({ status: 200, body: REFERENCE }),
    "GET /v1/spaces/p1/finance-gst/registration": () => ({ status: 200, body: REG() }),
    "GET /v1/spaces/p1/invoices?limit=100": () => ({ status: 200, body: { items: [INVOICE], total: 1, limit: 100, offset: 0 } }),
    "GET /v1/spaces/p1/expenses?limit=100": () => ({ status: 200, body: { items: [{ id: "e1", expense_date: "2026-02-10", amount: "1180.00", currency: "INR", vendor_name: "Vendor", status: "POSTED" }, { id: "e2", expense_date: "2026-02-11", amount: "10.00", currency: "USD", vendor_name: null, status: "POSTED" }], total: 2, limit: 100, offset: 0 } }),
    ...extra,
  });

  it("warns that nothing is filed or claimed as compliant and lists the unsupported cases", async () => {
    mockApi(gstRoutes());
    wrap(<GstPanel canEditRegistration canEditDetails canExport />);
    expect(await screen.findByText(/not GST-compliance software and files nothing/)).toBeInTheDocument();
    expect(screen.getByText(/not a government filing format/)).toBeInTheDocument();
    await userEvent.click(await screen.findByText("Supported and unsupported GST cases"));
    expect(screen.getByText("UTGST")).toBeInTheDocument();
    expect(screen.getByText("reverse charge")).toBeInTheDocument();
  });

  it("validates and saves the registration, and is read-only without the right", async () => {
    const api = mockApi(gstRoutes({ "PUT /v1/spaces/p1/finance-gst/registration": () => ({ status: 200, body: REG() }) }));
    wrap(<GstPanel canEditRegistration canEditDetails canExport />);
    const input = await screen.findByLabelText("GSTIN");
    await userEvent.clear(input);
    await userEvent.type(input, "BAD");
    await userEvent.click(screen.getByRole("button", { name: "Save registration" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("15 characters");
    await userEvent.clear(input);
    await userEvent.type(input, "27aapfu0939f1zv");
    await userEvent.click(screen.getByRole("button", { name: "Save registration" }));
    expect(await screen.findByText("GST registration saved.")).toBeInTheDocument();
    expect(api.calls.find((c) => c.key.startsWith("PUT"))!.body).toEqual({ gst_registered: true, gstin: "27AAPFU0939F1ZV" });
  });

  it("hides the registration actions from a read-only role and shows a masked GSTIN", async () => {
    mockApi(gstRoutes({ "GET /v1/spaces/p1/finance-gst/registration": () => ({ status: 200, body: REG({ gstin: "27**********1ZV", gstin_visible: false }) }) }));
    wrap(<GstPanel canEditRegistration={false} canEditDetails={false} canExport={false} />);
    expect(await screen.findByText(/view the registration but not change it/)).toBeInTheDocument();
    expect(screen.getByLabelText("GSTIN")).toBeDisabled();
    expect(screen.getByLabelText("GSTIN")).not.toHaveValue("27AAPFU0939F1ZV");
    expect(screen.queryByRole("button", { name: "Save registration" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Download preparation worksheet/ })).not.toBeInTheDocument();
  });

  it("shows the summary with reconciliation, flags for incomplete records and the limits", async () => {
    mockApi(gstRoutes({ "GET /v1/spaces/p1/finance-gst/summary": () => ({ status: 200, body: SUMMARY }) }));
    wrap(<GstPanel canEditRegistration canEditDetails canExport />);
    await userEvent.click(await screen.findByRole("button", { name: "Show GST summary" }));
    const sales = await screen.findByRole("table", { name: /Tax on sales \(2 invoice/ });
    expect(within(sales).getByText("90.07")).toBeInTheDocument();
    expect(within(sales).getByText("545.15")).toBeInTheDocument();
    expect(screen.getByText(/sales tax reconciles to the invoices/)).toBeInTheDocument();
    expect(screen.getByText(/Incomplete or unsupported records are left out/)).toBeInTheDocument();
    expect(screen.getByText(/EXCLUDED_NO_GST_DETAILS/)).toBeInTheDocument();
    expect(screen.getByText(/NOT an official GST return/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Download preparation worksheet/ })).toBeInTheDocument();
  });

  it("validates the period and reports a failed summary with a retry", async () => {
    let n = 0;
    mockApi(gstRoutes({ "GET /v1/spaces/p1/finance-gst/summary": () => (n++ === 0 ? problem(409, "CONFLICT", "GST support exists only in business workspaces") : { status: 200, body: SUMMARY }) }));
    wrap(<GstPanel canEditRegistration canEditDetails canExport />);
    await userEvent.type(await screen.findByLabelText("From"), "2026-02-01");
    await userEvent.click(screen.getByRole("button", { name: "Show GST summary" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Give both dates");
    await userEvent.clear(screen.getByLabelText("From"));
    await userEvent.click(screen.getByRole("button", { name: "Show GST summary" }));
    expect(await screen.findByText("GST support exists only in business workspaces")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("table", { name: /Tax on sales/ })).toBeInTheDocument();
  });

  it("previews and saves an invoice's GST split, requires the place of supply and shows frozen-invoice refusals", async () => {
    const split: InvoiceGst = { status: "PREVIEW", invoice_id: "i1", supplier_state: "27", recipient_gstin: null, place_of_supply_state: "27", place_of_supply_name: "Maharashtra", supply_type: "INTRA_STATE", snapshot_stale: false,
      invoice_total: "1180.00", invoice_tax_total: "180.00", breakdown: [{ tax_percent: "18.00", taxable_amount: "1000.00", cgst: "90.00", sgst: "90.00", igst: "0.00", total_tax: "180.00" }], warnings: ["No valid recipient GSTIN was recorded (unregistered or unknown recipient)."] };
    let saves = 0;
    const api = mockApi(gstRoutes({
      "GET /v1/spaces/p1/finance-gst/invoices/i1": () => ({ status: 200, body: { ...split, status: "NOT_RECORDED", breakdown: [], supply_type: null } }),
      "PUT /v1/spaces/p1/finance-gst/invoices/i1?preview=true": () => ({ status: 200, body: split }),
      "PUT /v1/spaces/p1/finance-gst/invoices/i1": () => (saves++ === 0 ? { status: 200, body: { ...split, status: "RECORDED" } } : problem(409, "INVOICE_IMMUTABLE", "The GST details of an issued invoice are frozen and are never recalculated")),
    }));
    wrap(<GstPanel canEditRegistration canEditDetails canExport />);
    await userEvent.selectOptions(await screen.findByLabelText("Invoice"), "i1");
    expect(await screen.findByText("No GST details recorded")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Preview split" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("place of supply");
    await userEvent.selectOptions(screen.getByLabelText("Place of supply", { selector: "#gst-place" }), "27");
    expect(screen.queryByRole("option", { name: /Other Territory/ })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Preview split" }));
    const card = await screen.findByRole("group", { name: "Invoice GST details" });
    expect(card).toHaveTextContent("Preview (not saved)");
    expect(card).toHaveTextContent("Intra-state supply: CGST + SGST");
    expect(within(card).getAllByText("90.00").length).toBe(2);
    expect(card).toHaveTextContent("No valid recipient GSTIN");
    const invoiceSection = screen.getByRole("region", { name: "GST details of an invoice" });
    await userEvent.click(within(invoiceSection).getByRole("button", { name: "Save GST details" }));
    expect(await screen.findByText("GST details saved for this invoice.")).toBeInTheDocument();
    await userEvent.click(within(invoiceSection).getByRole("button", { name: "Save GST details" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("frozen");
    expect(api.calls.filter((c) => c.key.startsWith("PUT /v1/spaces/p1/finance-gst/invoices/i1")).every((c) => (c.body as { place_of_supply: string }).place_of_supply === "27")).toBe(true);
  });

  it("flags a stale snapshot and offers no save to a role without the right", async () => {
    mockApi(gstRoutes({ "GET /v1/spaces/p1/finance-gst/invoices/i1": () => ({ status: 200, body: { status: "RECORDED", invoice_id: "i1", supplier_state: "27", recipient_gstin: null, place_of_supply_state: "27", place_of_supply_name: "Maharashtra",
      supply_type: "INTRA_STATE", snapshot_stale: true, invoice_total: "1181.00", invoice_tax_total: "181.00", breakdown: [], warnings: [] } }) }));
    wrap(<GstPanel canEditRegistration={false} canEditDetails={false} canExport={false} />);
    await userEvent.selectOptions(await screen.findByLabelText("Invoice"), "i1");
    expect(await screen.findByText(/no longer match the invoice/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save GST details" })).not.toBeInTheDocument();
  });

  it("validates a purchase bill's GST locally, lists only posted INR expenses, and shows server refusals", async () => {
    const api = mockApi(gstRoutes({ "PUT /v1/spaces/p1/finance-gst/expenses/e1": () => problem(422, "VALIDATION_FAILED", "taxable_amount plus the GST amounts must equal the expense amount INR 1180.00") }));
    wrap(<GstPanel canEditRegistration canEditDetails canExport />);
    const select = await screen.findByLabelText("Expense");
    const save = () => screen.getByRole("region", { name: "GST on a purchase bill" });
    expect(within(select).queryByText(/USD 10.00/)).not.toBeInTheDocument();
    await userEvent.click(within(save()).getByRole("button", { name: "Save GST details" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Choose an expense");
    await userEvent.selectOptions(select, "e1");
    await userEvent.click(within(save()).getByRole("button", { name: "Save GST details" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("place of supply");
    await userEvent.selectOptions(screen.getByLabelText("Place of supply", { selector: "#gst-exp-place" }), "27");
    await userEvent.type(screen.getByLabelText("Taxable amount"), "1000.005");
    await userEvent.click(within(save()).getByRole("button", { name: "Save GST details" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("at most 2 decimals");
    await userEvent.clear(screen.getByLabelText("Taxable amount"));
    await userEvent.type(screen.getByLabelText("Taxable amount"), "1000.00");
    await userEvent.type(screen.getByLabelText("CGST"), "90.00");
    await userEvent.click(within(save()).getByRole("button", { name: "Save GST details" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("must equal the expense amount");
    expect(api.calls.find((c) => c.key.includes("expenses/e1") && c.key.startsWith("PUT"))!.body).toMatchObject({ place_of_supply: "27", taxable_amount: "1000.00", cgst: "90.00" });
  });
});

describe("accounting integrations", () => {
  const STATUS: AccountingStatus = {
    provider: { key: "none", status: "NOT_CONFIGURED", approved: false },
    message: "No accounting provider has been approved or configured. Nothing is connected, nothing is synchronised, and nothing was sent to any accounting system.",
    capabilities: { oauth_connect: false, webhooks: false, sync: false }, token_vault: { available: false }, connections: [],
    pending_decisions: ["Which accounting provider(s) are approved (none is approved)", "OAuth client registration and redirect URIs for each environment (credentials are never invented)"],
  };

  it("never shows a connected status or a connect button when nothing is approved, and lists what is pending", async () => {
    mockApi({ ...me, "GET /v1/spaces/p1/finance-accounting": () => ({ status: 200, body: STATUS }) });
    wrap(<AccountingPanel />);
    expect(await screen.findByText(/Nothing is connected, nothing is synchronised/)).toBeInTheDocument();
    expect(screen.getByText("Provider: none configured")).toBeInTheDocument();
    expect(screen.getByText("Connect with OAuth: not available")).toBeInTheDocument();
    expect(screen.getByText("Synchronisation: not available")).toBeInTheDocument();
    expect(screen.getByText("Secure token storage: not configured")).toBeInTheDocument();
    expect(screen.getByText("No accounting system is connected.")).toBeInTheDocument();
    expect(screen.getByText(/Which accounting provider\(s\) are approved/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /connect/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/connected successfully/i)).not.toBeInTheDocument();
  });

  it("shows a refusal for a role without access and retries a failed load", async () => {
    let n = 0;
    mockApi({ ...me, "GET /v1/spaces/p1/finance-accounting": () => (n++ === 0 ? problem(403, "FORBIDDEN") : { status: 200, body: STATUS }) });
    wrap(<AccountingPanel />);
    expect(await screen.findByRole("alert")).toHaveTextContent("do not have permission");
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText("No accounting system is connected.")).toBeInTheDocument();
  });
});

describe("new business tabs follow the role", () => {
  const ws = (role: Role): Workspace => ({ id: "b1", name: "Acme", type: "BUSINESS", role, membership_id: "m1" });
  const empty = { items: [], total: 0, limit: 20, offset: 0 };
  const baseBiz = {
    ...me,
    "GET /v1/spaces/b1/clients?limit=100": () => ({ status: 200, body: empty }),
    "GET /v1/spaces/b1/vendors?limit=100": () => ({ status: 200, body: empty }),
    "GET /v1/spaces/b1/projects?limit=100": () => ({ status: 200, body: empty }),
    "GET /v1/spaces/b1/expense-categories": () => ({ status: 200, body: { items: [] } }),
    "GET /v1/spaces/b1/business-settings": () => ({ status: 200, body: { timezone: "Asia/Kolkata", base_currency: "INR", company_name: null, invoice_prefix: "INV-", invoice_padding: 4, invoice_next_number: 1, default_terms_days: 30, next_invoice_number_preview: "INV-0001", today: "2026-03-15" } }),
    "GET /v1/spaces/b1/business-summaries/overview": () => ({ status: 200, body: { from: "2026-03-01", to: "2026-03-15", as_of: "2026-03-15", currencies: [] } }),
    "GET /v1/spaces/b1/business-summaries/projects": () => ({ status: 200, body: { items: [] } }),
    "GET /v1/spaces/b1/expenses?limit=20&offset=0": () => ({ status: 200, body: empty }),
  };

  it.each([
    ["OWNER", ["Currency & region", "GST", "Integrations"], []],
    ["ACCOUNTANT", ["Currency & region", "GST", "Integrations"], []],
    ["ADMIN", ["Currency & region", "GST", "Integrations"], []],
  ] as [Role, string[], string[]][])("%s sees %j", async (role, shown, hidden) => {
    mockApi(baseBiz);
    renderWithSession(<BusinessApp workspace={ws(role)} />);
    for (const t of shown) expect(await screen.findByRole("tab", { name: t })).toBeInTheDocument();
    for (const t of hidden) expect(screen.queryByRole("tab", { name: t })).not.toBeInTheDocument();
  });

  it("gives a Contributor none of them", async () => {
    mockApi(baseBiz);
    renderWithSession(<BusinessApp workspace={ws("CONTRIBUTOR")} />);
    await screen.findByRole("tab", { name: "Documents" });
    for (const t of ["Currency & region", "GST", "Integrations"]) expect(screen.queryByRole("tab", { name: t })).not.toBeInTheDocument();
  });
});
