"use client";

import { useState, type FormEvent } from "react";
import { api, describeError } from "@/lib/api";
import { CURRENCIES, currentMonth, formatMoney, validateAmountText, type BudgetItem, type Category } from "@/lib/finance";
import { useFinance } from "@/lib/FinanceContext";
import { useResource } from "@/lib/useResource";
import { Loading, Notice } from "../Notice";

const STATUS_TEXT: Record<BudgetItem["status"], string> = { UNDER: "On track", WARNING: "Close to the limit", OVER: "Over budget" };

export function BudgetsPanel({ categories }: { categories: Category[] }) {
  const finance = useFinance();
  const [month, setMonth] = useState(currentMonth());
  const list = useResource((signal) => api<{ month: string; items: BudgetItem[] }>(`/v1/spaces/${finance.space}/budgets?month=${month}`, { signal }));
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
      <div className="row">
        <label htmlFor="budget-month">Month</label>
        <input id="budget-month" type="month" value={month} onChange={(e) => e.target.value && setMonth(e.target.value)} />
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
      {list.status === "ready" && list.data.items.length > 0 && (
        <ul className="cards" aria-label="Budgets">
          {list.data.items.map((b) => (
            <li key={b.id} className="card column">
              <strong>{b.category_name ?? "Everything"}</strong>
              <span>{formatMoney(b.spent, b.currency)} of {formatMoney(b.amount, b.currency)} ({b.percent_used}%)</span>
              <progress max={100} value={Math.min(100, Number(b.percent_used))} aria-label={`${b.category_name ?? "Overall"} budget used`} />
              <span className={`status status-${b.status.toLowerCase()}`}>{STATUS_TEXT[b.status]} — {formatMoney(b.remaining, b.currency)} left</span>
              <div className="row">
                <RaiseForm budget={b} onSave={(v) => void raise(b, v)} />
                <button type="button" className="link" onClick={() => void archive(b)}>Remove</button>
              </div>
            </li>
          ))}
        </ul>
      )}
      <form onSubmit={create} aria-labelledby="budget-title" noValidate>
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
