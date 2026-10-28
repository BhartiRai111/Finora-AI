import Link from "next/link";
import type { Metadata } from "next";

export const metadata: Metadata = { title: "Help Center · Finora AI" };

const topics = [
  { title: "Record your money", body: "Use Add transaction in the header to record an expense or income. Transfers, refunds and corrections are on the Transactions page.", href: "/finance/transactions", link: "Open Transactions" },
  { title: "Plan with budgets", body: "Set a monthly limit per category (or one overall). The Spending plan shows what you have spent, what is left and which limits are close or over.", href: "/finance/budgets", link: "Open the budget planner" },
  { title: "Use the calendar", body: "The calendar shows what you spent on each day, bills and recurring payments that are due, and lets you add a transaction to any date.", href: "/finance/calendar", link: "Open the calendar" },
  { title: "Track savings goals", body: "Create a goal with a target and add contributions. Progress and the amount remaining are calculated from what you record.", href: "/finance/goals", link: "Open Savings Goals" },
  { title: "Import and export", body: "Bring in a CSV file from Imports. Export downloads a CSV of the month's cash flow from the header or from Reports.", href: "/finance/imports", link: "Open Imports" },
  { title: "Account and security", body: "Change your password, manage two-step verification and sign out of other devices.", href: "/account", link: "Open account and security" },
];

export default function HelpPage() {
  return (
    <main id="main" className="help">
      <p className="eyebrow">Help Center</p>
      <h1>How Finora AI works</h1>
      <p>Finora AI shows only what you record. Balances are calculated from your own transactions.</p>
      <div className="grid-2">
        {topics.map((t) => (
          <section key={t.title} className="panel">
            <h2 style={{ fontSize: "1.05rem" }}>{t.title}</h2>
            <p>{t.body}</p>
            <Link href={t.href}>{t.link}</Link>
          </section>
        ))}
      </div>
      <section className="panel" aria-labelledby="help-limits">
        <h2 id="help-limits" style={{ fontSize: "1.05rem" }}>What Finora does not do yet</h2>
        <ul>
          <li>It does not connect to banks. Accounts are ones you add yourself.</li>
          <li>It does not send email, SMS or push messages. Notifications appear in the app only.</li>
          <li>It does not take payments. No card or bank details are ever requested.</li>
          <li>It has no live chat or support inbox in the app.</li>
        </ul>
      </section>
    </main>
  );
}
