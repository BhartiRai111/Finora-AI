"use client";

import Link from "next/link";
import { monthLabel } from "@/lib/calendar";
import { formatMoney, type Account, type BudgetItem } from "@/lib/finance";
import { useFinance } from "@/lib/FinanceContext";
import { useSelectedMonth } from "@/lib/month";
import { useDataRefresh } from "@/lib/useDataRefresh";
import { useResource } from "@/lib/useResource";

/**
 * Sidebar extras for personal finance, both read from the server: the accounts the person added (balances calculated from their transactions;
 * there is no bank connection, and the text says so) and a count of how the month's budgets are doing.
 */
export function SidebarSummary() {
  const finance = useFinance();
  const [month] = useSelectedMonth();
  const accounts = useResource((signal) => finance.get<{ items: Account[] }>("/accounts", signal).then((r) => r.items.filter((a) => !a.archived)));
  const budgets = useResource((signal) => finance.get<{ items: BudgetItem[] }>(`/budgets?month=${month}`, signal).then((r) => r.items), );
  useDataRefresh(accounts.reload);
  useDataRefresh(budgets.reload);
  const mine = budgets.status === "ready" ? budgets.data : [];
  const count = (s: BudgetItem["status"]) => mine.filter((b) => b.status === s).length;
  return (
    <>
      <section aria-labelledby="side-accounts" className="side-card">
        <h2 id="side-accounts" className="side-title">Your accounts</h2>
        {accounts.status === "loading" && <p className="muted">Loading…</p>}
        {accounts.status === "error" && <p className="muted" role="alert">Accounts could not be loaded.</p>}
        {accounts.status === "ready" && accounts.data.length === 0 && <p className="muted">No accounts yet. <Link href="/finance/accounts">Add one</Link>.</p>}
        {accounts.status === "ready" && accounts.data.length > 0 && (
          <ul className="side-list">
            {accounts.data.slice(0, 4).map((a) => (
              <li key={a.id}><span className="side-name">{a.name}</span><span className="num">{formatMoney(a.balance, a.currency)}</span></li>
            ))}
          </ul>
        )}
        <p className="muted side-note">Added by you; not linked to a bank.</p>
      </section>
      <section aria-labelledby="side-budget" className="side-card">
        <h2 id="side-budget" className="side-title">Budget status</h2>
        {budgets.status === "ready" && mine.length === 0 && <p className="muted">No budgets for {monthLabel(month)}.</p>}
        {budgets.status === "error" && <p className="muted" role="alert">Budgets could not be loaded.</p>}
        {mine.length > 0 && (
          <ul className="side-list">
            <li><span>On track</span><span className="num">{count("UNDER")}</span></li>
            <li><span>Approaching the limit</span><span className="num">{count("WARNING")}</span></li>
            <li><span>Over budget</span><span className="num">{count("OVER")}</span></li>
          </ul>
        )}
        <Link href="/finance/budgets" className="side-note">Open budget planner</Link>
      </section>
    </>
  );
}
