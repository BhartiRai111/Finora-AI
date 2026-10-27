"use client";

import { useState } from "react";
import { describeError } from "@/lib/api";
import { formatMoney } from "@/lib/finance";
import { useFinance } from "@/lib/FinanceContext";
import type { Candidate, StatementLine, UnmatchedRecord } from "@/lib/insights";
import { usePaged } from "@/lib/usePaged";
import { useResource } from "@/lib/useResource";
import { Loading, Notice } from "../Notice";
import { ListState, Pager } from "../business/Common";

const SUGGESTION: Record<string, string> = {
  NONE: "No matching record found",
  WEAK: "Weak suggestion: check the date",
  STRONG: "Strong suggestion",
  AMBIGUOUS: "Several records fit: choose carefully",
};

/** Reviews statement lines (imported from files; there is no live bank connection) against existing records. Nothing matches until you confirm it. */
export function ReconcilePanel({ canWrite }: { canWrite: boolean }) {
  const finance = useFinance();
  const [status, setStatus] = useState("UNMATCHED");
  const list = usePaged<StatementLine>("/finance-reconciliation/lines", { status });
  const summary = useResource((signal) => finance.get<{ unmatched: number; matched: number; ignored: number; unmatched_records: number }>("/finance-reconciliation/summary", signal));
  const unmatched = useResource((signal) => finance.get<{ items: UnmatchedRecord[] }>("/finance-reconciliation/unmatched-records?limit=20", signal));
  const [pending, setPending] = useState<{ line: StatementLine; candidate: Candidate } | null>(null);
  const [reasonFor, setReasonFor] = useState<{ id: string; kind: "undo" | "ignore"; matchId?: string } | null>(null);
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function act(fn: () => Promise<unknown>, done: string) {
    setBusy(true);
    setError(null);
    try {
      await fn();
      setMessage(done);
      setPending(null);
      setReasonFor(null);
      setReason("");
      list.reload();
      summary.reload();
      unmatched.reload();
    } catch (e) {
      setError(describeError(e));
    } finally {
      setBusy(false);
    }
  }

  function submitReason() {
    if (!reasonFor) return;
    if (!reason.trim()) return setError("Please give a reason.");
    if (reasonFor.kind === "undo") void act(() => finance.send("POST", `/finance-reconciliation/matches/${reasonFor.matchId}/undo`, { reason: reason.trim() }), "Match undone. Both items are unmatched again.");
    else void act(() => finance.send("POST", `/finance-reconciliation/lines/${reasonFor.id}/ignore`, { reason: reason.trim() }), "Line ignored.");
  }

  return (
    <>
      {error && <Notice>{error}</Notice>}
      {message && <Notice kind="success">{message}</Notice>}
      <p className="muted">Statement lines come from CSV files you import (type “bank statement”). There is no live bank connection. Suggestions are only suggestions: nothing is reconciled until you confirm a match.</p>
      {summary.status === "ready" && <p>{summary.data.unmatched} unmatched · {summary.data.matched} matched · {summary.data.ignored} ignored · {summary.data.unmatched_records} records in the statement period without a statement line</p>}
      <div className="filters">
        <div className="column">
          <label htmlFor="rec-status">Show</label>
          <select id="rec-status" value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="UNMATCHED">Unmatched lines</option>
            <option value="MATCHED">Matched lines</option>
            <option value="IGNORED">Ignored lines</option>
          </select>
        </div>
      </div>
      <ListState loading={list.loading} error={list.error} empty={list.items.length === 0} onRetry={list.reload}
        emptyText={status === "UNMATCHED" ? "No unmatched statement lines. Import a bank statement CSV to reconcile it." : `No ${status.toLowerCase()} lines.`}>
        <ul className="cards" aria-label="Statement lines">
          {list.items.map((l) => (
            <li key={l.id} className="card column">
              <strong>{l.date} · {l.direction === "IN" ? "In" : "Out"} {formatMoney(l.amount.replace("-", ""), l.currency)}</strong>
              <span>{l.description ?? "No description"}{l.reference && ` · ref ${l.reference}`}</span>
              {l.status === "UNMATCHED" && l.suggestion && <span className="muted">{SUGGESTION[l.suggestion]}</span>}
              {l.status === "UNMATCHED" && l.candidates?.map((c) => (
                <div key={c.target_id} className="card column" role="group" aria-label={`Candidate ${c.label ?? c.target_type}`}>
                  <span>{c.label ?? c.target_type.toLowerCase()} · {c.date} · {formatMoney(c.amount, c.currency)}{c.has_document && " · has a document"}</span>
                  <span className="muted">Match score {c.score} of 100: {c.signals.join(", ")}</span>
                  {canWrite && <button type="button" onClick={() => setPending({ line: l, candidate: c })}>Match to this</button>}
                </div>
              ))}
              {l.status === "MATCHED" && l.match && (
                <span>
                  Matched to {l.match.target_label ?? l.match.target_type.toLowerCase()} (score {l.match.score}: {l.match.signals.join(", ")}).
                  {!l.match.target_still_valid && <strong> That record has since been voided or changed; consider undoing this match.</strong>}
                </span>
              )}
              {l.status === "IGNORED" && <span>Ignored: {l.ignore_reason}</span>}
              {canWrite && (
                <div className="row">
                  {l.status === "MATCHED" && l.match && <button type="button" className="link" onClick={() => { setReasonFor({ id: l.id, kind: "undo", matchId: l.match!.id }); setReason(""); }}>Undo match</button>}
                  {l.status === "UNMATCHED" && <button type="button" className="link" onClick={() => { setReasonFor({ id: l.id, kind: "ignore" }); setReason(""); }}>Ignore this line</button>}
                  {l.status === "IGNORED" && <button type="button" className="link" onClick={() => void act(() => finance.send("POST", `/finance-reconciliation/lines/${l.id}/restore`, {}), "Line restored.")}>Restore</button>}
                </div>
              )}
            </li>
          ))}
        </ul>
        <Pager total={list.total} offset={list.offset} onGo={list.goTo} />
      </ListState>
      {pending && (
        <div className="card column" role="alertdialog" aria-label="Confirm match">
          <p>Match the statement line “{pending.line.description ?? pending.line.date}” to {pending.candidate.label ?? pending.candidate.target_type.toLowerCase()} of {formatMoney(pending.candidate.amount, pending.candidate.currency)} on {pending.candidate.date}? A record can be matched to one line only. You can undo this later.</p>
          <div className="row">
            <button type="button" disabled={busy} onClick={() => void act(() => finance.send("POST", `/finance-reconciliation/lines/${pending.line.id}/match`, { confirm: true, target_type: pending.candidate.target_type, target_id: pending.candidate.target_id }), "Matched.")}>Confirm match</button>
            <button type="button" className="link" onClick={() => setPending(null)}>Cancel</button>
          </div>
        </div>
      )}
      {reasonFor && (
        <div className="card column" role="group" aria-label={reasonFor.kind === "undo" ? "Undo match" : "Ignore line"}>
          <label htmlFor="rec-reason">{reasonFor.kind === "undo" ? "Why are you undoing this match?" : "Why is this line ignored?"}</label>
          <input id="rec-reason" value={reason} onChange={(e) => setReason(e.target.value)} />
          <div className="row">
            <button type="button" disabled={busy} onClick={submitReason}>{reasonFor.kind === "undo" ? "Undo match" : "Ignore line"}</button>
            <button type="button" className="link" onClick={() => setReasonFor(null)}>Cancel</button>
          </div>
        </div>
      )}
      <h2>Records without a statement line</h2>
      {unmatched.status === "loading" && <Loading label="Loading records…" />}
      {unmatched.status === "error" && <Notice>{describeError(unmatched.error)}</Notice>}
      {unmatched.status === "ready" && unmatched.data.items.length === 0 && <p>Every record inside the statement period is matched, or no statement has been imported.</p>}
      {unmatched.status === "ready" && unmatched.data.items.length > 0 && (
        <table>
          <caption className="visually-hidden">Records without a statement line</caption>
          <thead><tr><th scope="col">Date</th><th scope="col">Record</th><th scope="col">Amount</th></tr></thead>
          <tbody>{unmatched.data.items.map((r) => <tr key={r.target_id}><td>{r.date}</td><td>{r.label ?? r.target_type.toLowerCase()}{r.reference && ` · ${r.reference}`}</td><td>{formatMoney(r.amount, r.currency)}</td></tr>)}</tbody>
        </table>
      )}
    </>
  );
}
