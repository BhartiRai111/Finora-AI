"use client";

import { useState, type FormEvent } from "react";
import { describeError } from "@/lib/api";
import { ACCOUNT_TYPES, CURRENCIES, formatMoney, validateAmountText, todayLocal, type Account } from "@/lib/finance";
import { useFinance, useIntentKey } from "@/lib/FinanceContext";
import { Notice } from "../Notice";

export function AccountsPanel({ accounts, onChanged }: { accounts: Account[]; onChanged: () => void }) {
  const finance = useFinance();
  const intent = useIntentKey();
  const [name, setName] = useState("");
  const [type, setType] = useState("BANK");
  const [currency, setCurrency] = useState("INR");
  const [opening, setOpening] = useState("0");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [adjusting, setAdjusting] = useState<string | null>(null);
  const [newBalance, setNewBalance] = useState("");
  const [adjustNote, setAdjustNote] = useState("");
  const adjustIntent = useIntentKey();

  async function create(event: FormEvent) {
    event.preventDefault();
    if (!name.trim()) return setError("Enter an account name.");
    const problem = validateAmountText(opening, currency, { allowZero: true, allowNegative: false });
    if (problem) return setError(`Opening balance: ${problem}`);
    setBusy(true);
    setError(null);
    try {
      await finance.send("POST", "/accounts", { name: name.trim(), type, currency, opening_balance: opening.trim(), opening_date: todayLocal() }, intent.key());
      intent.done();
      setName("");
      setOpening("0");
      onChanged();
    } catch (e) {
      setError(describeError(e));
    } finally {
      setBusy(false);
    }
  }

  async function archive(a: Account) {
    setError(null);
    try {
      await finance.send("POST", `/accounts/${a.id}/${a.archived ? "restore" : "archive"}`, {});
      onChanged();
    } catch (e) {
      setError(describeError(e));
    }
  }

  async function adjust(a: Account) {
    const problem = validateAmountText(newBalance, a.currency, { allowZero: true });
    if (problem) return setError(problem);
    setError(null);
    try {
      await finance.send("POST", `/accounts/${a.id}/adjustments`, { new_balance: newBalance.trim(), note: adjustNote.trim() || undefined }, adjustIntent.key());
      adjustIntent.done();
      setAdjusting(null);
      setNewBalance("");
      setAdjustNote("");
      onChanged();
    } catch (e) {
      setError(describeError(e));
    }
  }

  return (
    <>
      {error && <Notice>{error}</Notice>}
      {accounts.length === 0 ? (
        <p>No accounts yet. Add your first account below — a bank account, cash, a wallet or a credit card.</p>
      ) : (
        <table>
          <caption className="visually-hidden">Accounts</caption>
          <thead>
            <tr><th scope="col">Account</th><th scope="col">Type</th><th scope="col">Balance</th><th scope="col">Actions</th></tr>
          </thead>
          <tbody>
            {accounts.map((a) => (
              <tr key={a.id}>
                <td>{a.name}{a.archived && " (archived)"}</td>
                <td>{ACCOUNT_TYPES.find((t) => t.value === a.type)?.label}{a.liability && " · you owe"}</td>
                <td>{formatMoney(a.balance, a.currency)}</td>
                <td>
                  <button type="button" className="link" onClick={() => void archive(a)}>{a.archived ? "Restore" : "Archive"}</button>
                  {!a.archived && <button type="button" className="link" onClick={() => setAdjusting(adjusting === a.id ? null : a.id)}>Set balance</button>}
                  {adjusting === a.id && (
                    <div className="card">
                      <label htmlFor={`nb-${a.id}`}>Balance today ({a.currency})</label>
                      <input id={`nb-${a.id}`} value={newBalance} onChange={(e) => setNewBalance(e.target.value)} inputMode="decimal" />
                      <label htmlFor={`nn-${a.id}`}>Note (optional)</label>
                      <input id={`nn-${a.id}`} value={adjustNote} onChange={(e) => setAdjustNote(e.target.value)} />
                      <p>This records an explicit adjustment for the difference. It is not income or an expense.</p>
                      <button type="button" onClick={() => void adjust(a)}>Record adjustment</button>
                    </div>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <form onSubmit={create} aria-labelledby="acc-title" noValidate>
        <h2 id="acc-title">Add an account</h2>
        <label htmlFor="acc-name">Name</label>
        <input id="acc-name" value={name} onChange={(e) => setName(e.target.value)} disabled={busy} />
        <label htmlFor="acc-type">Type</label>
        <select id="acc-type" value={type} onChange={(e) => setType(e.target.value)} disabled={busy}>
          {ACCOUNT_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
        </select>
        <label htmlFor="acc-currency">Currency</label>
        <select id="acc-currency" value={currency} onChange={(e) => setCurrency(e.target.value)} disabled={busy}>
          {CURRENCIES.map((c) => <option key={c} value={c}>{c}</option>)}
        </select>
        <label htmlFor="acc-opening">{type === "CREDIT_CARD" || type === "LOAN" || type === "OTHER_LIABILITY" ? "Amount owed today" : "Balance today"}</label>
        <input id="acc-opening" value={opening} onChange={(e) => setOpening(e.target.value)} inputMode="decimal" disabled={busy} />
        <button type="submit" disabled={busy}>{busy ? "Adding…" : "Add account"}</button>
      </form>
    </>
  );
}
