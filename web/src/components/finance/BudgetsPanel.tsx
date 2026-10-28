"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { api, describeError } from "@/lib/api";
import { monthLabel } from "@/lib/calendar";
import { sumAmounts } from "@/lib/decimal";
import { CURRENCIES, formatMoney, validateAmountText, type BudgetItem, type Category } from "@/lib/finance";
import { useFinance } from "@/lib/FinanceContext";
import { useSelectedMonth } from "@/lib/month";
import { useResource } from "@/lib/useResource";
import { Loading, Notice } from "../Notice";
import { ProgressBar } from "../ui/BarChart";

const STATUS_TEXT: Record<BudgetItem["status"], string> = { UNDER: "On track", WARNING: "Close to the limit", OVER: "Over budget" };

export function BudgetsPanel({ categories }: { categories: Category[] }) {
  const finance = useFinance();
  const [month, setMonth] = useSelectedMonth();
  const list = useResource((signal) => api<{ month: string; items: BudgetItem[] }>(`/v1/spaces/${finance.space}/budgets?month=${month}`, { signal }));
  // Choosing another month asks the server for that month.
  const shown = useRef(month);
  useEffect(() => {
    if (shown.current !== month) {
      shown.current = month;
      list.reload();
    }
  });
  const [categoryId, setCategoryId] = useState("");
  const [amount, setAmount] = useState("");
  const [currency, setCurrency] = useState("INR");
  const [error, setError] = useState<string | null>(null);

  const expenseCategories = categories.filter((c) => !c.archived && c.kind === "EXPENSE");

  async function create(event: FormEvent) {
    event.preventDefault();
    const problem = validateAmountText(amount, currency);
    if (problem) return setError(problem);
    setError(null);
    try {
      await finance.send("POST", "/budgets", { category_id: categoryId || undefined, amount: amount.trim(), currency });
      setAmount("");
      list.reload();
    } catch (e) {
      setError(describeError(e));
    }
  }

  async function archive(b: BudgetItem) {
    try {
      await finance.send("POST", `/budgets/${b.id}/archive`, {});
      list.reload();
    } catch (e) {
      setError(describeError(e));
    }
  }

  async function raise(b: BudgetItem, value: string) {
    const problem = validateAmountText(value, b.currency);
    if (problem) return setError(problem);
    try {
      await finance.send("PATCH", `/budgets/${b.id}`, { amount: value.trim(), version: b.version });
      list.reload();
    } catch (e) {
      setError(describeError(e));
    }
  }

  return (
    <>
      <div className="budget-toolbar">
        <h2 className="visually-hidden">Budgets for {monthLabel(month)}</h2>
        <label htmlFor="budget-month">Month</label>
        <input id="budget-month" type="month" value={month} onChange={(e) => e.target.value && setMonth(e.target.value)} />
        <span className="muted">The month selector in the header moves this page, the dashboard and the calendar together.</span>
      </div>
      {error && <Notice>{error}</Notice>}
      {list.status === "loading" && <Loading label="Loading budgets…" />}
      {list.status === "error" && (
        <>
          <Notice>{describeError(list.error)}</Notice>
          <button type="button" onClick={list.reload}>Try again</button>
        </>
      )}
      {list.status === "ready" && list.data.items.length === 0 && <p>No budgets yet. Set a monthly limit for a category, or one overall budget.</p>}
      {list.status === "ready" && list.data.items.length > 0 && <BudgetTotals items={list.data.items} />}
      {list.status === "ready" && list.data.items.length > 0 && (
        <ul className="budget-grid" aria-label="Budgets">
          {list.data.items.map((b) => (
            <li key={b.id} className="budget-card">
              <strong>{b.category_name ?? "Everything"}</strong>
              <span>{formatMoney(b.spent, b.currency)} of {formatMoney(b.amount, b.currency)} ({b.percent_used}%)</span>
              <ProgressBar percent={Number(b.percent_used)} tone={b.status === "OVER" ? "over" : b.status === "WARNING" ? "warning" : "under"} label={`${b.category_name ?? "Overall"} budget used`} />
              <span className={`status status-${b.status.toLowerCase()}`}>{STATUS_TEXT[b.status]} — {formatMoney(b.remaining, b.currency)} left</span>
              <div className="budget-actions">
                <RaiseForm budget={b} onSave={(v) => void raise(b, v)} />
                <button type="button" className="link" onClick={() => void archive(b)}>Remove</button>
              </div>
            </li>
          ))}
        </ul>
      )}
      <form onSubmit={create} aria-labelledby="budget-title" noValidate className="panel budget-form">
        <h2 id="budget-title">Set a monthly budget</h2>
        <label htmlFor="budget-category">Category</label>
        <select id="budget-category" value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
          <option value="">Everything (overall)</option>
          {expenseCategories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select>
        <label htmlFor="budget-amount">Monthly limit</label>
        <input id="budget-amount" value={amount} onChange={(e) => setAmount(e.target.value)} inputMode="decimal" />
        <label htmlFor="budget-currency">Currency</label>
        <select id="budget-currency" value={currency} onChange={(e) => setCurrency(e.target.value)}>
          {CURRENCIES.map((c) => <option key={c} value={c}>{c}</option>)}
        </select>
        <button type="submit">Save budget</button>
      </form>
    </>
  );
}

function RaiseForm({ budget, onSave }: { budget: BudgetItem; onSave: (value: string) => void }) {
  const [value, setValue] = useState(budget.amount);
  return (
    <span className="row">
      <label htmlFor={`b-${budget.id}`} className="visually-hidden">New limit</label>
      <input id={`b-${budget.id}`} value={value} onChange={(e) => setValue(e.target.value)} inputMode="decimal" size={10} />
      <button type="button" className="link" onClick={() => onSave(value)}>Change limit</button>
    </span>
  );
}

/** Totals per currency across the month's budgets: an overall budget if there is one, otherwise the sum of the category budgets (exact addition of the listed figures). */
function BudgetTotals({ items }: { items: BudgetItem[] }) {
  const currencies = [...new Set(items.map((b) => b.currency))];
  return (
    <div className="cluster budget-totals" aria-label="Budget totals">
      {currencies.map((cur) => {
        const mine = items.filter((b) => b.currency === cur);
        const overall = mine.find((b) => b.category_id === null);
        const parts = overall ? [overall] : mine;
        const total = sumAmounts(parts.map((b) => b.amount));
        const spent = sumAmounts(parts.map((b) => b.spent));
        const left = sumAmounts(parts.map((b) => b.remaining));
        const pct = Number(total) > 0 ? (Number(spent) / Number(total)) * 100 : 0;
        return (
          <div key={cur} className="stat">
            <div className="stat-top"><span>{overall ? "Overall budget" : `Total of ${parts.length} budget${parts.length === 1 ? "" : "s"}`} ({cur})</span></div>
            <div className="stat-value">{formatMoney(left, cur)} left</div>
            <ProgressBar percent={pct} tone={pct >= 100 ? "over" : pct >= 80 ? "warning" : "under"} label={`${cur} budget used`} />
            <span className="stat-sub">{formatMoney(spent, cur)} spent of {formatMoney(total, cur)}</span>
          </div>
        );
      })}
    </div>
  );
}
