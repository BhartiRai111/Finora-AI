"use client";

import Link from "next/link";
import { monthLabel } from "@/lib/calendar";
import { isNegative, sumAmounts } from "@/lib/decimal";
import { formatMoney, type BudgetItem } from "@/lib/finance";
import { Icon, type IconName } from "../ui/Icon";
import { ProgressBar } from "../ui/BarChart";

const STATUS_TEXT: Record<BudgetItem["status"], string> = { UNDER: "On track", WARNING: "Approaching the limit", OVER: "Over budget" };

/** A small icon for a category, chosen from its name (purely decorative; the name is always written out). */
export function categoryIcon(name: string | null): IconName {
  const n = (name ?? "").toLowerCase();
  if (/rent|hous|home|mortgage/.test(n)) return "home";
  if (/groc|food|dining|restaurant|meal/.test(n)) return "cart";
  if (/transport|travel|fuel|car|metro|taxi|uber/.test(n)) return "car";
  if (/life|entertain|fun|shop|leisure/.test(n)) return "sparkle";
  if (/util|bill|electric|water|internet/.test(n)) return "receipt";
  if (/sav|invest/.test(n)) return "piggy";
  return "tag";
}

/**
 * The month's spending plan for one currency, read from the server's budgets (each already carries its spent, remaining, percentage and state).
 * The only arithmetic is the exact addition of those listed figures for the totals: the overall budget if there is one, otherwise the category budgets.
 */
/** A half-circle gauge of how much of the month's limit is spent. The numbers are the server's; the arc only sizes the drawing (capped at a full circle). */
function Gauge({ pct, spent, total, currency }: { pct: number; spent: string; total: string; currency: string }) {
  const fill = Math.max(0, Math.min(100, pct));
  const tone = pct >= 100 ? "var(--danger)" : pct >= 80 ? "var(--warning)" : "url(#gauge-fill)";
  return (
    <figure className="gauge">
      <svg viewBox="0 0 220 128" role="img" aria-label={`${formatMoney(spent, currency)} spent of a ${formatMoney(total, currency)} limit, ${Math.round(pct)} percent`}>
        <defs><linearGradient id="gauge-fill" x1="0" x2="1"><stop offset="0%" stopColor="var(--series-3)" /><stop offset="100%" stopColor="var(--series-1)" /></linearGradient></defs>
        <path d="M20 110 A90 90 0 0 1 200 110" pathLength="100" fill="none" strokeWidth="22" strokeLinecap="round" className="gauge-track" />
        {fill > 0 && <path d="M20 110 A90 90 0 0 1 200 110" pathLength="100" fill="none" strokeWidth="22" strokeLinecap="round" strokeDasharray={`${fill} 100`} style={{ stroke: tone }} />}
      </svg>
      <figcaption><span>SPENT</span><strong className="num">{formatMoney(spent, currency)}</strong></figcaption>
    </figure>
  );
}

export function SpendingPlan({ budgets, month, currency, manageHref = "/finance/budgets" }: { budgets: BudgetItem[]; month: string; currency: string; manageHref?: string }) {
  const overall = budgets.find((b) => b.category_id === null);
  const categories = budgets.filter((b) => b.category_id !== null);
  const parts = overall ? [overall] : categories;
  const total = parts.length > 0 ? sumAmounts(parts.map((b) => b.amount)) : null;
  const spent = parts.length > 0 ? sumAmounts(parts.map((b) => b.spent)) : null;
  const left = parts.length > 0 ? sumAmounts(parts.map((b) => b.remaining)) : null;
  const pct = total !== null && spent !== null && Number(total) > 0 ? (Number(spent) / Number(total)) * 100 : 0;
  return (
    <section className="panel" aria-labelledby="plan-title">
      <div className="panel-head">
        <div><h3 id="plan-title">Spending limit</h3><p className="muted" style={{ margin: 0 }}>{monthLabel(month)} · {currency}</p></div>
        <Link href={manageHref}>Manage budgets</Link>
      </div>
      {total === null || spent === null || left === null ? (
        <p>No budgets are set for {monthLabel(month)} in {currency}. <Link href={manageHref}>Set a monthly budget</Link> to see your plan here.</p>
      ) : (
        <>
          <Gauge pct={pct} spent={spent} total={total} currency={currency} />
          <div className="plan-summary">
            <div><span className="muted">Spent</span><strong className="num">{formatMoney(spent, currency)}</strong></div>
            <div><span className="muted">{overall ? "Budget" : "Total budget"}</span><strong className="num">{formatMoney(total, currency)}</strong></div>
            <div><span className="muted">Remaining</span><strong className={`num ${isNegative(left) ? "neg" : ""}`}>{formatMoney(left, currency)}</strong></div>
          </div>
          <ProgressBar percent={pct} tone={pct >= 100 ? "over" : pct >= 80 ? "warning" : "under"} label={`${currency} budget used this month`} />
          <p className="muted" style={{ marginTop: "0.4rem" }}>{overall ? "Overall budget" : `Sum of ${parts.length} category budget${parts.length === 1 ? "" : "s"}`}{isNegative(left) ? " · over budget" : ""}</p>
          {categories.length > 0 && (
            <ul className="plan-list" aria-label="Category budgets">
              {categories.map((b) => (
                <li key={b.id}>
                  <span className="plan-icon" aria-hidden="true"><Icon name={categoryIcon(b.category_name)} size={16} /></span>
                  <div className="plan-body">
                    <div className="budget-line"><strong>{b.category_name}</strong><span className="num">{formatMoney(b.spent, b.currency)} of {formatMoney(b.amount, b.currency)}</span></div>
                    <ProgressBar percent={Number(b.percent_used)} tone={b.status === "OVER" ? "over" : b.status === "WARNING" ? "warning" : "under"} label={`${b.category_name} budget used`} />
                    <span className={`status status-${b.status.toLowerCase()}`}>{STATUS_TEXT[b.status]} · {b.percent_used}% used · {formatMoney(b.remaining, b.currency)} left</span>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </section>
  );
}
