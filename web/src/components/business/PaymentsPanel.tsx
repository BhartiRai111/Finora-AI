"use client";

import { useState, type FormEvent } from "react";
import { describeError } from "@/lib/api";
import { businessAccess, PAYMENT_METHODS, type Invoice, type Paged, type Payment } from "@/lib/business";
import { CURRENCIES, formatMoney, todayLocal, validateAmountText } from "@/lib/finance";
import { useFinance, useIntentKey } from "@/lib/FinanceContext";
import { useResource } from "@/lib/useResource";
import { usePaged } from "@/lib/usePaged";
import type { Role } from "@/lib/types";
import type { Lookups } from "./lookups";
import { Loading, Notice } from "../Notice";
import { ListState, Pager } from "./Common";

/** Records a payment, optionally applying it to one invoice (preset) or automatically to the oldest due invoices. */
export function PaymentForm({ lookups, baseCurrency, preset, onDone }: {
  lookups: Lookups; baseCurrency: string; preset?: Invoice; onDone: (p: Payment) => void;
}) {
  const finance = useFinance();
  const intent = useIntentKey();
  const outstanding = preset?.outstanding ?? "";
  const [client, setClient] = useState(preset?.client_id ?? "");
  const [date, setDate] = useState(todayLocal());
  const [amount, setAmount] = useState(outstanding);
  const [currency, setCurrency] = useState(preset?.currency ?? baseCurrency);
  const [method, setMethod] = useState("BANK_TRANSFER");
  const [reference, setReference] = useState("");
  const [mode, setMode] = useState<"invoice" | "auto" | "none">(preset ? "invoice" : "auto");
  const [apply, setApply] = useState(outstanding);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!client) return setError("Choose the client who paid.");
    const problem = validateAmountText(amount, currency);
    if (problem) return setError(`Amount: ${problem}`);
    const body: Record<string, unknown> = { client_id: client, payment_date: date, amount: amount.trim(), currency, method, reference: reference.trim() || undefined };
    if (mode === "auto") body.auto_allocate = true;
    if (mode === "invoice" && preset) {
      const p2 = validateAmountText(apply, currency);
      if (p2) return setError(`Amount to apply: ${p2}`);
      body.allocations = [{ invoice_id: preset.id, amount: apply.trim() }];
    }
    setBusy(true);
    setError(null);
    try {
      const created = await finance.send<Payment>("POST", "/payments", body, intent.key());
      intent.done();
      setReference("");
      onDone(created);
    } catch (e) {
      setError(describeError(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} aria-labelledby="pay-form-title" noValidate>
      <h2 id="pay-form-title">{preset ? `Record a payment for ${preset.number}` : "Record a payment"}</h2>
      {error && <Notice>{error}</Notice>}
      <label htmlFor="pay-client">Client</label>
      <select id="pay-client" value={client} onChange={(e) => setClient(e.target.value)} disabled={!!preset || busy}>
        <option value="">Choose a client</option>
        {lookups.clients.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
      </select>
      <label htmlFor="pay-date">Payment date</label>
      <input id="pay-date" type="date" value={date} max={todayLocal()} onChange={(e) => setDate(e.target.value)} disabled={busy} />
      <label htmlFor="pay-amount">Amount received</label>
      <input id="pay-amount" value={amount} onChange={(e) => setAmount(e.target.value)} inputMode="decimal" disabled={busy} />
      <label htmlFor="pay-currency">Currency</label>
      <select id="pay-currency" value={currency} onChange={(e) => setCurrency(e.target.value)} disabled={!!preset || busy}>
        {CURRENCIES.map((c) => <option key={c} value={c}>{c}</option>)}
      </select>
      <label htmlFor="pay-method">Method</label>
      <select id="pay-method" value={method} onChange={(e) => setMethod(e.target.value)} disabled={busy}>
        {PAYMENT_METHODS.map((m) => <option key={m.value} value={m.value}>{m.label}</option>)}
      </select>
      <label htmlFor="pay-ref">Reference (bank/UPI/cheque number, optional)</label>
      <input id="pay-ref" value={reference} onChange={(e) => setReference(e.target.value)} disabled={busy} />
      <fieldset>
        <legend>Apply to invoices</legend>
        {preset && (
          <label><input type="radio" name="pay-mode" checked={mode === "invoice"} onChange={() => setMode("invoice")} /> Apply to {preset.number} (outstanding {formatMoney(preset.outstanding, preset.currency)})</label>
        )}
        <label><input type="radio" name="pay-mode" checked={mode === "auto"} onChange={() => setMode("auto")} /> Oldest due invoices first</label>
        <label><input type="radio" name="pay-mode" checked={mode === "none"} onChange={() => setMode("none")} /> Do not apply yet (keep as advance)</label>
      </fieldset>
      {mode === "invoice" && preset && (
        <>
          <label htmlFor="pay-apply">Amount to apply to {preset.number}</label>
          <input id="pay-apply" value={apply} onChange={(e) => setApply(e.target.value)} inputMode="decimal" disabled={busy} />
        </>
      )}
      <p className="muted">Anything not applied stays on the payment as unallocated credit. Nothing is converted between currencies.</p>
      <button type="submit" disabled={busy}>{busy ? "Recording…" : "Record payment"}</button>
    </form>
  );
}

export function PaymentsPanel({ role, lookups, baseCurrency, onChanged }: { role: Role; lookups: Lookups; baseCurrency: string; onChanged: () => void }) {
  const finance = useFinance();
  const write = businessAccess.write(role);
  const [filters, setFilters] = useState({ q: "", client_id: "", from: "", to: "", method: "", voided: false });
  const list = usePaged<Payment>("/payments", {
    q: filters.q, client_id: filters.client_id, from: filters.from, to: filters.to, method: filters.method, include_voided: filters.voided ? "true" : "",
  });
  const [open, setOpen] = useState<Payment | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const set = (key: keyof typeof filters, value: string | boolean) => setFilters((f) => ({ ...f, [key]: value }));
  function recorded(p: Payment) {
    setMessage(`Payment recorded: ${formatMoney(p.amount, p.currency)}, applied ${formatMoney(p.allocated, p.currency)}${p.unallocated !== "0.00" && p.unallocated !== "0" ? `, ${formatMoney(p.unallocated, p.currency)} unallocated` : ""}.`);
    setOpen(p);
    list.reload();
    onChanged();
  }

  return (
    <>
      {error && <Notice>{error}</Notice>}
      {message && <Notice kind="success">{message}</Notice>}
      <div className="filters" role="search" aria-label="Filter payments">
        <div className="column"><label htmlFor="pay-q">Search reference or client</label><input id="pay-q" value={filters.q} onChange={(e) => set("q", e.target.value)} /></div>
        <div className="column">
          <label htmlFor="pay-client-f">Client</label>
          <select id="pay-client-f" value={filters.client_id} onChange={(e) => set("client_id", e.target.value)}>
            <option value="">All clients</option>
            {lookups.clients.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </div>
        <div className="column"><label htmlFor="pay-from">From</label><input id="pay-from" type="date" value={filters.from} onChange={(e) => set("from", e.target.value)} /></div>
        <div className="column"><label htmlFor="pay-to">To</label><input id="pay-to" type="date" value={filters.to} onChange={(e) => set("to", e.target.value)} /></div>
        <div className="column">
          <label htmlFor="pay-method-f">Method</label>
          <select id="pay-method-f" value={filters.method} onChange={(e) => set("method", e.target.value)}>
            <option value="">All</option>
            {PAYMENT_METHODS.map((m) => <option key={m.value} value={m.value}>{m.label}</option>)}
          </select>
        </div>
        <label><input type="checkbox" checked={filters.voided} onChange={(e) => set("voided", e.target.checked)} /> Show voided</label>
      </div>
      <ListState loading={list.loading} error={list.error} empty={list.items.length === 0} onRetry={list.reload}
        emptyText={filters.q || filters.client_id || filters.from || filters.to || filters.method ? "No payments match these filters." : "No payments recorded yet."}>
        <table>
          <caption className="visually-hidden">Payments</caption>
          <thead><tr><th scope="col">Date</th><th scope="col">Client</th><th scope="col">Amount</th><th scope="col">Unallocated</th><th scope="col"><span className="visually-hidden">Actions</span></th></tr></thead>
          <tbody>
            {list.items.map((p) => (
              <tr key={p.id} className={p.status === "VOIDED" ? "voided" : undefined}>
                <td>{p.payment_date}</td>
                <td>{p.client_name}{p.reference && <div className="muted">{p.reference}</div>}</td>
                <td>{formatMoney(p.amount, p.currency)}{p.status === "VOIDED" && " (voided)"}</td>
                <td>{p.status === "VOIDED" ? "—" : formatMoney(p.unallocated, p.currency)}</td>
                <td><button type="button" className="link" onClick={() => void finance.get<Payment>(`/payments/${p.id}`).then(setOpen).catch((e: unknown) => setError(describeError(e)))}>Details</button></td>
              </tr>
            ))}
          </tbody>
        </table>
        <Pager total={list.total} offset={list.offset} onGo={list.goTo} />
      </ListState>
      {open && <PaymentDetail key={`${open.id}-${open.status}-${open.unallocated}`} payment={open} write={write} onChanged={(p) => { setOpen(p); list.reload(); onChanged(); }} onError={setError} />}
      {write && <PaymentForm lookups={lookups} baseCurrency={baseCurrency} onDone={recorded} />}
    </>
  );
}

function PaymentDetail({ payment, write, onChanged, onError }: { payment: Payment; write: boolean; onChanged: (p: Payment) => void; onError: (m: string | null) => void }) {
  const finance = useFinance();
  const allocIntent = useIntentKey();
  const voidIntent = useIntentKey();
  const open = useResource((signal) => payment.status === "RECORDED" && payment.unallocated !== "0.00" && payment.unallocated !== "0"
    ? finance.get<Paged<Invoice>>(`/invoices?client_id=${payment.client_id}&status=ISSUED&limit=100`, signal)
    : Promise.resolve({ items: [], total: 0, limit: 0, offset: 0 } as Paged<Invoice>));
  const [invoice, setInvoice] = useState("");
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");
  const [voiding, setVoiding] = useState(false);

  async function allocate(event: FormEvent) {
    event.preventDefault();
    const problem = validateAmountText(amount, payment.currency);
    if (!invoice) return onError("Choose an invoice.");
    if (problem) return onError(`Amount: ${problem}`);
    onError(null);
    try {
      onChanged(await finance.send<Payment>("POST", `/payments/${payment.id}/allocations`, { allocations: [{ invoice_id: invoice, amount: amount.trim() }] }, allocIntent.key()));
      allocIntent.done();
      setAmount("");
    } catch (e) {
      onError(describeError(e));
    }
  }

  async function voidIt() {
    if (!reason.trim()) return onError("Say why you are voiding this payment.");
    onError(null);
    try {
      onChanged(await finance.send<Payment>("POST", `/payments/${payment.id}/void`, { reason: reason.trim() }, voidIntent.key()));
      voidIntent.done();
      setVoiding(false);
    } catch (e) {
      onError(describeError(e));
    }
  }

  const candidates = open.status === "ready" ? open.data.items.filter((i) => i.display_status !== "PAID" && i.currency === payment.currency) : [];
  return (
    <section className="card column" aria-label="Payment details">
      <h2>{formatMoney(payment.amount, payment.currency)} from {payment.client_name}</h2>
      <p className="muted">{payment.payment_date} · {PAYMENT_METHODS.find((m) => m.value === payment.method)?.label}{payment.reference && ` · ${payment.reference}`}</p>
      <p>Status: <strong>{payment.status === "VOIDED" ? "Voided" : "Recorded"}</strong>{payment.void_reason && ` (${payment.void_reason})`}</p>
      {payment.status === "RECORDED" && <p>Applied {formatMoney(payment.allocated, payment.currency)} · Unallocated {formatMoney(payment.unallocated, payment.currency)}</p>}
      {payment.allocations?.length === 0 && <p>Not applied to any invoice yet.</p>}
      {payment.allocations && payment.allocations.length > 0 && (
        <ul aria-label="Allocations">
          {payment.allocations.map((a) => <li key={a.invoice_id}>{a.invoice_number}: {formatMoney(a.amount, payment.currency)}</li>)}
        </ul>
      )}
      {write && payment.status === "RECORDED" && open.status === "loading" && <Loading label="Loading open invoices…" />}
      {write && payment.status === "RECORDED" && candidates.length > 0 && (
        <form onSubmit={allocate} aria-label="Apply to an invoice" noValidate>
          <label htmlFor="alloc-invoice">Apply some of the unallocated amount to</label>
          <select id="alloc-invoice" value={invoice} onChange={(e) => setInvoice(e.target.value)}>
            <option value="">Choose an invoice</option>
            {candidates.map((i) => <option key={i.id} value={i.id}>{i.number} — outstanding {formatMoney(i.outstanding, i.currency)}</option>)}
          </select>
          <label htmlFor="alloc-amount">Amount</label>
          <input id="alloc-amount" value={amount} onChange={(e) => setAmount(e.target.value)} inputMode="decimal" />
          <button type="submit">Apply</button>
        </form>
      )}
      {write && payment.status === "RECORDED" && (
        voiding ? (
          <div className="column">
            <label htmlFor="void-reason">Reason for voiding</label>
            <input id="void-reason" value={reason} onChange={(e) => setReason(e.target.value)} />
            <p className="muted">Voiding reverses every allocation: the invoices become outstanding again.</p>
            <div className="row"><button type="button" onClick={() => void voidIt()}>Confirm void</button><button type="button" className="link" onClick={() => setVoiding(false)}>Keep payment</button></div>
          </div>
        ) : <button type="button" className="link" onClick={() => setVoiding(true)}>Void this payment</button>
      )}
    </section>
  );
}
