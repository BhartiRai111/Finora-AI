"use client";

import Link from "next/link";
import { useState } from "react";
import { compareAmounts, isNegative, sumAmounts } from "@/lib/decimal";
import { formatMoney, type Account, type BudgetItem, type Commitment, type CurrencyCashflow, type CurrencyNetWorth, type Goal } from "@/lib/finance";
import { useFinance } from "@/lib/FinanceContext";
import { useSelectedMonth } from "@/lib/month";
import type { Report } from "@/lib/insights";
import { useDataRefresh } from "@/lib/useDataRefresh";
import { useResource } from "@/lib/useResource";
import { Loading } from "../Notice";
import { ProgressBar } from "../ui/BarChart";
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

function Stat({ icon, tone, label, value, children }: { icon: IconName; tone?: "forest" | "blue" | "green" | "amber"; label: string; value: string; children?: React.ReactNode }) {
  return (
    <div className="stat">
      <div className="stat-top"><span>{label}</span><span className={`stat-icon ${tone ?? ""}`}><Icon name={icon} /></span></div>
      <div className="stat-value">{value}</div>
      {children}
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
      {d.worth.length === 0 && <EmptyState title="Welcome to Finora">Add an account to see your balances here. Nothing on this page is example data: it fills in as you record your own money.</EmptyState>}

      <div className="dash-top">
        <div className="cluster"><SummaryCards from={d.from} to={d.to} cur={cur} cash={cash} budgets={budgets} /></div>
        <BalanceCard cur={cur} currencies={currencies} onCurrency={setPicked} worth={worth} />
      </div>

      <div className="dash-mid">
        <SpendingPlan budgets={budgets} month={month} currency={cur} />
        <CashFlowPanel month={month} to={d.to} currency={cur} />
      </div>

      <RecentTransactions from={d.from} to={d.to} />

      <div className="dash-main dash-main-rev">
        <GoalsPanel goals={goals} />
        <UpcomingPayments bills={d.bills} />
      </div>

      <InsightCard space={finance.space} />
      <TimeZone settings={d.settings} onSaved={() => { core.reload(); onChanged(); }} />
    </div>
  );
}

/** The balance (net worth) for one currency, a currency choice when there is more than one, and the accounts the person added (balances calculated from their transactions). */
function BalanceCard({ cur, currencies, onCurrency, worth }: { cur: string; currencies: string[]; onCurrency: (c: string) => void; worth: CurrencyNetWorth | undefined }) {
  const finance = useFinance();
  const accounts = useResource((signal) => finance.get<{ items: Account[] }>("/accounts", signal).then((r) => r.items.filter((a) => !a.archived)));
  useDataRefresh(accounts.reload);
  return (
    <section className="cluster balance" aria-labelledby="bal-title">
      <div className="balance-head">
        <h3 id="bal-title">Total balance</h3>
        {currencies.length > 1 ? (
          <span className="select-pill">
            <label htmlFor="dash-currency" className="visually-hidden">Currency</label>
            <select id="dash-currency" value={cur} onChange={(e) => onCurrency(e.target.value)}>{currencies.map((c) => <option key={c} value={c}>{c}</option>)}</select>
          </span>
        ) : <span className="select-pill">{cur}</span>}
      </div>
      <div className="balance-value num">{worth ? formatMoney(worth.net_worth, cur) : "—"}</div>
      <p className="muted">{worth ? `Assets ${formatMoney(worth.assets, cur)} · Liabilities ${formatMoney(worth.liabilities, cur)}` : "Add an account to see your balance"}{currencies.length > 1 && " · currencies are never added together"}</p>
      <div className="balance-actions">
        <Link href="/finance/transactions" className="btn-primary btn-link"><Icon name="swap" size={16} />Transfer</Link>
        <Link href="/finance/accounts" className="btn-link btn-ghost"><Icon name="plus" size={16} />Add account</Link>
      </div>
      <div className="wallets">
        <div className="wallets-head"><strong>Accounts</strong>{accounts.status === "ready" && <span className="muted">Total {accounts.data.length} {accounts.data.length === 1 ? "account" : "accounts"}</span>}</div>
        {accounts.status === "loading" && <Loading label="Loading accounts…" />}
        {accounts.status === "error" && <PanelError error={accounts.error} retry={accounts.reload} />}
        {accounts.status === "ready" && (accounts.data.length === 0 ? <p>No accounts yet.</p> : (
          <ul className="wallet-list">
            {accounts.data.map((a) => (
              <li key={a.id} className="wallet">
                <span className="wallet-amount num">{formatMoney(a.balance, a.currency)}</span><span className="chip chip-up">Active</span>
                <span className="muted wallet-name">{a.name} · {a.type.toLowerCase().replace(/_/g, " ")}</span>
              </li>
            ))}
          </ul>
        ))}
        <p className="muted side-note">Added by you; not linked to a bank.</p>
      </div>
    </section>
  );
}

function SummaryCards({ from, to, cur, cash, budgets }: { from: string; to: string; cur: string; cash: CurrencyCashflow | undefined; budgets: BudgetItem[] }) {
  const finance = useFinance();
  const compare = useResource((signal) => finance.get<Report>(`/finance-reports/cash-flow?from=${from}&to=${to}&granularity=MONTH&compare=previous`, signal).catch(() => null));
  useDataRefresh(compare.reload);
  const cmp = compare.status === "ready" && compare.data?.comparison ? compare.data.comparison.currencies.find((c) => c.currency === cur) : undefined;
  const label = compare.status === "ready" && compare.data?.comparison ? `vs ${compare.data.comparison.from} to ${compare.data.comparison.to}` : "";
  const overall = budgets.find((b) => b.category_id === null);
  const parts = overall ? [overall] : budgets.filter((b) => b.category_id !== null);
  const left = parts.length > 0 ? sumAmounts(parts.map((b) => b.remaining)) : null;
  return (
    <section aria-label="Financial summary" className="stat-grid">
      <Stat tone="green" icon="trendUp" label="Income" value={cash ? formatMoney(cash.income, cur) : "—"}>
        {cash ? <Delta change={cmp?.change.income} currency={cur} goodWhen="up" label={label} /> : <span className="stat-sub">No transactions this month yet</span>}
      </Stat>
      <Stat tone="blue" icon="trendDown" label="Spending" value={cash ? formatMoney(cash.net_expense, cur) : "—"}>
        {cash ? <Delta change={cmp?.change.net_expense} currency={cur} goodWhen="down" label={label} /> : <span className="stat-sub">No transactions this month yet</span>}
      </Stat>
      <Stat tone="amber" icon="target" label="Net saved" value={cash ? formatMoney(cash.net, cur) : "—"}>
        {cash ? <Delta change={cmp?.change.net} currency={cur} goodWhen="up" label={label} /> : <span className="stat-sub">Income minus spending</span>}
      </Stat>
      <Stat icon="wallet" label="Budget remaining" value={left !== null ? formatMoney(left, cur) : "—"}>
        {left !== null ? <span className="stat-sub">{overall ? "Overall budget" : `Across ${parts.length} category budget${parts.length === 1 ? "" : "s"}`}{isNegative(left) ? " · over budget" : ""}</span> : <span className="stat-sub"><Link href="/finance/budgets">Set a monthly budget</Link> to track what is left</span>}
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
