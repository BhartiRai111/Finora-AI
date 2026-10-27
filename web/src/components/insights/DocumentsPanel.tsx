"use client";

import { useState, type FormEvent } from "react";
import { describeError } from "@/lib/api";
import { CURRENCIES, formatMoney, todayLocal, validateAmountText, type Account } from "@/lib/finance";
import { useFinance, useIntentKey } from "@/lib/FinanceContext";
import { humanize, saveBlob, type FinanceDocument, type Listing } from "@/lib/insights";
import { useResource } from "@/lib/useResource";
import { Loading, Notice } from "../Notice";

const MAX_BYTES = 10 * 1024 * 1024;
const STATUS_TEXT: Record<FinanceDocument["status"], string> = {
  UPLOADED: "Uploaded",
  PROCESSING: "Reading…",
  EXTRACTED: "Text read, needs your review",
  FAILED: "Could not be read",
  UNAVAILABLE: "Text reading unavailable",
  CONFIRMED: "Recorded",
};

/** Receipts and invoices: upload, optional OCR, and a review step. A record exists only after the user confirms the values. */
export function DocumentsPanel({ domain, canUpload, canManage, accounts, baseCurrency }: {
  domain: "PERSONAL" | "BUSINESS"; canUpload: boolean; canManage: boolean; accounts: Account[]; baseCurrency: string;
}) {
  const finance = useFinance();
  const list = useResource((signal) => finance.get<Listing<FinanceDocument>>("/finance-documents?limit=50", signal));
  const [kind, setKind] = useState("RECEIPT");
  const [file, setFile] = useState<File | null>(null);
  const [selected, setSelected] = useState<FinanceDocument | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function upload(event: FormEvent) {
    event.preventDefault();
    setMessage(null);
    if (!file) return setError("Choose a file first.");
    if (file.size === 0) return setError("The file is empty.");
    if (file.size > MAX_BYTES) return setError("The file is larger than 10 MB.");
    setBusy(true);
    setError(null);
    try {
      const doc = await finance.upload<FinanceDocument>(`/finance-documents?kind=${kind}&filename=${encodeURIComponent(file.name)}`, file, "application/octet-stream");
      setFile(null);
      setSelected(doc);
      setMessage(doc.already_uploaded ? "This exact file was already uploaded; showing the existing document." : "Document uploaded.");
      list.reload();
    } catch (e) {
      setError(describeError(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      {error && <Notice>{error}</Notice>}
      {message && <Notice kind="success">{message}</Notice>}
      {canUpload && (
        <form onSubmit={upload} aria-labelledby="doc-upload-title" noValidate>
          <h2 id="doc-upload-title">Upload a document</h2>
          <label htmlFor="doc-kind">Type</label>
          <select id="doc-kind" value={kind} onChange={(e) => setKind(e.target.value)}>
            <option value="RECEIPT">Receipt</option>
            <option value="INVOICE">Invoice</option>
            <option value="OTHER">Other</option>
          </select>
          <label htmlFor="doc-file">PDF, PNG or JPEG, up to 10 MB</label>
          <input id="doc-file" type="file" accept=".pdf,.png,.jpg,.jpeg,application/pdf,image/png,image/jpeg" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
          <p className="muted">The file content is checked on the server; files that are not really a PDF, PNG or JPEG, or that contain scripts, are refused.</p>
          <button type="submit" disabled={busy}>{busy ? "Uploading…" : "Upload"}</button>
        </form>
      )}
      <h2>Documents</h2>
      {list.status === "loading" && <Loading label="Loading documents…" />}
      {list.status === "error" && (
        <>
          <Notice>{describeError(list.error)}</Notice>
          <button type="button" onClick={list.reload}>Try again</button>
        </>
      )}
      {list.status === "ready" && list.data.items.length === 0 && <p>No documents yet.{canUpload ? " Upload a receipt or invoice above." : ""}</p>}
      {list.status === "ready" && list.data.items.length > 0 && (
        <table>
          <caption className="visually-hidden">Documents</caption>
          <thead><tr><th scope="col">File</th><th scope="col">Type</th><th scope="col">Status</th><th scope="col">Size</th><th scope="col"><span className="visually-hidden">Open</span></th></tr></thead>
          <tbody>
            {list.data.items.map((d) => (
              <tr key={d.id}>
                <td>{d.filename}</td>
                <td>{humanize(d.kind.toLowerCase())}</td>
                <td>{STATUS_TEXT[d.status]}</td>
                <td>{Math.max(1, Math.round(d.size_bytes / 1024))} KB</td>
                <td><button type="button" className="link" onClick={() => { setSelected(d); setMessage(null); }}>Open {d.filename}</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {selected && (
        <DocumentView key={`${selected.id}-${selected.status}`} doc={selected} domain={domain} canManage={canManage} accounts={accounts} baseCurrency={baseCurrency}
          onChanged={(d) => { setSelected(d); list.reload(); }} onDeleted={() => { setSelected(null); setMessage("Document deleted."); list.reload(); }} />
      )}
    </>
  );
}

function DocumentView({ doc, domain, canManage, accounts, baseCurrency, onChanged, onDeleted }: {
  doc: FinanceDocument; domain: "PERSONAL" | "BUSINESS"; canManage: boolean; accounts: Account[]; baseCurrency: string;
  onChanged: (d: FinanceDocument) => void; onDeleted: () => void;
}) {
  const finance = useFinance();
  const intent = useIntentKey();
  const fields = doc.extraction?.fields ?? {};
  const [form, setForm] = useState({
    date: fields.document_date ?? todayLocal(), amount: fields.total_amount ?? "", currency: fields.currency ?? baseCurrency,
    description: fields.description ?? fields.vendor_name ?? "", reference: fields.reference ?? "", account: "", kind: "EXPENSE",
  });
  const [checked, setChecked] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function act<T>(fn: () => Promise<T>): Promise<T | undefined> {
    setBusy(true);
    setError(null);
    try {
      return await fn();
    } catch (e) {
      setError(describeError(e));
      return undefined;
    } finally {
      setBusy(false);
    }
  }

  async function download() {
    const file = await act(() => finance.download(`/finance-documents/${doc.id}/content`));
    if (file) saveBlob(file.blob, file.filename);
  }

  async function extract() {
    const d = await act(() => finance.send<FinanceDocument>("POST", `/finance-documents/${doc.id}/extract`, {}));
    if (d) onChanged(d);
  }

  async function remove() {
    const d = await act(() => finance.send<FinanceDocument>("DELETE", `/finance-documents/${doc.id}`));
    if (d) onDeleted();
  }

  async function confirm(event: FormEvent) {
    event.preventDefault();
    if (!form.description.trim()) return setError("Describe what this document is for.");
    const problem = validateAmountText(form.amount, form.currency);
    if (problem) return setError(`Amount: ${problem}`);
    if (domain === "PERSONAL" && !form.account) return setError("Choose the account.");
    if (!checked) return setError("Tick the box to confirm you checked the values against the document.");
    const body: Record<string, unknown> = { confirm: true, date: form.date, amount: form.amount.trim(), currency: form.currency, description: form.description.trim(), reference: form.reference.trim() || undefined };
    if (domain === "PERSONAL") {
      body.account_id = form.account;
      body.kind = form.kind;
    }
    const d = await act(() => finance.send<FinanceDocument>("POST", `/finance-documents/${doc.id}/confirm`, body, intent.key()));
    if (d) {
      intent.done();
      onChanged(d);
    }
  }

  const open = accounts.filter((a) => !a.archived);
  const reviewable = doc.status !== "CONFIRMED" && doc.status !== "PROCESSING";
  return (
    <section className="card column" aria-label="Document details">
      <h2>{doc.filename}</h2>
      <p>Status: <strong>{STATUS_TEXT[doc.status]}</strong> · {doc.content_type}</p>
      {error && <Notice>{error}</Notice>}
      {doc.status === "UNAVAILABLE" && <Notice kind="info">{doc.failure_reason ?? "Text reading is not available."}</Notice>}
      {doc.status === "FAILED" && <Notice>{doc.failure_reason ?? "The document could not be read."} You can try again or enter the values yourself.</Notice>}
      {doc.status === "CONFIRMED" && <Notice kind="success">This document supports a recorded {(doc.linked_type ?? "record").toLowerCase()}.</Notice>}
      <div className="row">
        <button type="button" onClick={() => void download()} disabled={busy}>Download</button>
        {canManage && doc.status !== "CONFIRMED" && doc.status !== "EXTRACTED" && doc.status !== "PROCESSING" && (
          <button type="button" onClick={() => void extract()} disabled={busy}>{doc.ocr_available ? "Read text from the document" : "Check text reading"}</button>
        )}
        {canManage && doc.status !== "CONFIRMED" && <button type="button" className="link" onClick={() => void remove()} disabled={busy}>Delete</button>}
      </div>
      {!doc.ocr_available && doc.status !== "CONFIRMED" && <p className="muted">No text-reading (OCR) provider is configured, so values are not read automatically. Enter them below from the document.</p>}
      {doc.extraction && (
        <div role="group" aria-label="Values read from the document">
          <h3>Read from the document (unverified)</h3>
          <p className="muted">This text came from the file and may be wrong. It is only a suggestion; check it against the document.</p>
          <ul>{Object.entries(doc.extraction.fields).map(([k, v]) => <li key={k}>{humanize(k)}: {v}</li>)}</ul>
          {doc.extraction.warnings.length > 0 && <ul aria-label="Reading warnings">{doc.extraction.warnings.map((w) => <li key={w}>{w}</li>)}</ul>}
        </div>
      )}
      {canManage && reviewable && (
        <form onSubmit={confirm} aria-label="Review and record" noValidate>
          <h3>Review and record</h3>
          <p className="muted">A {domain === "BUSINESS" ? "business expense" : "transaction"} is only created when you confirm these values.</p>
          {domain === "PERSONAL" && (
            <>
              <label htmlFor="dv-account">Account</label>
              <select id="dv-account" value={form.account} onChange={(e) => setForm({ ...form, account: e.target.value })}>
                <option value="">Choose an account</option>
                {open.map((a) => <option key={a.id} value={a.id}>{a.name} ({a.currency})</option>)}
              </select>
              <label htmlFor="dv-kind">Type</label>
              <select id="dv-kind" value={form.kind} onChange={(e) => setForm({ ...form, kind: e.target.value })}>
                <option value="EXPENSE">Expense</option>
                <option value="INCOME">Income</option>
              </select>
            </>
          )}
          <label htmlFor="dv-date">Date</label>
          <input id="dv-date" type="date" value={form.date} max={todayLocal()} onChange={(e) => setForm({ ...form, date: e.target.value })} />
          <label htmlFor="dv-amount">Total amount</label>
          <input id="dv-amount" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} inputMode="decimal" />
          <label htmlFor="dv-currency">Currency</label>
          <select id="dv-currency" value={form.currency} onChange={(e) => setForm({ ...form, currency: e.target.value })}>
            {CURRENCIES.map((c) => <option key={c} value={c}>{c}</option>)}
          </select>
          <label htmlFor="dv-desc">Description</label>
          <input id="dv-desc" value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
          <label htmlFor="dv-ref">Reference or bill number (optional)</label>
          <input id="dv-ref" value={form.reference} onChange={(e) => setForm({ ...form, reference: e.target.value })} />
          <label><input type="checkbox" checked={checked} onChange={(e) => setChecked(e.target.checked)} /> I checked these values against the document{form.amount && ` (${formatMoney(form.amount, form.currency)})`}</label>
          <button type="submit" disabled={busy}>{busy ? "Recording…" : "Confirm and record"}</button>
        </form>
      )}
    </section>
  );
}
