"use client";

import { useState, type FormEvent } from "react";
import { describeError } from "@/lib/api";
import { CURRENCIES, formatMoney, todayLocal, validateAmountText, type Account, type Commitment } from "@/lib/finance";
import { useFinance, useIntentKey } from "@/lib/FinanceContext";
import { useResource } from "@/lib/useResource";
import { Loading, Notice } from "../Notice";

type Totals = { currency: string; annualized_cost: string; monthly_equivalent: string };

/** Bills (one-off or repeating) and subscriptions share one panel; `kind` picks the endpoint. */
export function CommitmentsPanel({ kind, accounts }: { kind: "BILL" | "SUBSCRIPTION"; accounts: Account[] }) {
  const finance = useFinance();
  const segment = kind === "BILL" ? "bills" : "subscriptions";
  const noun = kind === "BILL" ? "bill" : "subscription";
  const list = useResource((signal) => finance.get<{ items: Commitment[]; totals: Totals[] }>(`/${segment}`, signal));
  const intent = useIntentKey();
  const [name, setName] = useState("");
  const [amount, setAmount] = useState("");
  const [currency, setCurrency] = useState("INR");
  const [frequency, setFrequency] = useState(kind === "BILL" ? "ONCE" : "MONTHLY");
  const [due, setDue] = useState(todayLocal());
  const [error, setError] = useState<string | null>(null);
  const open = accounts.filter((a) => !a.archived);

  async function create(event: FormEvent) {
    event.preventDefault();
    if (!name.trim()) return setError(`Name your ${noun}.`);
    const problem = validateAmountText(amount, currency);
    if (problem) return setError(`Amount: ${problem}`);
    setError(null);
    try {
      await finance.send("POST", `/${segment}`, { name: name.trim(), amount: amount.trim(), currency, frequency, first_due_date: due }, intent.key());
      intent.done();
      setName("");
      setAmount("");
      list.reload();
    } catch (e) {
      setError(describeError(e));
    }
  }

  return (
    <>
      {error && <Notice>{error}</Notice>}
      {list.status === "loading" && <Loading label={`Loading ${segment}…`} />}
      {list.status === "error" && (
        <>
          <Notice>{describeError(list.error)}</Notice>
          <button type="button" onClick={list.reload}>Try again</button>
        </>
      )}
      {list.status === "ready" && list.data.items.length === 0 && <p>No {segment} yet. Add one below to see what is due and what it costs over a year.</p>}
      {list.status === "ready" && list.data.totals.length > 0 && (
        <p>
          Recurring cost:{" "}
          {list.data.totals.map((t) => `${formatMoney(t.annualized_cost, t.currency)} a year (about ${formatMoney(t.monthly_equivalent, t.currency)} a month)`).join("; ")}
        </p>
      )}
      {list.status === "ready" && (
        <ul className="cards" aria-label={kind === "BILL" ? "Bills" : "Subscriptions"}>
          {list.data.items.map((c) => (
            <CommitmentRow key={c.id} item={c} segment={segment} accounts={open} onChanged={list.reload} onError={setError} />
          ))}
        </ul>
      )}
      <form onSubmit={create} aria-labelledby={`${segment}-title`} noValidate>
        <h2 id={`${segment}-title`}>Add {noun}</h2>
        <label htmlFor={`${segment}-name`}>Name</label>
        <input id={`${segment}-name`} value={name} onChange={(e) => setName(e.target.value)} />
        <label htmlFor={`${segment}-amount`}>Amount</label>
        <input id={`${segment}-amount`} value={amount} onChange={(e) => setAmount(e.target.value)} inputMode="decimal" />
        <label htmlFor={`${segment}-currency`}>Currency</label>
        <select id={`${segment}-currency`} value={currency} onChange={(e) => setCurrency(e.target.value)}>
          {CURRENCIES.map((c) => <option key={c} value={c}>{c}</option>)}
        </select>
        <label htmlFor={`${segment}-frequency`}>Repeats</label>
        <select id={`${segment}-frequency`} value={frequency} onChange={(e) => setFrequency(e.target.value)}>
          {(kind === "BILL" ? ["ONCE", "WEEKLY", "MONTHLY", "QUARTERLY", "YEARLY"] : ["WEEKLY", "MONTHLY", "QUARTERLY", "YEARLY"]).map((f) => (
            <option key={f} value={f}>{f.charAt(0) + f.slice(1).toLowerCase()}</option>
          ))}
        </select>
        <label htmlFor={`${segment}-due`}>First due date</label>
        <input id={`${segment}-due`} type="date" value={due} onChange={(e) => setDue(e.target.value)} />
        <button type="submit">Add {noun}</button>
      </form>
    </>
  );
}

function CommitmentRow({ item, segment, accounts, onChanged, onError }: {
  item: Commitment; segment: string; accounts: Account[]; onChanged: () => void; onError: (m: string | null) => void;
}) {
  const finance = useFinance();
  const payIntent = useIntentKey();
  const [paying, setPaying] = useState(false);
  const [accountId, setAccountId] = useState(item.account_id ?? accounts.find((a) => a.currency === item.currency)?.id ?? "");

  async function act(action: "pause" | "resume" | "cancel" | "skip") {
    onError(null);
    try {
      await finance.send("POST", `/${segment}/${item.id}/${action}`, {});
      onChanged();
    } catch (e) {
      onError(describeError(e));
    }
  }

  async function pay(event: FormEvent) {
    event.preventDefault();
    if (!accountId) return onError("Choose the account you paid from.");
    onError(null);
    try {
      await finance.send("POST", `/${segment}/${item.id}/pay`, { account_id: accountId, paid_on: todayLocal() }, payIntent.key());
      payIntent.done();
      setPaying(false);
      onChanged();
    } catch (e) {
      onError(describeError(e));
    }
  }

  const active = item.status === "ACTIVE";
  return (
    <li className="card">
      <div className="column">
        <strong>{item.name}</strong>
        <span>{formatMoney(item.amount, item.currency)} · {item.frequency.toLowerCase()}</span>
        <span className="muted">
          {item.status !== "ACTIVE" ? item.status.toLowerCase() : item.next_due_date ? `Next due ${item.next_due_date}` : "Nothing due"}
          {item.overdue && " — overdue"}
        </span>
        {item.monthly_equivalent && <span className="muted">About {formatMoney(item.monthly_equivalent, item.currency)} a month</span>}
        {paying && (
          <form onSubmit={pay} aria-label={`Pay ${item.name}`}>
            <label htmlFor={`pay-${item.id}`}>Paid from</label>
            <select id={`pay-${item.id}`} value={accountId} onChange={(e) => setAccountId(e.target.value)}>
              <option value="">Choose an account</option>
              {accounts.filter((a) => a.currency === item.currency).map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
            </select>
            <button type="submit">Confirm payment</button>
            <button type="button" className="link" onClick={() => setPaying(false)}>Cancel</button>
          </form>
        )}
      </div>
      <div className="row">
        {active && !paying && <button type="button" onClick={() => setPaying(true)}>Mark paid</button>}
        {active && item.frequency !== "ONCE" && <button type="button" className="link" onClick={() => void act("skip")}>Skip this one</button>}
        {active && <button type="button" className="link" onClick={() => void act("pause")}>Pause</button>}
        {item.status === "PAUSED" && <button type="button" className="link" onClick={() => void act("resume")}>Resume</button>}
        {(active || item.status === "PAUSED") && <button type="button" className="link" onClick={() => void act("cancel")}>Cancel</button>}
      </div>
    </li>
  );
}
