"use client";

import { useState, type FormEvent } from "react";
import { describeError } from "@/lib/api";
import { businessAccess, type BusinessSettings } from "@/lib/business";
import { CURRENCIES } from "@/lib/finance";
import { useFinance } from "@/lib/FinanceContext";
import { useResource } from "@/lib/useResource";
import type { Role } from "@/lib/types";
import { Loading, Notice } from "../Notice";

type Form = { company_name: string; timezone: string; base_currency: string; invoice_prefix: string; invoice_padding: string; invoice_next_number: string; default_terms_days: string };

export function BusinessSettingsPanel({ role, onSaved }: { role: Role; onSaved: () => void }) {
  const finance = useFinance();
  const settings = useResource((signal) => finance.get<BusinessSettings>("/business-settings", signal));
  const canWrite = businessAccess.settingsWrite(role);
  const [form, setForm] = useState<Form | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  if (settings.status === "loading") return <Loading label="Loading settings…" />;
  if (settings.status === "error") {
    return (
      <>
        <Notice>{describeError(settings.error)}</Notice>
        <button type="button" onClick={settings.reload}>Try again</button>
      </>
    );
  }
  const s = settings.data;
  const f: Form = form ?? {
    company_name: s.company_name ?? "", timezone: s.timezone, base_currency: s.base_currency, invoice_prefix: s.invoice_prefix,
    invoice_padding: String(s.invoice_padding), invoice_next_number: String(s.invoice_next_number), default_terms_days: String(s.default_terms_days),
  };
  const set = (key: keyof Form, value: string) => setForm({ ...f, [key]: value });

  async function save(event: FormEvent) {
    event.preventDefault();
    setSuccess(null);
    for (const key of ["invoice_padding", "invoice_next_number", "default_terms_days"] as const) {
      if (!/^\d{1,12}$/.test(f[key])) return setError("Numbers must be whole numbers.");
    }
    setError(null);
    try {
      await finance.send("PUT", "/business-settings", {
        company_name: f.company_name.trim() || undefined, timezone: f.timezone.trim(), base_currency: f.base_currency, invoice_prefix: f.invoice_prefix.trim(),
        invoice_padding: Number(f.invoice_padding), invoice_next_number: Number(f.invoice_next_number), default_terms_days: Number(f.default_terms_days),
      });
      setForm(null);
      setSuccess("Settings saved.");
      settings.reload();
      onSaved();
    } catch (e) {
      setError(describeError(e));
    }
  }

  return (
    <form onSubmit={save} aria-labelledby="bset-title" noValidate>
      <h2 id="bset-title">Business settings</h2>
      {error && <Notice>{error}</Notice>}
      {success && <Notice kind="success">{success}</Notice>}
      <p>Next invoice number: <strong>{s.next_invoice_number_preview}</strong>. Numbers are assigned when an invoice is issued and never reused.</p>
      <label htmlFor="bs-company">Company name (optional)</label>
      <input id="bs-company" value={f.company_name} onChange={(e) => set("company_name", e.target.value)} disabled={!canWrite} />
      <label htmlFor="bs-tz">Time zone (IANA name). Overdue status flips at midnight in this zone.</label>
      <input id="bs-tz" value={f.timezone} onChange={(e) => set("timezone", e.target.value)} disabled={!canWrite} />
      <label htmlFor="bs-cur">Default currency for new invoices and projects</label>
      <select id="bs-cur" value={f.base_currency} onChange={(e) => set("base_currency", e.target.value)} disabled={!canWrite}>
        {CURRENCIES.map((c) => <option key={c} value={c}>{c}</option>)}
      </select>
      <label htmlFor="bs-prefix">Invoice number prefix</label>
      <input id="bs-prefix" value={f.invoice_prefix} onChange={(e) => set("invoice_prefix", e.target.value)} disabled={!canWrite} />
      <label htmlFor="bs-pad">Minimum digits in the number</label>
      <input id="bs-pad" value={f.invoice_padding} onChange={(e) => set("invoice_padding", e.target.value)} inputMode="numeric" disabled={!canWrite} />
      <label htmlFor="bs-next">Next number (can only move forward)</label>
      <input id="bs-next" value={f.invoice_next_number} onChange={(e) => set("invoice_next_number", e.target.value)} inputMode="numeric" disabled={!canWrite} />
      <label htmlFor="bs-terms">Default payment terms (days)</label>
      <input id="bs-terms" value={f.default_terms_days} onChange={(e) => set("default_terms_days", e.target.value)} inputMode="numeric" disabled={!canWrite} />
      <p className="muted">Tax: no tax rules are configured. The tax % you type on an invoice line is used as entered; this is not GST compliance.</p>
      {canWrite ? <button type="submit">Save settings</button> : <p className="muted">Only an Owner or Admin can change these settings.</p>}
    </form>
  );
}
