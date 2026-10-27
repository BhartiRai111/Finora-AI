"use client";

import { useState, type FormEvent } from "react";
import { describeError } from "@/lib/api";
import { currentMonth, formatMoney, type BudgetItem, type Commitment, type CurrencyCashflow, type CurrencyNetWorth, type Goal } from "@/lib/finance";
import { useFinance } from "@/lib/FinanceContext";
import { useResource } from "@/lib/useResource";
import { Loading, Notice } from "../Notice";

type Settings = { timezone: string; today: string };

function monthRange(month: string, today: string): { from: string; to: string } {
  const [y = 1970, m = 1] = month.split("-").map(Number);
  const last = new Date(Date.UTC(y, m, 0)).getUTCDate();
  const end = `${month}-${String(last).padStart(2, "0")}`;
  return { from: `${month}-01`, to: today < end && today.startsWith(month) ? today : end };
}

export function OverviewPanel({ onChanged }: { onChanged: () => void }) {
  const finance = useFinance();
  const data = useResource(async (signal) => {
    const settings = await finance.get<Settings>("/finance-settings", signal);
    const month = settings.today.slice(0, 7) || currentMonth();
    const { from, to } = monthRange(month, settings.today);
    const [worth, cash, budgets, bills, goals] = await Promise.all([
      finance.get<{ currencies: CurrencyNetWorth[] }>("/net-worth", signal),
      finance.get<{ currencies: CurrencyCashflow[] }>(`/summaries/cashflow?from=${from}&to=${to}`, signal),
      finance.get<{ items: BudgetItem[] }>(`/budgets?month=${month}`, signal),
      finance.get<{ items: Commitment[] }>("/bills?due_within_days=14", signal),
      finance.get<{ items: Goal[] }>("/goals", signal),
    ]);
    return { settings, month, worth: worth.currencies, cash: cash.currencies, budgets: budgets.items, bills: bills.items, goals: goals.items };
  });
  const [zone, setZone] = useState("");
  const [error, setError] = useState<string | null>(null);

  async function saveZone(event: FormEvent) {
    event.preventDefault();
    setError(null);
    try {
      await finance.send("PUT", "/finance-settings", { timezone: zone.trim() });
      data.reload();
      onChanged();
    } catch (e) {
      setError(describeError(e));
    }
  }

  if (data.status === "loading") return <Loading label="Loading your overview…" />;
  if (data.status === "error") {
    return (
      <>
        <Notice>{describeError(data.error)}</Notice>
        <button type="button" onClick={data.reload}>Try again</button>
      </>
    );
  }
  const d = data.data;
  const warn = d.budgets.filter((b) => b.status !== "UNDER");
  return (
    <>
      {error && <Notice>{error}</Notice>}
      <h2>Net worth</h2>
      {d.worth.length === 0 && <p>Add an account to see your net worth.</p>}
      {d.worth.map((w) => (
        <p key={w.currency}>
          <strong>{formatMoney(w.net_worth, w.currency)}</strong>{" "}
          <span className="muted">assets {formatMoney(w.assets, w.currency)} · liabilities {formatMoney(w.liabilities, w.currency)}</span>
        </p>
      ))}
      {d.worth.length > 1 && <p className="muted">Currencies are never added together; each is shown on its own.</p>}
      <h2>This month ({d.month})</h2>
      {d.cash.length === 0 && <p>No transactions this month yet.</p>}
      {d.cash.map((c) => (
        <p key={c.currency}>
          Income {formatMoney(c.income, c.currency)} · Spent {formatMoney(c.net_expense, c.currency)} · Net {formatMoney(c.net, c.currency)}
        </p>
      ))}
      <h2>Budgets needing attention</h2>
      {d.budgets.length === 0 ? <p>No budgets set for this month.</p> : warn.length === 0 ? <p>All budgets are on track.</p> : (
        <ul>
          {warn.map((b) => (
            <li key={b.id}>{b.category_name ?? "Overall"}: {b.status === "OVER" ? "over budget" : "close to the limit"} ({formatMoney(b.spent, b.currency)} of {formatMoney(b.amount, b.currency)})</li>
          ))}
        </ul>
      )}
      <h2>Bills due in the next 14 days</h2>
      {d.bills.length === 0 ? <p>Nothing due soon.</p> : (
        <ul>
          {d.bills.map((b) => <li key={b.id}>{b.name} — {formatMoney(b.amount, b.currency)} on {b.next_due_date}{b.overdue && " (overdue)"}</li>)}
        </ul>
      )}
      <h2>Savings goals</h2>
      {d.goals.length === 0 ? <p>No goals yet.</p> : (
        <ul>
          {d.goals.map((g) => <li key={g.id}>{g.name}: {formatMoney(g.saved, g.currency)} of {formatMoney(g.target_amount, g.currency)} ({g.percent}%)</li>)}
        </ul>
      )}
      <form onSubmit={saveZone} aria-labelledby="tz-title" noValidate>
        <h2 id="tz-title">Time zone</h2>
        <p className="muted">Dates, month boundaries and recurring schedules follow this time zone. Currently {d.settings.timezone}.</p>
        <label htmlFor="tz">IANA time zone (for example Asia/Kolkata)</label>
        <input id="tz" value={zone} onChange={(e) => setZone(e.target.value)} placeholder={d.settings.timezone} />
        <button type="submit" disabled={!zone.trim()}>Save time zone</button>
      </form>
    </>
  );
}
