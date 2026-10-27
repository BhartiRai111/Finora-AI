"use client";

import { useEffect, useState } from "react";
import { describeError } from "@/lib/api";
import type { Expense, Invoice, Paged } from "@/lib/business";
import { useFinance } from "@/lib/FinanceContext";
import { saveBlob } from "@/lib/insights";
import type { ExpenseGst, GstReference, GstRegistration, GstSummary, InvoiceGst } from "@/lib/regional";
import { useResource } from "@/lib/useResource";
import { Loading, Notice } from "../Notice";

const GSTIN = /^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$/;

function Registration({ canEdit }: { canEdit: boolean }) {
  const finance = useFinance();
  const reg = useResource((signal) => finance.get<GstRegistration>("/finance-gst/registration", signal));
  const [registered, setRegistered] = useState<boolean | null>(null);
  const [gstin, setGstin] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  if (reg.status === "loading") return <Loading label="Loading GST registration…" />;
  if (reg.status === "error") return <><Notice>{describeError(reg.error)}</Notice><button type="button" onClick={reg.reload}>Try again</button></>;
  const r = reg.data;
  const isRegistered = registered ?? r.gst_registered;
  const value = gstin ?? (r.gstin_visible ? (r.gstin ?? "") : "");

  async function save() {
    setSaved(false);
    const g = value.trim().toUpperCase();
    if (isRegistered && !GSTIN.test(g)) return setError("Enter the GSTIN: 15 characters such as 27AAPFU0939F1ZV. The check character is verified by the server.");
    setError(null);
    try {
      await finance.send("PUT", "/finance-gst/registration", { gst_registered: isRegistered, gstin: g });
      setSaved(true);
      setRegistered(null);
      setGstin(null);
      reg.reload();
    } catch (e) {
      setError(describeError(e));
    }
  }

  return (
    <form aria-label="GST registration" onSubmit={(e) => { e.preventDefault(); void save(); }}>
      <h2>GST registration</h2>
      <p className="muted">Nothing is assumed: GST details can only be recorded once this workspace is marked as GST-registered with its GSTIN. A well-formed GSTIN is not proof of registration; nothing here contacts the GST portal.</p>
      {error && <Notice>{error}</Notice>}
      {saved && <Notice kind="success">GST registration saved.</Notice>}
      <div className="filters">
        <label><input type="checkbox" checked={isRegistered} disabled={!canEdit} onChange={(e) => setRegistered(e.target.checked)} /> This workspace is GST-registered</label>
        <div className="column">
          <label htmlFor="gst-gstin">GSTIN</label>
          <input id="gst-gstin" value={value} disabled={!canEdit} maxLength={15} onChange={(e) => setGstin(e.target.value)} placeholder={r.gstin_visible ? "27AAPFU0939F1ZV" : (r.gstin ?? "")} />
        </div>
        {r.state_name && <p>State: {r.state_name}</p>}
        {canEdit ? <button type="submit">Save registration</button> : <Notice kind="info">You can view the registration but not change it.</Notice>}
      </div>
    </form>
  );
}

function Money({ label, value }: { label: string; value: string }) {
  return <tr><th scope="row">{label}</th><td>{value}</td></tr>;
}

function SummaryView({ s }: { s: GstSummary }) {
  const rec = s.reconciliation;
  return (
    <section aria-label="GST summary">
      <Notice kind="info">{s.notice}</Notice>
      <p className="muted">Period {s.period.from} to {s.period.to}. All amounts are in INR. {s.registration.gst_registered ? `GSTIN ${s.registration.gstin_masked}.` : "This workspace is not marked as GST-registered."}</p>
      <div className="card column">
        <table>
          <caption>Tax on sales ({s.sales.invoices_included} invoice(s) with saved GST details)</caption>
          <tbody>
            <Money label="Taxable amount" value={s.sales.taxable_amount} /><Money label="CGST" value={s.sales.cgst} /><Money label="SGST" value={s.sales.sgst} />
            <Money label="IGST" value={s.sales.igst} /><Money label="Total tax" value={s.sales.total_tax} />
          </tbody>
        </table>
      </div>
      <div className="card column">
        <table>
          <caption>Tax on purchases ({s.purchases.expenses_included} expense(s) with saved GST details)</caption>
          <tbody>
            <Money label="Taxable amount" value={s.purchases.taxable_amount} /><Money label="CGST" value={s.purchases.cgst} /><Money label="SGST" value={s.purchases.sgst} />
            <Money label="IGST" value={s.purchases.igst} /><Money label="Total tax" value={s.purchases.total_tax} />
          </tbody>
        </table>
        <p className="muted">{s.purchases.note}</p>
      </div>
      <p role="status">
        Reconciliation: sales tax {rec.sales_reconciled ? "reconciles" : "DOES NOT reconcile"} to the invoices (included {String(rec.included_invoice_tax_total)}, excluded {String(rec.excluded_invoice_tax_total)}, issued INR total {String(rec.issued_inr_invoice_tax_total)});
        purchases {rec.purchases_reconciled ? "reconcile" : "DO NOT reconcile"} to the bills entered.
      </p>
      {s.flags.length > 0 && (
        <Notice kind="info">
          <strong>Incomplete or unsupported records are left out:</strong>
          <ul>{s.flags.map((f) => <li key={f}>{f}</li>)}</ul>
        </Notice>
      )}
      <details><summary>Limits of this worksheet</summary><ul>{s.limitations.map((l) => <li key={l}>{l}</li>)}</ul></details>
    </section>
  );
}

function SummarySection({ canExport }: { canExport: boolean }) {
  const finance = useFinance();
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [state, setState] = useState<{ s: "idle" } | { s: "loading" } | { s: "error"; m: string } | { s: "ready"; data: GstSummary }>({ s: "idle" });
  const [busy, setBusy] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);
  const [localError, setLocalError] = useState<string | null>(null);

  const qs = from && to ? `?from=${from}&to=${to}` : "";
  async function load() {
    if ((from && !to) || (!from && to)) return setLocalError("Give both dates, or neither for the last complete month.");
    if (from && to && from > to) return setLocalError("The start date cannot be after the end date.");
    setLocalError(null);
    setState({ s: "loading" });
    try {
      setState({ s: "ready", data: await finance.get<GstSummary>(`/finance-gst/summary${qs}`) });
    } catch (e) {
      setState({ s: "error", m: describeError(e) });
    }
  }
  async function download() {
    setBusy(true);
    setExportError(null);
    try {
      const file = await finance.download(`/finance-gst/export${qs}`);
      saveBlob(file.blob, file.filename);
    } catch (e) {
      setExportError(describeError(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <section aria-labelledby="gst-sum-title">
      <h2 id="gst-sum-title">GST summary and preparation worksheet</h2>
      {localError && <Notice>{localError}</Notice>}
      {exportError && <Notice>{exportError}</Notice>}
      <div className="filters" role="group" aria-label="GST period">
        <div className="column"><label htmlFor="gst-from">From</label><input id="gst-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></div>
        <div className="column"><label htmlFor="gst-to">To</label><input id="gst-to" type="date" value={to} onChange={(e) => setTo(e.target.value)} /></div>
        <button type="button" onClick={() => void load()}>Show GST summary</button>
        {canExport && <button type="button" onClick={() => void download()} disabled={busy}>{busy ? "Preparing…" : "Download preparation worksheet (CSV)"}</button>}
      </div>
      <p className="muted">The worksheet is in Finora&apos;s own format. It is not a government filing format and nothing is ever filed or submitted from here.</p>
      {state.s === "loading" && <Loading label="Loading GST summary…" />}
      {state.s === "error" && <><Notice>{state.m}</Notice><button type="button" onClick={() => void load()}>Try again</button></>}
      {state.s === "ready" && <SummaryView s={state.data} />}
    </section>
  );
}

function StateSelect({ id, label, states, value, onChange }: { id: string; label: string; states: Record<string, string>; value: string; onChange: (v: string) => void }) {
  return (
    <div className="column">
      <label htmlFor={id}>{label}</label>
      <select id={id} value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">Choose a state</option>
        {Object.entries(states).filter(([c]) => c !== "97" && c !== "99").map(([c, n]) => <option key={c} value={c}>{c} {n}</option>)}
      </select>
    </div>
  );
}

function InvoiceSection({ canEdit, states }: { canEdit: boolean; states: Record<string, string> }) {
  const finance = useFinance();
  const invoices = useResource((signal) => finance.get<Paged<Invoice>>("/invoices?limit=100", signal));
  const [id, setId] = useState("");
  const [place, setPlace] = useState("");
  const [result, setResult] = useState<InvoiceGst | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    if (!id) return;
    const controller = new AbortController();
    finance.get<InvoiceGst>(`/finance-gst/invoices/${id}`, controller.signal).then((r) => {
      if (!controller.signal.aborted) setResult(r);
    }).catch((e: unknown) => {
      if (!controller.signal.aborted) setError(describeError(e));
    });
    return () => controller.abort();
  }, [id, finance]);

  async function run(preview: boolean) {
    setSaved(false);
    if (!id) return setError("Choose an invoice.");
    if (!place) return setError("Choose the place of supply. It is never assumed.");
    setError(null);
    try {
      const r = await finance.send<InvoiceGst>("PUT", `/finance-gst/invoices/${id}${preview ? "?preview=true" : ""}`, { place_of_supply: place });
      setResult(r);
      setSaved(!preview);
    } catch (e) {
      setError(describeError(e));
    }
  }

  return (
    <section aria-labelledby="gst-inv-title">
      <h2 id="gst-inv-title">GST details of an invoice</h2>
      <p className="muted">Tax uses the percentage typed on each invoice line, split into CGST + SGST (same state) or IGST (different states). INR invoices only. Once an invoice is issued its GST details are frozen.</p>
      {error && <Notice>{error}</Notice>}
      {saved && <Notice kind="success">GST details saved for this invoice.</Notice>}
      {invoices.status === "loading" && <Loading label="Loading invoices…" />}
      {invoices.status === "error" && <><Notice>{describeError(invoices.error)}</Notice><button type="button" onClick={invoices.reload}>Try again</button></>}
      {invoices.status === "ready" && (
        <div className="filters">
          <div className="column">
            <label htmlFor="gst-inv">Invoice</label>
            <select id="gst-inv" value={id} onChange={(e) => { setId(e.target.value); setResult(null); setError(null); }}>
              <option value="">Choose an invoice</option>
              {invoices.data.items.filter((i) => i.status !== "CANCELLED").map((i) => <option key={i.id} value={i.id}>{i.number ?? "Draft"} · {i.client_name} · {i.currency} {i.total} ({i.status.toLowerCase()})</option>)}
            </select>
          </div>
          <StateSelect id="gst-place" label="Place of supply" states={states} value={place} onChange={setPlace} />
          <button type="button" onClick={() => void run(true)}>Preview split</button>
          {canEdit && <button type="button" onClick={() => void run(false)}>Save GST details</button>}
        </div>
      )}
      {result && (
        <div className="card column" role="group" aria-label="Invoice GST details">
          <h3>{result.status === "NOT_RECORDED" ? "No GST details recorded" : result.status === "PREVIEW" ? "Preview (not saved)" : "Recorded GST details"}</h3>
          {result.supply_type && <p>{result.supply_type === "INTRA_STATE" ? "Intra-state supply: CGST + SGST" : "Inter-state supply: IGST"} · place of supply {result.place_of_supply_name}{result.recipient_gstin ? ` · recipient GSTIN ${result.recipient_gstin}` : ""}</p>}
          {result.snapshot_stale && <Notice>These details no longer match the invoice and are left out of the GST summary until recalculated.</Notice>}
          {result.breakdown.length > 0 && (
            <table>
              <caption>Tax by rate (invoice total {result.invoice_total}, tax {result.invoice_tax_total})</caption>
              <thead><tr><th scope="col">Rate %</th><th scope="col">Taxable</th><th scope="col">CGST</th><th scope="col">SGST</th><th scope="col">IGST</th><th scope="col">Total tax</th></tr></thead>
              <tbody>{result.breakdown.map((b) => <tr key={b.tax_percent}><th scope="row">{b.tax_percent}</th><td>{b.taxable_amount}</td><td>{b.cgst}</td><td>{b.sgst}</td><td>{b.igst}</td><td>{b.total_tax}</td></tr>)}</tbody>
            </table>
          )}
          {result.warnings.map((w) => <p key={w} className="muted">{w}</p>)}
        </div>
      )}
    </section>
  );
}

function ExpenseSection({ canEdit, states }: { canEdit: boolean; states: Record<string, string> }) {
  const finance = useFinance();
  const expenses = useResource((signal) => finance.get<Paged<Expense>>("/expenses?limit=100", signal));
  const [id, setId] = useState("");
  const [form, setForm] = useState({ place: "", taxable: "", cgst: "", sgst: "", igst: "" });
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<ExpenseGst | null>(null);

  async function save() {
    if (!id) return setError("Choose an expense.");
    if (!form.place) return setError("Choose the place of supply. It is never assumed.");
    for (const [k, v] of Object.entries({ taxable: form.taxable, cgst: form.cgst, sgst: form.sgst, igst: form.igst })) {
      if (v.trim() !== "" && !/^\d+(\.\d{1,2})?$/.test(v.trim())) return setError(`${k.toUpperCase()} must be an amount with at most 2 decimals.`);
    }
    if (form.taxable.trim() === "") return setError("Enter the taxable amount shown on the bill.");
    setError(null);
    try {
      setResult(await finance.send<ExpenseGst>("PUT", `/finance-gst/expenses/${id}`, { place_of_supply: form.place, taxable_amount: form.taxable.trim(), cgst: form.cgst.trim() || undefined, sgst: form.sgst.trim() || undefined, igst: form.igst.trim() || undefined }));
    } catch (e) {
      setError(describeError(e));
    }
  }

  return (
    <section aria-labelledby="gst-exp-title">
      <h2 id="gst-exp-title">GST on a purchase bill</h2>
      <p className="muted">Enter the amounts printed on the vendor bill. Taxable amount plus tax must equal the expense amount. Whether the tax can be claimed as credit is not determined here.</p>
      {error && <Notice>{error}</Notice>}
      {expenses.status === "loading" && <Loading label="Loading expenses…" />}
      {expenses.status === "error" && <><Notice>{describeError(expenses.error)}</Notice><button type="button" onClick={expenses.reload}>Try again</button></>}
      {expenses.status === "ready" && (
        <div className="filters">
          <div className="column">
            <label htmlFor="gst-exp">Expense</label>
            <select id="gst-exp" value={id} onChange={(e) => { setId(e.target.value); setResult(null); }}>
              <option value="">Choose an expense</option>
              {expenses.data.items.filter((x) => x.status === "POSTED" && x.currency === "INR").map((x) => <option key={x.id} value={x.id}>{x.expense_date} · {x.vendor_name ?? "No vendor"} · INR {x.amount}</option>)}
            </select>
          </div>
          <StateSelect id="gst-exp-place" label="Place of supply" states={states} value={form.place} onChange={(v) => setForm({ ...form, place: v })} />
          {(["taxable", "cgst", "sgst", "igst"] as const).map((k) => (
            <div className="column" key={k}><label htmlFor={`gst-e-${k}`}>{k === "taxable" ? "Taxable amount" : k.toUpperCase()}</label><input id={`gst-e-${k}`} inputMode="decimal" value={form[k]} onChange={(e) => setForm({ ...form, [k]: e.target.value })} /></div>
          ))}
          {canEdit ? <button type="button" onClick={() => void save()}>Save GST details</button> : <Notice kind="info">You can view but not change GST details.</Notice>}
        </div>
      )}
      {result && <Notice kind="success">Saved: {result.supply_type ?? "supply type not determined"}, total tax {result.total_tax}.{result.warnings.map((w) => ` ${w}`)}</Notice>}
    </section>
  );
}

/** India GST support for a business workspace: registration, per-invoice and per-bill GST details, and a preparation worksheet. Nothing is filed from here. */
export function GstPanel({ canEditRegistration, canEditDetails, canExport }: { canEditRegistration: boolean; canEditDetails: boolean; canExport: boolean }) {
  const finance = useFinance();
  const reference = useResource((signal) => finance.get<GstReference>("/finance-gst/reference", signal));
  return (
    <>
      <Notice kind="info">GST support here is limited to the cases listed below and has not been validated by a qualified professional. It is not GST-compliance software and files nothing.</Notice>
      <Registration canEdit={canEditRegistration} />
      <SummarySection canExport={canExport} />
      {reference.status === "ready" && (
        <>
          <InvoiceSection canEdit={canEditDetails} states={reference.data.states} />
          <ExpenseSection canEdit={canEditDetails} states={reference.data.states} />
          <details>
            <summary>Supported and unsupported GST cases</summary>
            <h3>Supported</h3><ul>{reference.data.supported.map((s) => <li key={s}>{s}</li>)}</ul>
            <h3>Not supported (refused, never guessed)</h3><ul>{reference.data.unsupported.map((s) => <li key={s}>{s}</li>)}</ul>
          </details>
        </>
      )}
      {reference.status === "loading" && <Loading label="Loading GST reference…" />}
      {reference.status === "error" && <><Notice>{describeError(reference.error)}</Notice><button type="button" onClick={reference.reload}>Try again</button></>}
    </>
  );
}
