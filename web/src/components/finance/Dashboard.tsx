"use client";

import Link from "next/link";
import { useState } from "react";
import { shiftMonth } from "@/lib/calendar";
import { compareAmounts, isNegative } from "@/lib/decimal";
import { formatMoney, type BudgetItem, type Commitment, type CurrencyCashflow, type CurrencyNetWorth, type Goal } from "@/lib/finance";
import { useFinance } from "@/lib/FinanceContext";
import { useSelectedMonth } from "@/lib/month";
import type { Report } from "@/lib/insights";
import { useDataRefresh } from "@/lib/useDataRefresh";
import { useResource } from "@/lib/useResource";
import { Loading } from "../Notice";
import { ProgressBar } from "../ui/BarChart";
import { Sparkline } from "../ui/FlowCharts";
import { Icon, type IconName } from "../ui/Icon";
import { EmptyState } from "../ui/States";
import { CashFlowPanel, InsightCard, PanelError, RecentTransactions, TimeZone } from "./DashboardPanels";
import { SpendingPlan } from "./SpendingPlan";

type Settings = { timezone: string; today: string };

function monthRange(month: string, today: string): { from: string; to: string } {
  const [y = 1970, m = 1] = month.split("-").map(Number);
  const last = new Date(Date.UTC(y, m, 0)).getUTCDate();
  const end = `${month}-${String(last).padStart(2, "0")}`;
  return { from: `${month}-01`, to: today < end && today.startsWith(month) ? today : end };
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

function Stat({ icon, tone, label, value, trend, children }: { icon: IconName; tone?: "forest" | "blue" | "green" | "amber"; label: string; value: string; trend?: React.ReactNode; children?: React.ReactNode }) {
  return (
    <div className={`stat ${tone === "forest" ? "stat-forest" : ""}`}>
      <div className="stat-top"><span>{label}</span><span className={`stat-icon ${tone ?? ""}`}><Icon name={icon} /></span></div>
      <div className="stat-value">{value}</div>
      {children}
      {trend}
    </div>
  );
}

/**
 * The personal dashboard for the month chosen in the header. Every figure is read from the server (net worth, cash flow, reports with their
 * period comparison, budgets, bills, goals, transactions); the only arithmetic here is exact addition or averaging of figures already listed.
 * Each block loads and fails on its own, shows a plain empty state for a new workspace, and is never filled with example data.
 */
export function Dashboard({ onChanged }: { onChanged: () => void }) {
  const [month] = useSelectedMonth();
  // A new month remounts the body, which asks the server for that month's figures.
  return <DashboardMonth key={month} month={month} onChanged={onChanged} />;
}

function DashboardMonth({ month, onChanged }: { month: string; onChanged: () => void }) {
  const finance = useFinance();
  const core = useResource(async (signal) => {
    const settings = await finance.get<Settings>("/finance-settings", signal);
    const { from, to } = monthRange(month, settings.today);
    const [worth, cash, budgets, bills, goals] = await Promise.all([
      finance.get<{ currencies: CurrencyNetWorth[] }>("/net-worth", signal),
      finance.get<{ currencies: CurrencyCashflow[] }>(`/summaries/cashflow?from=${from}&to=${to}`, signal),
      finance.get<{ items: BudgetItem[] }>(`/budgets?month=${month}`, signal),
      finance.get<{ items: Commitment[] }>("/bills?due_within_days=14", signal),
      finance.get<{ items: Goal[] }>("/goals", signal),
    ]);
    return { settings, from, to, worth: worth.currencies, cash: cash.currencies, budgets: budgets.items, bills: bills.items, goals: goals.items };
  });
  useDataRefresh(core.reload);
  const [picked, setPicked] = useState<string | null>(null);

  if (core.status === "loading") return <Loading label="Loading your dashboard…" />;
  if (core.status === "error") return <PanelError error={core.error} retry={core.reload} />;
  const d = core.data;
  const currencies = [...new Set([...d.worth.map((w) => w.currency), ...d.cash.map((c) => c.currency), ...d.budgets.map((b) => b.currency)])];
  const cur = picked && currencies.includes(picked) ? picked : currencies.includes("INR") ? "INR" : (currencies[0] ?? "INR");
  const worth = d.worth.find((w) => w.currency === cur);
  const cash = d.cash.find((c) => c.currency === cur);
  const budgets = d.budgets.filter((b) => b.currency === cur);
  const goals = d.goals.filter((g) => g.status !== "ARCHIVED");

  return (
    <div className="dash">
      {currencies.length > 1 && (
        <div className="dash-head">
          <p style={{ margin: 0 }}>Currencies are never added together; choose which to view.</p>
          <div className="seg" role="group" aria-label="Currency">
            {currencies.map((c) => <button key={c} type="button" aria-pressed={c === cur} onClick={() => setPicked(c)}>{c}</button>)}
          </div>
        </div>
      )}
      {d.worth.length === 0 && <EmptyState title="Welcome to Finora">Add an account to see your balances here. Nothing on this page is example data: it fills in as you record your own money.</EmptyState>}

      <SummaryCards month={month} from={d.from} to={d.to} cur={cur} worth={worth} cash={cash} />

      <div className="dash-main">
        <CashFlowPanel month={month} to={d.to} currency={cur} />
        <SpendingPlan budgets={budgets} month={month} currency={cur} />
      </div>

      <div className="dash-main dash-main-rev">
        <RecentTransactions from={d.from} to={d.to} />
        <GoalsPanel goals={goals} />
      </div>

      <UpcomingPayments bills={d.bills} />

      <InsightCard space={finance.space} />
      <TimeZone settings={d.settings} onSaved={() => { core.reload(); onChanged(); }} />
    </div>
  );
}

function SummaryCards({ month, from, to, cur, worth, cash }: { month: string; from: string; to: string; cur: string; worth: CurrencyNetWorth | undefined; cash: CurrencyCashflow | undefined }) {
  const finance = useFinance();
  const compare = useResource((signal) => finance.get<Report>(`/finance-reports/cash-flow?from=${from}&to=${to}&granularity=MONTH&compare=previous`, signal).catch(() => null));
  useDataRefresh(compare.reload);
  const trend = useResource((signal) => finance.get<Report>(`/finance-reports/cash-flow?from=${shiftMonth(month, -5)}-01&to=${to}&granularity=MONTH`, signal).then((r) => r.currencies.find((c) => c.currency === cur)?.rows ?? []).catch(() => []));
  useDataRefresh(trend.reload);
  const cmp = compare.status === "ready" && compare.data?.comparison ? compare.data.comparison.currencies.find((c) => c.currency === cur) : undefined;
  const label = compare.status === "ready" && compare.data?.comparison ? `vs ${compare.data.comparison.from} to ${compare.data.comparison.to}` : "";
  const rows = trend.status === "ready" ? trend.data : [];
  const spark = (name: string, tone: string) => (rows.length > 1 ? <Sparkline values={rows.map((r) => r.values[name])} tone={tone} /> : null);
  return (
    <section aria-label="Financial summary" className="stat-grid">
      <Stat tone="forest" icon="wallet" label="Available balance" value={worth ? formatMoney(worth.net_worth, cur) : "—"}>
        <span className="stat-sub">{worth ? `Assets ${formatMoney(worth.assets, cur)} · Liabilities ${formatMoney(worth.liabilities, cur)}` : "Add an account to see your balance"}</span>
      </Stat>
      <Stat tone="green" icon="trendUp" label="Income" value={cash ? formatMoney(cash.income, cur) : "—"} trend={spark("income", "var(--series-1)")}>
        {cash ? <Delta change={cmp?.change.income} currency={cur} goodWhen="up" label={label} /> : <span className="stat-sub">No transactions this month yet</span>}
      </Stat>
      <Stat tone="blue" icon="trendDown" label="Spending" value={cash ? formatMoney(cash.net_expense, cur) : "—"} trend={spark("net_expense", "var(--series-4)")}>
        {cash ? <Delta change={cmp?.change.net_expense} currency={cur} goodWhen="down" label={label} /> : <span className="stat-sub">No transactions this month yet</span>}
      </Stat>
      <Stat tone="amber" icon="target" label="Net saved" value={cash ? formatMoney(cash.net, cur) : "—"} trend={spark("net", "var(--series-2)")}>
        {cash ? <Delta change={cmp?.change.net} currency={cur} goodWhen="up" label={label} /> : <span className="stat-sub">Income minus spending</span>}
      </Stat>
    </section>
  );
}

const GOAL_TONES = ["green", "blue", "amber", "coral"] as const;
const goalIcon = (name: string): IconName => (/car|vehicle|bike/i.test(name) ? "car" : /home|house|flat|rent/i.test(name) ? "home" : /emergen|safety|rain/i.test(name) ? "shield" : /trip|travel|holiday|vacation/i.test(name) ? "globe" : "piggy");

function GoalsPanel({ goals }: { goals: Goal[] }) {
  return (
    <section className="panel" aria-labelledby="dash-goals">
      <div className="panel-head"><h3 id="dash-goals">Savings goals</h3><Link href="/finance/goals" className="btn-link"><Icon name="plus" size={14} />Add goal</Link></div>
      {goals.length === 0 ? <p>No goals yet. Add one to start tracking your savings.</p> : (
        <ul className="goal-list">
          {goals.slice(0, 4).map((g, i) => (
            <li key={g.id}>
              <span className={`goal-icon ${GOAL_TONES[i % GOAL_TONES.length]}`} aria-hidden="true"><Icon name={goalIcon(g.name)} size={16} /></span>
              <div className="goal-body">
                <div className="budget-line"><strong>{g.name}</strong><span className="num">{g.percent}% complete</span></div>
                <ProgressBar percent={Number(g.percent)} label={`${g.name} progress`} />
                <span className="muted">{formatMoney(g.saved, g.currency)} of {formatMoney(g.target_amount, g.currency)} · {formatMoney(g.remaining, g.currency)} to go</span>
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

const FREQUENCY: Record<Commitment["frequency"], string> = { ONCE: "One time", WEEKLY: "Weekly", MONTHLY: "Monthly", QUARTERLY: "Quarterly", YEARLY: "Yearly" };

function UpcomingPayments({ bills }: { bills: Commitment[] }) {
  return (
    <section className="panel" aria-labelledby="dash-bills">
      <div className="panel-head"><h3 id="dash-bills">Upcoming payments</h3><Link href="/finance/bills">Manage bills</Link></div>
      {bills.length === 0 ? <p>Nothing due in the next 14 days.</p> : (
        <ul className="bill-cards">
          {bills.map((b) => (
            <li key={b.id} className="bill-card">
              <span className="plan-icon" aria-hidden="true"><Icon name="bill" size={16} /></span>
              <div>
                <strong>{b.name}</strong>
                <span className="muted">{b.next_due_date ? `Due ${b.next_due_date}` : "No due date"} · {FREQUENCY[b.frequency]}</span>
                <span className={`pill ${b.overdue ? "pill-voided" : "pill-posted"}`}>{b.overdue ? "Overdue" : "Upcoming"}</span>
              </div>
              <span className="num bill-amount">{formatMoney(b.amount, b.currency)}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
