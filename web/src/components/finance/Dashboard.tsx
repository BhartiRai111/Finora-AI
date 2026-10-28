"use client";

import Link from "next/link";
import { useMemo, useState, type FormEvent } from "react";
import { api, describeError } from "@/lib/api";
import { shiftMonth } from "@/lib/calendar";
import { compareAmounts, isNegative, sumAmounts } from "@/lib/decimal";
import { currentMonth, formatMoney, type BudgetItem, type Category, type Commitment, type CurrencyCashflow, type CurrencyNetWorth, type Goal, type TransactionPage } from "@/lib/finance";
import { useFinance } from "@/lib/FinanceContext";
import type { AssistantAnswer } from "@/lib/assistant";
import type { Report } from "@/lib/insights";
import { useDataRefresh } from "@/lib/useDataRefresh";
import { useMonthTransactions } from "@/lib/useMonthTransactions";
import { useResource } from "@/lib/useResource";
import { Loading, Notice } from "../Notice";
import { BarChart, DonutChart, LineChart, ProgressBar } from "../ui/BarChart";
import { Icon, type IconName } from "../ui/Icon";
import { EmptyState } from "../ui/States";
import { CalendarPlanner, TxRow } from "./CalendarPlanner";

type Settings = { timezone: string; today: string };

function monthRange(month: string, today: string): { from: string; to: string } {
  const [y = 1970, m = 1] = month.split("-").map(Number);
  const last = new Date(Date.UTC(y, m, 0)).getUTCDate();
  const end = `${month}-${String(last).padStart(2, "0")}`;
  return { from: `${month}-01`, to: today < end && today.startsWith(month) ? today : end };
}

function PanelError({ error, retry }: { error: unknown; retry: () => void }) {
  return <><Notice>{describeError(error)}</Notice><button type="button" onClick={retry}>Try again</button></>;
}

/** How a figure moved against the previous period, from the server's own comparison (amount and percentage); nothing is derived here. */
function Delta({ change, currency, goodWhen, label }: { change: { amount: string; percent: string | null } | undefined; currency: string; goodWhen: "up" | "down"; label: string }) {
  if (!change) return <span className="stat-sub">No comparison available yet</span>;
  const flat = compareAmounts(change.amount, "0") === 0;
  const up = !isNegative(change.amount) && !flat;
  const good = flat ? null : goodWhen === "up" ? up : !up;
  const text = change.percent !== null ? `${up ? "+" : ""}${change.percent}%` : `${up ? "+" : ""}${formatMoney(change.amount, currency)}`;
  return (
    <span className="stat-delta">
      <span className={`delta ${flat ? "flat" : good ? "good" : "bad"}`}>
        <Icon name={flat ? "chevronRight" : up ? "trendUp" : "trendDown"} size={14} />
        {flat ? "No change" : text}<span className="visually-hidden">{flat ? "" : up ? " increase" : " decrease"}</span>
      </span>
      <span className="stat-sub">{label}</span>
    </span>
  );
}

function Stat({ icon, tone, label, value, children }: { icon: IconName; tone?: "blue" | "green" | "amber"; label: string; value: string; children?: React.ReactNode }) {
  return (
    <div className="stat">
      <div className="stat-top"><span>{label}</span><span className={`stat-icon ${tone ?? ""}`}><Icon name={icon} /></span></div>
      <div className="stat-value">{value}</div>
      {children}
    </div>
  );
}

/**
 * The personal dashboard. Every figure is read from the server (net worth, cash flow, reports with their period comparison, budgets, bills,
 * goals, transactions); the only arithmetic here is exact addition of figures already listed (category totals, budget totals). Each block
 * loads and fails on its own, shows a plain empty state for a new workspace, and is never filled with example data.
 */
export function Dashboard({ onChanged }: { onChanged: () => void }) {
  const finance = useFinance();
  const core = useResource(async (signal) => {
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
    return { settings, month, from, to, worth: worth.currencies, cash: cash.currencies, budgets: budgets.items, bills: bills.items, goals: goals.items };
  });
  useDataRefresh(core.reload);
  // Recent transactions and category names load apart from the core figures, so a failure here never hides the balances.
  const extra = useResource(async (signal) => {
    const [recent, categories] = await Promise.all([
      finance.get<TransactionPage>("/transactions?limit=6", signal),
      finance.get<{ items: Category[] }>("/categories?include_archived=true", signal),
    ]);
    return { recent: recent.items.filter((t) => t.status === "POSTED"), categories: categories.items };
  });
  useDataRefresh(extra.reload);
  const categories = extra.status === "ready" ? extra.data.categories : [];
  const [picked, setPicked] = useState<string | null>(null);

  if (core.status === "loading") return <Loading label="Loading your dashboard…" />;
  if (core.status === "error") return <PanelError error={core.error} retry={core.reload} />;
  const d = core.data;
  const currencies = [...new Set([...d.worth.map((w) => w.currency), ...d.cash.map((c) => c.currency), ...d.budgets.map((b) => b.currency)])];
  const cur = picked && currencies.includes(picked) ? picked : currencies.includes("INR") ? "INR" : (currencies[0] ?? "INR");
  const worth = d.worth.find((w) => w.currency === cur);
  const cash = d.cash.find((c) => c.currency === cur);
  const budgets = d.budgets.filter((b) => b.currency === cur);
  const empty = d.worth.length === 0;

  return (
    <div className="dash">
      <div className="dash-head">
        <p style={{ margin: 0 }}>Month to date: {d.from} to {d.to}.{currencies.length > 1 && " Currencies are never added together; choose which to view."}</p>
        {currencies.length > 1 && (
          <div className="seg" role="group" aria-label="Currency">
            {currencies.map((c) => <button key={c} type="button" aria-pressed={c === cur} onClick={() => setPicked(c)}>{c}</button>)}
          </div>
        )}
      </div>

      {empty && <EmptyState title="Welcome to Finora">Add an account to see your balances here. Nothing on this page is example data: it fills in as you record your own money.</EmptyState>}

      <SummaryCards d={d} cur={cur} worth={worth} cash={cash} budgets={budgets} />

      <Analytics space={finance.space} month={d.month} today={d.settings.today} cur={cur} categories={categories} />

      <section aria-labelledby="dash-cal">
        <h2 id="dash-cal">Calendar</h2>
        <CalendarPlanner />
      </section>

      <div className="grid-2">
        <BudgetOverview budgets={budgets} cur={cur} />
        <section className="panel" aria-labelledby="dash-bills">
          <div className="panel-head"><h3 id="dash-bills">Upcoming payments</h3><Link href="/finance/bills">All bills</Link></div>
          {d.bills.length === 0 ? <p>Nothing due in the next 14 days.</p> : (
            <ul className="tx-list">
              {d.bills.map((b) => (
                <li key={b.id}>
                  <span className="tx-dot out" aria-hidden="true"><Icon name="bill" size={16} /></span>
                  <span><strong>{b.name}</strong><span className="muted" style={{ display: "block" }}>{b.next_due_date ? `Due ${b.next_due_date}` : "No due date"}{b.overdue ? " · overdue" : ""}</span></span>
                  <span className="tx-amount">{formatMoney(b.amount, b.currency)}</span>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>

      <div className="grid-2">
        <section className="panel" aria-labelledby="dash-recent">
          <div className="panel-head"><h3 id="dash-recent">Recent transactions</h3><Link href="/finance/transactions">View all</Link></div>
          {extra.status === "loading" && <Loading label="Loading recent transactions…" />}
          {extra.status === "error" && <PanelError error={extra.error} retry={extra.reload} />}
          {extra.status === "ready" && (extra.data.recent.length === 0 ? <p>No transactions yet. Use “Add expense” to record your first.</p> : (
            <ul className="tx-list">{extra.data.recent.map((t) => <TxRow key={t.id} t={t} categoryName={categories.find((c) => c.id === t.category_id)?.name} />)}</ul>
          ))}
        </section>
        <div className="dash">
          <section className="panel" aria-labelledby="dash-goals">
            <div className="panel-head"><h3 id="dash-goals">Savings goals</h3><Link href="/finance/goals">All goals</Link></div>
            {d.goals.length === 0 ? <p>No goals yet.</p> : (
              <div>{d.goals.slice(0, 4).map((g) => (
                <div key={g.id} className="budget-row">
                  <div className="budget-line"><strong>{g.name}</strong><span className="num">{formatMoney(g.saved, g.currency)} of {formatMoney(g.target_amount, g.currency)} · {g.percent}%</span></div>
                  <ProgressBar percent={Number(g.percent)} label={`${g.name} progress`} />
                </div>
              ))}</div>
            )}
          </section>
          <InsightCard space={finance.space} />
        </div>
      </div>
      <TimeZone settings={d.settings} onSaved={() => { core.reload(); onChanged(); }} />
    </div>
  );
}

type Core = { month: string; from: string; to: string; settings: Settings };

function SummaryCards({ cur, worth, cash, budgets, d }: { d: Core; cur: string; worth: CurrencyNetWorth | undefined; cash: CurrencyCashflow | undefined; budgets: BudgetItem[] }) {
  const finance = useFinance();
  const compare = useResource((signal) => finance.get<Report>(`/finance-reports/cash-flow?from=${d.from}&to=${d.to}&granularity=MONTH&compare=previous`, signal).catch(() => null));
  useDataRefresh(compare.reload);
  const cmp = compare.status === "ready" && compare.data?.comparison ? compare.data.comparison.currencies.find((c) => c.currency === cur) : undefined;
  const label = compare.status === "ready" && compare.data?.comparison ? `vs ${compare.data.comparison.from} to ${compare.data.comparison.to}` : "";

  const overall = budgets.find((b) => b.category_id === null);
  const parts = overall ? [overall] : budgets;
  const budgetTotal = parts.length > 0 ? sumAmounts(parts.map((b) => b.amount)) : null;
  const budgetSpent = parts.length > 0 ? sumAmounts(parts.map((b) => b.spent)) : null;
  const budgetLeft = parts.length > 0 ? sumAmounts(parts.map((b) => b.remaining)) : null;

  return (
    <section aria-label="Financial summary" className="stat-grid">
      <Stat icon="wallet" label="Total balance" value={worth ? formatMoney(worth.net_worth, cur) : "—"}>
        <span className="stat-sub">{worth ? `Assets ${formatMoney(worth.assets, cur)} · Liabilities ${formatMoney(worth.liabilities, cur)}` : "Add an account to see your balance"}</span>
      </Stat>
      <Stat icon="trendUp" tone="green" label="Monthly income" value={cash ? formatMoney(cash.income, cur) : "—"}>
        {cash ? <Delta change={cmp?.change.income} currency={cur} goodWhen="up" label={label} /> : <span className="stat-sub">No transactions this month yet</span>}
      </Stat>
      <Stat icon="trendDown" tone="blue" label="Monthly expenses" value={cash ? formatMoney(cash.net_expense, cur) : "—"}>
        {cash ? <Delta change={cmp?.change.net_expense} currency={cur} goodWhen="down" label={label} /> : <span className="stat-sub">No transactions this month yet</span>}
      </Stat>
      <Stat icon="target" tone="amber" label="Savings this month" value={cash ? formatMoney(cash.net, cur) : "—"}>
        {cash ? <Delta change={cmp?.change.net} currency={cur} goodWhen="up" label={label} /> : <span className="stat-sub">Income minus expenses</span>}
      </Stat>
      <Stat icon="bank" label="Budget remaining" value={budgetLeft !== null ? formatMoney(budgetLeft, cur) : "—"}>
        {budgetLeft !== null && budgetTotal !== null && budgetSpent !== null ? (
          <span className="stat-sub">{formatMoney(budgetSpent, cur)} spent of {formatMoney(budgetTotal, cur)}{overall ? " (overall budget)" : ` across ${parts.length} budget${parts.length === 1 ? "" : "s"}`}{isNegative(budgetLeft) ? " · over budget" : ""}</span>
        ) : <span className="stat-sub"><Link href="/finance/budgets">Set a monthly budget</Link> to track what is left</span>}
      </Stat>
    </section>
  );
}

function Analytics({ space, month, today, cur, categories }: { space: string; month: string; today: string; cur: string; categories: Category[] }) {
  const finance = useFinance();
  const from = `${shiftMonth(month, -5)}-01`;
  const series = useResource((signal) => finance.get<Report>(`/finance-reports/cash-flow?from=${from}&to=${today}&granularity=MONTH`, signal));
  useDataRefresh(series.reload);
  const tx = useMonthTransactions(space, month);
  const rows = series.status === "ready" ? (series.data.currencies.find((c) => c.currency === cur)?.rows.filter((r) => r.activity) ?? []) : [];
  const labels = rows.map((r) => r.period);
  const col = (name: string) => rows.map((r) => r.values[name]);

  const slices = useMemo(() => {
    if (tx.status !== "ready") return null;
    const byCat = new Map<string, string[]>();
    for (const t of tx.items) if (t.kind === "EXPENSE" && t.currency === cur) {
      const key = t.category_id ?? "none";
      byCat.set(key, [...(byCat.get(key) ?? []), t.amount]);
    }
    const all = [...byCat.entries()].map(([key, amounts]) => ({ key, label: key === "none" ? "Uncategorised" : (categories.find((c) => c.id === key)?.name ?? "Other"), amount: sumAmounts(amounts) }))
      .sort((a, b) => compareAmounts(b.amount, a.amount));
    const top = all.slice(0, 5);
    const rest = all.slice(5);
    if (rest.length > 0) top.push({ key: "rest", label: "Everything else", amount: sumAmounts(rest.map((r) => r.amount)) });
    return { slices: top, total: sumAmounts(all.map((a) => a.amount)) };
  }, [tx, cur, categories]);

  return (
    <section aria-labelledby="dash-analytics" className="dash">
      <h2 id="dash-analytics" style={{ margin: 0 }}>Spending analytics</h2>
      <div className="grid-3-2">
        <div className="panel">
          <h3>Income and expenses by month</h3>
          {series.status === "loading" && <Loading label="Loading the monthly figures…" />}
          {series.status === "error" && <PanelError error={series.error} retry={series.reload} />}
          {series.status === "ready" && (rows.length < 2 ? <p>Not enough history for a chart yet: it needs at least two months with activity in {cur}.</p> : (
            <BarChart title="Income and expenses" currency={cur} labels={labels} caption="exact figures are on the Analytics and reports screen"
              series={[{ key: "income", label: "Income", values: col("income") }, { key: "net_expense", label: "Expenses", values: col("net_expense") }]}
              summary={`Bar chart in ${cur}: income and expenses for ${rows.length} months, from ${labels[0]} to ${labels[labels.length - 1]}.`} />
          ))}
        </div>
        <div className="panel">
          <h3>Where this month&apos;s money went</h3>
          {tx.status === "loading" && <Loading label="Loading this month's expenses…" />}
          {tx.status === "error" && <PanelError error={tx.error} retry={tx.reload} />}
          {tx.status === "ready" && slices && (slices.slices.length === 0 ? <p>No expenses recorded this month in {cur}.</p> : (
            <>
              <DonutChart title="Expenses by category" currency={cur} slices={slices.slices} total={slices.total} summary={`Donut chart in ${cur}: ${slices.slices.length} categories of this month's expenses.`} />
              <p className="muted">Gross expenses recorded this month; refunds are not deducted here.{tx.truncated && " The month has more transactions than could be read, so this may be incomplete."}</p>
            </>
          ))}
        </div>
      </div>
      <div className="grid-2">
        <div className="panel">
          <h3>Spending trend</h3>
          {series.status === "ready" && (rows.length < 2 ? <p>A trend needs at least two months with activity in {cur}.</p> : (
            <LineChart title="Expenses per month" currency={cur} labels={labels} series={[{ key: "net_expense", label: "Expenses", values: col("net_expense") }]}
              summary={`Line chart in ${cur}: expenses per month from ${labels[0]} to ${labels[labels.length - 1]}.`} />
          ))}
        </div>
        <div className="panel">
          <h3>Savings trend</h3>
          {series.status === "ready" && (rows.length < 2 ? <p>A trend needs at least two months with activity in {cur}.</p> : (
            <LineChart title="Savings per month (income minus expenses)" currency={cur} labels={labels} series={[{ key: "net", label: "Savings", values: col("net") }]}
              summary={`Line chart in ${cur}: savings per month from ${labels[0]} to ${labels[labels.length - 1]}.`} />
          ))}
        </div>
      </div>
    </section>
  );
}

const STATUS_TEXT: Record<BudgetItem["status"], string> = { UNDER: "On track", WARNING: "Close to the limit", OVER: "Over budget" };

function BudgetOverview({ budgets, cur }: { budgets: BudgetItem[]; cur: string }) {
  return (
    <section className="panel" aria-labelledby="dash-budget">
      <div className="panel-head"><h3 id="dash-budget">Budget overview</h3><Link href="/finance/budgets">Manage budgets</Link></div>
      {budgets.length === 0 ? <p>No budgets set for this month in {cur}. <Link href="/finance/budgets">Set one</Link>.</p> : (
        <div>
          {budgets.map((b) => (
            <div key={b.id} className="budget-row">
              <div className="budget-line"><strong>{b.category_name ?? "Everything"}</strong><span className="num">{formatMoney(b.spent, b.currency)} of {formatMoney(b.amount, b.currency)}</span></div>
              <ProgressBar percent={Number(b.percent_used)} tone={b.status === "OVER" ? "over" : b.status === "WARNING" ? "warning" : "under"} label={`${b.category_name ?? "Overall"} budget used`} />
              <span className={`status status-${b.status.toLowerCase()}`}>{STATUS_TEXT[b.status]} · {b.percent_used}% used · {formatMoney(b.remaining, b.currency)} left</span>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

/** A real insight from the server's deterministic explanation of last month's spending change. It is labelled with what produced it. */
function InsightCard({ space }: { space: string }) {
  const insight = useResource((signal) => api<AssistantAnswer>(`/v1/spaces/${space}/finance-assistant/expense-change`, { signal }));
  return (
    <section className="panel insight" aria-labelledby="dash-insight">
      <div className="panel-head"><h3 id="dash-insight">Insight</h3><Link href="/finance/assistant">AI insights</Link></div>
      {insight.status === "loading" && <Loading label="Looking at your spending…" />}
      {insight.status === "error" && <p>{describeError(insight.error)}</p>}
      {insight.status === "ready" && (
        <>
          <p style={{ color: "var(--text)" }}><strong>{insight.data.summary ?? insight.data.clarification ?? "No insight is available yet."}</strong></p>
          {insight.data.period?.label && <p className="muted">Period: {insight.data.period.label}{insight.data.period.comparison_label ? ` · compared with ${insight.data.period.comparison_label}` : ""}</p>}
          <p className="muted">
            {insight.data.ai.status === "EXECUTED" ? "Explained by an AI model from the figures above." : "Calculated by Finora from your recorded data. No AI model wrote this."}
          </p>
        </>
      )}
    </section>
  );
}

function TimeZone({ settings, onSaved }: { settings: Settings; onSaved: () => void }) {
  const finance = useFinance();
  const [zone, setZone] = useState("");
  const [error, setError] = useState<string | null>(null);
  async function save(event: FormEvent) {
    event.preventDefault();
    setError(null);
    try {
      await finance.send("PUT", "/finance-settings", { timezone: zone.trim() });
      onSaved();
    } catch (e) {
      setError(describeError(e));
    }
  }
  return (
    <details className="panel">
      <summary>Time zone ({settings.timezone})</summary>
      {error && <Notice>{error}</Notice>}
      <form onSubmit={(e) => void save(e)} aria-labelledby="tz-title" noValidate>
        <h3 id="tz-title">Time zone</h3>
        <p className="muted">Dates, month boundaries and recurring schedules follow this time zone. Currently {settings.timezone}.</p>
        <label htmlFor="tz">IANA time zone (for example Asia/Kolkata)</label>
        <input id="tz" value={zone} onChange={(e) => setZone(e.target.value)} placeholder={settings.timezone} />
        <button type="submit" disabled={!zone.trim()}>Save time zone</button>
      </form>
    </details>
  );
}

