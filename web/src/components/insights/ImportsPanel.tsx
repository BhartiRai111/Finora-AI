"use client";

import { useState, type FormEvent } from "react";
import { describeError } from "@/lib/api";
import type { Account } from "@/lib/finance";
import { useFinance } from "@/lib/FinanceContext";
import { humanize, saveBlob, type ImportJob, type ImportRow, type Listing } from "@/lib/insights";
import { useResource } from "@/lib/useResource";
import { Loading, Notice } from "../Notice";

const MAX_BYTES = 2 * 1024 * 1024;
const STATUS_TEXT: Record<ImportJob["status"], string> = {
  NEEDS_MAPPING: "Needs column mapping",
  READY: "Ready to import",
  COMMITTING: "Importing…",
  COMPLETED: "Completed",
  PARTIAL: "Partly imported",
  FAILED: "Failed",
  CANCELLED: "Cancelled",
};

/** CSV imports: choose a file, map its columns, check the preview, then confirm. Nothing is written until the final confirmation. */
export function ImportsPanel({ domain, canWrite, accounts }: { domain: "PERSONAL" | "BUSINESS"; canWrite: boolean; accounts: Account[] }) {
  const finance = useFinance();
  const kinds = domain === "BUSINESS" ? [["EXPENSES", "Expenses"], ["STATEMENT", "Bank statement (for reconciliation)"]] : [["TRANSACTIONS", "Transactions"], ["STATEMENT", "Bank statement (for reconciliation)"]];
  const list = useResource((signal) => finance.get<Listing<ImportJob>>("/finance-imports?limit=20", signal));
  const [selected, setSelected] = useState<string | null>(null);
  const [kind, setKind] = useState(kinds[0]![0]!);
  const [account, setAccount] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const open = accounts.filter((a) => !a.archived);
  const needsAccount = domain === "PERSONAL";

  async function upload(event: FormEvent) {
    event.preventDefault();
    if (!file) return setError("Choose a CSV file first.");
    if (/\.(xlsx|xls|xlsm)$/i.test(file.name)) return setError("Excel files are not supported. In Excel choose Save As, then CSV (UTF-8), and upload that file.");
    if (file.size === 0) return setError("The file is empty.");
    if (file.size > MAX_BYTES) return setError("The file is larger than 2 MB. Split it into smaller files.");
    if (needsAccount && !account) return setError("Choose the account this file belongs to.");
    setBusy(true);
    setError(null);
    try {
      const q = new URLSearchParams({ kind, filename: file.name });
      if (needsAccount) q.set("account_id", account);
      const job = await finance.upload<ImportJob>(`/finance-imports?${q.toString()}`, file, "text/csv");
      setFile(null);
      setSelected(job.id);
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
      {canWrite && (
        <form onSubmit={upload} aria-labelledby="imp-upload-title" noValidate>
          <h2 id="imp-upload-title">Import a CSV file</h2>
          <label htmlFor="imp-kind">What is in the file</label>
          <select id="imp-kind" value={kind} onChange={(e) => setKind(e.target.value)}>
            {kinds.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
          </select>
          {needsAccount && (
            <>
              <label htmlFor="imp-account">Account</label>
              <select id="imp-account" value={account} onChange={(e) => setAccount(e.target.value)}>
                <option value="">Choose an account</option>
                {open.map((a) => <option key={a.id} value={a.id}>{a.name} ({a.currency})</option>)}
              </select>
            </>
          )}
          <label htmlFor="imp-file">CSV file (UTF-8, up to 2 MB and 5,000 rows)</label>
          <input id="imp-file" type="file" accept=".csv,text/csv" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
          <p className="muted">Excel workbooks are not supported; export the sheet as CSV. The file is only read and checked now; nothing is created until you confirm.</p>
          <button type="submit" disabled={busy}>{busy ? "Uploading…" : "Upload and continue"}</button>
        </form>
      )}
      <h2>Import history</h2>
      {list.status === "loading" && <Loading label="Loading imports…" />}
      {list.status === "error" && (
        <>
          <Notice>{describeError(list.error)}</Notice>
          <button type="button" onClick={list.reload}>Try again</button>
        </>
      )}
      {list.status === "ready" && list.data.items.length === 0 && <p>No imports yet.{canWrite ? " Upload a CSV file above to start." : ""}</p>}
      {list.status === "ready" && list.data.items.length > 0 && (
        <table>
          <caption className="visually-hidden">Imports</caption>
          <thead><tr><th scope="col">File</th><th scope="col">Type</th><th scope="col">Status</th><th scope="col">Imported</th><th scope="col">Not imported</th><th scope="col"><span className="visually-hidden">Open</span></th></tr></thead>
          <tbody>
            {list.data.items.map((j) => (
              <tr key={j.id}>
                <td>{j.filename}</td>
                <td>{humanize(j.kind.toLowerCase())}</td>
                <td>{STATUS_TEXT[j.status]}</td>
                <td>{j.committed_rows} of {j.total_rows}</td>
                <td>{j.rejected_rows + j.duplicate_rows + j.skipped_rows}</td>
                <td><button type="button" className="link" onClick={() => setSelected(j.id)}>Open {j.filename}</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {selected && <ImportJobView key={selected} id={selected} canWrite={canWrite} onChanged={list.reload} />}
    </>
  );
}

function ImportJobView({ id, canWrite, onChanged }: { id: string; canWrite: boolean; onChanged: () => void }) {
  const finance = useFinance();
  const job = useResource((signal) => finance.get<ImportJob>(`/finance-imports/${id}`, signal));
  if (job.status === "loading") return <Loading label="Loading import…" />;
  if (job.status === "error") {
    return (
      <>
        <Notice>{describeError(job.error)}</Notice>
        <button type="button" onClick={job.reload}>Try again</button>
      </>
    );
  }
  return <JobBody job={job.data} canWrite={canWrite} reload={() => { job.reload(); onChanged(); }} />;
}

function JobBody({ job, canWrite, reload }: { job: ImportJob; canWrite: boolean; reload: () => void }) {
  const finance = useFinance();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const fields = [...job.required_fields, ...job.optional_fields];
  const initial = Object.keys(job.mapping).length > 0 ? job.mapping : (job.suggested_mapping ?? {});
  const [mapping, setMapping] = useState<Record<string, string>>(initial);
  const [dateFormat, setDateFormat] = useState(job.options.date_format ?? "ISO");
  const [decimal, setDecimal] = useState(job.options.decimal_separator ?? ".");
  const [policy, setPolicy] = useState("");
  const [confirm, setConfirm] = useState(false);
  const [rowStatus, setRowStatus] = useState("");
  const rows = useResource((signal) => finance.get<Listing<ImportRow>>(`/finance-imports/${job.id}/rows?limit=50${rowStatus ? `&status=${rowStatus}` : ""}`, signal));
  const editable = job.status === "NEEDS_MAPPING" || job.status === "READY";

  async function run<T>(fn: () => Promise<T>): Promise<T | undefined> {
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

  async function saveMapping(event: FormEvent) {
    event.preventDefault();
    for (const r of job.required_fields) {
      if (!mapping[r]) return setError(`Choose the column for “${humanize(r).toLowerCase()}”.`);
    }
    const used = Object.values(mapping).filter(Boolean);
    if (new Set(used).size !== used.length) return setError("Each column can be used for one field only.");
    const ok = await run(() => finance.send<ImportJob>("PUT", `/finance-imports/${job.id}/mapping`, { fields: Object.fromEntries(Object.entries(mapping).filter(([, v]) => v)), options: { date_format: dateFormat, decimal_separator: decimal } }));
    if (ok) reload();
  }

  async function commit() {
    const choice = job.possible_duplicate_rows > 0 ? policy : "SKIP";
    if (!choice) return setError("Say what to do with rows that may already exist.");
    if (!confirm) return setError("Tick the box to confirm the import.");
    const ok = await run(() => finance.send<ImportJob>("POST", `/finance-imports/${job.id}/commit`, { confirm: true, possible_duplicates: choice }));
    if (ok) reload();
  }

  async function cancel() {
    const ok = await run(() => finance.send<ImportJob>("POST", `/finance-imports/${job.id}/cancel`, {}));
    if (ok) reload();
  }

  async function errorReport() {
    const file = await run(() => finance.download(`/finance-imports/${job.id}/error-report`));
    if (file) saveBlob(file.blob, file.filename);
  }

  const notImported = job.rejected_rows + job.duplicate_rows + job.skipped_rows;
  return (
    <section className="card column" aria-label="Import details">
      <h2>{job.filename}</h2>
      <p>Status: <strong>{STATUS_TEXT[job.status]}</strong> · {job.total_rows} rows</p>
      {error && <Notice>{error}</Notice>}
      {job.status === "COMPLETED" && <Notice kind="success">Import finished: {job.committed_rows} {job.kind === "STATEMENT" ? "statement lines saved" : "records created"}.{notImported > 0 && ` ${job.duplicate_rows} duplicate and ${job.skipped_rows} skipped rows were left out.`}</Notice>}
      {job.status === "PARTIAL" && <Notice>Partly imported: {job.committed_rows} rows were created, but {job.rejected_rows} rows were rejected. Download the error report to fix and re-import them.</Notice>}
      {job.status === "FAILED" && <Notice>Nothing was imported: {job.rejected_rows} rows were rejected. Download the error report to see why.</Notice>}
      {job.status === "CANCELLED" && <Notice kind="info">This import was cancelled. {job.committed_rows > 0 ? `${job.committed_rows} rows had already been created and stay.` : "Nothing was created."}</Notice>}
      {job.status === "COMMITTING" && <Notice kind="info">The import is running. You can check its progress again or cancel it; rows already created stay.</Notice>}

      {editable && canWrite && (
        <form onSubmit={saveMapping} aria-label="Column mapping" noValidate>
          <h3>Match the columns</h3>
          {fields.map((f) => (
            <div key={f} className="column">
              <label htmlFor={`map-${f}`}>{humanize(f)}{job.required_fields.includes(f) ? " (required)" : ""}</label>
              <select id={`map-${f}`} value={mapping[f] ?? ""} onChange={(e) => setMapping({ ...mapping, [f]: e.target.value })}>
                <option value="">Not in the file</option>
                {job.headers.map((h) => <option key={h} value={h}>{h}</option>)}
              </select>
            </div>
          ))}
          <label htmlFor="map-date-format">Date format in the file</label>
          <select id="map-date-format" value={dateFormat} onChange={(e) => setDateFormat(e.target.value)}>
            <option value="ISO">2026-03-31</option>
            <option value="DMY">31/03/2026 (day first)</option>
            <option value="MDY">03/31/2026 (month first)</option>
          </select>
          <label htmlFor="map-decimal">Decimal separator in amounts</label>
          <select id="map-decimal" value={decimal} onChange={(e) => setDecimal(e.target.value)}>
            <option value=".">Point (1,250.75)</option>
            <option value=",">Comma (1.250,75)</option>
          </select>
          <button type="submit" disabled={busy}>{busy ? "Checking…" : "Check the file"}</button>
        </form>
      )}

      {(job.status === "READY" || job.status === "COMMITTING" || job.status === "COMPLETED" || job.status === "PARTIAL" || job.status === "FAILED") && (
        <div className="column">
          <h3>{job.status === "READY" ? "Preview" : "Rows"}</h3>
          <p>
            {job.valid_rows} ready · {job.invalid_rows} with problems · {job.duplicate_rows} duplicates (same id already imported) · {job.possible_duplicate_rows} possible duplicates
            {job.committed_rows > 0 && ` · ${job.committed_rows} imported`}
          </p>
          <div className="filters">
            <div className="column">
              <label htmlFor="imp-row-status">Show rows</label>
              <select id="imp-row-status" value={rowStatus} onChange={(e) => setRowStatus(e.target.value)}>
                <option value="">All</option>
                <option value="VALID">Ready</option>
                <option value="INVALID">With problems</option>
                <option value="DUPLICATE">Duplicates</option>
                <option value="POSSIBLE_DUPLICATE">Possible duplicates</option>
                <option value="COMMITTED">Imported</option>
                <option value="REJECTED">Rejected</option>
                <option value="SKIPPED">Skipped</option>
              </select>
            </div>
          </div>
          {rows.status === "loading" && <Loading label="Loading rows…" />}
          {rows.status === "error" && <Notice>{describeError(rows.error)}</Notice>}
          {rows.status === "ready" && rows.data.items.length === 0 && <p>No rows in this view.</p>}
          {rows.status === "ready" && rows.data.items.length > 0 && (
            <table>
              <caption className="visually-hidden">Import rows</caption>
              <thead><tr><th scope="col">Row</th><th scope="col">Status</th><th scope="col">Original values</th><th scope="col">Problems</th></tr></thead>
              <tbody>
                {rows.data.items.map((r) => (
                  <tr key={r.row_number}>
                    <td>{r.row_number}</td>
                    <td>{humanize(r.status.toLowerCase())}</td>
                    <td>{r.raw.join(" | ")}</td>
                    <td>{r.errors.map((e) => e.message).join("; ") || r.possible_duplicate_of || ""}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {rows.status === "ready" && rows.data.total > rows.data.items.length && <p className="muted">Showing the first {rows.data.items.length} of {rows.data.total} rows. Download the error report for every problem row.</p>}
        </div>
      )}

      {job.status === "READY" && canWrite && (
        <div className="column" role="group" aria-label="Confirm the import">
          <h3>Confirm</h3>
          {job.possible_duplicate_rows > 0 && (
            <fieldset>
              <legend>{job.possible_duplicate_rows} rows look like records you already have</legend>
              <label><input type="radio" name="dup" checked={policy === "SKIP"} onChange={() => setPolicy("SKIP")} /> Skip them</label>
              <label><input type="radio" name="dup" checked={policy === "IMPORT"} onChange={() => setPolicy("IMPORT")} /> Import them anyway (they are real, separate entries)</label>
            </fieldset>
          )}
          {job.invalid_rows > 0 && <p className="muted">{job.invalid_rows} rows with problems will not be imported.</p>}
          <label><input type="checkbox" checked={confirm} onChange={(e) => setConfirm(e.target.checked)} /> I have checked the preview and want to import the {job.valid_rows + (policy === "IMPORT" ? job.possible_duplicate_rows : 0)} rows</label>
          <button type="button" onClick={() => void commit()} disabled={busy || job.valid_rows + job.possible_duplicate_rows === 0}>{busy ? "Importing…" : "Import now"}</button>
        </div>
      )}
      <div className="row">
        {job.status === "COMMITTING" && <button type="button" onClick={reload}>Check progress</button>}
        {canWrite && (editable || job.status === "COMMITTING") && <button type="button" className="link" onClick={() => void cancel()} disabled={busy}>Cancel this import</button>}
        {notImported > 0 && <button type="button" onClick={() => void errorReport()} disabled={busy}>Download error report</button>}
      </div>
    </section>
  );
}
