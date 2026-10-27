"use client";

import { useRef, useState } from "react";
import { describeError } from "@/lib/api";
import { KIND_LABEL, percentProblem, type AssistantAi, type AssistantAnswer, type AssistantStatus } from "@/lib/assistant";
import { useFinance } from "@/lib/FinanceContext";
import { useResource } from "@/lib/useResource";
import { Loading, Notice } from "../Notice";

const AI_TEXT: Record<AssistantAi["status"], string> = {
  NOT_CONFIGURED: "AI explanations: not configured",
  CONFIGURED: "AI explanations: provider configured",
  UNAVAILABLE: "AI explanations: provider unavailable",
  FAILED: "AI explanations: request failed",
  EXECUTED: "AI explanations: provided",
  SKIPPED: "AI explanations: not needed",
  BLOCKED: "AI explanations: blocked (data terms not approved)",
};

function AiNote({ ai }: { ai: AssistantAi }) {
  return (
    <div className="muted" aria-label="AI status">
      <strong>{AI_TEXT[ai.status]}.</strong> {ai.message}
    </div>
  );
}

/** One assistant answer: verified figures, estimates, hypotheticals and observations are kept apart and labelled; the optional AI text is last and labelled. */
export function AnswerView({ answer }: { answer: AssistantAnswer }) {
  return (
    <section aria-label="Assistant answer">
      {answer.status === "NEEDS_CLARIFICATION" && <Notice kind="info">{answer.clarification}</Notice>}
      {answer.status === "UNSUPPORTED" && <Notice kind="info">{answer.summary}</Notice>}
      {answer.status === "NO_DATA" && <Notice kind="info">{answer.summary}</Notice>}
      {answer.status === "ANSWERED" && <p><strong>{answer.summary}</strong></p>}
      {answer.period?.label && (
        <p className="muted">
          Period: {answer.period.label}
          {answer.period.comparison_label ? ` · compared with ${answer.period.comparison_label}` : ""}
        </p>
      )}
      {answer.notices.map((n) => <Notice key={n} kind="info">{n}</Notice>)}
      {answer.sections.map((s, i) => (
        <div key={`${s.title}-${i}`} className="column">
          <table>
            <caption>
              {s.title} <span className="muted">({KIND_LABEL[s.kind]})</span>
            </caption>
            <thead>
              <tr>{s.columns.map((c) => <th key={c} scope="col">{c}</th>)}</tr>
            </thead>
            <tbody>
              {s.rows.map((row, r) => (
                <tr key={r}>{row.map((cell, c) => (c === 0 ? <th key={c} scope="row">{cell}</th> : <td key={c}>{cell}</td>))}</tr>
              ))}
            </tbody>
          </table>
          {s.notes.map((n) => <p key={n} className="muted">{n}</p>)}
        </div>
      ))}
      {answer.ai.explanation && (
        <div className="column" role="note" aria-label="AI explanation">
          <p>{answer.ai.explanation}</p>
          <p className="muted">{answer.ai.label}</p>
        </div>
      )}
      <AiNote ai={answer.ai} />
      {(answer.assumptions.length > 0 || answer.limitations.length > 0 || answer.sources.length > 0) && (
        <details>
          <summary>Assumptions, limits and sources</summary>
          {answer.assumptions.length > 0 && <><h3>Assumptions</h3><ul>{answer.assumptions.map((a) => <li key={a}>{a}</li>)}</ul></>}
          {answer.limitations.length > 0 && <><h3>Limits</h3><ul>{answer.limitations.map((a) => <li key={a}>{a}</li>)}</ul></>}
          {answer.sources.length > 0 && (
            <>
              <h3>Sources</h3>
              <ul>
                {answer.sources.map((s, i) => (
                  <li key={i}>{s.type.toLowerCase()} {s.name}{s.from && s.to ? ` (${s.from} to ${s.to})` : s.to ? ` (as of ${s.to})` : ""}{s.ref ? ` ${s.ref}` : ""}</li>
                ))}
              </ul>
            </>
          )}
        </details>
      )}
    </section>
  );
}

type Outcome = { state: "idle" } | { state: "loading" } | { state: "error"; message: string; retry: () => void } | { state: "done"; answer: AssistantAnswer };

/** The assistant: ask a question about recorded data, explain a spending change, review potential anomalies, or try a what-if. Read-only. */
export function AssistantPanel({ domain }: { domain: "PERSONAL" | "BUSINESS" }) {
  const finance = useFinance();
  const status = useResource((signal) => finance.get<AssistantStatus>("/finance-assistant", signal));
  const [question, setQuestion] = useState("");
  const [outcome, setOutcome] = useState<Outcome>({ state: "idle" });
  const [localError, setLocalError] = useState<string | null>(null);
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [expense, setExpense] = useState("");
  const [revenue, setRevenue] = useState("");
  const [horizon, setHorizon] = useState("3");
  const seq = useRef(0);

  async function call(make: () => Promise<AssistantAnswer>, retry: () => void) {
    const mine = ++seq.current;
    setOutcome({ state: "loading" });
    try {
      const answer = await make();
      if (mine === seq.current) setOutcome({ state: "done", answer });
    } catch (e) {
      if (mine === seq.current) setOutcome({ state: "error", message: describeError(e), retry });
    }
  }

  function ask(text = question) {
    const q = text.trim();
    if (q.length < 3) return setLocalError("Type a question of at least 3 characters.");
    setLocalError(null);
    void call(() => finance.send<AssistantAnswer>("POST", "/finance-assistant/ask", { question: q }), () => ask(q));
  }

  function dates(): string | null {
    if ((from && !to) || (!from && to)) return "Give both a start and an end date, or leave both empty for the last complete month.";
    if (from && to && from > to) return "The start date cannot be after the end date.";
    return null;
  }

  function dated(path: string) {
    const problem = dates();
    if (problem) return setLocalError(problem);
    setLocalError(null);
    const qs = from && to ? `?from=${from}&to=${to}` : "";
    void call(() => finance.get<AssistantAnswer>(`${path}${qs}`), () => dated(path));
  }

  function whatIf() {
    const problem = percentProblem(expense, "Expense change") ?? percentProblem(revenue, domain === "BUSINESS" ? "Revenue change" : "Income change");
    if (problem) return setLocalError(problem);
    if (expense.trim() === "" && revenue.trim() === "") return setLocalError("Enter at least one assumption, for example expenses +10.");
    setLocalError(null);
    const body: Record<string, string> = { horizon_months: horizon };
    if (expense.trim() !== "") body.expense_change_percent = expense.trim();
    if (revenue.trim() !== "") body.revenue_change_percent = revenue.trim();
    void call(() => finance.send<AssistantAnswer>("POST", "/finance-assistant/what-if", body), whatIf);
  }

  const incomeWord = domain === "BUSINESS" ? "Revenue received" : "Income";
  return (
    <>
      <h2>Assistant</h2>
      <p className="muted">
        Ask about your recorded data. Answers are read-only: figures are calculated from your records by the server, never by an AI, and nothing is changed, paid, filed or cancelled.
      </p>
      {status.status === "loading" && <Loading label="Checking the assistant…" />}
      {status.status === "error" && (
        <>
          <Notice>{describeError(status.error)}</Notice>
          <button type="button" onClick={status.reload}>Try again</button>
        </>
      )}
      {status.status === "ready" && (
        <>
          <Notice kind="info">{AI_TEXT[status.data.ai.status]}. {status.data.ai.message}</Notice>
          <form onSubmit={(e) => { e.preventDefault(); ask(); }} aria-label="Ask a question">
            <label htmlFor="assistant-question">Your question</label>
            <textarea id="assistant-question" rows={2} maxLength={status.data.limits.question_max_chars} value={question} onChange={(e) => setQuestion(e.target.value)} />
            <button type="submit" disabled={outcome.state === "loading"}>Ask</button>
          </form>
          <div role="group" aria-label="Example questions">
            {status.data.supported.map((s) => (
              <button key={s.tool} type="button" className="link" onClick={() => { setQuestion(s.example); ask(s.example); }}>{s.example}</button>
            ))}
          </div>
        </>
      )}

      <fieldset>
        <legend>Explain a change, or look for potential anomalies</legend>
        <div className="filters">
          <div className="column"><label htmlFor="assistant-from">From</label><input id="assistant-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></div>
          <div className="column"><label htmlFor="assistant-to">To</label><input id="assistant-to" type="date" value={to} onChange={(e) => setTo(e.target.value)} /></div>
          <button type="button" onClick={() => dated("/finance-assistant/expense-change")} disabled={outcome.state === "loading"}>Explain the spending change</button>
          <button type="button" onClick={() => dated("/finance-assistant/anomalies")} disabled={outcome.state === "loading"}>Find potential anomalies</button>
        </div>
        <p className="muted">Without dates the last complete month is used. Potential anomalies come from fixed rules; they are not a finding of error or fraud.</p>
      </fieldset>

      <fieldset>
        <legend>What if? (hypothetical, never saved)</legend>
        <div className="filters">
          <div className="column"><label htmlFor="assistant-expense">Expenses change (%)</label><input id="assistant-expense" inputMode="decimal" value={expense} onChange={(e) => setExpense(e.target.value)} placeholder="e.g. 10" /></div>
          <div className="column"><label htmlFor="assistant-revenue">{incomeWord} change (%)</label><input id="assistant-revenue" inputMode="decimal" value={revenue} onChange={(e) => setRevenue(e.target.value)} placeholder="e.g. -20" /></div>
          <div className="column">
            <label htmlFor="assistant-horizon">Months ahead</label>
            <select id="assistant-horizon" value={horizon} onChange={(e) => setHorizon(e.target.value)}>
              {[1, 2, 3, 4, 5, 6].map((n) => <option key={n} value={n}>{n}</option>)}
            </select>
          </div>
          <button type="button" onClick={whatIf} disabled={outcome.state === "loading"}>Calculate scenario</button>
        </div>
      </fieldset>

      {localError && <Notice>{localError}</Notice>}
      {outcome.state === "loading" && <Loading label="Working on your question…" />}
      {outcome.state === "error" && (
        <>
          <Notice>{outcome.message}</Notice>
          <button type="button" onClick={outcome.retry}>Try again</button>
        </>
      )}
      {outcome.state === "done" && <AnswerView answer={outcome.answer} />}
    </>
  );
}
