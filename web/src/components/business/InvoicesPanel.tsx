"use client";

import { useState, type FormEvent } from "react";
import { describeError } from "@/lib/api";
import { businessAccess, type DisplayStatus, type Invoice, type Payment, STATUS_LABEL } from "@/lib/business";
import { CURRENCIES, formatMoney, todayLocal, validateAmountText } from "@/lib/finance";
import { useFinance, useIntentKey } from "@/lib/FinanceContext";
import { usePaged } from "@/lib/usePaged";
import type { Role } from "@/lib/types";
import type { Lookups } from "./lookups";
import { Notice } from "../Notice";
import { ListState, Pager, StatusBadge } from "./Common";
import { PaymentForm } from "./PaymentsPanel";

type LineForm = { description: string; quantity: string; unit_price: string; discount: string; tax_percent: string };
const blankLine = (): LineForm => ({ description: "", quantity: "1", unit_price: "", discount: "0", tax_percent: "0" });

/** Checks what can be checked in the browser; the server is authoritative and calculates every total. */
function lineProblem(l: LineForm, currency: string, n: number): string | null {
  if (!l.description.trim()) return `Line ${n}: add a description.`;
  if (!/^\d{1,9}(\.\d{1,4})?$/.test(l.quantity.trim()) || /^0+(\.0+)?$/.test(l.quantity.trim())) return `Line ${n}: quantity must be greater than zero (up to 4 decimals).`;
  const price = validateAmountText(l.unit_price, currency, { allowZero: true });
  if (price) return `Line ${n}: unit price. ${price}`;
  const discount = validateAmountText(l.discount || "0", currency, { allowZero: true });
  if (discount) return `Line ${n}: discount. ${discount}`;
  if (!/^\d{1,3}(\.\d{1,2})?$/.test((l.tax_percent || "0").trim())) return `Line ${n}: tax % must be a number with at most 2 decimals.`;
  return null;
}

export function InvoicesPanel({ role, lookups, baseCurrency, onChanged }: { role: Role; lookups: Lookups; baseCurrency: string; onChanged: () => void }) {
  const finance = useFinance();
  const write = businessAccess.write(role);
  const [filters, setFilters] = useState({ q: "", status: "", client_id: "", from: "", to: "" });
  const list = usePaged<Invoice>("/invoices", filters);
  const [selected, setSelected] = useState<Invoice | null>(null);
  const [mode, setMode] = useState<"view" | "edit" | "new">("view");
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  async function open(id: string) {
    setError(null);
    setSuccess(null);
    try {
      setSelected(await finance.get<Invoice>(`/invoices/${id}`));
      setMode("view");
    } catch (e) {
      setError(describeError(e));
    }
  }

  function refreshed(inv: Invoice, message?: string) {
    setSelected(inv);
    setMode("view");
    if (message) setSuccess(message);
    list.reload();
    onChanged();
  }

  const set = (key: keyof typeof filters, value: string) => setFilters((f) => ({ ...f, [key]: value }));
  return (
    <>
      {error && <Notice>{error}</Notice>}
      {success && <Notice kind="success">{success}</Notice>}
      <div className="filters" role="search" aria-label="Filter invoices">
        <div className="column"><label htmlFor="inv-q">Search number or client</label><input id="inv-q" value={filters.q} onChange={(e) => set("q", e.target.value)} /></div>
        <div className="column">
          <label htmlFor="inv-status">Status</label>
          <select id="inv-status" value={filters.status} onChange={(e) => set("status", e.target.value)}>
            <option value="">All</option>
            {(["DRAFT", "UNPAID", "PARTIALLY_PAID", "OVERDUE", "PAID", "CANCELLED"] as DisplayStatus[]).map((s) => <option key={s} value={s}>{STATUS_LABEL[s]}</option>)}
          </select>
        </div>
        <div className="column">
          <label htmlFor="inv-client">Client</label>
          <select id="inv-client" value={filters.client_id} onChange={(e) => set("client_id", e.target.value)}>
            <option value="">All clients</option>
            {lookups.clients.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </div>
        <div className="column"><label htmlFor="inv-from">Invoice date from</label><input id="inv-from" type="date" value={filters.from} onChange={(e) => set("from", e.target.value)} /></div>
        <div className="column"><label htmlFor="inv-to">to</label><input id="inv-to" type="date" value={filters.to} onChange={(e) => set("to", e.target.value)} /></div>
        {write && <button type="button" onClick={() => { setSelected(null); setMode("new"); setSuccess(null); }}>New invoice</button>}
      </div>
      <ListState loading={list.loading} error={list.error} empty={list.items.length === 0} onRetry={list.reload}
        emptyText={Object.values(filters).some(Boolean) ? "No invoices match these filters." : `No invoices yet.${write ? " Create your first invoice." : ""}`}>
        <table>
          <caption className="visually-hidden">Invoices</caption>
          <thead><tr><th scope="col">Number</th><th scope="col">Client</th><th scope="col">Due</th><th scope="col">Total</th><th scope="col">Outstanding</th><th scope="col">Status</th></tr></thead>
          <tbody>
            {list.items.map((i) => (
              <tr key={i.id}>
                <td><button type="button" className="link" onClick={() => void open(i.id)}>{i.number ?? "Draft"}<span className="visually-hidden"> for {i.client_name}</span></button></td>
                <td>{i.client_name}</td>
                <td>{i.due_date}{i.overdue && ` (${i.days_overdue} day${i.days_overdue === 1 ? "" : "s"} late)`}</td>
                <td>{formatMoney(i.total, i.currency)}</td>
                <td>{i.status === "ISSUED" ? formatMoney(i.outstanding, i.currency) : "—"}</td>
                <td><StatusBadge status={i.display_status} /></td>
              </tr>
            ))}
          </tbody>
        </table>
        <Pager total={list.total} offset={list.offset} onGo={list.goTo} />
      </ListState>

      {mode === "new" && <InvoiceForm lookups={lookups} baseCurrency={baseCurrency} onSaved={(inv) => refreshed(inv, "Draft saved. Totals were calculated by the server.")} onCancel={() => setMode("view")} />}
      {mode === "edit" && selected && <InvoiceForm lookups={lookups} baseCurrency={baseCurrency} invoice={selected} onSaved={(inv) => refreshed(inv, "Draft updated.")} onCancel={() => setMode("view")} />}
      {mode === "view" && selected && (
        <InvoiceDetail key={`${selected.id}-${selected.version}-${selected.paid}`} invoice={selected} write={write} lookups={lookups} baseCurrency={baseCurrency}
          onEdit={() => setMode("edit")} onChanged={refreshed} onReload={() => void open(selected.id)} onError={setError} />
      )}
    </>
  );
}

function InvoiceForm({ lookups, baseCurrency, invoice, onSaved, onCancel }: {
  lookups: Lookups; baseCurrency: string; invoice?: Invoice; onSaved: (i: Invoice) => void; onCancel: () => void;
}) {
  const finance = useFinance();
  const intent = useIntentKey();
  const [client, setClient] = useState(invoice?.client_id ?? "");
  const [project, setProject] = useState(invoice?.project_id ?? "");
  const [currency, setCurrency] = useState(invoice?.currency ?? baseCurrency);
  const [date, setDate] = useState(invoice?.invoice_date ?? todayLocal());
  const [due, setDue] = useState(invoice?.due_date ?? "");
  const [notes, setNotes] = useState(invoice?.notes ?? "");
  const [lines, setLines] = useState<LineForm[]>(invoice?.lines?.map((l) => ({
    description: l.description, quantity: l.quantity, unit_price: l.unit_price, discount: l.discount, tax_percent: l.tax_percent,
  })) ?? [blankLine()]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const projects = lookups.projects.filter((p) => p.currency === currency && (!client || !p.client_id || p.client_id === client));

  function setLine(i: number, key: keyof LineForm, value: string) {
    setLines((ls) => ls.map((l, n) => (n === i ? { ...l, [key]: value } : l)));
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!invoice && !client) return setError("Choose a client.");
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return setError("Choose the invoice date.");
    if (due && due < date) return setError("The due date cannot be before the invoice date.");
    if (lines.length === 0) return setError("Add at least one line.");
    for (let n = 0; n < lines.length; n++) {
      const p = lineProblem(lines[n]!, currency, n + 1);
      if (p) return setError(p);
    }
    setBusy(true);
    setError(null);
    const body = {
      lines: lines.map((l) => ({ description: l.description.trim(), quantity: l.quantity.trim(), unit_price: l.unit_price.trim(), discount: l.discount.trim() || "0", tax_percent: l.tax_percent.trim() || "0" })),
      invoice_date: date, due_date: due || undefined, notes: notes.trim() || undefined,
    };
    try {
      if (invoice) {
        onSaved(await finance.send<Invoice>("PATCH", `/invoices/${invoice.id}`, { ...body, project_id: project || undefined, clear_project: !project, version: invoice.version }));
      } else {
        const created = await finance.send<Invoice>("POST", "/invoices", { ...body, client_id: client, project_id: project || undefined, currency }, intent.key());
        intent.done();
        onSaved(created);
      }
    } catch (e) {
      setError(describeError(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} aria-labelledby="invform-title" noValidate>
      <h2 id="invform-title">{invoice ? "Edit draft invoice" : "New invoice"}</h2>
      {error && <Notice>{error}</Notice>}
      <label htmlFor="invf-client">Client</label>
      <select id="invf-client" value={client} onChange={(e) => setClient(e.target.value)} disabled={!!invoice || busy}>
        <option value="">Choose a client</option>
        {lookups.clients.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
      </select>
      <label htmlFor="invf-currency">Currency</label>
      <select id="invf-currency" value={currency} onChange={(e) => setCurrency(e.target.value)} disabled={!!invoice || busy}>
        {CURRENCIES.map((c) => <option key={c} value={c}>{c}</option>)}
      </select>
      <label htmlFor="invf-project">Project (optional)</label>
      <select id="invf-project" value={project} onChange={(e) => setProject(e.target.value)} disabled={busy}>
        <option value="">No project</option>
        {projects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
      </select>
      <label htmlFor="invf-date">Invoice date</label>
      <input id="invf-date" type="date" value={date} onChange={(e) => setDate(e.target.value)} disabled={busy} />
      <label htmlFor="invf-due">Due date (leave empty to use the payment terms)</label>
      <input id="invf-due" type="date" value={due} onChange={(e) => setDue(e.target.value)} disabled={busy} />
      <fieldset>
        <legend>Lines</legend>
        {lines.map((l, i) => (
          <div key={i} className="card column" role="group" aria-label={`Line ${i + 1}`}>
            <label htmlFor={`l-desc-${i}`}>Description</label>
            <input id={`l-desc-${i}`} value={l.description} onChange={(e) => setLine(i, "description", e.target.value)} disabled={busy} />
            <label htmlFor={`l-qty-${i}`}>Quantity</label>
            <input id={`l-qty-${i}`} value={l.quantity} onChange={(e) => setLine(i, "quantity", e.target.value)} inputMode="decimal" disabled={busy} />
            <label htmlFor={`l-price-${i}`}>Unit price ({currency})</label>
            <input id={`l-price-${i}`} value={l.unit_price} onChange={(e) => setLine(i, "unit_price", e.target.value)} inputMode="decimal" disabled={busy} />
            <label htmlFor={`l-disc-${i}`}>Discount ({currency}, whole line)</label>
            <input id={`l-disc-${i}`} value={l.discount} onChange={(e) => setLine(i, "discount", e.target.value)} inputMode="decimal" disabled={busy} />
            <label htmlFor={`l-tax-${i}`}>Tax % (as you enter it; no tax rules are applied)</label>
            <input id={`l-tax-${i}`} value={l.tax_percent} onChange={(e) => setLine(i, "tax_percent", e.target.value)} inputMode="decimal" disabled={busy} />
            {lines.length > 1 && <button type="button" className="link" onClick={() => setLines((ls) => ls.filter((_, n) => n !== i))}>Remove line {i + 1}</button>}
          </div>
        ))}
        <button type="button" onClick={() => setLines((ls) => [...ls, blankLine()])} disabled={lines.length >= 100}>Add a line</button>
      </fieldset>
      <label htmlFor="invf-notes">Notes (optional)</label>
      <textarea id="invf-notes" value={notes} onChange={(e) => setNotes(e.target.value)} disabled={busy} />
      <p className="muted">Totals are calculated by the server when you save. The invoice number is assigned when you issue it.</p>
      <button type="submit" disabled={busy}>{busy ? "Saving…" : invoice ? "Save changes" : "Save draft"}</button>
      <button type="button" className="link" onClick={onCancel}>Cancel</button>
    </form>
  );
}

function InvoiceDetail({ invoice: inv, write, lookups, baseCurrency, onEdit, onChanged, onReload, onError }: {
  invoice: Invoice; write: boolean; lookups: Lookups; baseCurrency: string; onEdit: () => void;
  onChanged: (i: Invoice, message?: string) => void; onReload: () => void; onError: (m: string | null) => void;
}) {
  const finance = useFinance();
  const issueIntent = useIntentKey();
  const cancelIntent = useIntentKey();
  const [panel, setPanel] = useState<"none" | "issue" | "cancel" | "pay">("none");
  const [confirm, setConfirm] = useState(false);
  const [reason, setReason] = useState("");

  async function issue() {
    if (!confirm) return onError("Tick the box to confirm you want to issue this invoice.");
    onError(null);
    try {
      const issued = await finance.send<Invoice>("POST", `/invoices/${inv.id}/issue`, { confirm: true, expected_total: inv.total }, issueIntent.key());
      issueIntent.done();
      onChanged(issued, `Invoice ${issued.number} issued.`);
    } catch (e) {
      onError(describeError(e));
    }
  }

  async function cancel() {
    if (!reason.trim()) return onError("Say why you are cancelling this invoice.");
    if (!confirm) return onError("Tick the box to confirm the cancellation.");
    onError(null);
    try {
      const cancelled = await finance.send<Invoice>("POST", `/invoices/${inv.id}/cancel`, { confirm: true, reason: reason.trim() }, cancelIntent.key());
      cancelIntent.done();
      onChanged(cancelled, "Invoice cancelled. It no longer counts in any total.");
    } catch (e) {
      onError(describeError(e));
    }
  }

  const draft = inv.status === "DRAFT";
  const issued = inv.status === "ISSUED";
  return (
    <section className="card column" aria-label="Invoice details">
      <h2>{inv.number ?? "Draft invoice"} <StatusBadge status={inv.display_status} /></h2>
      <p>{inv.client_name}{inv.project_name && ` · ${inv.project_name}`}</p>
      <p className="muted">Invoice date {inv.invoice_date} · Due {inv.due_date}{inv.overdue && ` · ${inv.days_overdue} day${inv.days_overdue === 1 ? "" : "s"} overdue`}</p>
      <table>
        <caption className="visually-hidden">Invoice lines</caption>
        <thead><tr><th scope="col">Description</th><th scope="col">Qty</th><th scope="col">Unit price</th><th scope="col">Discount</th><th scope="col">Tax</th><th scope="col">Line total</th></tr></thead>
        <tbody>
          {inv.lines?.map((l) => (
            <tr key={l.position}>
              <td>{l.description}</td><td>{l.quantity}</td><td>{formatMoney(l.unit_price, inv.currency)}</td><td>{formatMoney(l.discount, inv.currency)}</td>
              <td>{formatMoney(l.tax_amount, inv.currency)} ({l.tax_percent}%)</td><td>{formatMoney(l.line_total, inv.currency)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p>Subtotal {formatMoney(inv.subtotal, inv.currency)} · Discounts {formatMoney(inv.discount_total, inv.currency)} · Tax {formatMoney(inv.tax_total, inv.currency)}</p>
      <p><strong>Total {formatMoney(inv.total, inv.currency)}</strong>{issued && <> · Paid {formatMoney(inv.paid, inv.currency)} · <strong>Outstanding {formatMoney(inv.outstanding, inv.currency)}</strong></>}</p>
      {inv.notes && <p>{inv.notes}</p>}
      {inv.status === "CANCELLED" && <p>Cancelled: {inv.cancel_reason}</p>}
      {inv.payments && inv.payments.length > 0 && (
        <>
          <h3>Payment history</h3>
          <ul aria-label="Payment history">
            {inv.payments.map((p) => (
              <li key={p.payment_id} className={p.payment_status === "VOIDED" ? "voided" : undefined}>
                {p.payment_date}: {formatMoney(p.amount, inv.currency)} by {p.method.toLowerCase().replace("_", " ")}{p.reference && ` (${p.reference})`}{p.payment_status === "VOIDED" && " — voided"}
              </li>
            ))}
          </ul>
        </>
      )}
      {write && (
        <div className="row">
          {draft && <button type="button" onClick={onEdit}>Edit draft</button>}
          {draft && <button type="button" onClick={() => { setPanel(panel === "issue" ? "none" : "issue"); setConfirm(false); }}>Issue invoice</button>}
          {issued && inv.outstanding !== "0.00" && inv.outstanding !== "0" && <button type="button" onClick={() => setPanel(panel === "pay" ? "none" : "pay")}>Record payment</button>}
          {(draft || issued) && <button type="button" className="link" onClick={() => { setPanel(panel === "cancel" ? "none" : "cancel"); setConfirm(false); }}>{draft ? "Discard draft" : "Cancel invoice"}</button>}
        </div>
      )}
      {panel === "issue" && (
        <div className="column" role="group" aria-label="Issue this invoice">
          <p>Issuing assigns the next invoice number and freezes the invoice: it can no longer be edited. The total is {formatMoney(inv.total, inv.currency)}.</p>
          <label><input type="checkbox" checked={confirm} onChange={(e) => setConfirm(e.target.checked)} /> I confirm I want to issue this invoice</label>
          <button type="button" onClick={() => void issue()}>Issue invoice now</button>
        </div>
      )}
      {panel === "cancel" && (
        <div className="column" role="group" aria-label="Cancel this invoice">
          {issued && <p>An invoice with payments applied cannot be cancelled until those payments are voided.</p>}
          <label htmlFor="cancel-reason">Reason</label>
          <input id="cancel-reason" value={reason} onChange={(e) => setReason(e.target.value)} />
          <label><input type="checkbox" checked={confirm} onChange={(e) => setConfirm(e.target.checked)} /> I confirm the cancellation</label>
          <button type="button" onClick={() => void cancel()}>Confirm cancellation</button>
        </div>
      )}
      {panel === "pay" && (
        <PaymentForm lookups={lookups} baseCurrency={baseCurrency} preset={inv} onDone={(p: Payment) => {
          setPanel("none");
          finance.get<Invoice>(`/invoices/${inv.id}`).then((fresh) => onChanged(fresh, `Payment of ${formatMoney(p.amount, p.currency)} recorded.`)).catch(() => onReload());
        }} />
      )}
    </section>
  );
}
