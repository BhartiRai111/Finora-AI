"use client";

import { useState, type FormEvent } from "react";
import { describeError } from "@/lib/api";
import { formatMoney, todayLocal, validateAmountText, type Account, type RecurringRule } from "@/lib/finance";
import { useFinance, useIntentKey } from "@/lib/FinanceContext";
import { useResource } from "@/lib/useResource";
import { Loading, Notice } from "../Notice";

export function RecurringPanel({ accounts }: { accounts: Account[] }) {
  const finance = useFinance();
  const list = useResource((signal) => finance.get<{ items: RecurringRule[] }>("/recurring-rules", signal));
  const intent = useIntentKey();
  const open = accounts.filter((a) => !a.archived);
  const [kind, setKind] = useState("EXPENSE");
  const [accountId, setAccountId] = useState("");
  const [amount, setAmount] = useState("");
  const [payee, setPayee] = useState("");
  const [frequency, setFrequency] = useState("MONTHLY");
  const [start, setStart] = useState(todayLocal());
  const [autoPost, setAutoPost] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const account = open.find((a) => a.id === (accountId || open[0]?.id));

  async function create(event: FormEvent) {
    event.preventDefault();
    if (!account) return setError("Add an account first.");
    const problem = validateAmountText(amount, account.currency);
    if (problem) return setError(`Amount: ${problem}`);
    setError(null);
    try {
      await finance.send("POST", "/recurring-rules", { kind, account_id: account.id, amount: amount.trim(), payee: payee.trim() || undefined, frequency, interval_count: 1, start_date: start, auto_post: autoPost }, intent.key());
      intent.done();
      setAmount("");
      setPayee("");
      list.reload();
    } catch (e) {
      setError(describeError(e));
    }
  }

  async function toggle(rule: RecurringRule) {
    setError(null);
    try {
      await finance.send("POST", `/recurring-rules/${rule.id}/${rule.active ? "pause" : "resume"}`, {});
      list.reload();
    } catch (e) {
      setError(describeError(e));
    }
  }

  async function runNow() {
    setError(null);
    setInfo(null);
    try {
      const r = await finance.send<{ created: number }>("POST", "/recurring-rules/run", {}, undefined);
      setInfo(r.created === 0 ? "Nothing was due." : `Created ${r.created} transaction${r.created === 1 ? "" : "s"}. Review any drafts under Transactions.`);
      list.reload();
    } catch (e) {
      setError(describeError(e));
    }
  }

  return (
    <>
      {error && <Notice>{error}</Notice>}
      {info && <Notice kind="success">{info}</Notice>}
      {list.status === "loading" && <Loading label="Loading recurring transactions…" />}
      {list.status === "error" && (
        <>
          <Notice>{describeError(list.error)}</Notice>
          <button type="button" onClick={list.reload}>Try again</button>
        </>
      )}
      {list.status === "ready" && list.data.items.length === 0 && <p>No recurring transactions yet. Add one below for salary, rent and other regular items.</p>}
      {list.status === "ready" && (
        <ul className="cards" aria-label="Recurring transactions">
          {list.data.items.map((r) => (
            <li key={r.id} className="card">
              <div className="column">
                <strong>{r.kind === "INCOME" ? "Income" : "Expense"} · {formatMoney(r.amount, r.currency)}</strong>
                <span>Every {r.interval_count > 1 ? `${r.interval_count} ` : ""}{r.frequency.toLowerCase()} from {r.start_date}</span>
                <span className="muted">
                  {r.active ? (r.next_occurrence ? `Next ${r.next_occurrence}` : "Finished") : "Paused"} · {r.auto_post ? "posts automatically" : "creates drafts for review"}
                </span>
              </div>
              <button type="button" className="link" onClick={() => void toggle(r)}>{r.active ? "Pause" : "Resume"}</button>
            </li>
          ))}
        </ul>
      )}
      <p><button type="button" onClick={() => void runNow()}>Process due now</button></p>
      <form onSubmit={create} aria-labelledby="recurring-title" noValidate>
        <h2 id="recurring-title">Add recurring transaction</h2>
        <label htmlFor="rec-kind">Type</label>
        <select id="rec-kind" value={kind} onChange={(e) => setKind(e.target.value)}>
          <option value="EXPENSE">Expense</option>
          <option value="INCOME">Income</option>
        </select>
        <label htmlFor="rec-account">Account</label>
        <select id="rec-account" value={account?.id ?? ""} onChange={(e) => setAccountId(e.target.value)}>
          {open.map((a) => <option key={a.id} value={a.id}>{a.name} ({a.currency})</option>)}
        </select>
        <label htmlFor="rec-amount">Amount</label>
        <input id="rec-amount" value={amount} onChange={(e) => setAmount(e.target.value)} inputMode="decimal" />
        <label htmlFor="rec-payee">Payee or source (optional)</label>
        <input id="rec-payee" value={payee} onChange={(e) => setPayee(e.target.value)} />
        <label htmlFor="rec-frequency">Repeats</label>
        <select id="rec-frequency" value={frequency} onChange={(e) => setFrequency(e.target.value)}>
          {["DAILY", "WEEKLY", "MONTHLY", "YEARLY"].map((f) => <option key={f} value={f}>{f.charAt(0) + f.slice(1).toLowerCase()}</option>)}
        </select>
        <label htmlFor="rec-start">Start date</label>
        <input id="rec-start" type="date" value={start} onChange={(e) => setStart(e.target.value)} />
        <label><input type="checkbox" checked={autoPost} onChange={(e) => setAutoPost(e.target.checked)} /> Post automatically (otherwise each one waits as a draft for you to confirm)</label>
        <button type="submit">Add recurring transaction</button>
      </form>
    </>
  );
}
