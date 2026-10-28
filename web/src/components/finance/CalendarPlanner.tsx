"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { api, describeError } from "@/lib/api";
import { dayLabel, monthCells, monthLabel, monthOf, shiftMonth, weekdayNames } from "@/lib/calendar";
import { sumAmounts } from "@/lib/decimal";
import { formatMoney, type Category, type Commitment, type RecurringRule, type Transaction } from "@/lib/finance";
import { useFinance } from "@/lib/FinanceContext";
import { useMonthTransactions } from "@/lib/useMonthTransactions";
import { useResource } from "@/lib/useResource";
import { Loading, Notice } from "../Notice";
import { Icon } from "../ui/Icon";
import { AddExpenseDialog } from "./AddExpenseDialog";

type Due = { key: string; label: string; amount: string; currency: string; kind: "Bill" | "Subscription" | "Recurring" };

function expenseTotals(items: Transaction[]): { currency: string; total: string }[] {
  const byCurrency = new Map<string, string[]>();
  for (const t of items) if (t.kind === "EXPENSE") byCurrency.set(t.currency, [...(byCurrency.get(t.currency) ?? []), t.amount]);
  return [...byCurrency.entries()].map(([currency, amounts]) => ({ currency, total: sumAmounts(amounts) }));
}

/** One transaction as a list row: what it was, its category, and the amount in its own currency. */
export function TxRow({ t, categoryName }: { t: Transaction; categoryName: string | undefined }) {
  const income = t.kind === "INCOME" || t.kind === "REFUND";
  const title = t.payee || t.note || (t.kind === "TRANSFER" ? "Transfer" : t.kind.charAt(0) + t.kind.slice(1).toLowerCase());
  return (
    <li>
      <span className={`tx-dot ${income ? "in" : t.kind === "EXPENSE" ? "out" : ""}`} aria-hidden="true"><Icon name={income ? "trendUp" : t.kind === "EXPENSE" ? "trendDown" : "repeat"} size={16} /></span>
      <span style={{ minWidth: 0 }}>
        <strong style={{ display: "block", overflowWrap: "anywhere" }}>{title}</strong>
        <span className="muted">{t.kind.charAt(0) + t.kind.slice(1).toLowerCase()}{categoryName ? ` · ${categoryName}` : ""} · {t.occurred_on}</span>
      </span>
      <span className={`tx-amount ${income ? "in" : ""}`}>{income ? "+" : ""}{formatMoney(t.amount, t.currency)}</span>
    </li>
  );
}

/**
 * The expense calendar. A month grid (or an agenda list) built from the server's transactions for the visible month: each day shows what was
 * spent, dots mark income and upcoming payments, and choosing a day lists its transactions and lets you add an expense dated to it.
 * Upcoming payments are the next due date the server holds for each bill, subscription and recurring rule; later occurrences are not guessed.
 */
export function CalendarPlanner() {
  const finance = useFinance();
  const settings = useResource((signal) => finance.get<{ today: string; timezone: string }>("/finance-settings", signal));
  if (settings.status === "loading") return <Loading label="Loading the calendar…" />;
  if (settings.status === "error") return <><Notice>{describeError(settings.error)}</Notice><button type="button" onClick={settings.reload}>Try again</button></>;
  return <Planner today={settings.data.today} timezone={settings.data.timezone} />;
}

function Planner({ today, timezone }: { today: string; timezone: string }) {
  const finance = useFinance();
  const [month, setMonth] = useState(monthOf(today));
  const [selected, setSelected] = useState(today);
  const [view, setView] = useState<"month" | "agenda">("month");
  const [adding, setAdding] = useState(false);
  const tx = useMonthTransactions(finance.space, month);
  const categories = useResource((signal) => finance.get<{ items: Category[] }>("/categories?include_archived=true", signal).then((r) => r.items).catch(() => [] as Category[]));
  const upcoming = useResource(async (signal) => {
    const [bills, subs, rules] = await Promise.all([
      api<{ items: Commitment[] }>(`/v1/spaces/${finance.space}/bills`, { signal }),
      api<{ items: Commitment[] }>(`/v1/spaces/${finance.space}/subscriptions`, { signal }),
      api<{ items: RecurringRule[] }>(`/v1/spaces/${finance.space}/recurring-rules`, { signal }),
    ]);
    const map = new Map<string, Due[]>();
    const add = (date: string | null, due: Due) => { if (date) map.set(date, [...(map.get(date) ?? []), due]); };
    for (const b of bills.items) if (b.status === "ACTIVE") add(b.next_due_date, { key: `b-${b.id}`, label: b.name, amount: b.amount, currency: b.currency, kind: "Bill" });
    for (const s of subs.items) if (s.status === "ACTIVE") add(s.next_due_date, { key: `s-${s.id}`, label: s.name, amount: s.amount, currency: s.currency, kind: "Subscription" });
    for (const r of rules.items) if (r.active) add(r.next_occurrence, { key: `r-${r.id}`, label: `${r.kind === "INCOME" ? "Income" : "Expense"} (recurring)`, amount: r.amount, currency: r.currency, kind: "Recurring" });
    return map;
  });

  const byDate = useMemo(() => {
    const map = new Map<string, Transaction[]>();
    if (tx.status === "ready") for (const t of tx.items) map.set(t.occurred_on, [...(map.get(t.occurred_on) ?? []), t]);
    return map;
  }, [tx]);
  const dueMap = upcoming.status === "ready" ? upcoming.data : new Map<string, Due[]>();
  const catName = (id: string | null) => (id && categories.status === "ready" ? categories.data.find((c) => c.id === id)?.name : undefined);

  const cells = monthCells(month, 1);
  const dayItems = byDate.get(selected) ?? [];
  const dayDue = dueMap.get(selected) ?? [];
  const dayTotals = expenseTotals(dayItems);
  const agendaDays = [...new Set([...byDate.keys(), ...[...dueMap.keys()].filter((d) => d.startsWith(month))])].filter((d) => d.startsWith(month)).sort();

  function go(next: string) {
    setMonth(next);
    // keep the selection in the visible month: the same day number where it exists, otherwise the first day
    const day = selected.slice(8);
    const candidate = `${next}-${day}`;
    setSelected(monthCells(next, 1).some((c) => c.inMonth && c.date === candidate) ? candidate : `${next}-01`);
  }

  function pick(date: string) {
    setSelected(date);
    if (monthOf(date) !== month) setMonth(monthOf(date));
  }

  function cellLabel(date: string): string {
    const items = byDate.get(date) ?? [];
    const totals = expenseTotals(items);
    const due = dueMap.get(date) ?? [];
    const parts = [dayLabel(date)];
    parts.push(items.length === 0 ? "no transactions" : `${items.length} transaction${items.length === 1 ? "" : "s"}`);
    if (totals.length > 0) parts.push(`spent ${totals.map((t) => formatMoney(t.total, t.currency)).join(" and ")}`);
    if (due.length > 0) parts.push(`${due.length} payment${due.length === 1 ? "" : "s"} due`);
    return parts.join(", ");
  }

  return (
    <div className="dash">
      <div className="panel">
        <div className="cal-head">
          <button type="button" className="icon-btn" onClick={() => go(shiftMonth(month, -1))} aria-label="Previous month"><Icon name="chevronLeft" /></button>
          <h2 className="cal-title" aria-live="polite">{monthLabel(month)}</h2>
          <button type="button" className="icon-btn" onClick={() => go(shiftMonth(month, 1))} aria-label="Next month"><Icon name="chevronRight" /></button>
          <button type="button" onClick={() => { setMonth(monthOf(today)); setSelected(today); }}>Today</button>
          <div className="seg" role="group" aria-label="Calendar view">
            <button type="button" aria-pressed={view === "month"} onClick={() => setView("month")}>Month</button>
            <button type="button" aria-pressed={view === "agenda"} onClick={() => setView("agenda")}>Agenda</button>
          </div>
          <span className="spacer" style={{ flex: 1 }} />
          <button type="button" className="btn-primary" onClick={() => setAdding(true)} aria-haspopup="dialog"><Icon name="plus" />Add expense</button>
        </div>
        {tx.status === "loading" && <Loading label="Loading this month's transactions…" />}
        {tx.status === "error" && <><Notice>{describeError(tx.error)}</Notice><button type="button" onClick={tx.reload}>Try again</button></>}
        {tx.status === "ready" && tx.truncated && <Notice kind="info">This month has more transactions than can be shown here, so day totals may be incomplete. Use the Transactions screen for the full list.</Notice>}
        {upcoming.status === "error" && <p className="muted" role="status">Upcoming payments could not be loaded, so they are not marked.</p>}
        {view === "month" ? (
          <div role="group" aria-label={`Calendar for ${monthLabel(month)}`}>
            <div className="cal-grid">
              {weekdayNames(1).map((d) => <div key={d} className="cal-dow" aria-hidden="true">{d}</div>)}
              {cells.map((c) => {
                const items = c.inMonth ? (byDate.get(c.date) ?? []) : [];
                const totals = expenseTotals(items);
                const due = c.inMonth ? (dueMap.get(c.date) ?? []) : [];
                const hasIncome = items.some((t) => t.kind === "INCOME" || t.kind === "REFUND");
                const cls = ["cal-cell", c.inMonth ? "" : "outside", c.date === today ? "today" : ""].filter(Boolean).join(" ");
                return (
                  <button key={c.date} type="button" className={cls} aria-pressed={c.date === selected} aria-label={cellLabel(c.date)} onClick={() => pick(c.date)}>
                    <span className="cal-day">{Number(c.date.slice(8))}</span>
                    {totals[0] && <span className="cal-total">{formatMoney(totals[0].total, totals[0].currency)}{totals.length > 1 ? ` +${totals.length - 1}` : ""}</span>}
                    <span className="cal-marks" aria-hidden="true">
                      {items.some((t) => t.kind === "EXPENSE") && <span className="cal-dot" />}
                      {hasIncome && <span className="cal-dot income" />}
                      {due.length > 0 && <span className="cal-dot bill" />}
                    </span>
                  </button>
                );
              })}
            </div>
            <ul className="legend" aria-label="Calendar key">
              <li><span className="cal-dot" style={{ display: "inline-block" }} /> Expenses recorded</li>
              <li><span className="cal-dot income" style={{ display: "inline-block" }} /> Income recorded</li>
              <li><span className="cal-dot bill" style={{ display: "inline-block" }} /> Payment due (next date held by the server)</li>
            </ul>
          </div>
        ) : (
          <ul className="agenda" aria-label={`Agenda for ${monthLabel(month)}`}>
            {tx.status === "ready" && agendaDays.length === 0 && <li>Nothing recorded or due in {monthLabel(month)}.</li>}
            {agendaDays.map((d) => (
              <li key={d}>
                <button type="button" className="link" onClick={() => { pick(d); setView("month"); }}>{dayLabel(d)}</button>
                <ul className="tx-list">
                  {(byDate.get(d) ?? []).map((t) => <TxRow key={t.id} t={t} categoryName={catName(t.category_id)} />)}
                </ul>
                {(dueMap.get(d) ?? []).map((due) => <p key={due.key} className="muted"><span className="badge badge-warning">Due</span> {due.kind}: {due.label} · {formatMoney(due.amount, due.currency)}</p>)}
              </li>
            ))}
          </ul>
        )}
        <p className="muted">Dates follow your time zone ({timezone}). Day totals add up the expenses the server lists for that day, per currency.</p>
      </div>

      <section className="panel" aria-labelledby="cal-day-title">
        <div className="panel-head">
          <h3 id="cal-day-title">{dayLabel(selected)}</h3>
          <button type="button" onClick={() => setAdding(true)}><Icon name="plus" />Add expense on this date</button>
        </div>
        {tx.status === "ready" && (
          <p>
            {dayItems.length === 0 ? "No transactions on this day." : `${dayItems.length} transaction${dayItems.length === 1 ? "" : "s"}.`}
            {dayTotals.length > 0 && <> Spent: <strong className="num">{dayTotals.map((t) => formatMoney(t.total, t.currency)).join(" · ")}</strong></>}
          </p>
        )}
        {dayItems.length > 0 && <ul className="tx-list" aria-label="Transactions on this day">{dayItems.map((t) => <TxRow key={t.id} t={t} categoryName={catName(t.category_id)} />)}</ul>}
        {dayDue.length > 0 && (
          <>
            <h4>Due on this day</h4>
            <ul className="list">{dayDue.map((d) => <li key={d.key}><span className="badge badge-warning">{d.kind}</span> {d.label} · {formatMoney(d.amount, d.currency)}</li>)}</ul>
          </>
        )}
        <p><Link href="/finance/transactions">Open all transactions</Link> to edit or remove one.</p>
      </section>
      <AddExpenseDialog open={adding} space={finance.space} date={selected} onClose={() => setAdding(false)} />
    </div>
  );
}
