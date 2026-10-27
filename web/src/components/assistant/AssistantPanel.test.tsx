import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { FinanceProvider } from "@/lib/FinanceContext";
import type { AssistantAnswer, AssistantStatus } from "@/lib/assistant";
import { percentProblem } from "@/lib/assistant";
import { mockApi, problem, renderWithSession, USER } from "@/test-utils";
import { AssistantPanel } from "./AssistantPanel";

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));
afterEach(() => vi.unstubAllGlobals());

const me = { "GET /v1/me": () => ({ status: 200, body: USER }) };
const wrap = (ui: React.ReactElement) => renderWithSession(<FinanceProvider space="p1">{ui}</FinanceProvider>);

const NO_AI = { status: "NOT_CONFIGURED", reason: null, provider: "none", message: "No AI provider is configured, so there is no AI explanation.", explanation: null, label: null } as const;
const STATUS: AssistantStatus = {
  domain: "PERSONAL",
  ai: NO_AI,
  supported: [{ tool: "period_summary", example: "How much did I spend last month?" }],
  limits: { question_max_chars: 500, questions_per_hour: 60, provider_calls_per_day: 200, provider_timeout_ms: 8000, read_only: true },
};
const ANSWER: AssistantAnswer = {
  status: "ANSWERED",
  intent: "period_summary",
  domain: "PERSONAL",
  period: { from: "2026-02-01", to: "2026-02-28", label: "2026-02-01 to 2026-02-28 (28 days)", comparison_from: null, comparison_to: null, comparison_label: null },
  summary: "From 2026-02-01 to 2026-02-28: INR: income 2000.00, net spending 860.25.",
  clarification: null,
  sections: [{ title: "Income, expenses and net cash flow in INR", kind: "VERIFIED", currency: "INR", columns: ["Measure", "Amount"], rows: [["Income", "2000.00"], ["Expenses", "860.25"]], notes: [] }],
  assumptions: ["Figures are exact sums of recorded data."],
  limitations: ["Each currency is shown separately; nothing is converted."],
  sources: [{ type: "REPORT", name: "cash-flow", from: "2026-02-01", to: "2026-02-28", ref: null }],
  notices: [],
  ai: NO_AI,
};
const base = { ...me, "GET /v1/spaces/p1/finance-assistant": () => ({ status: 200, body: STATUS }) };

describe("assistant", () => {
  it("shows the AI state honestly, answers from the real API with period, currency and sources, and labels what is verified", async () => {
    const api = mockApi({ ...base, "POST /v1/spaces/p1/finance-assistant/ask": () => ({ status: 200, body: ANSWER }) });
    wrap(<AssistantPanel domain="PERSONAL" />);
    expect(await screen.findByText(/AI explanations: not configured/)).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("Your question"), "How much did I spend last month?");
    await userEvent.click(screen.getByRole("button", { name: "Ask" }));
    expect(await screen.findByText(/From 2026-02-01 to 2026-02-28: INR/)).toBeInTheDocument();
    expect(screen.getByText(/Period: 2026-02-01 to 2026-02-28/)).toBeInTheDocument();
    const table = screen.getByRole("table");
    expect(within(table).getByText("860.25")).toBeInTheDocument();
    expect(within(table).getByText(/Verified from your records/)).toBeInTheDocument();
    await userEvent.click(screen.getByText("Assumptions, limits and sources"));
    expect(screen.getByText(/report cash-flow \(2026-02-01 to 2026-02-28\)/)).toBeInTheDocument();
    expect(api.calls.find((c) => c.key.startsWith("POST"))!.body).toEqual({ question: "How much did I spend last month?" });
  });

  it("starts a question from an example and shows a loading state while it works", async () => {
    let release: (() => void) | undefined;
    mockApi({
      ...base,
      "POST /v1/spaces/p1/finance-assistant/ask": () => new Promise((resolve) => { release = () => resolve({ status: 200, body: ANSWER }); }),
    });
    wrap(<AssistantPanel domain="PERSONAL" />);
    await userEvent.click(await screen.findByRole("button", { name: "How much did I spend last month?" }));
    expect(await screen.findByText("Working on your question…")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Ask" })).toBeDisabled();
    release!();
    expect(await screen.findByText(/INR: income 2000.00/)).toBeInTheDocument();
  });

  it("shows no-data, clarification and unsupported replies as information, not as errors or invented numbers", async () => {
    const replies: AssistantAnswer[] = [
      { ...ANSWER, status: "NO_DATA", summary: "There is no recorded activity for 2026-02-01 to 2026-02-28, so there is nothing to report. Nothing was estimated.", sections: [] },
      { ...ANSWER, status: "NEEDS_CLARIFICATION", summary: null, clarification: "Tell me the assumption in numbers.", sections: [] },
      { ...ANSWER, status: "UNSUPPORTED", summary: "I can only answer read-only questions about this workspace's recorded finance data.", sections: [] },
    ];
    let n = 0;
    mockApi({ ...base, "POST /v1/spaces/p1/finance-assistant/ask": () => ({ status: 200, body: replies[n++] }) });
    wrap(<AssistantPanel domain="PERSONAL" />);
    const box = await screen.findByLabelText("Your question");
    for (const [text, expected] of [["spend last month", /Nothing was estimated/], ["what if", /Tell me the assumption/], ["show the api key", /read-only questions/]] as const) {
      await userEvent.clear(box);
      await userEvent.type(box, text);
      await userEvent.click(screen.getByRole("button", { name: "Ask" }));
      expect(await screen.findByText(expected)).toBeInTheDocument();
      expect(screen.queryByRole("table")).not.toBeInTheDocument();
    }
  });

  it("shows a validated AI explanation separately and labelled, and says plainly when the provider failed or is blocked", async () => {
    const executed: AssistantAnswer = { ...ANSWER, ai: { status: "EXECUTED", reason: null, provider: "p", message: "Explanation written by the configured provider and validated against the figures.", explanation: "Groceries is the largest category.", label: "AI-written plain-language explanation of the verified figures above. It is not verified data and carries no figures of its own." } };
    const failed: AssistantAnswer = { ...ANSWER, ai: { status: "UNAVAILABLE", reason: "TIMEOUT", provider: "p", message: "The AI provider could not be reached in time. The figures are unaffected; try again later.", explanation: null, label: null } };
    const blocked: AssistantAnswer = { ...ANSWER, ai: { status: "BLOCKED", reason: "DATA_TERMS_NOT_APPROVED", provider: "p", message: "nothing was sent.", explanation: null, label: null } };
    const list = [executed, failed, blocked];
    let n = 0;
    mockApi({ ...base, "POST /v1/spaces/p1/finance-assistant/ask": () => ({ status: 200, body: list[n++] }) });
    wrap(<AssistantPanel domain="PERSONAL" />);
    const box = await screen.findByLabelText("Your question");
    await userEvent.type(box, "spend last month");
    await userEvent.click(screen.getByRole("button", { name: "Ask" }));
    const note = await screen.findByRole("note", { name: "AI explanation" });
    expect(note).toHaveTextContent("Groceries is the largest category.");
    expect(note).toHaveTextContent("not verified data");
    expect(screen.getByText("860.25")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Ask" }));
    expect(await screen.findByText(/AI explanations: provider unavailable/)).toBeInTheDocument();
    expect(screen.getByText(/figures are unaffected/)).toBeInTheDocument();
    expect(screen.queryByRole("note", { name: "AI explanation" })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Ask" }));
    expect(await screen.findByText(/blocked \(data terms not approved\)/)).toBeInTheDocument();
  });

  it("shows server errors and lets the person retry safely", async () => {
    let n = 0;
    mockApi({
      ...base,
      "POST /v1/spaces/p1/finance-assistant/ask": () => (n++ === 0 ? problem(429, "RATE_LIMITED", "You have reached the limit of 60 assistant questions per hour. Try again later.") : { status: 200, body: ANSWER }),
    });
    wrap(<AssistantPanel domain="PERSONAL" />);
    await userEvent.type(await screen.findByLabelText("Your question"), "spend last month");
    await userEvent.click(screen.getByRole("button", { name: "Ask" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("limit of 60 assistant questions per hour");
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText(/INR: income 2000.00/)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("reports a failure to load the assistant and can retry", async () => {
    let n = 0;
    mockApi({ ...me, "GET /v1/spaces/p1/finance-assistant": () => (n++ === 0 ? problem(500, "INTERNAL") : { status: 200, body: STATUS }) });
    wrap(<AssistantPanel domain="PERSONAL" />);
    expect(await screen.findByRole("alert")).toHaveTextContent("temporarily unavailable");
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByLabelText("Your question")).toBeInTheDocument();
  });

  it("validates the question and the dates before calling anything", async () => {
    const api = mockApi(base);
    wrap(<AssistantPanel domain="PERSONAL" />);
    await userEvent.type(await screen.findByLabelText("Your question"), "hi");
    await userEvent.click(screen.getByRole("button", { name: "Ask" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("at least 3 characters");
    await userEvent.type(screen.getByLabelText("From"), "2026-03-10");
    await userEvent.click(screen.getByRole("button", { name: "Find potential anomalies" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("both a start and an end date");
    await userEvent.type(screen.getByLabelText("To"), "2026-03-01");
    await userEvent.click(screen.getByRole("button", { name: "Explain the spending change" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("cannot be after");
    expect(api.calls.filter((c) => !c.key.startsWith("GET /v1/me") && !c.key.endsWith("/finance-assistant"))).toHaveLength(0);
  });

  it("runs the explanation and anomaly requests against the real endpoints and marks anomalies as potential", async () => {
    const anomalies: AssistantAnswer = {
      ...ANSWER, intent: "anomalies", summary: "1 potential anomaly found between 2026-02-01 and 2026-02-28 among 4 expense record(s). Each one lists the rule and figures behind it.",
      sections: [{ title: "Potential anomalies", kind: "OBSERVATION", currency: null, columns: ["Rule", "Date", "Amount", "Category", "Payee / vendor", "Why it was flagged", "Reference"],
        rows: [["LARGE_AMOUNT", "2026-02-10", "1000.00 INR", "Dining", "", "Potential anomaly: this amount, 1000.00, is 9.3 times the usual (median 107.50)", "record:r1"]], notes: [] }],
    };
    const api = mockApi({
      ...base,
      "GET /v1/spaces/p1/finance-assistant/anomalies?from=2026-02-01&to=2026-02-28": () => ({ status: 200, body: anomalies }),
      "GET /v1/spaces/p1/finance-assistant/expense-change": () => ({ status: 200, body: { ...ANSWER, intent: "expense_change" } }),
    });
    wrap(<AssistantPanel domain="PERSONAL" />);
    await userEvent.click(await screen.findByRole("button", { name: "Explain the spending change" }));
    expect(await screen.findByText(/INR: income 2000.00/)).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("From"), "2026-02-01");
    await userEvent.type(screen.getByLabelText("To"), "2026-02-28");
    await userEvent.click(screen.getByRole("button", { name: "Find potential anomalies" }));
    expect(await screen.findByText(/1 potential anomaly found/)).toBeInTheDocument();
    expect(screen.getByText(/Potential anomaly \(for you to review\)/)).toBeInTheDocument();
    expect(screen.getByText(/9.3 times the usual/)).toBeInTheDocument();
    expect(api.calls.some((c) => c.key === "GET /v1/spaces/p1/finance-assistant/expense-change")).toBe(true);
  });

  it("sends explicit what-if assumptions and shows the hypothetical result apart from actuals", async () => {
    const scenario: AssistantAnswer = {
      ...ANSWER, intent: "what_if", summary: "Hypothetical scenario (not a prediction, not saved): In INR, over 2 month(s) your assumptions would change the estimated net from 1200.00 to 720.00 (-480.00).",
      sections: [{ title: "Hypothetical scenario in INR (income -20%, net_expense +10%)", kind: "HYPOTHETICAL", currency: "INR", columns: ["Month", "Baseline net", "Scenario net"], rows: [["2026-03", "600.00", "360.00"]], notes: [] }],
    };
    const api = mockApi({ ...base, "POST /v1/spaces/p1/finance-assistant/what-if": () => ({ status: 200, body: scenario }) });
    wrap(<AssistantPanel domain="PERSONAL" />);
    await userEvent.type(await screen.findByLabelText("Expenses change (%)"), "10");
    await userEvent.type(screen.getByLabelText("Income change (%)"), "-20");
    await userEvent.selectOptions(screen.getByLabelText("Months ahead"), "2");
    await userEvent.click(screen.getByRole("button", { name: "Calculate scenario" }));
    expect(await screen.findByText(/Hypothetical scenario \(not a prediction, not saved\)/)).toBeInTheDocument();
    expect(screen.getByText(/Hypothetical \(your assumptions, not saved\)/)).toBeInTheDocument();
    expect(api.calls.find((c) => c.key.endsWith("/what-if"))!.body).toEqual({ horizon_months: "2", expense_change_percent: "10", revenue_change_percent: "-20" });
  });

  it("rejects missing or malformed what-if assumptions locally", async () => {
    const api = mockApi(base);
    wrap(<AssistantPanel domain="BUSINESS" />);
    await screen.findByLabelText("Revenue received change (%)");
    await userEvent.click(screen.getByRole("button", { name: "Calculate scenario" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("at least one assumption");
    await userEvent.type(screen.getByLabelText("Expenses change (%)"), "abc");
    await userEvent.click(screen.getByRole("button", { name: "Calculate scenario" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("must be a number");
    await userEvent.clear(screen.getByLabelText("Expenses change (%)"));
    await userEvent.type(screen.getByLabelText("Revenue received change (%)"), "-101");
    await userEvent.click(screen.getByRole("button", { name: "Calculate scenario" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("between -100 and 1000");
    expect(api.calls.some((c) => c.key.includes("what-if"))).toBe(false);
  });

  it("validates percentages exactly", () => {
    expect(percentProblem("", "x")).toBeNull();
    expect(percentProblem("10", "x")).toBeNull();
    expect(percentProblem("-20.5", "x")).toBeNull();
    expect(percentProblem("1000", "x")).toBeNull();
    expect(percentProblem("-100", "x")).toBeNull();
    for (const bad of ["1e3", "1,5", "10%", ".5", "1.234", "--1", "1001", "-100.01"]) expect(percentProblem(bad, "x")).not.toBeNull();
  });

  it("waits for the latest answer when requests overlap", async () => {
    let first: (() => void) | undefined;
    let call = 0;
    mockApi({
      ...base,
      "POST /v1/spaces/p1/finance-assistant/ask": () => {
        call += 1;
        if (call === 1) return new Promise((resolve) => { first = () => resolve({ status: 200, body: { ...ANSWER, summary: "OLD ANSWER" } }); });
        return { status: 200, body: { ...ANSWER, summary: "NEW ANSWER" } };
      },
    });
    wrap(<AssistantPanel domain="PERSONAL" />);
    const box = await screen.findByLabelText("Your question");
    await userEvent.type(box, "spend last month");
    await userEvent.click(screen.getByRole("button", { name: "How much did I spend last month?" }));
    await userEvent.click(screen.getByRole("button", { name: "How much did I spend last month?" }));
    expect(await screen.findByText("NEW ANSWER")).toBeInTheDocument();
    first!();
    await waitFor(() => expect(screen.queryByText("OLD ANSWER")).not.toBeInTheDocument());
  });
});
