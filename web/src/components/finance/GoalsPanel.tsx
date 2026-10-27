"use client";

import { useState, type FormEvent } from "react";
import { describeError } from "@/lib/api";
import { CURRENCIES, formatMoney, validateAmountText, type Goal } from "@/lib/finance";
import { useFinance, useIntentKey } from "@/lib/FinanceContext";
import { useResource } from "@/lib/useResource";
import { Loading, Notice } from "../Notice";

export function GoalsPanel() {
  const finance = useFinance();
  const list = useResource((signal) => finance.get<{ items: Goal[] }>("/goals", signal));
  const [name, setName] = useState("");
  const [target, setTarget] = useState("");
  const [currency, setCurrency] = useState("INR");
  const [date, setDate] = useState("");
  const [error, setError] = useState<string | null>(null);

  async function create(event: FormEvent) {
    event.preventDefault();
    if (!name.trim()) return setError("Name your goal.");
    const problem = validateAmountText(target, currency);
    if (problem) return setError(`Target: ${problem}`);
    setError(null);
    try {
      await finance.send("POST", "/goals", { name: name.trim(), target_amount: target.trim(), currency, target_date: date || undefined });
      setName("");
      setTarget("");
      setDate("");
      list.reload();
    } catch (e) {
      setError(describeError(e));
    }
  }

  return (
    <>
      {error && <Notice>{error}</Notice>}
      {list.status === "loading" && <Loading label="Loading goals…" />}
      {list.status === "error" && (
        <>
          <Notice>{describeError(list.error)}</Notice>
          <button type="button" onClick={list.reload}>Try again</button>
        </>
      )}
      {list.status === "ready" && list.data.items.length === 0 && <p>No savings goals yet. Create one below to track your progress.</p>}
      {list.status === "ready" && (
        <ul className="cards" aria-label="Savings goals">
          {list.data.items.map((g) => <GoalCard key={g.id} goal={g} onChanged={list.reload} onError={setError} />)}
        </ul>
      )}
      <form onSubmit={create} aria-labelledby="goal-title" noValidate>
        <h2 id="goal-title">New savings goal</h2>
        <label htmlFor="goal-name">Name</label>
        <input id="goal-name" value={name} onChange={(e) => setName(e.target.value)} />
        <label htmlFor="goal-target">Target amount</label>
        <input id="goal-target" value={target} onChange={(e) => setTarget(e.target.value)} inputMode="decimal" />
        <label htmlFor="goal-currency">Currency</label>
        <select id="goal-currency" value={currency} onChange={(e) => setCurrency(e.target.value)}>
          {CURRENCIES.map((c) => <option key={c} value={c}>{c}</option>)}
        </select>
        <label htmlFor="goal-date">Target date (optional)</label>
        <input id="goal-date" type="date" value={date} onChange={(e) => setDate(e.target.value)} />
        <button type="submit">Create goal</button>
      </form>
    </>
  );
}

function GoalCard({ goal, onChanged, onError }: { goal: Goal; onChanged: () => void; onError: (m: string | null) => void }) {
  const finance = useFinance();
  const intent = useIntentKey();
  const [amount, setAmount] = useState("");

  async function contribute(event: FormEvent) {
    event.preventDefault();
    const problem = validateAmountText(amount, goal.currency, { allowNegative: true });
    if (problem) return onError(problem);
    onError(null);
    try {
      await finance.send("POST", `/goals/${goal.id}/contributions`, { amount: amount.trim() }, intent.key());
      intent.done();
      setAmount("");
      onChanged();
    } catch (e) {
      onError(describeError(e));
    }
  }

  return (
    <li className="card column">
      <strong>{goal.name}</strong>
      <span>{formatMoney(goal.saved, goal.currency)} of {formatMoney(goal.target_amount, goal.currency)} ({goal.percent}%)</span>
      <progress max={100} value={Math.min(100, Number(goal.percent))} aria-label={`${goal.name} progress`} />
      {goal.status === "ACHIEVED" && <span className="status status-under">Goal reached 🎉</span>}
      {goal.required_monthly && goal.status === "IN_PROGRESS" && (
        <span>
          {goal.months_left === 0 ? "Past the target date: " : `Save ${formatMoney(goal.required_monthly, goal.currency)} a month for ${goal.months_left} month${goal.months_left === 1 ? "" : "s"}.`}
          {goal.months_left === 0 && `${formatMoney(goal.required_monthly, goal.currency)} still to save.`}
        </span>
      )}
      {goal.status !== "ARCHIVED" && (
        <form onSubmit={contribute} aria-label={`Add to ${goal.name}`} className="row" noValidate>
          <label htmlFor={`gc-${goal.id}`} className="visually-hidden">Amount to add</label>
          <input id={`gc-${goal.id}`} value={amount} onChange={(e) => setAmount(e.target.value)} inputMode="decimal" placeholder="Amount (negative to withdraw)" />
          <button type="submit">Add</button>
        </form>
      )}
    </li>
  );
}
