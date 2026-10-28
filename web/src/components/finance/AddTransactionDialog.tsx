"use client";

import { useState, type FormEvent } from "react";
import { describeError } from "@/lib/api";
import { announceDataChanged } from "@/lib/dataEvents";
import { formatMoney, todayLocal, validateAmountText, type Account, type Category } from "@/lib/finance";
import { FinanceProvider, useFinance, useIntentKey } from "@/lib/FinanceContext";
import { useResource } from "@/lib/useResource";
import { Dialog } from "../ui/Dialog";
import { Loading, Notice } from "../Notice";

/**
 * "Add transaction" from anywhere in the app: a dialog (expense or income) that saves through the same endpoint as the Transactions screen. It
 * works on one workspace's money (`space`), loads that workspace's accounts and categories when it opens, and tells every open screen to refresh
 * after saving. Transfers stay on the Transactions screen, which asks for both accounts.
 */
export function AddTransactionDialog({ open, space, date, onClose, onSaved }: { open: boolean; space: string; date?: string; onClose: () => void; onSaved?: () => void }) {
  return (
    <Dialog open={open} title="Add a transaction" onClose={onClose}>
      <FinanceProvider space={space}>
        <TransactionForm date={date} onClose={onClose} onSaved={onSaved} />
      </FinanceProvider>
    </Dialog>
  );
}

function TransactionForm({ date, onClose, onSaved }: { date?: string; onClose: () => void; onSaved?: () => void }) {
  const finance = useFinance();
  const intent = useIntentKey();
  const base = useResource(async (signal) => {
    const [a, c] = await Promise.all([finance.get<{ items: Account[] }>("/accounts", signal), finance.get<{ items: Category[] }>("/categories", signal)]);
    return { accounts: a.items.filter((x) => !x.archived), categories: c.items.filter((x) => !x.archived) };
  });
  const [kind, setKind] = useState<"EXPENSE" | "INCOME">("EXPENSE");
  const [amount, setAmount] = useState("");
  const [when, setWhen] = useState(date ?? todayLocal());
  const [accountId, setAccountId] = useState("");
  const [categoryId, setCategoryId] = useState("");
  const [payee, setPayee] = useState("");
  const [note, setNote] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (base.status === "loading") return <Loading label="Loading your accounts…" />;
  if (base.status === "error") {
    return (
      <>
        <Notice>{describeError(base.error)}</Notice>
        <div className="row"><button type="button" onClick={base.reload}>Try again</button><button type="button" onClick={onClose}>Close</button></div>
      </>
    );
  }
  const { accounts, categories } = base.data;
  if (accounts.length === 0) {
    return (
      <>
        <Notice kind="info">Add an account first. An expense is always recorded against one of your accounts.</Notice>
        <button type="button" onClick={onClose}>Close</button>
      </>
    );
  }
  const account = accounts.find((a) => a.id === accountId) ?? accounts[0]!;
  const kindCategories = categories.filter((c) => c.kind === kind);

  async function save(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    const problem = validateAmountText(amount, account.currency);
    if (problem) return setError(problem);
    if (!when) return setError("Choose a date.");
    setBusy(true);
    setError(null);
    try {
      await finance.send("POST", "/transactions", {
        kind, account_id: account.id, amount: amount.trim(), occurred_on: when,
        category_id: categoryId || undefined, payee: payee.trim() || undefined, note: note.trim() || undefined,
      }, intent.key());
      intent.done();
      announceDataChanged();
      onSaved?.();
      onClose();
    } catch (e) {
      setError(describeError(e));
      setBusy(false);
    }
  }

  return (
    <form onSubmit={(e) => void save(e)} noValidate aria-label="New transaction">
      {error && <Notice>{error}</Notice>}
      <div className="seg" role="group" aria-label="Type" style={{ justifySelf: "start" }}>
        <button type="button" aria-pressed={kind === "EXPENSE"} onClick={() => { setKind("EXPENSE"); setCategoryId(""); }} disabled={busy}>Expense</button>
        <button type="button" aria-pressed={kind === "INCOME"} onClick={() => { setKind("INCOME"); setCategoryId(""); }} disabled={busy}>Income</button>
      </div>
      <label htmlFor="ae-amount">Amount ({account.currency})</label>
      <input id="ae-amount" data-autofocus inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="0.00" disabled={busy} />
      <label htmlFor="ae-payee">Description</label>
      <input id="ae-payee" value={payee} onChange={(e) => setPayee(e.target.value)} maxLength={120} placeholder={kind === "EXPENSE" ? "Where or what for" : "Where it came from"} disabled={busy} />
      <div className="filters">
        <div className="column">
          <label htmlFor="ae-date">Date</label>
          <input id="ae-date" type="date" value={when} onChange={(e) => setWhen(e.target.value)} disabled={busy} />
        </div>
        <div className="column">
          <label htmlFor="ae-account">Account</label>
          <select id="ae-account" value={account.id} onChange={(e) => setAccountId(e.target.value)} disabled={busy}>
            {accounts.map((a) => <option key={a.id} value={a.id}>{a.name} ({a.currency})</option>)}
          </select>
        </div>
        <div className="column">
          <label htmlFor="ae-category">Category</label>
          <select id="ae-category" value={categoryId} onChange={(e) => setCategoryId(e.target.value)} disabled={busy}>
            <option value="">Uncategorised</option>
            {kindCategories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </div>
      </div>
      <label htmlFor="ae-note">Note (optional)</label>
      <input id="ae-note" value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} disabled={busy} />
      <p className="muted">Balance of {account.name}: {formatMoney(account.balance, account.currency)}</p>
      <div className="row">
        <button type="submit" disabled={busy}>{busy ? "Saving…" : "Save transaction"}</button>
        <button type="button" onClick={onClose} disabled={busy}>Cancel</button>
      </div>
    </form>
  );
}
