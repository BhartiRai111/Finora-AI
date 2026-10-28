"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { api, describeError } from "@/lib/api";
import { shiftMonth } from "@/lib/calendar";
import { averageAmount, percentOf, sumAmounts } from "@/lib/decimal";
import { formatMoney, type Account, type Category, type Transaction, type TransactionPage } from "@/lib/finance";
import { useFinance } from "@/lib/FinanceContext";
import type { AssistantAnswer } from "@/lib/assistant";
import type { Report } from "@/lib/insights";
import { useDataRefresh } from "@/lib/useDataRefresh";
import { useResource } from "@/lib/useResource";
import { Loading, Notice } from "../Notice";
import { CashFlowChart } from "../ui/FlowCharts";
import { Icon } from "../ui/Icon";

export function PanelError({ error, retry }: { error: unknown; retry: () => void }) {
  return <><Notice>{describeError(error)}</Notice><button type="button" onClick={retry}>Try again</button></>;
}

const RANGES = [{ id: "3M", months: 3 }, { id: "6M", months: 6 }, { id: "1Y", months: 12 }] as const;

/** Income and spending per month with the net savings line, for 3, 6 or 12 months ending at the month being viewed. All figures come from the server's cash-flow report. */
export function CashFlowPanel({ month, to, currency }: { month: string; to: string; currency: string }) {
  const [range, setRange] = useState<(typeof RANGES)[number]["id"]>("6M");
  const months = RANGES.find((r) => r.id === range)!.months;
  return (
    <section className="panel" aria-labelledby="cash-title">
      <div className="panel-head">
        <div><h3 id="cash-title">Cash flow</h3><p className="muted" style={{ margin: 0 }}>Income, spending and net saved · {currency}</p></div>
        <div className="seg" role="group" aria-label="Cash flow range">
          {RANGES.map((r) => <button key={r.id} type="button" aria-pressed={r.id === range} onClick={() => setRange(r.id)}>{r.id}</button>)}
        </div>
      </div>
      <CashFlowBody key={`${month}-${months}-${currency}`} from={`${shiftMonth(month, -(months - 1))}-01`} to={to} currency={currency} />
    </section>
  );
}

function CashFlowBody({ from, to, currency }: { from: string; to: string; currency: string }) {
  const finance = useFinance();
  const series = useResource((signal) => finance.get<Report>(`/finance-reports/cash-flow?from=${from}&to=${to}&granularity=MONTH`, signal));
  useDataRefresh(series.reload);
  if (series.status === "loading") return <Loading label="Loading the cash flow…" />;
  if (series.status === "error") return <PanelError error={series.error} retry={series.reload} />;
  const rows = series.data.currencies.find((c) => c.currency === currency)?.rows ?? [];
  if (!rows.some((r) => r.activity)) return <p>No income or spending recorded in {currency} for this period yet.</p>;
  const pick = (name: string) => rows.map((r) => r.values[name] ?? "0");
  const income = pick("income");
  const spending = pick("net_expense");
  const net = pick("net");
  const totalIncome = sumAmounts(income);
  const rate = percentOf(sumAmounts(net), totalIncome);
  const labels = rows.map((r) => r.period);
  return (
    <>
      <p className="big-figure num" aria-label="Net saved over the period">{formatMoney(sumAmounts(net), currency)}<span className="muted"> net saved</span></p>
      <CashFlowChart currency={currency} labels={labels} income={income} spending={spending} net={net}
        summary={`Chart in ${currency}: income, spending and net savings for ${rows.length} months, from ${labels[0]} to ${labels[labels.length - 1]}.`} />
      <dl className="cash-summary">
        <div><dt>Average income</dt><dd className="num">{formatMoney(averageAmount(income), currency)}</dd></div>
        <div><dt>Average spending</dt><dd className="num">{formatMoney(averageAmount(spending), currency)}</dd></div>
        <div><dt>Savings rate</dt><dd className="num">{rate === null ? "—" : `${rate}%`}</dd></div>
      </dl>
    </>
  );
}

const KIND_FILTER = [{ id: "", label: "All" }, { id: "INCOME", label: "Income" }, { id: "EXPENSE", label: "Expense" }] as const;
const kindText = (k: string) => k.charAt(0) + k.slice(1).toLowerCase();

/** The latest transactions of the month being viewed as a table, filtered by type and search words by the server. */
export function RecentTransactions({ from, to }: { from: string; to: string }) {
  const [kind, setKind] = useState("");
  const [draft, setDraft] = useState("");
  const [q, setQ] = useState("");
  function search(event: FormEvent) {
    event.preventDefault();
    setQ(draft.trim());
  }
  return (
    <section className="panel" aria-labelledby="recent-title">
      <div className="panel-head">
        <h3 id="recent-title">Recent activities</h3>
        <Link href="/finance/transactions">View all</Link>
      </div>
      <div className="table-tools">
        <div className="seg" role="group" aria-label="Transaction type">
          {KIND_FILTER.map((k) => <button key={k.id} type="button" aria-pressed={k.id === kind} onClick={() => setKind(k.id)}>{k.label}</button>)}
        </div>
        <form role="search" aria-label="Search recent transactions" onSubmit={search} className="tx-search">
          <Icon name="search" size={16} />
          <label htmlFor="recent-search" className="visually-hidden">Search recent transactions</label>
          <input id="recent-search" type="search" value={draft} onChange={(e) => setDraft(e.target.value)} placeholder="Search" maxLength={100} autoComplete="off" />
        </form>
      </div>
      <RecentRows key={`${kind}|${q}|${from}`} from={from} to={to} kind={kind} q={q} />
    </section>
  );
}

function RecentRows({ from, to, kind, q }: { from: string; to: string; kind: string; q: string }) {
  const finance = useFinance();
  const data = useResource(async (signal) => {
    const p = new URLSearchParams({ from, to, limit: "8" });
    if (kind) p.set("kind", kind);
    if (q) p.set("q", q);
    const [page, categories, accounts] = await Promise.all([
      finance.get<TransactionPage>(`/transactions?${p.toString()}`, signal),
      finance.get<{ items: Category[] }>("/categories?include_archived=true", signal),
      finance.get<{ items: Account[] }>("/accounts?include_archived=true", signal),
    ]);
    return { items: page.items, categories: categories.items, accounts: accounts.items };
  });
  useDataRefresh(data.reload);
  if (data.status === "loading") return <Loading label="Loading recent transactions…" />;
  if (data.status === "error") return <PanelError error={data.error} retry={data.reload} />;
  if (data.data.items.length === 0) return <p>{q || kind ? "No transactions match." : "No transactions in this month yet. Use “Add transaction” to record one."}</p>;
  return (
    <div className="table-wrap">
      <table className="data-table">
        <caption className="visually-hidden">Recent transactions</caption>
        <thead><tr><th scope="col">Description</th><th scope="col">Date and category</th><th scope="col">Account</th><th scope="col">Status</th><th scope="col" className="num">Amount</th></tr></thead>
        <tbody>
          {data.data.items.map((t: Transaction) => {
            const income = t.kind === "INCOME" || t.kind === "REFUND";
            const title = t.payee || t.note || (t.kind === "TRANSFER" ? "Transfer" : kindText(t.kind));
            const cat = data.data.categories.find((c) => c.id === t.category_id)?.name;
            return (
              <tr key={t.id}>
                <td data-label="Description"><span className={`tx-dot ${income ? "in" : t.kind === "EXPENSE" ? "out" : ""}`} aria-hidden="true"><Icon name={income ? "trendUp" : t.kind === "EXPENSE" ? "trendDown" : "repeat"} size={14} /></span><strong>{title}</strong></td>
                <td data-label="Date and category">{t.occurred_on}<span className="muted"> · {cat ?? kindText(t.kind)}</span></td>
                <td data-label="Account">{data.data.accounts.find((a) => a.id === t.account_id)?.name ?? "—"}</td>
                <td data-label="Status"><span className={`pill pill-${t.status.toLowerCase()}`}>{kindText(t.status)}</span></td>
                <td data-label="Amount" className={`num tx-amount ${income ? "in" : ""}`}>{income ? "+" : t.kind === "EXPENSE" ? "−" : ""}{formatMoney(t.amount, t.currency)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/** A real insight from the server's deterministic explanation of last month's spending change. It is labelled with what produced it. */
export function InsightCard({ space }: { space: string }) {
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
          <p className="muted">{insight.data.ai.status === "EXECUTED" ? "Explained by an AI model from the figures above." : "Calculated by Finora from your recorded data. No AI model wrote this."}</p>
        </>
      )}
    </section>
  );
}

export function TimeZone({ settings, onSaved }: { settings: { timezone: string }; onSaved: () => void }) {
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
